(ns craft-harness.preflight-test
  "D39: friendly, pack-aware validation before any agent turn."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def preflight (str (fs/path repo-root "bin" "preflight")))
(def ^:dynamic *sandboxes* nil)

(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t) (finally (doseq [d @*sandboxes*] (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-d39."})]
    (swap! *sandboxes* conj d) d))

(defn run! [dir pack & [env]]
  (let [r (sh/sh "bash" preflight "--project" (str dir) "--pack" pack
                 :env (merge {"PATH" (System/getenv "PATH")
                              "HOME" (str (tmp-dir))
                              "GIT_CONFIG_NOSYSTEM" "1"}
                             env))]
    (assoc r :all (str (:out r) (:err r)))))

(defn git! [dir & args]
  (let [r (apply sh/sh "git" (concat args [:dir (str dir)
                                             :env {"PATH" (System/getenv "PATH")
                                                   "HOME" (str (tmp-dir))
                                                   "GIT_CONFIG_NOSYSTEM" "1"}]))]
    (when-not (zero? (:exit r)) (throw (ex-info "git failed" r))) r))

(defn project! [contract]
  (let [d (fs/path (tmp-dir) "project")]
    (fs/create-dirs d)
    (git! d "init" "-q" "-b" "main")
    (spit (str (fs/path d "task.md")) "bounded task\n")
    (spit (str (fs/path d "project.prompt")) contract)
    (git! d "add" "-A")
    (git! d "-c" "user.name=Setup" "-c" "user.email=setup@example.invalid"
          "commit" "-q" "-m" "baseline")
    d))

(deftest contract-failure-is-distinct-and-actionable
  (let [p (project! "owns:\n  src/**\n") r (run! p "solo-pack")]
    (is (= 41 (:exit r)))
    (is (re-find #"(?i)contract.*test.*missing" (:all r)))))

(deftest missing-command-tool-is-distinct-and-actionable
  (let [p (project! "owns:\n  src/**\ntest: definitely-not-installed-d39 --version\n")
        r (run! p "solo-pack")]
    (is (= 42 (:exit r)))
    (is (str/includes? (:all r) "definitely-not-installed-d39"))
    (is (re-find #"(?i)test.*PATH" (:all r)))))

(deftest every-declared-command-kind-checks-its-entrypoint
  (doseq [[pack label contract]
          [["solo-pack" "quality:lint"
            "owns:\n  src/**\ntest: true\nquality:\n  lint: missing-quality-d39\n"]
           ["six-pack" "accept"
            "owns:\n  src/**\ntest: true\naccept: missing-accept-d39\n"]
           ["six-pack" "mutation"
            (str "owns:\n  src/**\ntest: true\naccept: true\nmutation:\n"
                 "  tool: pitest\n  threshold: 80\n  command: missing-mutation-d39\n")]]]
    (testing label
      (let [r (run! (project! contract) pack)]
        (is (= 42 (:exit r)))
        (is (str/includes? (:all r) label))))))

(deftest dirty-tree-failure-is-distinct-and-actionable
  (let [p (project! "owns:\n  task.md\ntest: true\n")]
    (spit (str (fs/path p "task.md")) "dirty\n")
    (let [r (run! p "solo-pack")]
      (is (= 43 (:exit r)))
      (is (re-find #"(?i)tracked working tree.*commit or stash" (:all r))))))

(deftest absent-identity-is-seeded-before-a-phase-could-start
  (let [p (project! "owns:\n  src/**\ntest: true\n") r (run! p "solo-pack")]
    (is (zero? (:exit r)) (:all r))
    (is (= "craft-harness" (str/trim (:out (sh/sh "git" "config" "--local" "user.name" :dir (str p))))))
    (is (= "noreply@craft-harness.local" (str/trim (:out (sh/sh "git" "config" "--local" "user.email" :dir (str p))))))))

(deftest identity-seeding-failure-is-distinct-and-actionable
  (let [p (project! "owns:\n  src/**\ntest: true\n")
        r (run! p "solo-pack" {"CRAFT_PREFLIGHT_TEST_IDENTITY_FAIL" "1"})]
    (is (= 44 (:exit r)))
    (is (re-find #"(?i)git identity.*configure" (:all r)))))

(deftest in-flight-session-uses-doctors-established-exit-code
  (let [p (project! "owns:\n  src/**\ntest: true\n")
        status (fs/path p ".craft-harness" "six" "current" "status")]
    (fs/create-dirs (fs/parent status))
    (spit (str status) "running_code\n")
    (let [r (run! p "solo-pack")]
      (is (= 30 (:exit r)))
      (is (re-find #"(?i)in-flight.*doctor" (:all r))))))

(deftest six-pack-requires-accept-before-any-agent-turn
  (let [p (project! "owns:\n  src/**\ntest: true\n") r (run! p "six-pack")]
    (is (= 41 (:exit r)))
    (is (re-find #"(?i)six-pack.*accept" (:all r)))))
