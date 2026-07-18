(ns craft-harness.runner-test
  "Milestone 2, behavior B4 (docs/current-milestone.md): scenario runner
   orchestration, exercised exclusively through fake agents (cost rule:
   zero paid runs in bb test). The runner must drive the five phases, play
   the daemon's delivery role, run the deterministic asserts, attribute
   failures to the exact phase, and leave a run manifest (R10 provenance)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def runner (str (fs/path repo-root "adapters" "run-scenario")))
(def fixtures (fs/path repo-root "test" "fixtures" "fake-agent"))

(def phase-names ["edit" "test" "commit" "handoff" "wakeup"])

(def ^:dynamic *sandboxes* nil)

(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t)
           (finally (doseq [d @*sandboxes*]
                      (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m2-runner."})]
    (swap! *sandboxes* conj d)
    d))

(defn run-scenario! [variant out & args]
  (let [start (System/currentTimeMillis)
        res (apply sh/sh "bash" runner
                   "--adapter" (str (fs/path fixtures variant))
                   "--out" (str out)
                   (concat args [:env {"PATH" (System/getenv "PATH")
                                       "HOME" (System/getenv "HOME")
                                       "GIT_CONFIG_NOSYSTEM" "1"}]))]
    (assoc res
           :all (str (:out res) (:err res))
           :elapsed-ms (- (System/currentTimeMillis) start))))

(defn manifest [out]
  (let [f (fs/path out "manifest.json")]
    (is (fs/exists? f) "manifest.json must exist after every run")
    (when (fs/exists? f)
      (json/parse-string (slurp (str f)) true))))

(defn phase-outcomes [m]
  (mapv (juxt :name :outcome) (:phases m)))

;; --- fake happy agent: the full scenario passes --------------------------

(deftest happy-agent-completes-the-scenario
  (let [out (tmp-dir)
        res (run-scenario! "happy" out)]
    (is (= 0 (:exit res)) (str "happy scenario must pass:\n" (:all res)))
    (let [m (manifest out)]
      (testing "all five phases ran and passed, in order"
        (is (= (mapv #(vector % "ok") phase-names) (phase-outcomes m))))
      (testing "outcome and provenance (R10)"
        (is (= "success" (:outcome m)))
        (is (str/includes? (str (:adapter_info m)) "fake-agent happy"))
        (is (re-matches #"[0-9a-f]{40}" (str (:fork_commit m))))
        (is (re-matches #"[0-9a-f]{40}" (str (:baseline_commit m))))))
    (testing "per-phase logs are kept as evidence"
      (doseq [p phase-names]
        (is (fs/exists? (fs/path out "logs" (str p ".log"))) (str p ".log"))))
    (testing "the handoff went outbox → inbox and was consumed (D7)"
      (let [wd (fs/path out "workdir")
            handoffs (fn [sub]
                       (->> (fs/path wd ".swarmforge" "handoffs" sub)
                            fs/list-dir
                            (filter #(str/ends-with? (str %) ".handoff"))))]
        (is (empty? (handoffs "outbox")))
        (is (empty? (handoffs "inbox/new")))
        (is (= 1 (count (handoffs "inbox/in_process"))))))))

;; --- fake silent agent: failure attributed to the handoff phase ----------

(deftest silent-agent-fails-at-the-handoff-phase
  (let [out (tmp-dir)
        res (run-scenario! "silent" out)]
    (is (not= 0 (:exit res)))
    (let [m (manifest out)]
      (is (= "failed" (:outcome m)))
      (is (= "handoff" (:failed_phase m))
          "the manifest must name the exact phase that failed")
      (is (= "assert_failed" (:failure m)))
      (is (= [["edit" "ok"] ["test" "ok"] ["commit" "ok"] ["handoff" "assert_failed"]]
             (phase-outcomes m))
          "phases before the failure stay ok; nothing runs after it"))))

;; --- fake naughty agent (B5): confinement negative verification ----------
;; The toy repo has no pre-commit hook installed, and the naughty fake uses
;; --no-verify anyway (decisions.md D2: the hook is a tripwire agents can
;; bypass). The assert layer must catch the forbidden commit regardless.

(deftest naughty-agent-is-caught-by-the-commit-assert
  (let [out (tmp-dir)
        res (run-scenario! "naughty" out)]
    (is (not= 0 (:exit res)))
    (let [m (manifest out)]
      (is (= "failed" (:outcome m)))
      (is (= "commit" (:failed_phase m))
          "the tampering must be caught at the commit phase")
      (is (= "assert_failed" (:failure m))))
    (is (str/includes? (:all res) "task.md")
        "the runner's failure output must name the offending path")))

;; --- fake stuck agent: R10 breaker through the runner --------------------

(deftest stuck-agent-times-out-with-attribution
  (let [out (tmp-dir)
        res (run-scenario! "stuck" out "--phase-timeout" "1")]
    (is (not= 0 (:exit res)))
    (is (< (:elapsed-ms res) 60000)
        (str "the runner must return within a bound, took " (:elapsed-ms res) "ms"))
    (let [m (manifest out)]
      (is (= "failed" (:outcome m)))
      (is (= "edit" (:failed_phase m)))
      (is (= "timeout" (:failure m))))
    (is (fs/exists? (fs/path out "workdir"))
        "the workdir is evidence and must be left in place")))
