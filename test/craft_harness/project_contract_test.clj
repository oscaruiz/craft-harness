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

;; --- m7 (D35): the optional `mutation:` gate (PIT/Java) ----------------------
;; Opt-in per project. A structured block declaring the mutation TOOL, the
;; project-owned THRESHOLD (0-100), and the COMMAND that produces a PIT-shaped
;; report. run-solo ignores it; run-six runs it as a hard gate when present.
;; The PROJECT owns the standard (threshold), the harness owns execution + verdict
;; — never a universal harness-fixed threshold (the D27/D28 toy-CRAP mistake).

(deftest mutation-block-emits-a-trailing-mutation-record
  (let [r (parse-text (str "owns:\n  src/core/**\n"
                           "test: mvn -q -pl src/core test\n"
                           "quality:\n  architecture: mvn -q test -Dtest=ArchTest\n"
                           "accept: ./acceptance/run.sh\n"
                           "mutation:\n"
                           "  tool: pitest\n"
                           "  threshold: 80\n"
                           "  command: mvn -q -pl src/core org.pitest:pitest-maven:mutationCoverage\n"))]
    (is (zero? (:exit r)) (:err r))
    (is (= ["OWN\tsrc/core/**"
            "TEST\tmvn -q -pl src/core test"
            "QUALITY\tarchitecture\tmvn -q test -Dtest=ArchTest"
            "ACCEPT\t./acceptance/run.sh"
            "MUTATION\tpitest\t80\tmvn -q -pl src/core org.pitest:pitest-maven:mutationCoverage"]
           (str/split-lines (:out r)))
        "MUTATION record is tool<TAB>threshold<TAB>command, emitted last")))

(deftest mutation-keys-are-order-independent
  (let [r (parse-text (str "owns:\n  core/**\ntest: t\n"
                           "mutation:\n  command: bash m.sh\n  tool: pitest\n  threshold: 0\n"))]
    (is (zero? (:exit r)) (:err r))
    (is (= "MUTATION\tpitest\t0\tbash m.sh"
           (last (str/split-lines (:out r)))))))

(deftest mutation-is-optional-and-backward-compatible
  (testing "a contract with no mutation: block emits no MUTATION record and still succeeds"
    (let [r (parse-text "owns:\n  core/**\ntest: bash run-unit.sh\naccept: bash a.sh\n")]
      (is (zero? (:exit r)) (:err r))
      (is (not (str/includes? (:out r) "MUTATION"))))))

(deftest mutation-threshold-boundaries-accepted
  (doseq [t ["0" "1" "80" "99" "100"]]
    (let [r (parse-text (str "owns:\n  core/**\ntest: t\n"
                             "mutation:\n  tool: pitest\n  threshold: " t "\n  command: bash m.sh\n"))]
      (is (zero? (:exit r)) (str "threshold " t " must be accepted: " (:err r))))))

(deftest malformed-mutation-fails-closed
  (doseq [text [;; duplicate block
                "owns:\n  a\ntest: t\nmutation:\n  tool: pitest\n  threshold: 80\n  command: m\nmutation:\n  tool: pitest\n  threshold: 80\n  command: m\n"
                ;; inline value after mutation: (must start a block)
                "owns:\n  a\ntest: t\nmutation: pitest\n"
                ;; missing tool
                "owns:\n  a\ntest: t\nmutation:\n  threshold: 80\n  command: m\n"
                ;; missing threshold
                "owns:\n  a\ntest: t\nmutation:\n  tool: pitest\n  command: m\n"
                ;; missing command
                "owns:\n  a\ntest: t\nmutation:\n  tool: pitest\n  threshold: 80\n"
                ;; empty block
                "owns:\n  a\ntest: t\nmutation:\n\n"
                ;; duplicate key inside block
                "owns:\n  a\ntest: t\nmutation:\n  tool: pitest\n  tool: pitest\n  threshold: 80\n  command: m\n"
                ;; unknown key inside block
                "owns:\n  a\ntest: t\nmutation:\n  tool: pitest\n  threshold: 80\n  command: m\n  extra: nope\n"
                ;; threshold out of range (high)
                "owns:\n  a\ntest: t\nmutation:\n  tool: pitest\n  threshold: 101\n  command: m\n"
                ;; threshold non-numeric
                "owns:\n  a\ntest: t\nmutation:\n  tool: pitest\n  threshold: eighty\n  command: m\n"
                ;; threshold negative (not a 0-100 integer)
                "owns:\n  a\ntest: t\nmutation:\n  tool: pitest\n  threshold: -5\n  command: m\n"
                ;; tool token contains whitespace
                "owns:\n  a\ntest: t\nmutation:\n  tool: pit est\n  threshold: 80\n  command: m\n"]]
    (let [r (parse-text text)]
      (is (not (zero? (:exit r))) (str "must reject:\n" text)))))
