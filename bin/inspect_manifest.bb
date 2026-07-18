#!/usr/bin/env bb
;; craft-harness inspect_manifest.bb — reads run-pack's manifest.json and
;; prints the requested fields, one value per line, in the order asked. An
;; absent field prints an empty line (the caller decides whether that is
;; fatal). Keeps bin/inspect-run in portable bash while still parsing JSON.
;;   inspect_manifest.bb <manifest.json> <key>...
(require '[cheshire.core :as json])

(let [[manifest & keys] *command-line-args*]
  (when (or (nil? manifest) (empty? keys))
    (binding [*out* *err*]
      (println "Usage: inspect_manifest.bb <manifest.json> <key>..."))
    (System/exit 2))
  (let [m (json/parse-string (slurp manifest) true)]
    (doseq [k keys]
      (println (str (get m (keyword k) ""))))))
