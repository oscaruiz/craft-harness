(ns craft-harness.project-contract-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def parser (str (fs/path (fs/cwd) "bin" "parse-project")))
(defn parse-text [text]
  (let [d (fs/create-temp-dir {:prefix "craft-contract."})
        f (fs/path d "project.prompt")]
    (try (spit (str f) text)
         (sh/sh "bash" parser (str f))
         (finally (fs/delete-tree d)))))

(deftest strict-contract-emits-exact-ordered-records
  (let [r (parse-text (str "Project prose.\n"
                           "owns:\n  src/core/**\n  pom.xml\n"
                           "test: mvn -q -pl src/core test\n"
                           "quality:\n  crap: mvn -q verify -Pcrap\n"
                           "  architecture: mvn -q test -Dtest=ArchitectureTest\n"))]
    (is (zero? (:exit r)) (:err r))
    (is (= ["OWN\tsrc/core/**" "OWN\tpom.xml"
            "TEST\tmvn -q -pl src/core test"
            "QUALITY\tcrap\tmvn -q verify -Pcrap"
            "QUALITY\tarchitecture\tmvn -q test -Dtest=ArchitectureTest"]
           (str/split-lines (:out r))))))

(deftest required-fields-and-duplicates-fail-closed
  (doseq [text ["test: true\n"
                "owns:\n  src/**\n"
                "owns:\n  src/**\ntest: true\ntest: false\n"
                "owns:\n  src/**\nowns:\n  test/**\ntest: true\n"
                "owns:\n  ../escape/**\ntest: true\n"
                "owns:\n  src/**\n  src/**\ntest: true\n"
                "owns:\n  src/**\ntest:true\n"
                "owns:\n  src/**\ntest: true\nquality:\n"
                "owns:\n  src/**\ntest: true\nquality:\n  lint:\n"]]
    (let [r (parse-text text)]
      (is (not (zero? (:exit r))) (str "must reject:\n" text)))))

(deftest missing-file-fails-closed
  (let [r (sh/sh "bash" parser "/definitely/not/a/project.prompt")]
    (is (not (zero? (:exit r))))))
