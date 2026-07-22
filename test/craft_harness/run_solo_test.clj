(ns craft-harness.run-solo-test
  "Milestone 4, behavior B2 (docs/current-milestone.md): the sequential runner
   bin/run-solo. Three phases (specify -> code+clean -> verify), each a fresh
   headless invoke in its own workdir (no tmux, no daemon), with the structured
   handoff (B1) validated before the next phase starts. The specify phase is
   gated on the owner's approval (R6) via a resume model: run-solo pauses after
   specify and only continues once a valid approval (an un-forgeable token it
   printed) is present. Solo session state is persistent so a crash mid-run is
   visible as 'session in flight' (R8/D6). Driven by fake solo agents — zero
   paid runs."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def run-solo (str (fs/path repo-root "bin" "run-solo")))
(def fixtures (fs/path repo-root "test" "fixtures" "solo-agent"))

(def ^:dynamic *sandboxes* nil)
(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t) (finally (doseq [d @*sandboxes*] (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m4-solo."})]
    (swap! *sandboxes* conj d) d))

(defn write-file [p t] (fs/create-dirs (fs/parent p)) (spit (str p) t))

(defn sh-run [{:keys [dir]} & args]
  (apply sh/sh (concat args [:dir (str dir)
                             :env {"PATH" (System/getenv "PATH")
                                   "HOME" (System/getenv "HOME")
                                   "GIT_CONFIG_NOSYSTEM" "1"}])))
(defn git! [dir & args]
  (let [r (apply sh-run {:dir dir} "git" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git failed " (vec args)) r))) r))

(defn make-project! []
  (let [dir (fs/path (tmp-dir) "project")]
    (fs/create-dirs dir)
    (git! dir "init" "-q" "-b" "main")
    (git! dir "config" "user.email" "toy@example.com")
    (git! dir "config" "user.name" "Toy User")
    (write-file (fs/path dir "task.md") "Make ./test.sh pass: sut.sh must print 42.\n")
    (write-file (fs/path dir "project.prompt")
                "Project: toy fixture.\nowns:\n  sut.sh\ntest: ./test.sh\n")
    (write-file (fs/path dir "sut.sh") "#!/usr/bin/env bash\necho broken\n")
    (write-file (fs/path dir "test.sh")
                "#!/usr/bin/env bash\nset -euo pipefail\n[[ \"$(bash ./sut.sh)\" == \"42\" ]]\n")
    (sh-run {:dir dir} "chmod" "+x" "sut.sh" "test.sh")
    (git! dir "add" "-A") (git! dir "commit" "-q" "-m" "toy baseline")
    dir))

(def ^:dynamic *extra-env* {})

(defn private-state-root [project]
  (str (fs/path (fs/parent project) "runner-private-state")))

(defn run-solo! [project variant & extra]
  (let [r (apply sh/sh "bash" run-solo
                 "--project" (str project)
                 "--adapter" (str (fs/path fixtures variant))
                 (concat extra
                         [:env (merge {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")
                                       "GIT_CONFIG_NOSYSTEM" "1"
                                       "CRAFT_HARNESS_PRIVATE_STATE" (private-state-root project)}
                                      *extra-env*)]))]
    (assoc r :all (str (:out r) (:err r)))))

(defn state [project] (fs/path project ".craft-harness" "solo" "current"))
(defn status [project]
  (let [f (fs/path (state project) "status")]
    (when (fs/exists? f) (str/trim (slurp (str f))))))
(defn approval-token [res]
  (second (re-find #"APPROVAL_TOKEN:\s*(\S+)" (:all res))))
(defn approve! [project token]
  (write-file (fs/path (state project) "approval" "APPROVED") token))
(defn head [project] (str/trim (:out (git! project "rev-parse" "HEAD"))))
(defn sut [project] (slurp (str (fs/path project "sut.sh"))))
(defn manifest [project]
  (let [f (fs/path (state project) "manifest.json")]
    (when (fs/exists? f) (json/parse-string (slurp (str f)) true))))

(defn prompt-line
  "The single seeded-prompt line beginning with `prefix` (its whole content), or
   nil. Used for EXACT-line injection assertions (D22 coda): a substring check is
   exactly what let a bled TEST_CMD/HANDOFF_PATH line 'pass for the wrong reason'
   — the injected machine line must be precisely the value, nothing trailing."
  [prompt prefix]
  (->> (str/split-lines prompt)
       (filter #(str/starts-with? % prefix))
       first))

;; --- the human gate: specify runs, then the run pauses -----------------------

(deftest specify-runs-then-pauses-for-approval
  (let [p (make-project!)
        base (head p)
        r1 (run-solo! p "happy")]
    (is (zero? (:exit r1)) (str "the first invocation should pause cleanly:\n" (:all r1)))
    (testing "it paused at the gate awaiting approval"
      (is (re-find #"(?i)approval" (:all r1)))
      (is (= "awaiting_approval" (status p))))
    (testing "the spec was produced"
      (is (fs/exists? (fs/path (state p) "spec" "spec.md")))
      (is (fs/exists? (fs/path (state p) "spec" "features" "toy.feature"))))
    (testing "NO code ran — sut.sh untouched, no new commit (R6: approval before code)"
      (is (str/includes? (sut p) "broken"))
      (is (= base (head p))))
    (testing "a token was offered for approval"
      (is (some? (approval-token r1)))
      (is (= (approval-token r1)
             (slurp (str (fs/path (private-state-root p) "solo"
                                  (str/trim (:out (sh/sh "bash" "-c"
                                                         "printf '%s' \"$1\" | sha256sum | awk '{print $1}'"
                                                         "_" (str (fs/absolutize p)))))
                                  "approval.token"))))
          "the same generated token is retained outside the project for craft-harness approve"))))

(deftest without-approval-the-run-stays-paused
  (let [p (make-project!) base (head p)]
    (run-solo! p "happy")
    (testing "re-running with no approval file does not proceed to code"
      (let [r2 (run-solo! p "happy")]
        (is (= "awaiting_approval" (status p)))
        (is (= base (head p)) "code must not have run")))))

(deftest forged-approval-is-rejected
  (let [p (make-project!) base (head p)]
    (run-solo! p "happy")
    (approve! p "not-the-real-token")
    (let [r2 (run-solo! p "happy")]
      (testing "an approval with the wrong token does not open the gate"
        (is (= "awaiting_approval" (status p)))
        (is (= base (head p)))))))

;; --- approval opens the gate; the whole pipeline completes -------------------

(deftest approved-run-completes-all-phases
  (let [p (make-project!)
        base (head p)
        r1 (run-solo! p "happy")
        _ (approve! p (approval-token r1))
        r2 (run-solo! p "happy")]
    (is (zero? (:exit r2)) (str "the approved run must complete:\n" (:all r2)))
    (testing "code ran: sut.sh fixed and committed on main"
      (is (str/includes? (sut p) "42"))
      (is (not= base (head p)))
      (is (= "main" (str/trim (:out (git! p "rev-parse" "--abbrev-ref" "HEAD"))))))
    (testing "every phase left a schema-valid handoff"
      (doseq [phase ["specify" "code" "verify"]]
        (let [h (fs/path (state p) "handoffs" (str phase ".handoff"))]
          (is (fs/exists? h) (str phase ".handoff"))
          (is (zero? (:exit (sh/sh "bb" (str (fs/path repo-root "bin" "handoff-validate.bb")) (str h))))
              (str phase " handoff must be schema-valid")))))
    (testing "the run manifest records success (R10 provenance)"
      (let [m (manifest p)]
        (is (= "solo-pack" (str (:pack m))))
        (is (= "success" (str (:outcome m))))
        (is (= ["specify" "code" "verify"] (mapv :name (:phases m))))))
    (testing "the session is no longer in flight"
      (is (= "done" (status p))))))

(deftest verifier-claim-cannot-bypass-runner-owned-test
  (testing "a verifier that claims pass without running a failing test cannot make the run green"
    (let [p (make-project!)
          r1 (run-solo! p "falseclaim")
          _ (approve! p (approval-token r1))
          r2 (run-solo! p "falseclaim")]
      (is (not (zero? (:exit r2))))
      (is (re-find #"phase 'test'.*declared command failed" (:all r2)))
      (is (= "failed" (status p)))
      (is (str/includes? (slurp (str (fs/path (state p) "commands.tsv")))
                         "test\t")))))

(deftest declared-quality-commands-are-run-in-order-and-enforced
  (let [p (make-project!)]
    (write-file (fs/path p "project.prompt")
                (str "owns:\n  sut.sh\ntest: ./test.sh\nquality:\n"
                     "  first: test -f sut.sh\n  second: grep -q 42 sut.sh\n"))
    (git! p "add" "project.prompt")
    (git! p "commit" "-q" "-m" "test: declare quality gates")
    (let [r1 (run-solo! p "happy")
          _ (approve! p (approval-token r1))
          r2 (run-solo! p "happy")
          records (str/split-lines (slurp (str (fs/path (state p) "commands.tsv"))))]
      (is (zero? (:exit r2)) (:all r2))
      (is (= ["test" "quality:first" "quality:second"]
             (mapv #(first (str/split % #"\t")) records))))))

(deftest failing-quality-command-stops-before-success
  (let [p (make-project!)]
    (write-file (fs/path p "project.prompt")
                "owns:\n  sut.sh\ntest: ./test.sh\nquality:\n  architecture: false\n")
    (git! p "add" "project.prompt")
    (git! p "commit" "-q" "-m" "test: declare failing quality gate")
    (let [r1 (run-solo! p "happy")
          _ (approve! p (approval-token r1))
          r2 (run-solo! p "happy")]
      (is (not (zero? (:exit r2))))
      (is (re-find #"phase 'quality:architecture'" (:all r2)))
      (is (= "failed" (status p))))))

;; --- end-to-end: a completed solo run inspects green -------------------------

(deftest completed-run-inspects-green
  (testing "the session run-solo produces is accepted by the solo inspector"
    (let [p (make-project!)
          r1 (run-solo! p "happy")
          _ (approve! p (approval-token r1))
          _ (run-solo! p "happy")
          inspect (str (fs/path repo-root "bin" "inspect-run"))
          r (sh/sh "bash" inspect (str (state p)) (str p)
                   :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")
                         "GIT_CONFIG_NOSYSTEM" "1"
                         "CRAFT_HARNESS_PRIVATE_STATE" (private-state-root p)})
          out (str (:out r) (:err r))]
      (is (zero? (:exit r)) (str "the solo inspector must be green on a real run-solo session:\n" out))
      (is (str/includes? out "RESULT: PASS"))
      (testing "the runner recorded its exact successful test command"
        (is (str/includes? (slurp (str (fs/path (state p) "commands.tsv")))
                           "test\t"))))))

(deftest tampered-command-record-is-rejected
  (testing "runner-owned command evidence is authenticated, not trusted as plain project state"
    (let [p (make-project!)
          r1 (run-solo! p "happy")
          _ (approve! p (approval-token r1))
          r2 (run-solo! p "happy")
          commands (fs/path (state p) "commands.tsv")
          inspect (str (fs/path repo-root "bin" "inspect-run"))]
      (is (zero? (:exit r2)) (:all r2))
      ;; Preserve the exact label/rc/command structure the old inspector checked;
      ;; change only a numeric duration so rejection proves authentication.
      (let [[label _duration rc command] (str/split (str/trim (slurp (str commands))) #"\t")]
        (spit (str commands) (str label "\t999\t" rc "\t" command "\n")))
      (let [r (sh/sh "bash" inspect (str (state p)) (str p)
                     :env {"PATH" (System/getenv "PATH")
                           "HOME" (System/getenv "HOME")
                           "GIT_CONFIG_NOSYSTEM" "1"
                           "CRAFT_HARNESS_PRIVATE_STATE" (private-state-root p)})
            out (str (:out r) (:err r))]
        (is (not (zero? (:exit r))) "tampered evidence must not inspect green")
        (is (re-find #"(?i)tamper|authentic|digest|command evidence" out))))))

;; --- a phase's invalid handoff stops the run with attribution ----------------

(deftest invalid-handoff-stops-the-run-naming-phase-and-field
  (let [p (make-project!)
        r1 (run-solo! p "badhandoff")]
    (is (not (zero? (:exit r1))) "an invalid handoff must fail the run")
    (testing "the failure names the phase and the offending field"
      (is (re-find #"(?i)specify" (:all r1)))
      (is (str/includes? (:all r1) "assumptions")))
    (testing "the gate was never opened (specify's handoff failed first)"
      (is (not= "done" (status p))))))

;; --- crash mid-run is visible as an in-flight session (R8/D6) -----------------

(deftest paused-session-is-in-flight-for-doctor
  (let [p (make-project!)]
    (run-solo! p "happy")
    (let [launcher (str (fs/path repo-root "bin" "craft-harness"))
          r (sh/sh "bash" launcher "doctor" "--project" (str p)
                   :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")
                         "GIT_CONFIG_NOSYSTEM" "1"})]
      (is (not (zero? (:exit r))) "doctor must not report healthy while a solo session is in flight")
      (is (re-find #"(?i)in.?flight|solo" (str (:out r) (:err r)))))))

;; --- B3: verifier isolation --------------------------------------------------

(defn drive-to-verify!
  "Run specify, approve, then continue — returns the second invocation's result."
  [p variant]
  (let [r1 (run-solo! p variant)]
    (approve! p (approval-token r1))
    (run-solo! p variant)))

(deftest verifier-at-the-wrong-commit-turns-the-run-red
  (testing "a worktree planted at baseline (not the candidate) must fail the run"
    (let [p (make-project!)
          r2 (binding [*extra-env* {"CRAFT_SOLO_TEST_VERIFY_AT_BASELINE" "1"}]
               (drive-to-verify! p "happy"))]
      (is (not (zero? (:exit r2)))
          "verify against the baseline (sut still broken) must turn the run red")
      (is (re-find #"(?i)verify" (:all r2)) "the failure must be attributed to verify")
      (is (not= "done" (status p))))))

(deftest verify-workdir-is-clean-of-prior-phase-scratch
  (testing "the verifier's checkout carries no transcript or scratch from earlier phases"
    (let [p (make-project!)
          _ (drive-to-verify! p "happy")
          listing (slurp (str (fs/path (state p) "verify-listing.txt")))]
      (testing "it sees the candidate's tracked files"
        (is (re-find #"(?m)^sut\.sh$" listing))
        (is (re-find #"(?m)^test\.sh$" listing))
        (is (re-find #"(?m)^task\.md$" listing)))
      (testing "no listed entry is solo state, the spec, handoffs, or a phase log"
        ;; check ENTRY lines (ls -a1 output), not the cwd= path which naturally
        ;; contains .craft-harness because the worktree lives under it.
        (is (not (re-find #"(?m)^\.craft-harness$" listing))
            "the solo session dir must not appear as an entry in the verify workdir")
        (is (not (re-find #"(?m)^spec\.md$" listing)))
        (is (not (re-find #"(?m)^handoffs$" listing)))
        (is (not (re-find #"(?m)^specify\.log$" listing))))
      (testing "the verify workdir is a separate path from the project"
        (is (re-find #"cwd=.*verify-workdir" listing))))))

;; --- B4: breakers (R10) ------------------------------------------------------

(defn run-solo-timed! [project variant & extra]
  (let [start (System/currentTimeMillis)
        r (apply run-solo! project variant extra)]
    (assoc r :elapsed-ms (- (System/currentTimeMillis) start))))

(deftest stuck-phase-is-killed-by-the-per-phase-timeout-with-attribution
  (testing "a hanging phase is killed and the failure is attributed"
    (let [p (make-project!)
          r (run-solo-timed! p "stuck" "--phase-timeout" "1" "--retry-cap" "1")]
      (is (not (zero? (:exit r))))
      (is (< (:elapsed-ms r) 60000)
          (str "the breaker must return within a bound, took " (:elapsed-ms r) "ms"))
      (is (re-find #"(?i)specify" (:all r)) "attributed to the phase that hung")
      (is (re-find #"(?i)timeout" (:all r)) "named as a timeout")
      (is (not= "done" (status p))))))

(deftest retry-cap-trips-and-stops-the-run
  (testing "a phase that keeps failing is retried up to the cap, then the run stops"
    (let [p (make-project!)
          r (run-solo-timed! p "stuck" "--phase-timeout" "1" "--retry-cap" "3")]
      (is (not (zero? (:exit r))))
      (is (< (:elapsed-ms r) 60000)
          (str "retries must stay bounded, took " (:elapsed-ms r) "ms"))
      (is (re-find #"(?i)retry cap" (:all r)) "the failure must name the retry cap")
      (testing "it actually retried (attempts logged up to the cap)"
        (is (re-find #"attempt 3/3" (:all r)))))))

;; --- D16: phase content comes from the solo-pack pack branch -----------------

(defn branch-role-body [pack phase]
  (str/trim (:out (sh/sh "git" "-C" (str repo-root) "show"
                         (str pack ":swarmforge/roles/" phase ".prompt")))))

(deftest seeded-prompts-are-sourced-from-the-solo-pack-branch
  (testing "each seeded phase prompt carries the body from solo-pack's role file"
    (let [p (make-project!)]
      (run-solo! p "happy")                 ; pauses at the gate; all prompts seeded
      (doseq [phase ["specify" "code" "verify"]]
        (let [seeded (slurp (str (fs/path (state p) "prompts" (str phase ".prompt"))))
              body (branch-role-body "solo-pack" phase)]
          (is (not (str/blank? body)) (str "solo-pack must carry a " phase " role prompt"))
          (is (str/includes? seeded body)
              (str phase ".prompt must be sourced from the solo-pack branch")))))))

(deftest unknown-pack-branch-fails-cleanly-naming-the-pack
  (testing "run-solo resolves --pack against the fork and refuses an absent one"
    (let [p (make-project!)
          r (run-solo! p "happy" "--pack" "no-such-pack-branch")]
      (is (not (zero? (:exit r))) "an absent pack branch must stop the run")
      (is (re-find #"(?i)pack" (:all r)))
      (is (str/includes? (:all r) "no-such-pack-branch")
          "the message must name the missing pack"))))

(deftest a-pack-without-the-solo-phase-list-is-rejected
  (testing "a branch whose conf is not the solo phase sequence is rejected (two-pack-lite)"
    (let [p (make-project!)
          base (head p)
          r (run-solo! p "happy" "--pack" "two-pack-lite")]
      (is (not (zero? (:exit r))) "two-pack-lite has no solo phase list — must be rejected")
      (is (str/includes? (:all r) "two-pack-lite") "the failure must name the offending pack")
      (is (re-find #"(?i)phase list|sequence" (:all r)) "and the phase-list mismatch")
      (is (= base (head p)) "no phase may run against a mis-shaped pack"))))

;; --- m4.5 / D17 / D19: the owned-path contract on the candidate commit -------
;; run-solo must scope the candidate commit to project.prompt's `owns:` set:
;; after the code phase, every path the commit touches must match an owned glob,
;; else the run FAILS before verify, naming the offending paths. No owns-set =>
;; no check (existing tests above run without a project.prompt and are unaffected).

(defn write-owns! [project block]
  (write-file (fs/path project "project.prompt")
              (str "Project role prompt — prose the agent reads.\n\nowns:\n" block "\ntest: ./test.sh\n"))
  (git! project "add" "project.prompt")
  (git! project "commit" "-q" "-m" "test: update project contract"))

(defn candidate-touched
  "Paths the candidate commit changed vs the baseline commit `base`."
  [project base]
  (->> (:out (git! project "diff" "--name-only" base "HEAD"))
       str/split-lines (remove str/blank?) set))

(deftest owned-scope-in-bounds-run-completes
  (testing "when the candidate commit touches only owned paths, the run completes"
    (let [p (make-project!)
          _ (write-owns! p "  sut.sh")
          base (head p)
          r1 (run-solo! p "happy")
          _ (approve! p (approval-token r1))
          r2 (run-solo! p "happy")]
      (is (zero? (:exit r2)) (str "an in-scope run must complete:\n" (:all r2)))
      (is (= "done" (status p)))
      (is (= #{"sut.sh"} (candidate-touched p base))
          "the candidate commit touched only the owned path"))))

(deftest planted-out-of-scope-file-turns-the-run-red
  (testing "D17: a candidate commit touching a path outside owns fails before verify"
    (let [p (make-project!)
          _ (write-owns! p "  sut.sh")
          base (head p)
          r1 (run-solo! p "outofscope")
          _ (approve! p (approval-token r1))
          r2 (run-solo! p "outofscope")]
      (is (not (zero? (:exit r2)))
          "a commit that sweeps an out-of-scope file must turn the run red")
      (testing "the failure names the offending path and is attributed to code / owned paths"
        (is (str/includes? (:all r2) "notes.txt") "the offending path must be named")
        (is (re-find #"(?i)owned|owns|scope" (:all r2))))
      (testing "the run stopped BEFORE verify (R4: the verifier never judged a contaminated commit)"
        (is (not (fs/exists? (fs/path (state p) "handoffs" "verify.handoff")))
            "verify must not have produced a handoff")
        (is (not= "done" (status p)))))))

(deftest dirty-tree-does-not-contaminate-the-candidate-commit
  (testing "the exact myCQRS condition: unrelated working-tree dirt present, but a scoped agent commits only owned paths and the run passes"
    (let [p (make-project!)
          _ (write-owns! p "  sut.sh")
          ;; unrelated dirt in the working tree, outside the owned set — the
          ;; kind a naive `git add -A` would sweep into the candidate (D17/D18).
          _ (write-file (fs/path p "dirt.txt") "pre-existing junk\n")
          base (head p)
          r1 (run-solo! p "happy")
          _ (approve! p (approval-token r1))
          r2 (run-solo! p "happy")]
      (is (zero? (:exit r2)) (str "dirt present must not fail a scoped run:\n" (:all r2)))
      (is (= #{"sut.sh"} (candidate-touched p base))
          "the candidate commit must carry only the owned path, never the dirt")
      (testing "the dirt is still just sitting in the working tree, uncommitted"
        (is (fs/exists? (fs/path p "dirt.txt")))
        (is (re-find #"(?m)dirt\.txt" (:out (git! p "status" "--porcelain")))
            "dirt.txt stayed untracked/uncommitted")))))

(deftest a-malformed-owned-path-contract-stops-the-run-early
  (testing "a malformed owns: block fails fast (fail-closed), before any phase runs"
    (let [p (make-project!)
          _ (write-owns! p "  ../escape/**")     ; traversal — malformed
          base (head p)
          r (run-solo! p "happy")]
      (is (not (zero? (:exit r))) "a malformed contract must stop the run")
      (is (re-find #"(?i)owns|owned|contract|project\.prompt" (:all r)))
      (is (= base (head p)) "no phase may run against a malformed contract")
      (is (not (fs/exists? (fs/path (state p) "spec" "spec.md")))
          "specify must not have run"))))

;; --- m4.6 / D21: handoff routing must not depend on agent-side env expansion --
;; The seeded prompt must carry the RESOLVED ABSOLUTE handoff path literally, so a
;; confined headless agent (which under acceptEdits cannot shell-expand
;; $SOLO_HANDOFF) can still route its handoff where run-solo reads it. Proven with
;; fake agents that read the path ONLY from the prompt — zero paid runs.

(deftest handoff-path-is-injected-literally-into-each-phase-prompt
  (testing "each seeded phase prompt names the absolute handoff path on an EXACTLY-terminated line"
    (let [p (make-project!)]
      (run-solo! p "happy")            ; seeds all phase prompts, pauses at the gate
      (doseq [phase ["specify" "code" "verify"]]
        (let [prompt (slurp (str (fs/path (state p) "prompts" (str phase ".prompt"))))
              expected (str (fs/path (state p) "handoffs" (str phase ".handoff")))]
          ;; EXACT line, not substring (D22 coda): nothing may bleed onto it.
          (is (= (str "HANDOFF_PATH: " expected) (prompt-line prompt "HANDOFF_PATH:"))
              (str phase ".prompt HANDOFF_PATH line must be exactly the path, nothing trailing")))))))

(deftest confined-agent-routes-handoff-from-the-prompt-not-the-env
  (testing "an agent that finds the handoff path ONLY in the prompt (never $SOLO_HANDOFF) completes the pipeline"
    (let [p (make-project!)
          r1 (run-solo! p "confined")]
      (is (zero? (:exit r1)) (str "confined specify must reach the gate:\n" (:all r1)))
      (is (= "awaiting_approval" (status p)))
      (approve! p (approval-token r1))
      (let [r2 (run-solo! p "confined")]
        (is (zero? (:exit r2)) (str "the confined run must complete when the path is injected:\n" (:all r2)))
        (is (= "done" (status p)))
        (testing "every phase landed a schema-valid handoff at the runner-expected path"
          (doseq [phase ["specify" "code" "verify"]]
            (let [h (fs/path (state p) "handoffs" (str phase ".handoff"))]
              (is (fs/exists? h) (str phase ".handoff must exist at the runner-expected path"))
              (is (zero? (:exit (sh/sh "bb" (str (fs/path repo-root "bin" "handoff-validate.bb")) (str h))))
                  (str phase " handoff must be schema-valid")))))))))

(deftest a-misrouted-handoff-fails-the-run-attributed
  (testing "a phase that writes its handoff to the wrong filename turns the run red, attributed"
    (let [p (make-project!)
          base (head p)
          r1 (run-solo! p "misroute")]
      (is (not (zero? (:exit r1))) "a mis-routed handoff must fail the run")
      (is (re-find #"(?i)specify" (:all r1)) "attributed to the phase")
      (is (re-find #"(?i)handoff" (:all r1)) "names the handoff as the problem")
      (testing "the gate never opened and no valid handoff exists at the expected path"
        (is (not (fs/exists? (fs/path (state p) "handoffs" "specify.handoff"))))
        (is (not= "awaiting_approval" (status p)))
        (is (= base (head p)) "no code ran")))))

;; --- m4.7 / D22: code/verify target the project's DECLARED test command ------
;; The pack's code/verify role prompts must not hard-code the toy ./test.sh:
;; run-solo reads a strict `test:` line from project.prompt and injects it as a
;; literal TEST_CMD: line. Proven against a NON-TOY (Maven-shaped) fixture whose
;; test command is not ./test.sh — deliberately unlike the toy, to defeat the
;; "fixtures-mirror-the-toy blindness" (D22). Fake mvn, zero paid runs.

(def fake-mvn-dir (str (fs/path repo-root "test" "fixtures" "fake-mvn")))

(defn make-maven-project!
  "A Maven-shaped project stub whose declared test command is NOT ./test.sh and
   which has NO repo-local git identity (D23): the baseline is committed with an
   ephemeral -c identity that is not stored, so run-solo must seed one before the
   code phase can commit. The real myCQRS condition — no identity anywhere."
  []
  (let [dir (fs/path (tmp-dir) "mvnproj")]
    (fs/create-dirs (fs/path dir "src" "core"))
    (git! dir "init" "-q" "-b" "main")
    (write-file (fs/path dir "pom.xml")
                "<project><modules><module>src/core</module></modules></project>\n")
    (write-file (fs/path dir "task.md")
                "---\nhuman-approved: true\n---\n# Task: add the core impl so the module tests pass.\n")
    (write-file (fs/path dir "project.prompt")
                (str "Project: a Maven-shaped stub for the D22/D23 tests.\n"
                     "test: mvn -q -pl src/core test\n"
                     "owns:\n  src/core/**\n"))
    (git! dir "add" "-A")
    ;; ephemeral identity for the baseline only — NOT written to repo config.
    (git! dir "-c" "user.name=Setup" "-c" "user.email=setup@example.invalid"
          "commit" "-q" "-m" "maven baseline")
    dir))

(defn path-without-tool
  "The current PATH with every directory that resolves `tool` removed — so the
   'tool absent' case holds even on hosts where the real tool is installed."
  [tool]
  (->> (str/split (or (System/getenv "PATH") "") #":")
       (remove (fn [dir] (let [f (fs/path dir tool)]
                           (and (fs/exists? f) (fs/executable? f)))))
       (str/join ":")))

(defn run-solo-maven*
  "run-solo against the Maven-shaped fixture under a CLEAN, identity-free HOME
   (no global git identity leaks in — so identity seeding is deterministic and
   matches the real myCQRS condition). `on-path?` puts the fake mvn on PATH."
  [project variant on-path? extra]
  (binding [*extra-env* {"PATH" (if on-path?
                                  (str fake-mvn-dir ":" (System/getenv "PATH"))
                                  (path-without-tool "mvn"))
                         "HOME" (str (tmp-dir))
                         "GIT_CONFIG_NOSYSTEM" "1"}]
    (apply run-solo! project variant extra)))

(defn run-solo-maven!
  "run-solo against the Maven fixture WITH the fake mvn on PATH (tool present)."
  [project variant & extra]
  (run-solo-maven* project variant true extra))

(defn run-solo-maven-no-mvn!
  "run-solo against the Maven fixture with NO mvn on PATH (tool absent)."
  [project variant & extra]
  (run-solo-maven* project variant false extra))

(deftest code-and-verify-prompts-carry-the-projects-declared-test-command
  (testing "a project that declares `test:` gets THAT command in code/verify prompts, not ./test.sh"
    (let [p (make-maven-project!)]
      (run-solo-maven! p "maven")            ; seeds all prompts, pauses at the gate
      (doseq [phase ["code" "verify"]]
        (let [prompt (slurp (str (fs/path (state p) "prompts" (str phase ".prompt"))))]
          ;; EXACT line, not substring (D22 coda): a bled TEST_CMD — the next
          ;; instruction running onto this line — must FAIL this assertion.
          (is (= "TEST_CMD: mvn -q -pl src/core test" (prompt-line prompt "TEST_CMD:"))
              (str phase ".prompt TEST_CMD line must be exactly the declared command, nothing trailing"))
          (is (not (re-find #"(?i)\./test\.sh|\bsut\.sh\b" prompt))
              (str phase ".prompt must NOT hard-code the toy ./test.sh / sut.sh")))))))

(deftest a-non-toy-project-drives-green-through-the-generic-prompts
  (testing "D22 root-cause kill: a Maven-shaped project (test cmd != ./test.sh) runs green end to end"
    (let [p (make-maven-project!)
          base (head p)
          r1 (run-solo-maven! p "maven")]
      (is (zero? (:exit r1)) (str "specify must reach the gate:\n" (:all r1)))
      (is (= "awaiting_approval" (status p)))
      (approve! p (approval-token r1))
      (let [r2 (run-solo-maven! p "maven")]
        (is (zero? (:exit r2)) (str "the non-toy run must complete via the declared command:\n" (:all r2)))
        (is (= "done" (status p)))
        (is (not= base (head p)) "a candidate commit was produced")
        (testing "the candidate is scoped to the owned path and carries the impl"
          (is (= #{"src/core/impl.txt"} (candidate-touched p base))))
        (testing "the verify handoff names the declared command, never ./test.sh"
          (let [h (slurp (str (fs/path (state p) "handoffs" "verify.handoff")))]
            (is (str/includes? h "mvn -q -pl src/core test"))
            (is (not (re-find #"(?i)\./test\.sh" h)))))))))

(deftest absent-test-declaration-fails-closed
  (testing "a project with no `test:` line is rejected before any phase"
    (let [p (make-project!)]
      (write-file (fs/path p "project.prompt") "owns:\n  sut.sh\n")
      (git! p "add" "project.prompt")
      (git! p "commit" "-q" "-m" "test: remove test command")
      (let [r (run-solo! p "happy")]
        (is (not (zero? (:exit r))))
        (is (re-find #"(?i)test.*missing|invalid project contract" (:all r)))
        (is (not (fs/exists? (state p))))))))

;; --- m4.8 / D23: inherit-env — commit identity + toolchain reachability ------
;; run-solo must guarantee the code phase can author its commit even when the
;; project has no git identity (seed one repo-locally), and the phase must be
;; able to invoke the project's declared test command when its tool is on PATH —
;; failing ATTRIBUTED, never silently, when it is not. Driven by the non-toy
;; Maven fixture (which now has NO identity) — the toy never needed either.

(defn local-ident [project field]
  (:out (sh-run {:dir project} "git" "config" "--local" (str "user." field))))

(deftest a-project-without-git-identity-gets-one-seeded-before-code
  (testing "D23: run-solo seeds a repo-local commit identity so the code phase can commit"
    (let [p (make-maven-project!)]
      (testing "the fixture starts with NO repo-local identity"
        (is (not (zero? (:exit (sh-run {:dir p} "git" "config" "--local" "user.email"))))))
      (let [base (head p)
            r1 (run-solo-maven! p "maven")]
        (is (= "noreply@craft-harness.local" (str/trim (local-ident p "email")))
            "preflight seeded identity before the first specify turn")
        (approve! p (approval-token r1))
        (let [r2 (run-solo-maven! p "maven")]
          (is (zero? (:exit r2)) (str "the run must complete once identity is seeded:\n" (:all r2)))
          (is (not= base (head p)) "a candidate commit was produced")
          (testing "the seeded harness identity authored the candidate commit"
            (is (= "craft-harness" (str/trim (:out (git! p "log" "-1" "--format=%an")))))
            (is (= "noreply@craft-harness.local" (str/trim (:out (git! p "log" "-1" "--format=%ae"))))))
          (testing "the identity was seeded repo-locally (not globally)"
            (is (= "noreply@craft-harness.local" (str/trim (local-ident p "email"))))))))))

(deftest an-absent-test-tool-fails-preflight-before-any-phase
  (testing "D39: a missing declared tool fails before specify spends an agent turn"
    (let [p (make-maven-project!)
          base (head p)
          r1 (run-solo-maven-no-mvn! p "maven")]
      (is (= 42 (:exit r1)))
      (is (= base (head p)) "no candidate was produced")
      (is (nil? (status p)) "no phase/session state was created")
      (is (re-find #"(?i)test.*mvn.*PATH" (:all r1))))))

(deftest configured-developer-identity-authors-the-candidate
  (testing "D39: preflight preserves the developer's resolved identity"
    (let [p (make-maven-project!)]
      (git! p "config" "user.name" "Real Developer")
      (git! p "config" "user.email" "developer@example.test")
      (let [r1 (run-solo-maven! p "maven")]
        (approve! p (approval-token r1))
        (let [r2 (run-solo-maven! p "maven")]
          (is (zero? (:exit r2)) (:all r2))
          (is (= "Real Developer <developer@example.test>"
                 (str/trim (:out (git! p "log" "-1" "--format=%an <%ae>"))))))))))


(deftest a-present-test-tool-is-invoked-through-inherited-path
  (testing "D23: when the tool IS on PATH, the phase resolves and runs it (inherited, not provisioned)"
    (let [p (make-maven-project!)
          base (head p)
          r1 (run-solo-maven! p "maven")
          _ (approve! p (approval-token r1))
          r2 (run-solo-maven! p "maven")]
      (is (zero? (:exit r2)) (str "with the tool on PATH the run completes:\n" (:all r2)))
      (is (= "done" (status p)))
      (testing "verify actually ran the declared command (its handoff names it)"
        (let [h (slurp (str (fs/path (state p) "handoffs" "verify.handoff")))]
          (is (str/includes? h "mvn -q -pl src/core test")))))))

;; --- interface hygiene -------------------------------------------------------

(deftest run-solo-usage-is-clear
  (let [r (sh/sh "bash" run-solo :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")})]
    (is (not (zero? (:exit r))))
    (is (re-find #"(?i)usage" (str (:out r) (:err r))))))
