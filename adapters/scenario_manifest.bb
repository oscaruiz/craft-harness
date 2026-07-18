#!/usr/bin/env bb
;; craft-harness scenario_manifest.bb — writes run-scenario's manifest.json
;; (R10 provenance: minimal reproducibility, no telemetry). Usage:
;;   scenario_manifest.bb <manifest-file> <phases-tsv> key=value...
;; The TSV holds one "<phase>\t<duration-s>\t<outcome>" line per executed
;; phase; every key=value pair lands verbatim in the manifest.
(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(let [[out phases-tsv & kvs] *command-line-args*
      _ (when (or (nil? out) (nil? phases-tsv))
          (binding [*out* *err*]
            (println "Usage: scenario_manifest.bb <manifest-file> <phases-tsv> key=value..."))
          (System/exit 2))
      phases (if (fs/exists? phases-tsv)
               (vec (for [line (str/split-lines (slurp phases-tsv))
                          :when (not (str/blank? line))
                          :let [[phase duration outcome] (str/split line #"\t")]]
                      {:name phase
                       :duration_s (parse-long duration)
                       :outcome outcome}))
               [])
      manifest (-> (into {} (for [kv kvs
                                  :let [[k v] (str/split kv #"=" 2)]]
                              [(keyword k) v]))
                   (update :phase_timeout_s #(some-> % parse-long))
                   (assoc :phases phases))]
  (spit out (str (json/generate-string manifest {:pretty true}) "\n")))
