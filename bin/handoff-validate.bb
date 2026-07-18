#!/usr/bin/env bb
;; craft-harness handoff-validate.bb — milestone 4, behavior B1.
;;
;;   handoff-validate.bb <handoff-file> [--changed]
;;
;; Validates a solo-pack structured handoff against docs/solo-handoff-schema.md:
;; a header block (id / from / to / phase / created_at, optional commit) then the
;; five mandatory sections (done / decisions / assumptions / open items /
;; commands executed). The direct heir of the original harness's progress/ files
;; (design §5). One validator, reused by bin/run-solo (between phases) and by
;; bin/inspect-run (post-run). Exit 0 = valid; 1 = invalid (reason naming the
;; offending field on stderr); 2 = usage.
;;
;; --changed (or a non-empty `commit:` header) marks a phase that produced
;; changes: such a phase MUST record at least one decision.
(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(def required-header ["id" "from" "to" "phase" "created_at"])
(def required-sections ["done" "decisions" "assumptions" "open items" "commands executed"])

(defn fail! [msg]
  (binding [*out* *err*] (println (str "handoff-validate: " msg)))
  (System/exit 1))

(let [args *command-line-args*
      file (first (remove #(str/starts-with? % "--") args))
      changed-flag (boolean (some #{"--changed"} args))]
  (when (nil? file)
    (binding [*out* *err*] (println "Usage: handoff-validate.bb <handoff-file> [--changed]"))
    (System/exit 2))
  (when-not (.exists (io/file file))
    (fail! (str "file not found: " file)))

  (let [lines (str/split-lines (slurp file))
        [header-lines rest-lines] (split-with #(not (str/blank? %)) lines)]

    ;; --- header: every line must be 'key: value' ---------------------------
    (doseq [l header-lines]
      (when-not (re-matches #"^[A-Za-z_][A-Za-z0-9_]*:\s.*$" l)
        (fail! (str "malformed header line (expected 'key: value'): " (pr-str l)))))
    (let [header (into {} (for [l header-lines
                                :let [[k v] (str/split l #":\s" 2)]]
                            [k (str/trim (or v ""))]))]
      (doseq [k required-header]
        (when (str/blank? (get header k))
          (fail! (str "missing required header field: " k))))

      ;; --- sections ---------------------------------------------------------
      (let [body (drop-while str/blank? rest-lines)
            sections (loop [ls body cur nil acc {}]
                       (if (empty? ls)
                         acc
                         (let [l (first ls)
                               m (re-matches #"^#+\s+(.*\S)\s*$" l)]
                           (if m
                             (let [name (str/lower-case (str/trim (second m)))]
                               (recur (rest ls) name (assoc acc name (get acc name []))))
                             (recur (rest ls) cur (if cur (update acc cur conj l) acc))))))]
        (doseq [s required-sections]
          (when-not (contains? sections s)
            (fail! (str "missing required section: " s))))

        ;; --- decisions must be non-empty when the phase changed things ------
        (let [changed (or changed-flag (not (str/blank? (get header "commit"))))
              decisions-nonempty (some #(not (str/blank? %)) (get sections "decisions"))]
          (when (and changed (not decisions-nonempty))
            (fail! "empty 'decisions' section but the phase produced changes (--changed / commit set)")))

        (println "handoff-validate: OK")
        (System/exit 0)))))
