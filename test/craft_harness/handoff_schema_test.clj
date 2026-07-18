(ns craft-harness.handoff-schema-test
  "Milestone 4, behavior B1 (docs/current-milestone.md): the solo-pack's
   structured handoff — the direct heir of the original harness's progress/
   files (design §5). A schema doc plus one validator (bin/handoff-validate.bb)
   reused by the runner (run-solo) and the inspector (inspect-run). Every
   deftest builds a valid handoff AND the malformed variants the validator must
   reject, each failing with the offending field NAMED — the negative
   verification CLAUDE.md demands."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def validator (str (fs/path repo-root "bin" "handoff-validate.bb")))

(def ^:dynamic *sandboxes* nil)

(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t)
           (finally (doseq [d @*sandboxes*] (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m4-handoff."})]
    (swap! *sandboxes* conj d)
    d))

;; --- building a handoff from parts so each test can corrupt exactly one -----

(def default-header
  [["id" "20260718T120000Z_specify"]
   ["from" "specify"]
   ["to" "code"]
   ["phase" "specify"]
   ["created_at" "2026-07-18T12:00:00Z"]])

(def default-sections
  ;; section name -> body bullet lines
  [["done"              ["- produced spec.md and features/toy.feature"]]
   ["decisions"         ["- scenario SUT-1 covers the 42 output"]]
   ["assumptions"       ["- bash and git are available"]]
   ["open items"        ["- none"]]
   ["commands executed" ["- cat task.md"]]])

(defn render [header sections]
  (str (str/join "\n" (map (fn [[k v]] (str k ": " v)) header))
       "\n\n"
       (str/join "\n\n"
                 (map (fn [[name bullets]]
                        (str "# " name "\n" (str/join "\n" bullets)))
                      sections))
       "\n"))

(defn write-handoff [header sections]
  (let [f (fs/path (tmp-dir) "handoff.txt")]
    (spit (str f) (render header sections))
    (str f)))

(defn validate [file & flags]
  (apply sh/sh "bb" validator file flags))

(defn ok? [res] (zero? (:exit res)))
(defn out [res] (str (:out res) (:err res)))

;; --- valid ------------------------------------------------------------------

(deftest valid-handoff-passes
  (let [f (write-handoff default-header default-sections)]
    (is (ok? (validate f)) (str "a complete handoff must validate:\n" (out (validate f))))))

(deftest valid-handoff-with-changes-and-nonempty-decisions-passes
  (let [f (write-handoff (conj default-header ["commit" "abcdef1234"]) default-sections)]
    (is (ok? (validate f "--changed")))))

;; --- missing header field ---------------------------------------------------

(deftest missing-header-field-fails-naming-it
  (doseq [drop-key ["id" "from" "to" "phase" "created_at"]]
    (testing (str "missing header '" drop-key "'")
      (let [f (write-handoff (remove (fn [[k _]] (= k drop-key)) default-header)
                             default-sections)
            res (validate f)]
        (is (not (ok? res)))
        (is (str/includes? (out res) drop-key)
            (str "the failure must name the missing field '" drop-key "'"))))))

;; --- malformed header -------------------------------------------------------

(deftest malformed-header-line-fails
  (testing "a header line with no 'key: value' shape is rejected"
    (let [good (render default-header default-sections)
          ;; corrupt the first header line into a bare token
          broken (str/replace-first good "id: 20260718T120000Z_specify" "id 20260718T120000Z_specify")
          f (fs/path (tmp-dir) "h.txt")]
      (spit (str f) broken)
      (let [res (validate (str f))]
        (is (not (ok? res)))
        (is (re-find #"(?i)header|malformed" (out res)))))))

;; --- missing section --------------------------------------------------------

(deftest missing-section-fails-naming-it
  (doseq [drop-name ["done" "decisions" "assumptions" "open items" "commands executed"]]
    (testing (str "missing section '" drop-name "'")
      (let [f (write-handoff default-header
                             (remove (fn [[n _]] (= n drop-name)) default-sections))
            res (validate f)]
        (is (not (ok? res)))
        (is (str/includes? (out res) drop-name)
            (str "the failure must name the missing section '" drop-name "'"))))))

;; --- empty decisions with changes -------------------------------------------

(deftest empty-decisions-with-changes-fails
  (testing "a phase that produced changes must record decisions"
    (let [sections (map (fn [[n b]] (if (= n "decisions") [n []] [n b])) default-sections)
          f (write-handoff (conj default-header ["commit" "abcdef1234"]) sections)
          res (validate f "--changed")]
      (is (not (ok? res)))
      (is (str/includes? (out res) "decisions")
          "the failure must name 'decisions'"))))

(deftest empty-decisions-without-changes-passes
  (testing "with no changes, an empty decisions section is acceptable"
    (let [sections (map (fn [[n b]] (if (= n "decisions") [n []] [n b])) default-sections)
          f (write-handoff default-header sections)]
      (is (ok? (validate f)) "empty decisions is fine when the phase changed nothing"))))

;; --- interface hygiene ------------------------------------------------------

(deftest usage-without-file
  (let [res (sh/sh "bb" validator)]
    (is (not (ok? res)))
    (is (re-find #"(?i)usage" (out res)))))
