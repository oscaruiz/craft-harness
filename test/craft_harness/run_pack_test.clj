(ns craft-harness.run-pack-test
  "Milestone 3, behavior B5 (docs/current-milestone.md): the session driver
   bin/run-pack. It boots a real tmux window per role (default shell, D9),
   send-keys the faithful claude launch string, plays the daemon's delivery
   and wake-up role (D7) with the R10 breakers (wake-up cap + per-session
   timeout), captures the panes into a session dir, and hands off to
   bin/inspect-run. Exercised with a fake `claude` on PATH — zero paid runs.

   The two star behaviors of the exit criteria are here: run-pack refuses
   without task.md (R6), and the wake-up cap breaker kills a session that
   never quiesces."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def run-pack (str (fs/path repo-root "bin" "run-pack")))
(def fake-bin (str (fs/path repo-root "test" "fixtures" "two-pack")))

(def tmux? (zero? (:exit (sh/sh "bash" "-c" "command -v tmux >/dev/null 2>&1"))))

(def ^:dynamic *sandboxes* nil)

(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t)
           (finally (doseq [d @*sandboxes*]
                      (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m3-runpack."})]
    (swap! *sandboxes* conj d)
    d))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn sh-run [{:keys [dir]} & args]
  (apply sh/sh (concat args [:dir (str dir)
                             :env {"PATH" (System/getenv "PATH")
                                   "HOME" (System/getenv "HOME")
                                   "GIT_CONFIG_NOSYSTEM" "1"}])))

(defn git! [dir & args]
  (let [r (apply sh-run {:dir dir} "git" args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "git failed: " (str/join " " args)) r)))
    r))

(def conf
  ;; the two-pack-lite window shape (adapters/CONTRACT.md Mode 2); worktree
  ;; token is the pack's, run-pack maps it to the project root for the toy run.
  "window coder claude master task\nwindow cleaner claude cleaner batch\n")

(defn make-project!
  "A toy project ready for the pack: baseline commit on main, task.md, broken
   sut.sh, and a materialized swarmforge/ (conf + role prompts). If
   :with-task? is false, task.md is omitted (the R6 negative case)."
  [& {:keys [with-task?] :or {with-task? true}}]
  (let [dir (fs/path (tmp-dir) "project")]
    (fs/create-dirs dir)
    (git! dir "init" "-q" "-b" "main")
    (git! dir "config" "user.email" "toy@example.com")
    (git! dir "config" "user.name" "Toy User")
    (when with-task?
      (write-file (fs/path dir "task.md") "Make ./test.sh pass: sut.sh must print 42.\n"))
    (write-file (fs/path dir "sut.sh") "#!/usr/bin/env bash\necho broken\n")
    (write-file (fs/path dir "test.sh")
                "#!/usr/bin/env bash\nset -euo pipefail\n[[ \"$(bash ./sut.sh)\" == \"42\" ]]\n")
    (write-file (fs/path dir "swarmforge" "swarmforge.conf") conf)
    (write-file (fs/path dir "swarmforge" "roles" "coder.prompt") "You are the coder.\n")
    (write-file (fs/path dir "swarmforge" "roles" "cleaner.prompt") "You are the cleaner.\n")
    (sh-run {:dir dir} "chmod" "+x" "sut.sh" "test.sh")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "-m" "toy baseline")
    dir))

(defn run-pack! [project out {:keys [variant wake-cap session-timeout idle-grace]
                              :or {variant "happy" wake-cap 8 session-timeout 60 idle-grace 2}}
                 & extra]
  (let [start (System/currentTimeMillis)
        args (concat ["bash" run-pack
                      "--project" (str project) "--out" (str out)
                      "--wake-cap" (str wake-cap)
                      "--session-timeout" (str session-timeout)
                      "--idle-grace" (str idle-grace)]
                     extra
                     [:env {"PATH" (str fake-bin ":" (System/getenv "PATH"))
                            "HOME" (System/getenv "HOME")
                            "GIT_CONFIG_NOSYSTEM" "1"
                            "RUNPACK_FAKE" variant}])
        r (apply sh/sh args)]
    (assoc r :all (str (:out r) (:err r)) :elapsed-ms (- (System/currentTimeMillis) start))))

(defn manifest [out]
  (let [f (fs/path out "manifest.json")]
    (when (fs/exists? f) (json/parse-string (slurp (str f)) true))))

(defn slurp-if [p] (when (fs/exists? p) (slurp (str p))))

;; --- R6: refuse without task.md (no tmux needed) -------------------------

(deftest run-pack-refuses-without-task-md
  (let [project (make-project! :with-task? false)
        out (fs/path (tmp-dir) "session")
        r (run-pack! project out {})]
    (is (not (zero? (:exit r))) "run-pack must refuse to start without task.md (R6)")
    (is (re-find #"(?i)task\.md" (:all r)) "the refusal must name task.md")
    (testing "it refuses BEFORE booting anything"
      (is (not (fs/exists? (fs/path out "logs" "coder.log")))
          "no window should have been launched"))))

(deftest run-pack-usage-is-clear
  (let [r (sh/sh "bash" run-pack :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")})]
    (is (not (zero? (:exit r))))
    (is (re-find #"(?i)usage" (str (:out r) (:err r))))))

;; --- happy path with fakes: the full pipeline, green inspector -----------

(deftest happy-two-pack-completes-and-inspects-green
  (if-not tmux?
    (println "SKIP happy-two-pack: tmux not available on this host")
    (let [project (make-project!)
          out (fs/path (tmp-dir) "session")
          r (run-pack! project out {:variant "happy" :wake-cap 6
                                    :session-timeout 30 :idle-grace 2})]
      (is (zero? (:exit r)) (str "happy run must succeed and inspect green:\n" (:all r)))
      (let [m (manifest out)]
        (is (= "completed" (:outcome m)) (str "outcome should be completed:\n" (:all r)))
        (testing "provenance (R10): the manifest records the run"
          (is (= "two-pack-lite" (str (:pack m))))
          (is (re-matches #"[0-9a-f]{40}" (str (:baseline_commit m))))
          (is (= "main" (str (:enforced_branch m)))))
        (testing "wake-up count stayed within the cap"
          (is (<= (Integer/parseInt (str (:wake_count m))) 6))))
      (testing "per-window panes were captured as evidence"
        (is (fs/exists? (fs/path out "logs" "coder.log")))
        (is (fs/exists? (fs/path out "logs" "cleaner.log")))
        (is (str/includes? (slurp-if (fs/path out "logs" "cleaner.log")) "crap: threshold=6")
            "the cleaner's captured pane must show the CRAP wrapper ran at threshold 6"))
      (testing "the inspector was run and its report saved, green"
        (let [rep (slurp-if (fs/path out "inspect-report.txt"))]
          (is (some? rep) "inspect-report.txt must exist")
          (is (str/includes? (str rep) "RESULT: PASS")
              (str "the inspector must be green on the real evidence:\n" rep))))
      (testing "the pack actually did work: HEAD advanced past baseline"
        (let [head (str/trim (:out (git! project "rev-parse" "HEAD")))
              base (str (:baseline_commit (manifest out)))]
          (is (not= head base) "coder+cleaner must have committed")))
      (testing "the tmux session was torn down"
        (let [sock (str (fs/path out "tmux.sock"))
              hs (sh/sh "bash" "-c" (str "tmux -S '" sock "' has-session 2>/dev/null; echo $?"))]
          (is (not (str/includes? (:out hs) "\n0")) "no session should remain on the private socket"))))))

;; --- wake-up cap breaker: the required R10 negative ----------------------

(deftest wake-cap-breaker-kills-a-runaway-session
  (if-not tmux?
    (println "SKIP wake-cap-breaker: tmux not available on this host")
    (let [project (make-project!)
          out (fs/path (tmp-dir) "session")
          r (run-pack! project out {:variant "pingpong" :wake-cap 4
                                    :session-timeout 30 :idle-grace 1})]
      (is (not (zero? (:exit r))) "a never-quiescing session must fail")
      (is (< (:elapsed-ms r) 30000)
          (str "the breaker must fire well before the session timeout, took " (:elapsed-ms r) "ms"))
      (let [m (manifest out)]
        (is (= "wake_cap_exceeded" (:outcome m))
            (str "the breaker outcome must be recorded:\n" (:all r)))
        (is (> (Integer/parseInt (str (:wake_count m))) 4)
            "the recorded wake count must exceed the cap"))
      (is (re-find #"(?i)wake|cap" (:all r)) "the failure must name the breaker")
      (testing "the runaway session was torn down by the breaker"
        (let [sock (str (fs/path out "tmux.sock"))
              hs (sh/sh "bash" "-c" (str "tmux -S '" sock "' has-session 2>/dev/null; echo $?"))]
          (is (not (str/includes? (:out hs) "\n0"))))))))

;; --- session-timeout breaker: whole-run bound (R10) ----------------------

(deftest session-timeout-breaker-kills-a-stuck-session
  (if-not tmux?
    (println "SKIP session-timeout-breaker: tmux not available on this host")
    (let [project (make-project!)
          out (fs/path (tmp-dir) "session")
          r (run-pack! project out {:variant "stuck" :wake-cap 8
                                    :session-timeout 4 :idle-grace 1})]
      (is (not (zero? (:exit r))) "a stuck session must fail")
      (is (< (:elapsed-ms r) 20000)
          (str "the whole-run timeout must bound wall-clock, took " (:elapsed-ms r) "ms"))
      (let [m (manifest out)]
        (is (= "timeout" (:outcome m)) (str "the timeout outcome must be recorded:\n" (:all r)))))))
