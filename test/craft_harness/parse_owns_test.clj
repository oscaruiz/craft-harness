(ns craft-harness.parse-owns-test
  "Milestone 4.5, behavior B1 (docs/current-milestone.md; D19): bin/parse-owns
   reads the strict `owns:` block from a project.prompt file into a
   newline-separated glob allowlist on stdout. This is the ONE machine-readable
   owned-path contract that closes D17 — 'work only inside <paths>' becomes an
   executable set instead of prose the agent may ignore. The parser is
   deliberately narrow: it reads ONLY the keyed block, ignores prose everywhere
   else, and fails LOUDLY (fail-closed) on a malformed block. Missing file or no
   block => no allowlist (empty output, exit 0) so existing packs are unaffected."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def parse-owns (str (fs/path repo-root "bin" "parse-owns")))

(def ^:dynamic *sandboxes* nil)
(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t) (finally (doseq [d @*sandboxes*] (fs/delete-tree d)))))))

(defn tmp-file [content]
  (let [d (fs/create-temp-dir {:prefix "craft-harness-owns."})
        f (fs/path d "project.prompt")]
    (swap! *sandboxes* conj d)
    (spit (str f) content)
    (str f)))

(defn parse
  "Run bin/parse-owns on a file path; returns {:exit :out :err :globs}."
  [file]
  (let [r (sh/sh "bash" parse-owns file
                 :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")})]
    (assoc r :globs (->> (str/split-lines (:out r))
                         (remove str/blank?)
                         vec))))

;; --- the happy path: a valid block is read; prose is ignored -----------------

(deftest a-valid-owns-block-is-parsed-into-globs
  (testing "one glob per indented line, in order, with all prose ignored"
    (let [f (tmp-file (str "# myCQRS — project prompt\n"
                           "You work ONLY inside the domain core. Do not touch infra.\n"
                           "\n"
                           "owns:\n"
                           "  src/core/**\n"
                           "  build.gradle\n"
                           "\n"
                           "Any prose after the block is ignored too.\n"))
          r (parse f)]
      (is (zero? (:exit r)) (str "a valid block must parse cleanly:\n" (:err r)))
      (is (= ["src/core/**" "build.gradle"] (:globs r))))))

(deftest a-block-at-end-of-file-without-a-trailing-blank-line-still-parses
  (testing "EOF ends the block just like a blank line or a dedent"
    (let [f (tmp-file "owns:\n  a/**\n  b.txt")
          r (parse f)]
      (is (zero? (:exit r)))
      (is (= ["a/**" "b.txt"] (:globs r))))))

(deftest indented-prose-not-under-owns-is-not-an-entry
  (testing "only lines under the owns: header count; other indented lines are ignored"
    (let [f (tmp-file (str "Notes:\n"
                           "  this indented line is prose, not an owned path\n"
                           "owns:\n"
                           "  src/**\n"))
          r (parse f)]
      (is (zero? (:exit r)))
      (is (= ["src/**"] (:globs r))))))

;; --- backward compatibility: empty/missing => no allowlist -------------------

(deftest a-missing-file-is-no-allowlist
  (testing "a project without a project.prompt has no owned-path contract"
    (let [d (fs/create-temp-dir {:prefix "craft-harness-owns."})
          _ (swap! *sandboxes* conj d)
          r (parse (str (fs/path d "does-not-exist.prompt")))]
      (is (zero? (:exit r)) "a missing file is not an error — just no allowlist")
      (is (empty? (:globs r))))))

(deftest a-file-with-no-owns-block-is-no-allowlist
  (testing "prose-only project.prompt yields an empty allowlist"
    (let [f (tmp-file "Just a role description. No machine-readable field here.\n")
          r (parse f)]
      (is (zero? (:exit r)))
      (is (empty? (:globs r))))))

;; --- malformed blocks fail loudly (fail-closed) ------------------------------

(deftest an-inline-value-after-owns-is-malformed
  (testing "owns: must start a block, not carry an inline value"
    (let [f (tmp-file "owns: src/core/**\n")
          r (parse f)]
      (is (not (zero? (:exit r))) "an inline owns: value must fail loudly")
      (is (re-find #"(?i)owns" (:err r))))))

(deftest an-empty-owns-block-is-malformed
  (testing "declaring owns: with no globs is a mistake, not an empty allowlist"
    (let [f (tmp-file "owns:\n\nsome prose\n")
          r (parse f)]
      (is (not (zero? (:exit r))))
      (is (re-find #"(?i)empty" (:err r))))))

(deftest an-entry-with-embedded-whitespace-is-malformed
  (let [f (tmp-file "owns:\n  src/ core/**\n")
        r (parse f)]
    (is (not (zero? (:exit r))) "a glob is a single token")
    (is (re-find #"(?i)whitespace" (:err r)))))

(deftest an-absolute-path-entry-is-malformed
  (testing "owned paths are relative to the project root"
    (let [f (tmp-file "owns:\n  /etc/passwd\n")
          r (parse f)]
      (is (not (zero? (:exit r))))
      (is (re-find #"(?i)relative|absolute" (:err r))))))

(deftest a-traversal-entry-is-malformed
  (testing "a .. entry could escape the project and is refused"
    (let [f (tmp-file "owns:\n  ../secrets/**\n")
          r (parse f)]
      (is (not (zero? (:exit r))))
      (is (re-find #"\.\." (:err r))))))

(deftest a-duplicate-owns-block-is-malformed
  (testing "two owns: blocks are ambiguous and refused"
    (let [f (tmp-file "owns:\n  a/**\nowns:\n  b/**\n")
          r (parse f)]
      (is (not (zero? (:exit r))))
      (is (re-find #"(?i)duplicate" (:err r))))))

;; --- interface hygiene -------------------------------------------------------

(deftest parse-owns-usage-is-clear
  (let [r (sh/sh "bash" parse-owns
                 :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")})]
    (is (not (zero? (:exit r))))
    (is (re-find #"(?i)usage" (str (:out r) (:err r))))))
