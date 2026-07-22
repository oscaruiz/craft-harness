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
;; PIT emits one <mutation> element per generated mutant, each carrying a `status`
;; (a DetectionStatus) and its authoritative boolean `detected` attribute. The
;; mutation score is PIT's own: detected / total over ALL mutants --
;;   total  = every <mutation> (PIT's getTotalMutations() denominator);
;;   killed = every mutant whose status is a DETECTED one (isDetected()==true):
;;            KILLED, TIMED_OUT, MEMORY_ERROR, RUN_ERROR, NON_VIABLE, EQUIVALENT.
;; SURVIVED and NO_COVERAGE are the only not-detected terminal statuses. NON_VIABLE
;; is NOT excluded -- PIT declares NON_VIABLE(true), so it counts in BOTH numerator
;; and denominator (see docs/decisions.md D36; upstream DetectionStatus.java /
;; MutationStatistics.java). See docs/mutation-report-schema.md.
;;
;; Fail-closed on an off-schema report (D36/D37) -- the whole report, envelope and
;; records:
;;   ENVELOPE (D37): the <mutations> root is a COMPLETED PIT report -- `partial`, if
;;     present, must be "false" (an incomplete partial='true' report fails closed);
;;     no other root attribute is allowed; the only valid direct children are
;;     <mutation> elements (a <garbage/> smuggled alongside real mutations, or stray
;;     text, is rejected -- never silently discarded).
;;   RECORDS (D36): `status` must be a terminal PIT status (an in-flight
;;     STARTED/NOT_STARTED means an incomplete report; anything else is invented),
;;     `detected` must AGREE with that status's canonical isDetected() value (a
;;     self-contradictory detected='true' status='SURVIVED' is rejected, not scored),
;;     and only <mutation> elements that are DIRECT children of the <mutations> root
;;     count (a <mutation> nested anywhere else is ignored, never smuggled in).
;;
;; This recognizes the ACTUAL score -- a suite where every mutant SURVIVES yields
;; killed=0 and a real 0%, even though PIT (and this reporter) exit 0. Exit 0 is
;; not the gate; the parsed score is.
;;
;; Exit 0 = parsed (killed<TAB>total on stdout); 1 = missing/malformed/zero-mutant/
;; off-schema report; 2 = usage.
(require '[clojure.data.xml :as xml]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

;; PIT DetectionStatus -> isDetected(), restricted to the TERMINAL statuses that a
;; completed report may carry. STARTED/NOT_STARTED are deliberately absent: they are
;; in-flight states, so their presence marks an incomplete report -> fail closed.
(def detected-by-status
  {"KILLED"       true
   "TIMED_OUT"    true
   "MEMORY_ERROR" true
   "RUN_ERROR"    true
   "NON_VIABLE"   true
   "EQUIVALENT"   true
   "SURVIVED"     false
   "NO_COVERAGE"  false})

(defn die! [code msg]
  (binding [*out* *err*] (println (str "parse-mutation-report: " msg)))
  (System/exit code))

(let [file (first *command-line-args*)]
  (when (nil? file) (die! 2 "usage: parse-mutation-report.bb <mutations.xml>"))
  (when-not (.exists (io/file file)) (die! 1 (str "report not found: " file)))
  ;; clojure.data.xml parses LAZILY, so a malformed document only throws while the
  ;; tree is walked -- force full realization inside the guard so every parse error
  ;; is mapped to a clean fail-closed exit, never an uncaught stack trace.
  (let [root (try (let [r (xml/parse-str (slurp file))]
                    ;; clojure.data.xml parses LAZILY; force a full deep walk here so
                    ;; ANY malformed node throws inside this guard, never mid-scoring.
                    (doall (xml-seq r))
                    r)
                  (catch Exception e (die! 1 (str "malformed XML: " (ex-message e)))))]
    (when-not (= :mutations (:tag root))
      (die! 1 (str "not a PIT mutations report (root <" (name (or (:tag root) :?)) ">, want <mutations>)")))
    ;; Validate the root ENVELOPE, not just the mutation records (D37). PIT's real
    ;; <mutations> root carries at most a `partial` attribute: a COMPLETE report is
    ;; `partial="false"` (older PIT omits it entirely). `partial="true"` marks an
    ;; INCOMPLETE report -- fail closed, exactly as an in-flight per-mutant status
    ;; does -- and any other root attribute is off-schema (invented).
    (doseq [[k v] (:attrs root)]
      (when-not (= :partial k)
        (die! 1 (str "unexpected attribute on <mutations> root: " (name k)
                     "=" (pr-str v) " (report off-schema)")))
      (when-not (= "false" v)
        (die! 1 (str "mutations report is not a completed report (root partial=" (pr-str v)
                     ", want \"false\" or absent) -- fail closed"))))
    ;; The only valid DIRECT children of <mutations> are <mutation> elements.
    ;; Inter-element whitespace (pretty-printed reports) is ignored, but any other
    ;; element -- or non-blank text -- directly under the root is off-schema and is
    ;; REJECTED, never silently discarded (a <garbage/> smuggled alongside real
    ;; mutations, or a stray <mutation> nested one level down, must not pass).
    (doseq [child (:content root)]
      (cond
        (map? child)    (when-not (= :mutation (:tag child))
                          (die! 1 (str "unexpected element under <mutations> root: <"
                                       (name (:tag child)) "> (report off-schema)")))
        (string? child) (when-not (str/blank? child)
                          (die! 1 "unexpected text content under <mutations> root (report off-schema)"))))
    ;; Only <mutation> elements that are DIRECT children of <mutations> count -- a
    ;; <mutation> nested inside some other element is not part of PIT's report.
    (let [muts (filter #(and (map? %) (= :mutation (:tag %))) (:content root))]
      (when (empty? muts)
        (die! 1 "report contains zero mutations -- a mutation gate over no mutants proves nothing"))
      (let [read-mut
            (fn [m]
              (let [detected (get-in m [:attrs :detected])
                    status   (get-in m [:attrs :status])]
                (when-not (#{"true" "false"} detected)
                  (die! 1 (str "mutation has missing/invalid detected attribute: " (pr-str detected))))
                (when-not (contains? detected-by-status status)
                  (die! 1 (str "mutation has an unknown or non-terminal status: " (pr-str status)
                               " (report incomplete or off-schema)")))
                (let [canonical (get detected-by-status status)]
                  (when-not (= canonical (= "true" detected))
                    (die! 1 (str "self-contradictory mutation: status " status " implies detected="
                                 canonical " but the report says detected=" (pr-str detected))))
                  {:detected canonical})))
            parsed (map read-mut muts)
            total  (count parsed)                        ; PIT's denominator: every mutant
            killed (count (filter :detected parsed))]
        (println (str killed "\t" total))
        (System/exit 0)))))
