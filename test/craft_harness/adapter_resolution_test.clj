(ns craft-harness.adapter-resolution-test
  "Milestone 2, behavior B3 (docs/current-milestone.md): adapter resolution.
   `run-scenario --adapter <name-or-dir>` resolves a contract directory
   (executables `invoke` + `info`); every failure mode dies with a message
   that names the problem. The success path is exercised by the runner
   orchestration tests (B4)."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def runner (str (fs/path repo-root "adapters" "run-scenario")))

(def ^:dynamic *sandboxes* nil)

(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t)
           (finally (doseq [d @*sandboxes*]
                      (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m2-resolve."})]
    (swap! *sandboxes* conj d)
    d))

(defn run-scenario [& args]
  (let [res (apply sh/sh "bash" runner
                   (concat args [:env {"PATH" (System/getenv "PATH")
                                       "HOME" (System/getenv "HOME")}]))]
    (assoc res :all (str (:out res) (:err res)))))

(defn write-exec [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text)
  (sh/sh "chmod" "+x" (str path)))

(deftest unknown-adapter-name-fails-clearly
  (let [out (tmp-dir)
        res (run-scenario "--adapter" "no-such-cli" "--out" (str out))]
    (is (not= 0 (:exit res)))
    (is (str/includes? (:all res) "no-such-cli")
        "the message must name the adapter that failed to resolve")
    (is (re-find #"(?i)adapter" (:all res)))))

(deftest adapter-dir-without-invoke-fails-clearly
  (let [out (tmp-dir)
        dir (fs/path (tmp-dir) "half-adapter")]
    (write-exec (fs/path dir "info") "#!/usr/bin/env bash\necho fake 0.0\n")
    (let [res (run-scenario "--adapter" (str dir) "--out" (str out))]
      (is (not= 0 (:exit res)))
      (is (str/includes? (:all res) "invoke")))))

(deftest adapter-dir-without-info-fails-clearly
  (let [out (tmp-dir)
        dir (fs/path (tmp-dir) "half-adapter")]
    (write-exec (fs/path dir "invoke") "#!/usr/bin/env bash\nexit 0\n")
    (let [res (run-scenario "--adapter" (str dir) "--out" (str out))]
      (is (not= 0 (:exit res)))
      (is (str/includes? (:all res) "info")))))

(deftest non-executable-invoke-fails-clearly
  (let [out (tmp-dir)
        dir (fs/path (tmp-dir) "lame-adapter")]
    (fs/create-dirs dir)
    (spit (str (fs/path dir "invoke")) "#!/usr/bin/env bash\nexit 0\n")
    (write-exec (fs/path dir "info") "#!/usr/bin/env bash\necho fake 0.0\n")
    (let [res (run-scenario "--adapter" (str dir) "--out" (str out))]
      (is (not= 0 (:exit res)))
      (is (re-find #"(?i)executable" (:all res))))))

(deftest missing-arguments-show-usage
  (let [res (run-scenario)]
    (is (not= 0 (:exit res)))
    (is (re-find #"(?i)usage" (:all res)))))
