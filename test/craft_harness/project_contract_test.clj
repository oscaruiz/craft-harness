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

;; --- m7: the optional `accept:` acceptance command (executable Gherkin) -------
;; Additive to the D28 contract: an OPTIONAL single acceptance command emitted as
;; an ACCEPT record after TEST/QUALITY. run-solo ignores it; run-six requires it.
;; Backward compatible — every existing solo contract must still parse unchanged.

(deftest accept-command-emits-a-trailing-accept-record
  (let [r (parse-text (str "owns:\n  core/**\n"
                           "test: bash run-unit.sh\n"
                           "quality:\n  architecture: bash tools/arch-check.sh\n"
                           "accept: bash acceptance/run-acceptance.sh\n"))]
    (is (zero? (:exit r)) (:err r))
    (is (= ["OWN\tcore/**"
            "TEST\tbash run-unit.sh"
            "QUALITY\tarchitecture\tbash tools/arch-check.sh"
            "ACCEPT\tbash acceptance/run-acceptance.sh"]
           (str/split-lines (:out r))))))

(deftest accept-is-optional-and-backward-compatible
  (testing "a contract with no accept: line emits no ACCEPT record and still succeeds"
    (let [r (parse-text "owns:\n  sut.sh\ntest: ./test.sh\n")]
      (is (zero? (:exit r)) (:err r))
      (is (= ["OWN\tsut.sh" "TEST\t./test.sh"] (str/split-lines (:out r))))
      (is (not (str/includes? (:out r) "ACCEPT"))))))

(deftest malformed-accept-fails-closed
  (doseq [text ["owns:\n  a\ntest: t\naccept: x\naccept: y\n"   ; duplicate
                "owns:\n  a\ntest: t\naccept:\n"                 ; empty (no command)
                "owns:\n  a\ntest: t\naccept:x\n"]]              ; no space
    (let [r (parse-text text)]
      (is (not (zero? (:exit r))) (str "must reject:\n" text)))))
