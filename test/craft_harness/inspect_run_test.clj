(ns craft-harness.inspect-run-test
  "Milestone 3, behavior B4 (docs/current-milestone.md): the post-run
   inspector bin/inspect-run <session-dir> <project>. Pure deterministic
   checks over the session's logs and the project's git/handoff state — the
   agent's word is never evidence, exactly like the m2 scenario asserts. The
   suite drives it against FABRICATED good and bad session dirs; every bad
   state a check must reject is built here, including the star negative: a
   planted mutation call MUST turn the inspector red.

   The five checks (design §7.3, R3/R6/R9/R10):
     (a) NO mutation invocation anywhere in the session logs — the star assert
     (b) the executed CRAP threshold was 6, read from the wrapper logs
     (c) commits only on the enforced branch, touching no blacklisted path
     (d) handoffs well-formed and consumed
     (e) wake-up count within the R10 cap"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def inspect-run (str (fs/path repo-root "bin" "inspect-run")))

(def ^:dynamic *sandboxes* nil)

(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t)
           (finally (doseq [d @*sandboxes*]
                      (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m3-inspect."})]
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

;; --- fabricating a project repo the coder+cleaner would have left ---------

(def broken-sut "#!/usr/bin/env bash\necho broken\n")
(def fixed-sut "#!/usr/bin/env bash\necho 42\n")
(def toy-test
  "#!/usr/bin/env bash\nset -euo pipefail\n[[ \"$(bash ./sut.sh)\" == \"42\" ]]\n")

(defn make-project!
  "Toy project on branch main: committed baseline, then a coder+cleaner commit
   fixing sut.sh. Returns {:dir :baseline}."
  []
  (let [dir (fs/path (tmp-dir) "project")]
    (fs/create-dirs dir)
    (git! dir "init" "-q" "-b" "main")
    (git! dir "config" "user.email" "toy@example.com")
    (git! dir "config" "user.name" "Toy User")
    (write-file (fs/path dir "task.md") "Make ./test.sh pass: sut.sh must print 42.\n")
    (write-file (fs/path dir "sut.sh") broken-sut)
    (write-file (fs/path dir "test.sh") toy-test)
    (sh-run {:dir dir} "chmod" "+x" "sut.sh" "test.sh")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "-m" "toy baseline")
    (let [baseline (str/trim (:out (git! dir "rev-parse" "HEAD")))]
      (write-file (fs/path dir "sut.sh") fixed-sut)
      (git! dir "add" "sut.sh")
      (git! dir "commit" "-q" "-m" "fix sut (coder+cleaner)")
      {:dir dir :baseline baseline})))

(defn head-10 [{:keys [dir]}]
  (str/trim (:out (git! dir "rev-parse" "--short=10" "HEAD"))))

;; --- fabricating a consumed handoff (swarm_handoff.bb format) -------------

(defn handoff-text [commit-abbrev & {:keys [dequeued? drop-headers to]
                                     :or {dequeued? true drop-headers #{} to "cleaner"}}]
  (let [headers (cond-> [["id" "20260718T120000Z_000001_from_coder"]
                         ["from" "coder"]
                         ["to" to]
                         ["priority" "50"]
                         ["type" "git_handoff"]
                         ["role" "coder"]
                         ["task" "toy-task"]
                         ["commit" commit-abbrev]
                         ["created_at" "2026-07-18T12:00:00Z"]]
                  dequeued? (conj ["dequeued_at" "2026-07-18T12:01:00Z"]))]
    (str (->> headers
              (remove (fn [[k _]] (contains? drop-headers k)))
              (map (fn [[k v]] (str k ": " v)))
              (str/join "\n"))
         "\n\nRe-read your role and constitution.\n")))

(def handoff-name "50_20260718T120000Z_000001_from_coder_to_cleaner.handoff")

(defn put-handoff! [{:keys [dir]} sub text]
  (write-file (fs/path dir ".swarmforge" "handoffs" "inbox" sub handoff-name) text))

;; --- fabricating the session dir (logs/ + manifest.json) -----------------

(def clean-cleaner-log
  ;; what run-pack's pane capture of the cleaner window would contain: the
  ;; wrappers ran and reported, no mutation anywhere.
  (str "[cleaner] running quality gates on changed code\n"
       "$ crap.sh sut.sh\n"
       "crap: score=0\n"
       "crap: threshold=6\n"
       "crap: offenders=none\n"
       "crap: result=pass\n"
       "$ dry.sh sut.sh\n"
       "dry: score=0\n"
       "dry: threshold=0\n"
       "dry: offenders=none\n"
       "dry: result=pass\n"
       "[cleaner] gates green; committing and handing back to coder\n"))

(def clean-coder-log
  "[coder] read task.md, fixed sut.sh, committed, handed off to cleaner\n")

(defn make-session!
  "Session dir with logs/ and manifest.json. opts override log contents and
   manifest fields so each test can plant exactly one defect."
  [project {:keys [coder-log cleaner-log manifest]
            :or {coder-log clean-coder-log cleaner-log clean-cleaner-log}}]
  (let [sdir (fs/path (tmp-dir) "session")
        m (merge {:pack "two-pack-lite"
                  :project (str (:dir project))
                  :adapter "fake"
                  :adapter_info "fake-agent 0.1"
                  :fork_commit (str/join (repeat 40 "0"))
                  :baseline_commit (:baseline project)
                  :enforced_branch "main"
                  :wake_cap 8
                  :wake_count 2
                  :session_timeout_s 600
                  :duration_s 5
                  :outcome "completed"}
                 manifest)]
    (write-file (fs/path sdir "logs" "coder.log") coder-log)
    (write-file (fs/path sdir "logs" "cleaner.log") cleaner-log)
    (write-file (fs/path sdir "manifest.json")
                (str (json/generate-string m {:pretty true}) "\n"))
    sdir))

(defn inspect [session project]
  (let [r (sh/sh "bash" inspect-run (str session) (str (:dir project))
                 :env {"PATH" (System/getenv "PATH")
                       "HOME" (System/getenv "HOME")
                       "GIT_CONFIG_NOSYSTEM" "1"})]
    (assoc r :all (str (:out r) (:err r)))))

(defn green? [r] (zero? (:exit r)))

;; --- the good session: everything passes ---------------------------------

(deftest good-session-is-green
  (let [p (make-project!)
        _ (put-handoff! p "in_process" (handoff-text (head-10 p)))
        s (make-session! p {})
        r (inspect s p)]
    (is (green? r) (str "a clean session must be green:\n" (:all r)))
    (is (re-find #"(?i)pass" (:all r)))))

;; --- (a) mutation: the star negative assert ------------------------------

(deftest planted-mutation-call-turns-it-red
  (testing "a mutate wrapper invocation in the logs MUST be caught"
    (let [p (make-project!)
          _ (put-handoff! p "in_process" (handoff-text (head-10 p)))
          s (make-session! p {:cleaner-log
                              (str clean-cleaner-log
                                   "$ ./mutate.sh sut.sh   # forbidden in this pack\n"
                                   "mutate: 3 mutants killed\n")})
          r (inspect s p)]
      (is (not (green? r)) "a mutation invocation must turn the inspector red")
      (is (re-find #"(?i)mutation|mutate" (:all r))
          "the failure must name the mutation violation")))
  (testing "a PIT (pitest) invocation is also caught"
    (let [p (make-project!)
          _ (put-handoff! p "in_process" (handoff-text (head-10 p)))
          s (make-session! p {:cleaner-log
                              (str clean-cleaner-log
                                   "$ mvn org.pitest:pitest-maven:mutationCoverage\n")})
          r (inspect s p)]
      (is (not (green? r)))))
  (testing "a Stryker run is also caught"
    (let [p (make-project!)
          _ (put-handoff! p "in_process" (handoff-text (head-10 p)))
          s (make-session! p {:cleaner-log
                              (str clean-cleaner-log "$ npx stryker run\n")})
          r (inspect s p)]
      (is (not (green? r))))))

(deftest cleaner-prompt-prose-about-mutation-is-not-a-false-positive
  (testing "the cleaner narrating its compliance ('I will not run mutation testing') is not an invocation"
    (let [p (make-project!)
          _ (put-handoff! p "in_process" (handoff-text (head-10 p)))
          s (make-session! p {:cleaner-log
                              (str "[cleaner] Per my role I will NOT run mutation testing: "
                                   "no PIT, no Stryker, no mutate tool. Quality is CRAP+DRY only.\n"
                                   clean-cleaner-log)})
          r (inspect s p)]
      (is (green? r)
          (str "prose mentioning mutation/PIT/Stryker must not trip the assert:\n" (:all r))))))

;; --- (b) executed CRAP threshold was 6 -----------------------------------

(deftest wrong-crap-threshold-in-the-logs-turns-it-red
  (let [p (make-project!)
        _ (put-handoff! p "in_process" (handoff-text (head-10 p)))
        s (make-session! p {:cleaner-log
                            (str/replace clean-cleaner-log
                                         "crap: threshold=6" "crap: threshold=8")})
        r (inspect s p)]
    (is (not (green? r)) "a CRAP threshold other than 6 must turn it red")
    (is (re-find #"(?i)threshold" (:all r)))
    (is (str/includes? (:all r) "8") "the wrong value must be named")))

(deftest missing-crap-log-turns-it-red
  (testing "if the CRAP wrapper never ran, the gate was not exercised — not valid evidence"
    (let [p (make-project!)
          _ (put-handoff! p "in_process" (handoff-text (head-10 p)))
          s (make-session! p {:cleaner-log "[cleaner] did nothing useful\n"})
          r (inspect s p)]
      (is (not (green? r)))
      (is (re-find #"(?i)crap|threshold" (:all r))))))

;; --- (c) commits only on the enforced branch, no blacklisted path --------

(deftest commit-touching-a-blacklisted-path-turns-it-red
  (let [p (make-project!)]
    ;; agent bypassed the hook with --no-verify and committed task.md
    (write-file (fs/path (:dir p) "task.md") "tampered\n")
    (git! (:dir p) "add" "task.md")
    (git! (:dir p) "commit" "-q" "--no-verify" "-m" "sneaky")
    (put-handoff! p "in_process" (handoff-text (head-10 p)))
    (let [s (make-session! p {})
          r (inspect s p)]
      (is (not (green? r)) "a blacklisted path in the diff must turn it red")
      (is (str/includes? (:all r) "task.md")))))

(deftest commits-on-the-wrong-branch-turn-it-red
  (let [p (make-project!)]
    (git! (:dir p) "switch" "-q" "-c" "rogue")
    (put-handoff! p "in_process" (handoff-text (head-10 p)))
    ;; manifest still says the enforced branch is main, but HEAD is on rogue
    (let [s (make-session! p {})
          r (inspect s p)]
      (is (not (green? r)))
      (is (re-find #"(?i)branch" (:all r))))))

;; --- (d) handoffs well-formed and consumed -------------------------------

(deftest unconsumed-handoff-turns-it-red
  (testing "a handoff left sitting in inbox/new was never consumed"
    (let [p (make-project!)
          _ (put-handoff! p "new" (handoff-text (head-10 p) :dequeued? false))
          s (make-session! p {})
          r (inspect s p)]
      (is (not (green? r)))
      (is (re-find #"(?i)consum|new|handoff" (:all r))))))

(deftest malformed-handoff-turns-it-red
  (testing "a handoff missing a required header is not well-formed"
    (let [p (make-project!)
          _ (put-handoff! p "in_process"
                          (handoff-text (head-10 p) :drop-headers #{"task"}))
          s (make-session! p {})
          r (inspect s p)]
      (is (not (green? r)))
      (is (re-find #"(?i)handoff|header|task" (:all r))))))

(deftest no-handoff-at-all-turns-it-red
  (testing "the pack must have produced at least one handoff"
    (let [p (make-project!)
          s (make-session! p {})
          r (inspect s p)]
      (is (not (green? r)))
      (is (re-find #"(?i)handoff" (:all r))))))

;; --- (e) wake-up count within the R10 cap --------------------------------

(deftest wake-count-over-cap-turns-it-red
  (let [p (make-project!)
        _ (put-handoff! p "in_process" (handoff-text (head-10 p)))
        s (make-session! p {:manifest {:wake_count 9 :wake_cap 8}})
        r (inspect s p)]
    (is (not (green? r)) "a wake count above the cap must turn it red")
    (is (re-find #"(?i)wake|cap" (:all r)))))

;; --- interface hygiene ---------------------------------------------------

(deftest blacklist-stays-identical-to-the-pre-commit-hook
  (let [extract (fn [file]
                  (some #(when (str/starts-with? % "BLACKLIST=") %)
                        (str/split-lines (slurp file))))]
    (is (= (extract (str (fs/path repo-root "hooks" "pre-commit")))
           (extract inspect-run))
        "inspect-run must use byte-identical BLACKLIST to hooks/pre-commit — fix the drift")))

(deftest inspect-run-usage-is-clear
  (let [r (sh/sh "bash" inspect-run
                 :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")})]
    (is (not (zero? (:exit r))))
    (is (re-find #"(?i)usage" (str (:out r) (:err r))))))
