(ns craft-harness.real-adapters-test
  "Milestone 2, behavior B6 (docs/current-milestone.md): the real adapters'
   command construction, verified WITHOUT spending money — a stub binary
   placed first on PATH records its argv; no network, no CLI needed. The
   flags themselves get validated against the installed CLIs in the paid
   certification runs (B7)."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))

(def ^:dynamic *sandboxes* nil)

(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t)
           (finally (doseq [d @*sandboxes*]
                      (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m2-real."})]
    (swap! *sandboxes* conj d)
    d))

(def stub-script
  "#!/usr/bin/env bash
if [[ \"${1:-}\" == \"--version\" ]]; then
  echo \"9.9.9-stub\"
  exit 0
fi
printf '%s\\n' \"$@\" > \"$STUB_LOG\"
echo \"stub ran\"
")

(defn make-stub!
  "A bin dir whose <cli-name> records its argv (one arg per line) into log."
  [cli-name]
  (let [bin (fs/path (tmp-dir) "bin")
        log (fs/path (fs/parent bin) "argv.log")]
    (fs/create-dirs bin)
    (spit (str (fs/path bin cli-name)) stub-script)
    (sh/sh "chmod" "+x" (str (fs/path bin cli-name)))
    {:bin bin :log log}))

(defn adapter-exe [adapter exe]
  (str (fs/path repo-root "adapters" adapter exe)))

(defn run-adapter* [adapter exe {:keys [bin log]} extra-env & args]
  (apply sh/sh "bash" (adapter-exe adapter exe)
         (concat args [:env (merge {"PATH" (str bin ":" (System/getenv "PATH"))
                                    "HOME" (System/getenv "HOME")
                                    "STUB_LOG" (str log)}
                                   extra-env)])))

(defn run-adapter [adapter exe stub & args]
  (apply run-adapter* adapter exe stub {} args))

(defn make-workdir-and-prompt []
  (let [d (tmp-dir)
        wd (fs/path d "workdir")
        prompt (fs/path d "edit.prompt")]
    (fs/create-dirs wd)
    (spit (str prompt) "PHASE: edit\n\nApply the fix task.md describes.\n")
    {:wd wd :prompt prompt}))

(defn argv [stub]
  (str/split-lines (slurp (str (:log stub)))))

;; --- claude-code ----------------------------------------------------------

(deftest claude-code-invoke-builds-a-confined-headless-command
  (let [stub (make-stub! "claude")
        {:keys [wd prompt]} (make-workdir-and-prompt)
        res (run-adapter "claude-code" "invoke" stub
                         "--workdir" (str wd) "--prompt-file" (str prompt))]
    (is (= 0 (:exit res)) (str (:out res) (:err res)))
    (let [args (argv stub)]
      (testing "headless one-shot"
        (is (some #{"-p"} args)))
      (testing "the D23 skip-all bypass is GONE (D38): a scoped allowlist replaces it"
        (is (not (some #{"--dangerously-skip-permissions"} args)))
        (is (not (some #{"--permission-mode"} args)))
        (is (some #{"--allowedTools"} args)))
      (testing "baseline allowlist: file edits, git, and nothing else"
        (is (some #{"Edit"} args))
        (is (some #{"Write"} args))
        (is (some #{"Bash(git:*)"} args)))
      (testing "agent-tool network egress is denied explicitly (D38 item 1)"
        (is (some #{"--disallowedTools"} args))
        (is (some #{"WebFetch"} args))
        (is (some #{"WebSearch"} args)))
      (testing "no --add-dir outside a runner phase (no runner env present)"
        (is (not (some #{"--add-dir"} args))))
      (testing "the prompt text is passed through"
        (is (some #(str/includes? % "PHASE: edit") args))))))

(deftest claude-code-invoke-allowlists-exactly-the-declared-commands
  (let [stub (make-stub! "claude")
        {:keys [wd prompt]} (make-workdir-and-prompt)]
    (spit (str (fs/path wd "project.prompt"))
          (str "owns:\n  src/**\n"
               "test: mvn -q test\n"
               "quality:\n  architecture: bash tools/arch.sh\n"
               "accept: bash acceptance/run.sh\n"
               "mutation:\n  tool: pitest\n  threshold: 80\n"
               "  command: mvn -q org.pitest:pitest-maven:mutationCoverage\n"))
    (let [res (run-adapter "claude-code" "invoke" stub
                           "--workdir" (str wd) "--prompt-file" (str prompt))]
      (is (= 0 (:exit res)) (str (:out res) (:err res)))
      (let [args (argv stub)]
        (testing "each declared command is allowed EXACTLY and with appended args — test, quality, accept, mutation"
          (doseq [cmd ["mvn -q test"
                       "bash tools/arch.sh"
                       "bash acceptance/run.sh"
                       "mvn -q org.pitest:pitest-maven:mutationCoverage"]]
            (is (some #{(str "Bash(" cmd ")")} args) cmd)
            (is (some #{(str "Bash(" cmd ":*)")} args) (str cmd " with args"))))
        (testing "NO blanket first-token rule leaks in (a declared 'bash x.sh' must not allow all of bash)"
          (is (not (some #{"Bash(bash:*)"} args)))
          (is (not (some #{"Bash(mvn:*)"} args))))))))

(deftest claude-code-invoke-tolerates-an-unparseable-contract
  (testing "an invalid project.prompt degrades to the baseline allowlist (the runners fail-closed on it long before a phase; a direct invoke must not crash)"
    (let [stub (make-stub! "claude")
          {:keys [wd prompt]} (make-workdir-and-prompt)]
      (spit (str (fs/path wd "project.prompt")) "not a contract at all\n")
      (let [res (run-adapter "claude-code" "invoke" stub
                             "--workdir" (str wd) "--prompt-file" (str prompt))]
        (is (= 0 (:exit res)) (str (:out res) (:err res)))
        (let [args (argv stub)]
          (is (some #{"Bash(git:*)"} args))
          (is (not (some #(str/starts-with? % "Bash(mvn") args))))))))

(deftest claude-code-invoke-adds-the-runner-state-dir-for-worktree-phases
  (testing "when the runner exports the handoffs dir (qa/verify run in a detached worktree), the session state dir is granted via --add-dir"
    (let [stub (make-stub! "claude")
          {:keys [wd prompt]} (make-workdir-and-prompt)
          state (fs/path (tmp-dir) "six-state")
          handoffs (fs/path state "handoffs")]
      (fs/create-dirs handoffs)
      (doseq [env-var ["SIX_HANDOFFS_DIR" "SOLO_HANDOFFS_DIR"]]
        (let [res (run-adapter* "claude-code" "invoke" stub
                                {env-var (str handoffs)}
                                "--workdir" (str wd) "--prompt-file" (str prompt))]
          (is (= 0 (:exit res)) (str env-var ": " (:out res) (:err res)))
          (let [args (argv stub)]
            (is (some #{"--add-dir"} args) env-var)
            (is (some #{(str (fs/canonicalize state))} args) env-var)))))))

(deftest claude-code-info-names-the-cli
  (let [stub (make-stub! "claude")
        res (run-adapter "claude-code" "info" stub)]
    (is (= 0 (:exit res)))
    (is (str/starts-with? (str/trim (:out res)) "claude "))
    (is (str/includes? (:out res) "9.9.9-stub"))))

;; --- codex ----------------------------------------------------------------

(deftest codex-invoke-builds-a-confined-headless-command
  (let [stub (make-stub! "codex")
        {:keys [wd prompt]} (make-workdir-and-prompt)
        res (run-adapter "codex" "invoke" stub
                         "--workdir" (str wd) "--prompt-file" (str prompt))]
    (is (= 0 (:exit res)) (str (:out res) (:err res)))
    (let [args (argv stub)]
      (testing "headless one-shot"
        (is (= "exec" (first args))))
      (testing "workdir is explicit"
        (is (some #{"-C"} args))
        (is (some #{(str wd)} args)))
      (testing "containment layer (D2): sandboxed to the workspace"
        (is (some #{"--sandbox"} args))
        (is (some #{"workspace-write"} args)))
      (testing "the prompt text is passed through"
        (is (some #(str/includes? % "PHASE: edit") args))))))

(deftest codex-info-names-the-cli
  (let [stub (make-stub! "codex")
        res (run-adapter "codex" "info" stub)]
    (is (= 0 (:exit res)))
    (is (str/starts-with? (str/trim (:out res)) "codex "))))

;; --- contract hygiene -----------------------------------------------------

(deftest real-adapters-reject-bad-arguments
  (doseq [adapter ["claude-code" "codex"]]
    (testing (str adapter " invoke without --workdir/--prompt-file")
      (let [stub (make-stub! "whatever")
            res (run-adapter adapter "invoke" stub)]
        (is (not= 0 (:exit res)))
        (is (re-find #"(?i)usage" (str (:out res) (:err res))))))))
