#!/usr/bin/env bb
;; craft-harness parse-accept-report.bb — m7, executable Gherkin.
;;
;;   parse-accept-report.bb <report.ndjson>
;;
;; Parses the machine-readable acceptance report (docs/acceptance-report-schema.md)
;; and prints one tab-separated record per scenario: <scenario-id><TAB><status>.
;; This is the runner-owned STRUCTURAL parse — run-six enforces scenario coverage
;; from these records, never by substring-matching the raw report (the D22/D27
;; "passed for the wrong reason" trap).
;;
;; Exit 0 = parsed (records on stdout); 1 = a malformed line / bad record; 2 = usage.
(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[cheshire.core :as json])

(defn die! [code msg]
  (binding [*out* *err*] (println (str "parse-accept-report: " msg)))
  (System/exit code))

(let [file (first *command-line-args*)]
  (when (nil? file) (die! 2 "usage: parse-accept-report.bb <report.ndjson>"))
  (when-not (.exists (io/file file)) (die! 1 (str "report not found: " file)))
  (let [lines (->> (slurp file) str/split-lines (remove str/blank?))
        valid-status #{"passed" "failed" "undefined" "skipped"}
        valid-id #"@?[A-Z][A-Z0-9]*-[0-9]+"
        seen (volatile! #{})]
    (doseq [l lines]
      (let [obj (try (json/parse-string l true)
                     (catch Exception _ (die! 1 (str "malformed JSON line: " (pr-str l)))))
            id (:scenario obj) status (:status obj)]
        (when-not (and (string? id) (seq id))
          (die! 1 (str "line missing string 'scenario': " (pr-str l))))
        (when-not (re-matches valid-id id)
          (die! 1 (str "line has malformed scenario ID (" (pr-str id) "): " (pr-str l))))
        (when (contains? @seen id)
          (die! 1 (str "duplicate scenario ID in report: " id)))
        (vswap! seen conj id)
        (when-not (contains? valid-status status)
          (die! 1 (str "line has invalid 'status' (" (pr-str status) "): " (pr-str l))))
        (println (str id "\t" status))))
    (System/exit 0)))
