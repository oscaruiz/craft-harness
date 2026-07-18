#!/usr/bin/env bb
;; Atomically rename src over dst (POSIX rename(2) semantics). Used by
;; bin/craft-harness for the swap step: GNU `mv -T` is not portable to macOS,
;; and `ln -sfn` is unlink+create, not atomic. babashka is already a
;; sanctioned dependency (CLAUDE.md).
(require '[babashka.fs :as fs])
(let [[src dst] *command-line-args*]
  (when-not (and src dst)
    (binding [*out* *err*] (println "usage: atomic_rename.bb <src> <dst>"))
    (System/exit 2))
  (fs/move src dst {:atomic-move true}))
