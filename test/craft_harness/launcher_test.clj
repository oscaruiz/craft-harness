(ns craft-harness.launcher-test
  "Milestone 1 exit-criteria suite (docs/current-milestone.md §Exit criteria).
   These five tests ARE the spec: written first, red until behaviors C1-C6
   of the launcher exist. They only exercise observable behavior of
   bin/craft-harness and hooks/pre-commit against throwaway repos."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def pack-branch "test-pack")

;; doctor exit codes (part of the contract: run/upgrade compose over them)
(def exit-healthy 0)
(def exit-outdated 10)
(def exit-modified 20)
(def exit-in-flight 30)

;; --- sandbox plumbing ---------------------------------------------------

(def ^:dynamic *sandboxes* nil)

(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t)
           (finally (doseq [d @*sandboxes*]
                      (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m1."})]
    (swap! *sandboxes* conj d)
    d))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn sh-run
  [{:keys [dir env ok?]} & args]
  (let [result (apply sh/sh (concat args [:dir (str dir)
                                          :env (merge {"PATH" (System/getenv "PATH")
                                                       "HOME" (System/getenv "HOME")
                                                       "GIT_CONFIG_NOSYSTEM" "1"}
                                                      env)]))]
    (when (and (not (false? ok?)) (not= 0 (:exit result)))
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      (assoc result :args args))))
    result))

(defn git! [dir & args]
  (apply sh-run {:dir dir} "git" args))

(defn make-fork!
  "Copy of the current working tree with its own git history and a pack
   branch, so tests can simulate fork changes without touching this repo."
  []
  (let [fork (fs/path (tmp-dir) "fork")]
    (fs/copy-tree repo-root fork)
    (fs/delete-tree (fs/path fork ".git"))
    (write-file (fs/path fork "PACKS") (str pack-branch "\n"))
    (git! fork "init" "-q" "-b" "main")
    (git! fork "config" "user.email" "test@example.com")
    (git! fork "config" "user.name" "Test User")
    (git! fork "add" "-A")
    (git! fork "commit" "-q" "-m" "fork baseline")
    (git! fork "branch" pack-branch)
    fork))

(defn make-toy!
  "Minimal project repo: a README, a task.md (R6) and a baseline commit."
  []
  (let [toy (fs/path (tmp-dir) "toy")]
    (fs/create-dirs toy)
    (git! toy "init" "-q" "-b" "main")
    (git! toy "config" "user.email" "toy@example.com")
    (git! toy "config" "user.name" "Toy User")
    (write-file (fs/path toy "README.md") "toy project\n")
    (write-file (fs/path toy "task.md") "Toy task: exercise the launcher.\n")
    (write-file (fs/path toy "project.prompt") "owns:\n  **\ntest: true\n")
    (git! toy "add" "-A")
    (git! toy "commit" "-q" "-m" "toy baseline")
    toy))

;; --- launcher access ----------------------------------------------------

(defn launcher [fork]
  (let [path (fs/path fork "bin" "craft-harness")]
    (when-not (fs/exists? path)
      (throw (ex-info "bin/craft-harness not found — launcher not implemented yet"
                      {:fork (str fork)})))
    (str path)))

(defn craft! [fork opts & args]
  (apply sh-run (merge {:dir (str fork)} opts) "bash" (launcher fork) args))

(defn manifest-entries [fork]
  (let [mf (fs/path fork "MANAGED_FILES.manifest")]
    (when-not (fs/exists? mf)
      (throw (ex-info "MANAGED_FILES.manifest not found — behavior C1 not implemented yet"
                      {:fork (str fork)})))
    (->> (str/split-lines (slurp (str mf)))
         (map str/trim)
         (remove #(or (str/blank? %) (str/starts-with? % "#"))))))

(defn first-managed-file [fork]
  (or (some #(when (fs/regular-file? (fs/path fork %)) %) (manifest-entries fork))
      (throw (ex-info "manifest lists no existing regular file" {:fork (str fork)}))))

(defn fork-rev [fork ref]
  (str/trim (:out (git! fork "rev-parse" ref))))

(defn commit-fork-change!
  "Simulate a fork change on the pack branch; returns the changed path."
  [fork]
  (let [target (first-managed-file fork)]
    (git! fork "checkout" "-q" pack-branch)
    (spit (str (fs/path fork target)) "\n# fork change for propagation test\n" :append true)
    (git! fork "add" target)
    (git! fork "commit" "-q" "-m" "fork change")
    target))

(defn install! [fork toy]
  (craft! fork {} "run" "--project" (str toy) "--pack" pack-branch))

(defn snapshot
  "path -> content for every managed file present in the toy, plus the
   version file and the installed hook. Byte-identical snapshots = no
   hybrid state."
  [fork toy]
  (into {}
        (for [rel (concat (manifest-entries fork)
                          [".craft-harness-version" ".git/hooks/pre-commit"])
              :let [f (fs/path toy rel)]
              :when (fs/regular-file? f)]
          [rel (slurp (str f))])))

(defn fabricate-in-flight!
  "In-flight session exactly as the upstream plumbing leaves it (decisions.md
   D6): an unconsumed handoff in .swarmforge/handoffs/inbox/new/."
  [toy]
  (write-file (fs/path toy ".swarmforge" "handoffs" "inbox" "new" "0001-cleaner.handoff")
              (str "id: 1\nfrom: coder\nto: cleaner\npriority: 10\n"
                   "type: git_handoff\ntask: toy-task\n\n"
                   "merge_and_process coder deadbeef\n")))

;; --- exit criterion 1: fork change propagates via upgrade ---------------

(deftest upgrade-propagates-fork-change
  (let [fork (make-fork!)
        toy (make-toy!)]
    (install! fork toy)
    (let [target (commit-fork-change! fork)]
      (craft! fork {} "upgrade" "--project" (str toy))
      (is (str/includes? (slurp (str (fs/path toy target)))
                         "fork change for propagation test")
          "upgrade must propagate the fork change into the managed file")
      (is (= (fork-rev fork pack-branch)
             (str/trim (slurp (str (fs/path toy ".craft-harness-version")))))
          ".craft-harness-version must record the new fork commit"))))

;; --- exit criterion 2: interrupted upgrade leaves no hybrid state -------

(def die-steps
  "Injection points honored by the launcher (decisions.md D3): the process
   kill -9s itself right at that step."
  ["post-staging" "pre-swap" "mid-swap"])

(deftest interrupted-upgrade-leaves-no-hybrid-state
  (let [fork (make-fork!)
        toy (make-toy!)]
    (install! fork toy)
    (commit-fork-change! fork)
    (let [before (snapshot fork toy)]
      (doseq [step die-steps]
        (testing (str "killed at " step)
          (let [res (craft! fork {:ok? false
                                  :env {"CRAFT_HARNESS_TEST_DIE_AT" step}}
                            "upgrade" "--project" (str toy))]
            (is (not= 0 (:exit res))
                "an interrupted upgrade must not report success")
            (is (= before (snapshot fork toy))
                "project must stay byte-identical to its pre-upgrade state")))))))

;; --- exit criterion 3: hook rejects commits to forbidden paths ----------

(def forbidden-paths
  ["task.md"
   "swarmforge/constitution/articles/engineering.prompt"
   "adapters/contract.md"])

(deftest pre-commit-rejects-forbidden-paths
  (let [fork (make-fork!)
        toy (make-toy!)]
    (install! fork toy)
    (doseq [path forbidden-paths]
      (testing (str "commit touching " path)
        (write-file (fs/path toy path) "tampered by agent\n")
        (git! toy "add" "-f" path)
        (let [res (sh-run {:dir toy :ok? false} "git" "commit" "-m" (str "touch " path))]
          (is (not= 0 (:exit res))
              (str "commit touching " path " must be rejected by the hook"))
          (is (str/includes? (str (:out res) (:err res)) path)
              "the rejection message must name the offending path"))
        ;; leave the toy clean for the next attempt
        (git! toy "reset" "-q")
        (git! toy "checkout" "-q" "--" ".")
        (git! toy "clean" "-qfd")))
    (testing "a legitimate commit on the working branch still passes"
      (write-file (fs/path toy "src" "feature.txt") "legit work\n")
      (git! toy "add" "src/feature.txt")
      (let [res (sh-run {:dir toy :ok? false} "git" "commit" "-q" "-m" "legit work")]
        (is (= 0 (:exit res))
            (str "hook must not block allowed paths: " (:out res) (:err res)))))))

;; --- m4.5 / D17 / D19: the hook enforces the owns: allowlist too ---------
;; Defence in depth (D2): when project.prompt declares an owns: set, the
;; pre-commit hook refuses staged paths outside it (the blacklist still runs
;; first). So a bypassed run-solo is caught by the hook, and a bypassed hook is
;; caught by run-solo. No owns: block => the old blacklist-only behavior.

(defn write-owns! [toy block]
  (write-file (fs/path toy "project.prompt")
              (str "Role prompt prose the agent reads.\n\nowns:\n" block "\n")))

(deftest pre-commit-allowlist-rejects-out-of-scope-staged-path
  (let [fork (make-fork!) toy (make-toy!)]
    (install! fork toy)
    (write-owns! toy "  src/**")
    (write-file (fs/path toy "infra" "deploy.txt") "out of scope\n")
    (git! toy "add" "infra/deploy.txt")
    (let [res (sh-run {:dir toy :ok? false} "git" "commit" "-m" "touch out-of-scope path")]
      (is (not= 0 (:exit res)) "a staged path outside owns: must be rejected")
      (is (str/includes? (str (:out res) (:err res)) "infra/deploy.txt")
          "the rejection must name the offending path")
      (is (re-find #"(?i)owned|owns|scope" (str (:out res) (:err res)))))))

(deftest pre-commit-allowlist-allows-in-scope-staged-path
  (let [fork (make-fork!) toy (make-toy!)]
    (install! fork toy)
    (write-owns! toy "  src/**")
    (write-file (fs/path toy "src" "feature.txt") "legit owned work\n")
    (git! toy "add" "src/feature.txt")
    (let [res (sh-run {:dir toy :ok? false} "git" "commit" "-q" "-m" "owned work")]
      (is (= 0 (:exit res))
          (str "a staged path inside owns: must be allowed: " (:out res) (:err res))))))

(deftest pre-commit-allowlist-is-fail-closed-on-a-malformed-contract
  (let [fork (make-fork!) toy (make-toy!)]
    (install! fork toy)
    (write-owns! toy "  ../escape/**")       ; traversal — malformed
    (write-file (fs/path toy "src" "feature.txt") "work\n")
    (git! toy "add" "src/feature.txt")
    (let [res (sh-run {:dir toy :ok? false} "git" "commit" "-m" "work under a malformed contract")]
      (is (not= 0 (:exit res)) "a malformed owns: block must refuse the commit (fail-closed)")
      (is (re-find #"(?i)owns|owned|contract" (str (:out res) (:err res)))))))

(deftest pre-commit-without-owns-fails-closed
  (testing "no project.prompt => the required containment contract rejects commits"
    (let [fork (make-fork!) toy (make-toy!)]
      (install! fork toy)
      (fs/delete (fs/path toy "project.prompt"))
      (write-file (fs/path toy "anywhere" "file.txt") "no allowlist declared\n")
      (git! toy "add" "anywhere/file.txt")
      (let [res (sh-run {:dir toy :ok? false} "git" "commit" "-q" "-m" "unowned but allowed")]
        (is (not= 0 (:exit res)))
        (is (re-find #"(?i)project\.prompt|owns|contract" (str (:out res) (:err res))))))))

;; --- exit criterion 4: run refuses over an in-flight session ------------

(deftest run-refuses-over-in-flight-session
  (let [fork (make-fork!)
        toy (make-toy!)]
    (install! fork toy)
    (fabricate-in-flight! toy)
    (let [before (snapshot fork toy)
          res (craft! fork {:ok? false} "run" "--project" (str toy)
                      "--pack" pack-branch)]
      (is (not= 0 (:exit res)) "run over an in-flight session must refuse")
      (is (re-find #"(?i)in.flight|session" (str (:out res) (:err res)))
          "the refusal must say an in-flight session was detected")
      (is (= before (snapshot fork toy))
          "a refused run must not touch the project"))))

;; --- behavior micro-tests (C1, C3, C5) ----------------------------------

(deftest run-refuses-without-task-md
  (let [fork (make-fork!)
        toy (make-toy!)]
    (fs/delete (fs/path toy "task.md"))
    (let [res (craft! fork {:ok? false} "run" "--project" (str toy)
                      "--pack" pack-branch)]
      (is (not= 0 (:exit res)) "run without task.md must refuse (R6)")
      (is (str/includes? (str (:out res) (:err res)) "task.md"))
      (is (not (fs/exists? (fs/path toy ".craft-harness")))
          "a refused run must leave nothing behind"))))

(deftest run-touches-only-manifest-paths
  (let [fork (make-fork!)
        toy (make-toy!)]
    (write-file (fs/path toy "src" "untouched.txt") "precious\n")
    (install! fork toy)
    (is (= "precious\n" (slurp (str (fs/path toy "src" "untouched.txt"))))
        "files outside the manifest must never be touched")
    (is (= pack-branch (str/trim (slurp (str (fs/path toy ".craft-harness" "pack")))))
        "run must record the installed pack")
    (is (= "main" (str/trim (slurp (str (fs/path toy ".craft-harness" "branch")))))
        "run must record the enforced working branch")
    (doseq [e (manifest-entries fork)]
      (is (fs/exists? (fs/path toy e)) (str e " must be materialized")))))

(deftest run-keeps-project-git-history-clean
  (let [fork (make-fork!)
        toy (make-toy!)]
    (install! fork toy)
    (testing "git status shows only the versioned footprint"
      (let [lines (->> (git! toy "status" "--porcelain")
                       :out
                       str/split-lines
                       (remove str/blank?))]
        (is (= ["?? .craft-harness-version"] lines)
            "after run, only .craft-harness-version may appear (task.md is already committed; materialized paths must be ignored)")))
    (testing "materialized paths are gitignored by the launcher itself"
      (doseq [path (conj (vec (manifest-entries fork)) ".craft-harness")]
        (is (= 0 (:exit (sh-run {:dir toy :ok? false} "git" "check-ignore" "-q" path)))
            (str path " must be ignored by the launcher-managed excludes"))))
    (testing "the versioned footprint is NOT ignored"
      (doseq [path ["task.md" ".craft-harness-version"]]
        (is (= 1 (:exit (sh-run {:dir toy :ok? false} "git" "check-ignore" "-q" path)))
            (str path " must stay visible to git"))))))

(deftest upgrade-refuses-over-in-flight-session
  (let [fork (make-fork!)
        toy (make-toy!)]
    (install! fork toy)
    (commit-fork-change! fork)
    (fabricate-in-flight! toy)
    (let [before (snapshot fork toy)
          res (craft! fork {:ok? false} "upgrade" "--project" (str toy))]
      (is (not= 0 (:exit res)) "upgrade over an in-flight session must refuse")
      (is (re-find #"(?i)in.flight" (str (:out res) (:err res))))
      (is (= before (snapshot fork toy))
          "a refused upgrade must not touch the project"))))

;; --- exit criterion 5: doctor distinguishes the four states -------------

(defn doctor [fork toy]
  (craft! fork {:ok? false} "doctor" "--project" (str toy)))

(deftest doctor-distinguishes-four-states
  (testing "healthy"
    (let [fork (make-fork!) toy (make-toy!)]
      (install! fork toy)
      (let [res (doctor fork toy)]
        (is (= exit-healthy (:exit res)))
        (is (re-find #"(?i)healthy" (:out res))))))
  (testing "outdated"
    (let [fork (make-fork!) toy (make-toy!)]
      (install! fork toy)
      (commit-fork-change! fork)
      (let [res (doctor fork toy)]
        (is (= exit-outdated (:exit res)))
        (is (re-find #"(?i)outdated" (:out res))))))
  (testing "managed file locally modified"
    (let [fork (make-fork!) toy (make-toy!)]
      (install! fork toy)
      (let [target (first-managed-file fork)]
        (spit (str (fs/path toy target)) "\nlocal tamper\n" :append true)
        (let [res (doctor fork toy)]
          (is (= exit-modified (:exit res)))
          (is (str/includes? (:out res) target)
              "doctor must name the modified managed file")))))
  (testing "installed pre-commit hook locally modified (decisions.md D1)"
    (let [fork (make-fork!) toy (make-toy!)]
      (install! fork toy)
      (spit (str (fs/path toy ".git" "hooks" "pre-commit")) "\n# tamper\n" :append true)
      (let [res (doctor fork toy)]
        (is (= exit-modified (:exit res)))
        (is (str/includes? (:out res) "pre-commit")
            "doctor must flag the tampered hook"))))
  (testing "session in flight"
    (let [fork (make-fork!) toy (make-toy!)]
      (install! fork toy)
      (fabricate-in-flight! toy)
      (let [res (doctor fork toy)]
        (is (= exit-in-flight (:exit res)))
        (is (re-find #"(?i)in.flight" (:out res)))))))
