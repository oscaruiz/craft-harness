#!/usr/bin/env bb
;; craft-harness parse-mutation-report.bb — m7 (D35), the mutation gate (PIT/Java).
;;
;;   parse-mutation-report.bb <mutations.xml>
;;
;; Parses a PITest mutations.xml report STRUCTURALLY (as XML, never by substring --
;; the D22/D27 "passed for the wrong reason" trap) and prints the real score as two
;; tab-separated integers: "<killed><TAB><total>". run-six and inspect-run derive
;; the verdict with EXACT integer arithmetic (killed*100 >= threshold*total), so no
;; float rounding can push a run over its declared threshold.
;;
;; PIT emits one <mutation> element per generated mutant, each carrying an
;; authoritative boolean `detected` attribute (KILLED/TIMED_OUT/MEMORY_ERROR/
;; RUN_ERROR => detected='true'; SURVIVED/NO_COVERAGE => detected='false') and a
;; `status`. The mutation score is detected / total. We follow PIT's own
;; denominator and EXCLUDE non-viable (uncompilable) mutants, which are not real
;; test targets; every other status counts. See docs/mutation-report-schema.md.
;;
;; This recognizes the ACTUAL score -- a suite where every mutant SURVIVES yields
;; killed=0 and a real 0%, even though PIT (and this reporter) exit 0. Exit 0 is
;; not the gate; the parsed score is.
;;
;; Exit 0 = parsed (killed<TAB>total on stdout); 1 = missing/malformed/zero-mutant
;; report; 2 = usage.
(require '[clojure.data.xml :as xml]
         '[clojure.java.io :as io])

(defn die! [code msg]
  (binding [*out* *err*] (println (str "parse-mutation-report: " msg)))
  (System/exit code))

(let [file (first *command-line-args*)]
  (when (nil? file) (die! 2 "usage: parse-mutation-report.bb <mutations.xml>"))
  (when-not (.exists (io/file file)) (die! 1 (str "report not found: " file)))
  ;; clojure.data.xml parses LAZILY, so a malformed document only throws while the
  ;; tree is walked -- force full realization inside the guard so every parse error
  ;; is mapped to a clean fail-closed exit, never an uncaught stack trace.
  (let [root (try (xml/parse-str (slurp file))
                  (catch Exception e (die! 1 (str "malformed XML: " (ex-message e)))))
        all  (try (doall (xml-seq root))
                  (catch Exception e (die! 1 (str "malformed XML: " (ex-message e)))))]
    (when-not (= :mutations (:tag root))
      (die! 1 (str "not a PIT mutations report (root <" (name (or (:tag root) :?)) ">, want <mutations>)")))
    (let [muts (filter #(and (map? %) (= :mutation (:tag %))) all)]
      (when (empty? muts)
        (die! 1 "report contains zero mutations -- a mutation gate over no mutants proves nothing"))
      (let [read-mut
            (fn [m]
              (let [detected (get-in m [:attrs :detected])
                    status   (get-in m [:attrs :status])]
                (when-not (#{"true" "false"} detected)
                  (die! 1 (str "mutation has missing/invalid detected attribute: " (pr-str detected))))
                (when-not (and (string? status) (seq status))
                  (die! 1 "mutation is missing its status attribute"))
                {:detected (= "true" detected) :non-viable (= "NON_VIABLE" status)}))
            parsed (map read-mut muts)
            scored (remove :non-viable parsed)          ; PIT excludes uncompilable mutants
            total  (count scored)
            killed (count (filter :detected scored))]
        (when (zero? total)
          (die! 1 "report has no scorable (viable) mutations -- mutation gate cannot be satisfied"))
        (println (str killed "\t" total))
        (System/exit 0)))))
