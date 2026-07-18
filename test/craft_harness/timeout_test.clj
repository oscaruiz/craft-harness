(ns craft-harness.timeout-test
  "Milestone 2, behavior B2 (docs/current-milestone.md): per-phase timeout
   with process containment. The R10 circuit-breaker primitive the scenario
   runner (B4) wraps around every `invoke`: the command runs in its own
   process group; on timeout the WHOLE group is killed — a stuck agent must
   not survive as an orphan, and the runner must return within a bound."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def timeout-script (str (fs/path repo-root "adapters" "run-with-timeout")))

(def exit-timeout 124) ; GNU timeout convention, part of the contract

(def ^:dynamic *sandboxes* nil)

(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t)
           (finally (doseq [d @*sandboxes*]
                      (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m2-timeout."})]
    (swap! *sandboxes* conj d)
    d))

(defn sh-run [& args]
  (apply sh/sh (concat args [:env {"PATH" (System/getenv "PATH")
                                   "HOME" (System/getenv "HOME")}])))

(defn run-with-timeout [secs & cmd]
  (let [start (System/currentTimeMillis)
        res (apply sh-run "bash" timeout-script (str secs) "--" cmd)]
    (assoc res :elapsed-ms (- (System/currentTimeMillis) start))))

(defn alive? [pid]
  (zero? (:exit (sh-run "bash" "-c" (str "kill -0 " pid)))))

(def stuck-agent
  "The fake stuck agent: records its own pid, spawns a child sleeper (the
   orphan candidate), records the child's pid, then hangs."
  "#!/usr/bin/env bash
echo $$ > \"$1/self.pid\"
sleep 300 &
echo $! > \"$1/child.pid\"
wait
")

(defn read-pid [dir name]
  (str/trim (slurp (str (fs/path dir name)))))

(deftest stuck-agent-is-killed-with-its-whole-process-group
  (let [dir (tmp-dir)
        script (fs/path dir "stuck.sh")]
    (spit (str script) stuck-agent)
    (let [res (run-with-timeout 1 "bash" (str script) (str dir))]
      (testing "the runner returns within a bound, not after 300s"
        (is (< (:elapsed-ms res) 15000)
            (str "took " (:elapsed-ms res) "ms")))
      (testing "timeout has its own distinct exit code"
        (is (= exit-timeout (:exit res))))
      (testing "no orphans: the agent AND its child sleeper are both dead"
        (Thread/sleep 300)
        (let [self (read-pid dir "self.pid")
              child (read-pid dir "child.pid")]
          (is (not (alive? self)) (str "stuck agent pid " self " still alive"))
          (is (not (alive? child)) (str "orphaned child pid " child " still alive")))))))

(deftest finished-commands-pass-through
  (testing "a command that finishes in time propagates exit 0"
    (let [res (run-with-timeout 10 "bash" "-c" "exit 0")]
      (is (= 0 (:exit res)))
      (is (< (:elapsed-ms res) 5000) "must not wait for the timeout")))
  (testing "a failing command propagates its own exit code, not the timeout's"
    (let [res (run-with-timeout 10 "bash" "-c" "exit 7")]
      (is (= 7 (:exit res)))))
  (testing "stdout of the command is preserved"
    (let [res (run-with-timeout 10 "bash" "-c" "echo hello")]
      (is (str/includes? (:out res) "hello")))))

(deftest usage-errors-are-clear
  (testing "missing separator / command"
    (let [res (sh-run "bash" timeout-script "5")]
      (is (not= 0 (:exit res)))
      (is (re-find #"(?i)usage" (str (:out res) (:err res))))))
  (testing "non-numeric timeout"
    (let [res (sh-run "bash" timeout-script "soon" "--" "true")]
      (is (not= 0 (:exit res)))
      (is (re-find #"(?i)usage|number" (str (:out res) (:err res)))))))
