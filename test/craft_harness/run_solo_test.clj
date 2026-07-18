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
    (write-file (fs/path dir "sut.sh") "#!/usr/bin/env bash\necho broken\n")
    (write-file (fs/path dir "test.sh")
                "#!/usr/bin/env bash\nset -euo pipefail\n[[ \"$(bash ./sut.sh)\" == \"42\" ]]\n")
    (sh-run {:dir dir} "chmod" "+x" "sut.sh" "test.sh")
    (git! dir "add" "-A") (git! dir "commit" "-q" "-m" "toy baseline")
    dir))

(defn run-solo! [project variant & extra]
  (let [r (apply sh/sh "bash" run-solo
                 "--project" (str project)
                 "--adapter" (str (fs/path fixtures variant))
                 (concat extra
                         [:env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")
                                "GIT_CONFIG_NOSYSTEM" "1"}]))]
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
      (is (some? (approval-token r1))))))

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

;; --- interface hygiene -------------------------------------------------------

(deftest run-solo-usage-is-clear
  (let [r (sh/sh "bash" run-solo :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")})]
    (is (not (zero? (:exit r))))
    (is (re-find #"(?i)usage" (str (:out r) (:err r))))))
