(ns craft-harness.inspect-solo-test
  "Milestone 4, behavior B5 (docs/current-milestone.md): bin/inspect-run learns
   solo-pack sessions. The same negative asserts as m3 (no mutation; executed
   CRAP threshold 6 from the wrapper logs; commits only on the enforced branch,
   no blacklisted path) PLUS the solo specifics: structured handoffs (B1) are
   schema-valid, and every Gherkin scenario ID in the approved spec is traced to
   a test or a verify-phase check. Fabricated good/bad session dirs — including a
   planted mutation call and an untraceable scenario ID."
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
      (try (t) (finally (doseq [d @*sandboxes*] (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m4-isolo."})]
    (swap! *sandboxes* conj d) d))
(defn write-file [p t] (fs/create-dirs (fs/parent p)) (spit (str p) t))
(defn sh-run [{:keys [dir]} & args]
  (apply sh/sh (concat args [:dir (str dir)
                             :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")
                                   "GIT_CONFIG_NOSYSTEM" "1"}])))
(defn git! [dir & args]
  (let [r (apply sh-run {:dir dir} "git" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git " (vec args)) r))) r))

;; --- a finished solo project: baseline + candidate on main -------------------

(defn make-project! [& {:keys [blacklist?]}]
  (let [dir (fs/path (tmp-dir) "project")]
    (fs/create-dirs dir)
    (git! dir "init" "-q" "-b" "main")
    (git! dir "config" "user.email" "toy@example.com")
    (git! dir "config" "user.name" "Toy User")
    (write-file (fs/path dir "task.md") "Make ./test.sh pass.\n")
    (write-file (fs/path dir "sut.sh") "#!/usr/bin/env bash\necho broken\n")
    (write-file (fs/path dir "test.sh") "#!/usr/bin/env bash\n[[ \"$(bash ./sut.sh)\" == 42 ]]\n")
    (git! dir "add" "-A") (git! dir "commit" "-q" "-m" "toy baseline")
    (let [baseline (str/trim (:out (git! dir "rev-parse" "HEAD")))]
      (write-file (fs/path dir "sut.sh") "#!/usr/bin/env bash\necho 42\n")
      (when blacklist? (write-file (fs/path dir "task.md") "tampered\n"))
      (git! dir "add" "-A")
      (git! dir "commit" "-q" (if blacklist? "--no-verify" "-q") "-m" "code: SUT-1")
      {:dir dir :baseline baseline})))

;; --- a valid structured handoff ---------------------------------------------

(defn handoff [phase to & {:keys [commit decisions drop-section]
                           :or {decisions ["- a decision"]}}]
  (str/join
   "\n"
   (concat
    [(str "id: 20260718T120000Z_" phase)
     (str "from: " phase) (str "to: " to) (str "phase: " phase)
     "created_at: 2026-07-18T12:00:00Z"]
    (when commit [(str "commit: " commit)])
    [""]
    (mapcat (fn [[name lines]]
              (when-not (= name drop-section)
                (concat [(str "# " name)] lines)))
            [["done" ["- did the phase"]]
             ["decisions" decisions]
             ["assumptions" ["- bash available"]]
             ["open items" ["- none"]]
             ["commands executed" ["- ran things"]]])
    [""])))

;; --- fabricate the solo session dir -----------------------------------------

(defn make-session!
  [project & {:keys [wrappers verify-log feature code-handoff manifest-extra]
              :or {wrappers "crap: score=0\ncrap: threshold=6\ncrap: result=pass\n"
                   verify-log "[verify] SUT-1: pass — ran ./test.sh\n"
                   feature "Feature: toy\n  @SUT-1\n  Scenario: prints 42\n    Then it exits 0\n"}}]
  (let [s (fs/path (tmp-dir) "session")
        cand (str/trim (:out (git! (:dir project) "rev-parse" "--short=10" "HEAD")))
        m (merge {:pack "solo-pack" :project (str (:dir project))
                  :adapter "fake" :enforced_branch "main"
                  :baseline_commit (:baseline project)
                  :candidate_commit cand :outcome "success"}
                 manifest-extra)]
    (write-file (fs/path s "manifest.json") (json/generate-string m {:pretty true}))
    (write-file (fs/path s "logs" "wrappers.log") wrappers)
    (write-file (fs/path s "logs" "specify.log") "[specify] wrote spec\n")
    (write-file (fs/path s "logs" "code.log") "[code] fixed sut.sh\n")
    (write-file (fs/path s "logs" "verify.log") verify-log)
    (write-file (fs/path s "spec" "features" "toy.feature") feature)
    (write-file (fs/path s "handoffs" "specify.handoff") (handoff "specify" "code"))
    (write-file (fs/path s "handoffs" "code.handoff")
                (or code-handoff (handoff "code" "verify" :commit cand
                                          :decisions ["- implemented SUT-1"])))
    (write-file (fs/path s "handoffs" "verify.handoff")
                (handoff "verify" "done" :decisions ["- SUT-1 verified pass"]))
    s))

(defn inspect [session project]
  (let [r (sh/sh "bash" inspect-run (str session) (str (:dir project))
                 :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")
                       "GIT_CONFIG_NOSYSTEM" "1"})]
    (assoc r :all (str (:out r) (:err r)))))
(defn green? [r] (zero? (:exit r)))

;; --- good --------------------------------------------------------------------

(deftest good-solo-session-is-green
  (let [p (make-project!)
        s (make-session! p)
        r (inspect s p)]
    (is (green? r) (str "a clean solo session must be green:\n" (:all r)))
    (is (str/includes? (:all r) "solo-pack"))
    (is (re-find #"(?i)result: pass" (:all r)))))

;; --- the m3 negative asserts still bite -------------------------------------

(deftest planted-mutation-turns-solo-red
  (let [p (make-project!)
        s (make-session! p :verify-log "[verify] $ ./mutate.sh sut.sh\nmutants killed\n")
        r (inspect s p)]
    (is (not (green? r)))
    (is (re-find #"(?i)mutation|mutate" (:all r)))))

(deftest wrong-threshold-turns-solo-red
  (let [p (make-project!)
        s (make-session! p :wrappers "crap: score=0\ncrap: threshold=8\ncrap: result=pass\n")
        r (inspect s p)]
    (is (not (green? r)))
    (is (str/includes? (:all r) "8"))))

(deftest blacklisted-commit-turns-solo-red
  (let [p (make-project! :blacklist? true)
        s (make-session! p)
        r (inspect s p)]
    (is (not (green? r)))
    (is (str/includes? (:all r) "task.md"))))

;; --- solo specifics ----------------------------------------------------------

(deftest invalid-structured-handoff-turns-solo-red
  (testing "a code handoff missing the decisions section fails the schema check"
    (let [p (make-project!)
          cand (str/trim (:out (git! (:dir p) "rev-parse" "--short=10" "HEAD")))
          bad (handoff "code" "verify" :commit cand :drop-section "decisions")
          s (make-session! p :code-handoff bad)
          r (inspect s p)]
      (is (not (green? r)))
      (is (re-find #"(?i)handoff|schema|decisions" (:all r))))))

(deftest untraceable-scenario-id-turns-solo-red
  (testing "a scenario ID referenced by no test or verify check is caught"
    (let [p (make-project!)
          s (make-session! p
                           :feature "Feature: toy\n  @SUT-2\n  Scenario: unchecked\n    Then nothing\n"
                           :verify-log "[verify] ran ./test.sh, all good\n")
          r (inspect s p)]
      (is (not (green? r)))
      (is (re-find #"(?i)SUT-2|scenario|trace" (:all r))))))

(deftest missing-phase-handoff-turns-solo-red
  (let [p (make-project!)
        s (make-session! p)
        _ (fs/delete (fs/path s "handoffs" "verify.handoff"))
        r (inspect s p)]
    (is (not (green? r)))
    (is (re-find #"(?i)verify|handoff" (:all r)))))
