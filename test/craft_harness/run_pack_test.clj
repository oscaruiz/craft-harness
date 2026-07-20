(ns craft-harness.run-pack-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (fs/cwd))
(def run-pack (str (fs/path repo-root "bin" "run-pack")))

(deftest two-pack-lite-runner-is-retired
  (testing "direct invocation cannot report success or mutate a project"
    (let [project (fs/create-temp-dir {:prefix "craft-retired-pack."})
          marker (fs/path project "marker")
          _ (spit (str marker) "unchanged\n")
          r (sh/sh "bash" run-pack "--project" (str project) "--out" (str (fs/path project "out"))) ]
      (try
        (is (not (zero? (:exit r))))
        (is (re-find #"(?i)retired|solo-pack" (str (:out r) (:err r))))
        (is (= "unchanged\n" (slurp (str marker))))
        (is (not (fs/exists? (fs/path project "out"))))
        (finally (fs/delete-tree project))))))

(deftest two-pack-lite-is-not-registered
  (let [packs (->> (str/split-lines (slurp (str (fs/path repo-root "PACKS"))))
                   (map str/trim)
                   (remove #(or (str/blank? %) (str/starts-with? % "#")))
                   set)]
    (testing "the retired two-pack-lite is never a registered installable pack (D28)"
      (is (not (contains? packs "two-pack-lite"))))
    (testing "the supported packs are the fork's own light path and the six-pack (m7)"
      (is (contains? packs "solo-pack"))
      (is (= #{"solo-pack" "six-pack"} packs)))))
