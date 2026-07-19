(ns craft-harness.pack-manifest-test
  "Guards the fork's real published pack branches against the D16/D20
   distillation defect: every path in MANAGED_FILES.manifest must resolve in
   every pack branch listed in the PACKS registry, or
   `craft-harness run --pack <branch>` dies at install_tree (bin/craft-harness
   install_tree: `git show <commit>:<entry>` fails -> die).

   launcher-test only installs a synthetic `test-pack` branch copied from the
   fork's own working tree, so it can never catch a distilled pack branch that
   under-carries the manifest. This test closes that gap by validating the
   ACTUAL branches. Packs are declared explicitly in PACKS (never by naming
   convention), so upstream packs (two-pack/four-pack/six-pack) and milestone
   dev branches (m*-...) are not swept in."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def repo-root (fs/cwd))

(defn- read-registry
  "Non-blank, non-comment (#) lines of a fork-root file, trimmed."
  [rel]
  (let [f (fs/path repo-root rel)]
    (when-not (fs/exists? f)
      (throw (ex-info (str rel " not found at fork root") {:path (str f)})))
    (->> (slurp (str f))
         str/split-lines
         (map str/trim)
         (remove str/blank?)
         (remove #(str/starts-with? % "#")))))

(defn- git-exit [& args]
  (:exit (apply sh/sh (concat ["git"] args [:dir (str repo-root)]))))

(defn- branch-exists? [b]
  (zero? (git-exit "rev-parse" "--verify" "--quiet" (str b "^{commit}"))))

(defn- resolves? [branch entry]
  (zero? (git-exit "cat-file" "-e" (str branch ":" entry))))

(deftest packs-registry-is-nonempty
  (is (seq (read-registry "PACKS"))
      "PACKS must declare at least one pack branch"))

(deftest every-pack-branch-satisfies-the-manifest
  (let [entries (read-registry "MANAGED_FILES.manifest")
        packs   (read-registry "PACKS")]
    (is (seq entries) "MANAGED_FILES.manifest must list at least one entry")
    (doseq [b packs]
      (is (branch-exists? b)
          (str "pack branch '" b "' declared in PACKS does not exist"))
      (when (branch-exists? b)
        (doseq [e entries]
          (is (resolves? b e)
              (str "pack branch '" b "' is missing manifest entry '" e
                   "' — `craft-harness run --pack " b "` would die at install_tree")))))))
