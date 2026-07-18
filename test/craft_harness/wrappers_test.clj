(ns craft-harness.wrappers-test
  "Milestone 3, behavior B3 (docs/current-milestone.md): the toy quality
   wrappers tools/toy/crap.sh and tools/toy/dry.sh. They implement the
   normalized, self-identified wrapper output contract (`<tool>: key=value`,
   e.g. `crap: threshold=6`) deterministically for the toy task's
   language-free code, with an
   env knob to simulate a failing score. Two invariants the rest of the
   milestone leans on: (1) the CRAP wrapper is the single source of truth for
   the threshold — it always prints `threshold: 6`, no flag changes it; the
   number never comes from the prompt (design §3); (2) a score above the
   threshold is a non-zero exit (a gate the cleaner must fix)."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def crap (str (fs/path repo-root "tools" "toy" "crap.sh")))
(def dry (str (fs/path repo-root "tools" "toy" "dry.sh")))

(def ^:dynamic *sandboxes* nil)

(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t)
           (finally (doseq [d @*sandboxes*]
                      (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m3-wrap."})]
    (swap! *sandboxes* conj d)
    d))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn run
  "Run a wrapper in <dir> with <env> overrides; never throws on non-zero."
  [script dir env & args]
  (apply sh/sh "bash" script
         (concat args
                 [:dir (str dir)
                  :env (merge {"PATH" (System/getenv "PATH")
                               "HOME" (System/getenv "HOME")}
                              env)])))

(defn output [res] (str (:out res) (:err res)))
(defn passes? [res] (zero? (:exit res)))

;; A clean toy repo: sut.sh with no control-flow (a trivially low CRAP score).
(defn clean-repo []
  (let [d (tmp-dir)]
    (write-file (fs/path d "sut.sh") "#!/usr/bin/env bash\necho 42\n")
    d))

;; --- CRAP wrapper --------------------------------------------------------

(deftest crap-passes-on-clean-code-and-prints-the-contract
  (let [d (clean-repo)
        res (run crap d {} "sut.sh")]
    (is (passes? res) (str "clean toy code must pass:\n" (output res)))
    (testing "normalized report fields are all present, self-identified"
      (is (re-find #"(?m)^crap: score=\d+" (output res)))
      (is (re-find #"(?m)^crap: threshold=6\b" (output res)))
      (is (re-find #"(?m)^crap: offenders=" (output res)))
      (is (re-find #"(?m)^crap: result=pass" (output res))))))

(deftest crap-threshold-is-always-6-single-source-of-truth
  (testing "no argument or env changes the emitted threshold: it is the wrapper's own constant"
    (let [d (clean-repo)]
      (doseq [env [{} {"CRAP_TOY_SCORE" "3"} {"CRAP_TOY_SCORE" "99"}]]
        (let [res (run crap d env "sut.sh")]
          (is (re-find #"(?m)^crap: threshold=6\b" (output res))
              (str "threshold must be 6 regardless of env " env "\n" (output res))))))))

(deftest crap-fails-when-the-score-exceeds-the-threshold
  (let [d (clean-repo)
        res (run crap d {"CRAP_TOY_SCORE" "9"} "sut.sh")]
    (is (not (passes? res)) "a score above 6 must be a non-zero exit (a gate)")
    (is (re-find #"(?m)^crap: score=9\b" (output res)))
    (is (re-find #"(?m)^crap: threshold=6\b" (output res)))
    (is (re-find #"(?m)^crap: result=fail" (output res)))
    (testing "the failing report names the offending file"
      (is (str/includes? (output res) "sut.sh")))))

(deftest crap-score-at-the-threshold-passes
  (testing "6 is at-or-below the enforced threshold (<= 6), so it passes"
    (let [d (clean-repo)
          res (run crap d {"CRAP_TOY_SCORE" "6"} "sut.sh")]
      (is (passes? res))
      (is (re-find #"(?m)^crap: result=pass" (output res))))))

(deftest crap-computes-a-deterministic-score-from-control-flow
  (testing "language-free proxy: more branchy code scores higher, deterministically"
    (let [d (tmp-dir)]
      (write-file (fs/path d "branchy.sh")
                  (str "#!/usr/bin/env bash\n"
                       "if true; then echo a; fi\n"
                       "for x in 1 2; do echo $x; done\n"
                       "while false; do echo b; done\n"))
      (let [a (run crap d {} "branchy.sh")
            b (run crap d {} "branchy.sh")]
        (is (= (output a) (output b)) "same input → same report (deterministic)")
        (let [score (some->> (output a) (re-find #"(?m)^crap: score=(\d+)") second parse-long)]
          (is (and score (pos? score)) "branchy code must score above zero"))))))

(deftest crap-clean-file-scores-zero-without-noise
  (testing "a zero-match file must score exactly 0 and emit no arithmetic noise"
    (let [d (clean-repo)
          res (run crap d {} "sut.sh")]
      (is (passes? res))
      (is (re-find #"(?m)^crap: score=0\b" (output res)))
      (is (not (str/includes? (output res) "syntax error"))
          (str "the wrapper must not leak grep/arith errors on a zero-match file:\n" (output res))))))

(deftest crap-sums-across-files-including-zero-match-ones
  (testing "mixing a zero-match file with a branchy one must sum correctly, no noise"
    (let [d (tmp-dir)]
      (write-file (fs/path d "a.sh") "#!/usr/bin/env bash\necho hi\n")
      (write-file (fs/path d "b.sh") "#!/usr/bin/env bash\nif true; then echo x; fi\n")
      (let [res (run crap d {} "a.sh" "b.sh")]
        (is (passes? res) (output res))
        (is (re-find #"(?m)^crap: score=1\b" (output res)) "0 + 1 = 1")
        (is (not (str/includes? (output res) "syntax error")))))))

;; --- DRY wrapper ---------------------------------------------------------

(deftest dry-passes-on-non-duplicated-code
  (let [d (clean-repo)
        res (run dry d {} "sut.sh")]
    (is (passes? res) (str "clean toy code must pass DRY:\n" (output res)))
    (is (re-find #"(?m)^dry: score=\d+" (output res)))
    (is (re-find #"(?m)^dry: offenders=" (output res)))
    (is (re-find #"(?m)^dry: result=pass" (output res)))))

(deftest dry-fails-when-duplication-is-simulated
  (let [d (clean-repo)
        res (run dry d {"DRY_TOY_DUPES" "3"} "sut.sh")]
    (is (not (passes? res)) "simulated duplication must be a non-zero exit")
    (is (re-find #"(?m)^dry: score=3\b" (output res)))
    (is (re-find #"(?m)^dry: result=fail" (output res)))))

;; --- interface hygiene ---------------------------------------------------

(deftest wrappers-are-executable-and-portable
  (doseq [w [crap dry]]
    (is (fs/executable? w) (str w " must be executable"))
    (let [src (slurp w)]
      (is (str/starts-with? src "#!/usr/bin/env bash") (str w " must be portable bash"))
      (is (str/includes? src "set -euo pipefail") (str w " must use set -euo pipefail")))))
