(ns craft-harness.six-pack-test
  "Milestone 7 (docs/current-milestone.md): the six-pack runner bin/run-six.
   A fuller domain pipeline — specify -> code -> harden -> qa — with the defining
   new control, EXECUTABLE Gherkin: the specifier writes @ID-tagged feature files,
   the runner snapshots the approved scenario IDs at the R6 gate, and after the
   agent phases the runner runs the declared `accept:` command ITSELF and enforces
   that every approved scenario appears PASSED in the machine-readable report
   (docs/acceptance-report-schema.md) — never by substring (D22/D27).

   Fixture-first, against a deliberately NON-TOY, multi-module project (core+web,
   real module boundary, genuine unit/architecture/dup gates, a genuine
   dependency-free acceptance runtime) — so no test could pass against the sut.sh
   toy (the D20/D21/D22/D23/D27 'fixtures-mirror-the-toy blindness'). Zero paid runs."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def run-six (str (fs/path repo-root "bin" "run-six")))
(def fixtures (fs/path repo-root "test" "fixtures" "sixpack-agent"))
(def template (fs/path repo-root "test" "fixtures" "sixpack" "template"))

(def ^:dynamic *sandboxes* nil)
(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t)
           (finally (doseq [d @*sandboxes*]
                      ;; robust teardown: git marks loose objects read-only, which
                      ;; can trip delete-tree on some filesystems — never let a
                      ;; teardown error mask a real result.
                      (try (fs/delete-tree d) (catch Exception _))))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m7-six."})]
    (swap! *sandboxes* conj d) d))

(defn sh-run [{:keys [dir]} & args]
  (apply sh/sh (concat args [:dir (str dir)
                             :env {"PATH" (System/getenv "PATH")
                                   "HOME" (System/getenv "HOME")
                                   "GIT_CONFIG_NOSYSTEM" "1"}])))
(defn git! [dir & args]
  (let [r (apply sh-run {:dir dir} "git" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git failed " (vec args)) r))) r))

(defn make-sixpack-project!
  "The non-toy multi-module fixture: copy the checked-in template (project.prompt
   with owns/test/quality/accept, run-unit.sh, the genuine acceptance runtime, the
   architecture + dup gates) into a fresh git repo. NO repo-local git identity
   (D23) — the baseline is committed with an ephemeral, unstored -c identity, so
   run-six must seed one before the code phase can author its candidate."
  []
  (let [dir (fs/path (tmp-dir) "sixproj")]
    (fs/create-dirs dir)
    (fs/copy-tree template dir)
    (git! dir "init" "-q" "-b" "main")
    (git! dir "add" "-A")
    (git! dir "-c" "user.name=Setup" "-c" "user.email=setup@example.invalid"
          "commit" "-q" "-m" "sixpack baseline (scaffolding only)")
    dir))

(defn drop-accept! [project]
  "Rewrite project.prompt WITHOUT the accept: line, and commit it."
  (let [p (fs/path project "project.prompt")
        kept (->> (str/split-lines (slurp (str p)))
                  (remove #(str/starts-with? % "accept:"))
                  (str/join "\n"))]
    (spit (str p) (str kept "\n"))
    (git! project "add" "project.prompt")
    (git! project "-c" "user.name=Setup" "-c" "user.email=setup@example.invalid"
          "commit" "-q" "-m" "drop accept")))

(defn private-state-root [project]
  (str (fs/path (fs/parent project) "runner-private-state")))

(defn run-six!
  "run-six against the fixture under a CLEAN, identity-free HOME (no global git
   identity leaks in — so identity seeding is deterministic, the real condition)."
  [project variant & extra]
  (let [r (apply sh/sh "bash" run-six
                 "--project" (str project)
                 "--adapter" (str (fs/path fixtures variant))
                 "--pack" "six-pack"
                 (concat extra
                         [:env {"PATH" (System/getenv "PATH")
                                "HOME" (str (tmp-dir))
                                "GIT_CONFIG_NOSYSTEM" "1"
                                "CRAFT_HARNESS_PRIVATE_STATE" (private-state-root project)}]))]
    (assoc r :all (str (:out r) (:err r)))))

(defn state [project] (fs/path project ".craft-harness" "six" "current"))
(defn status [project]
  (let [f (fs/path (state project) "status")]
    (when (fs/exists? f) (str/trim (slurp (str f))))))
(defn approval-token [res]
  (second (re-find #"APPROVAL_TOKEN:\s*(\S+)" (:all res))))
(defn approve! [project token]
  (let [f (fs/path (state project) "approval" "APPROVED")]
    (fs/create-dirs (fs/parent f)) (spit (str f) token)))
(defn head [project] (str/trim (:out (git! project "rev-parse" "HEAD"))))
(defn manifest [project]
  (let [f (fs/path (state project) "manifest.json")]
    (when (fs/exists? f) (json/parse-string (slurp (str f)) true))))
(defn candidate-touched [project base]
  (->> (:out (git! project "diff" "--name-only" base "HEAD"))
       str/split-lines (remove str/blank?) set))
(defn prompt-line
  "The single seeded-prompt line beginning with `prefix` (its whole content), or
   nil — for EXACT-line injection assertions (D22 coda), never substring."
  [prompt prefix]
  (->> (str/split-lines prompt) (filter #(str/starts-with? % prefix)) first))
(defn seeded-prompt [project phase]
  (slurp (str (fs/path (state project) "prompts" (str phase ".prompt")))))
(defn approve-and-resume! [project variant]
  (let [r1 (run-six! project variant)]
    (approve! project (approval-token r1))
    (run-six! project variant)))

;; --- the specifier + R6 gate -------------------------------------------------

(deftest specify-runs-then-pauses-for-approval
  (let [p (make-sixpack-project!)
        base (head p)
        r1 (run-six! p "happy")]
    (is (zero? (:exit r1)) (str "the first invocation should pause cleanly:\n" (:all r1)))
    (testing "it paused at the R6 gate awaiting approval"
      (is (re-find #"(?i)approval" (:all r1)))
      (is (= "awaiting_approval" (status p))))
    (testing "the spec + executable feature files were produced"
      (is (fs/exists? (fs/path (state p) "spec" "spec.md")))
      (is (fs/exists? (fs/path (state p) "spec" "features" "core.feature"))))
    (testing "the approved scenario IDs were snapshotted at the gate"
      (let [snap (slurp (str (fs/path (state p) "spec" "approved-scenarios.txt")))]
        (is (str/includes? snap "SUT-1"))
        (is (str/includes? snap "SUT-2"))))
    (testing "NO code ran (R6: approval before code)"
      (is (= base (head p)))
      (is (some? (approval-token r1))))))

;; --- the full pipeline completes, executable Gherkin enforced ----------------

(deftest approved-run-completes-all-phases
  (let [p (make-sixpack-project!)
        base (head p)
        r2 (approve-and-resume! p "happy")]
    (is (zero? (:exit r2)) (str "the approved six-pack run must complete:\n" (:all r2)))
    (is (= "done" (status p)))
    (testing "a scoped candidate commit was produced on main"
      (is (not= base (head p)))
      (is (= "main" (str/trim (:out (git! p "rev-parse" "--abbrev-ref" "HEAD")))))
      (is (empty? (remove #(or (str/starts-with? % "core/")
                               (str/starts-with? % "web/")
                               (str/starts-with? % "features/")
                               (= % "acceptance/steps.sh"))
                          (candidate-touched p base)))))
    (testing "all four phases left schema-valid handoffs"
      (doseq [phase ["specify" "code" "harden" "qa"]]
        (let [h (fs/path (state p) "handoffs" (str phase ".handoff"))]
          (is (fs/exists? h) (str phase ".handoff"))
          (is (zero? (:exit (sh/sh "bb" (str (fs/path repo-root "bin" "handoff-validate.bb")) (str h))))
              (str phase " handoff must be schema-valid")))))
    (testing "the runner ran the acceptance suite and every approved scenario PASSED"
      (let [report (slurp (str (fs/path (state p) "accept-report.ndjson")))]
        (doseq [id ["SUT-1" "SUT-2"]]
          (is (re-find (re-pattern (str "\\{[^}]*\"scenario\":\"" id "\"[^}]*\"status\":\"passed\"")) report)
              (str id " must be reported passed")))))
    (testing "the manifest records success for the six-pack (R10 provenance)"
      (let [m (manifest p)]
        (is (= "six-pack" (str (:pack m))))
        (is (= "success" (str (:outcome m))))
        (is (= ["specify" "code" "harden" "qa"] (mapv :name (:phases m))))))
    (testing "run-six reports SUCCESS only after the inspector passed"
      (is (re-find #"(?i)SUCCESS" (:all r2)))
      (is (re-find #"RESULT: PASS" (:all r2))))))

;; --- SIGNATURE: executable Gherkin — a planted unimplemented scenario is RED --

(deftest executable-gherkin-unimplemented-scenario-turns-the-run-red
  (testing "an approved scenario with no step handler is reported undefined -> the run fails, attributed, naming the scenario"
    (let [p (make-sixpack-project!)
          base (head p)
          r1 (run-six! p "unimpl-scenario")]
      (is (zero? (:exit r1)) (str "specify must still reach the gate:\n" (:all r1)))
      (is (str/includes? (slurp (str (fs/path (state p) "spec" "approved-scenarios.txt"))) "SUT-3"))
      (approve! p (approval-token r1))
      (let [r2 (run-six! p "unimpl-scenario")]
        (is (not (zero? (:exit r2))) "an unimplemented approved scenario MUST turn the run red")
        (is (re-find #"SUT-3" (:all r2)) "the failure must name the offending scenario ID")
        (is (re-find #"(?i)accept|scenario|qa" (:all r2)) "attributed to the acceptance/qa gate")
        (is (not= "done" (status p)))))))

(deftest a-dropped-approved-scenario-turns-the-run-red
  (testing "an approved scenario the coder never puts in the suite never appears passed -> red"
    (let [p (make-sixpack-project!)
          r1 (run-six! p "dropped-scenario")]
      (approve! p (approval-token r1))
      (let [r2 (run-six! p "dropped-scenario")]
        (is (not (zero? (:exit r2))) "a dropped approved scenario MUST turn the run red")
        (is (re-find #"SUT-2" (:all r2)) "the failure must name the missing scenario ID")
        (is (not= "done" (status p)))))))

;; --- harden / architecture gate ----------------------------------------------

(deftest an-architecture-violation-turns-the-run-red
  (testing "web reaching into core's private impl fails the declared architecture command"
    (let [p (make-sixpack-project!)
          r1 (run-six! p "arch-violation")]
      (approve! p (approval-token r1))
      (let [r2 (run-six! p "arch-violation")]
        (is (not (zero? (:exit r2))) "an architecture violation MUST turn the run red")
        (is (re-find #"(?i)architecture" (:all r2)) "attributed to the architecture quality gate")
        (is (not= "done" (status p)))))))

;; --- owned scope + verifier isolation (reused controls, six-pack shape) -------

(deftest an-out-of-scope-commit-turns-the-run-red
  (let [p (make-sixpack-project!)
        r1 (run-six! p "outofscope")]
    (approve! p (approval-token r1))
    (let [r2 (run-six! p "outofscope")]
      (is (not (zero? (:exit r2))) "an out-of-scope candidate MUST turn the run red")
      (is (re-find #"tools/extra\.sh" (:all r2)) "names the offending out-of-scope path")
      (is (re-find #"(?i)owned|owns|scope" (:all r2)))
      (testing "it stopped before the acceptance gate (no accept report / not done)"
        (is (not= "done" (status p)))))))

(deftest a-qa-verifier-that-mutates-its-worktree-turns-the-run-red
  (let [p (make-sixpack-project!)
        r1 (run-six! p "qa-mutates")]
    (approve! p (approval-token r1))
    (let [r2 (run-six! p "qa-mutates")]
      (is (not (zero? (:exit r2))) "a verifier that mutates its candidate worktree MUST turn the run red")
      (is (re-find #"(?i)qa|verif|worktree|modif" (:all r2)))
      (is (not= "done" (status p))))))

;; --- injection contract (exact lines, D22 coda) + non-toy prompts ------------

(deftest code-and-qa-prompts-carry-exact-injected-command-lines
  (let [p (make-sixpack-project!)]
    (run-six! p "happy")   ; seeds all phase prompts, pauses at the gate
    (testing "the code prompt carries the EXACT declared test command, never a toy default"
      (let [prompt (seeded-prompt p "code")]
        (is (= "TEST_CMD: bash run-unit.sh" (prompt-line prompt "TEST_CMD:")))
        (is (not (re-find #"(?i)\./test\.sh|\bsut\.sh\b" prompt)))))
    (testing "the qa prompt carries the EXACT declared acceptance command"
      (let [prompt (seeded-prompt p "qa")]
        (is (= "ACCEPT_CMD: bash acceptance/run-acceptance.sh" (prompt-line prompt "ACCEPT_CMD:")))
        (is (not (re-find #"(?i)\./test\.sh|\bsut\.sh\b" prompt)))))))

;; --- fail-closed: six-pack requires an executable acceptance command ---------

(deftest run-six-requires-an-accept-command
  (testing "a project with no accept: line is rejected before any phase (fail-closed)"
    (let [p (make-sixpack-project!)]
      (drop-accept! p)
      (let [r (run-six! p "happy")]
        (is (not (zero? (:exit r))))
        (is (re-find #"(?i)accept" (:all r)))
        (is (not (fs/exists? (state p))) "no session state on a fail-closed contract")))))

(deftest run-six-usage-is-clear
  (let [r (sh/sh "bash" run-six :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")})]
    (is (not (zero? (:exit r))))
    (is (re-find #"(?i)usage" (str (:out r) (:err r))))))
