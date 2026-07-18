(ns craft-harness.scenario-asserts-test
  "Milestone 2, behavior B1 (docs/current-milestone.md): deterministic
   asserts of the scenario runner. Each assert is a pure function of
   repo/git state — no agent involved. Every deftest fabricates a good
   state AND the bad states the assert must reject: the negative
   verification demanded by CLAUDE.md is built into the block."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def asserts-script (str (fs/path repo-root "adapters" "scenario-asserts")))

;; --- sandbox plumbing (same shape as launcher-test) ---------------------

(def ^:dynamic *sandboxes* nil)

(use-fixtures :each
  (fn [t]
    (binding [*sandboxes* (atom [])]
      (try (t)
           (finally (doseq [d @*sandboxes*]
                      (fs/delete-tree d)))))))

(defn tmp-dir []
  (let [d (fs/create-temp-dir {:prefix "craft-harness-m2."})]
    (swap! *sandboxes* conj d)
    d))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn sh-run
  [{:keys [dir env ok?]} & args]
  (let [result (apply sh/sh (concat args [:dir (str dir)
                                          :env (merge {"PATH" (System/getenv "PATH")
                                                       "HOME" (System/getenv "HOME")
                                                       "GIT_CONFIG_NOSYSTEM" "1"}
                                                      env)]))]
    (when (and (not (false? ok?)) (not= 0 (:exit result)))
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      (assoc result :args args))))
    result))

(defn git! [dir & args]
  (apply sh-run {:dir dir} "git" args))

;; --- the canonical toy scenario workdir ---------------------------------

(def broken-sut "#!/usr/bin/env bash\necho broken\n")
(def fixed-sut "#!/usr/bin/env bash\necho 42\n")
(def toy-test
  "#!/usr/bin/env bash\nset -euo pipefail\n[[ \"$(bash ./sut.sh)\" == \"42\" ]]\n")

(defn make-workdir!
  "Toy repo exactly as run-scenario will build it (B4): committed task.md,
   broken sut.sh, deterministic test.sh, baseline commit on branch main.
   Returns {:dir :baseline}."
  []
  (let [dir (fs/path (tmp-dir) "workdir")]
    (fs/create-dirs dir)
    (git! dir "init" "-q" "-b" "main")
    (git! dir "config" "user.email" "toy@example.com")
    (git! dir "config" "user.name" "Toy User")
    (write-file (fs/path dir "task.md") "Make ./test.sh pass: sut.sh must print 42.\n")
    (write-file (fs/path dir "sut.sh") broken-sut)
    (write-file (fs/path dir "test.sh") toy-test)
    (sh-run {:dir dir} "chmod" "+x" "sut.sh" "test.sh")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "-m" "toy baseline")
    {:dir dir
     :baseline (str/trim (:out (git! dir "rev-parse" "HEAD")))}))

(defn fix-sut! [{:keys [dir]}]
  (write-file (fs/path dir "sut.sh") fixed-sut))

(defn commit-all! [{:keys [dir]} message]
  (git! dir "add" "-A")
  (git! dir "commit" "-q" "-m" message))

(defn head-10 [{:keys [dir]}]
  (str/trim (:out (git! dir "rev-parse" "--short=10" "HEAD"))))

;; --- invoking the asserts ------------------------------------------------

(defn scenario-assert
  "Run one phase assert; returns the sh result (never throws on non-zero)."
  [{:keys [dir baseline]} phase & extra]
  (apply sh-run {:dir dir :ok? false}
         "bash" asserts-script phase "--workdir" (str dir)
         (concat (when baseline ["--baseline" baseline])
                 extra)))

(defn passes? [res] (zero? (:exit res)))
(defn output [res] (str (:out res) (:err res)))

;; --- handoff fabrication (format of swarm_handoff.bb's write-handoff!) --

(defn handoff-text [commit-abbrev & {:keys [drop-headers priority]
                                     :or {drop-headers #{} priority "50"}}]
  (let [headers [["id" "20260718T120000Z_000001_from_coder"]
                 ["from" "coder"]
                 ["to" "verifier"]
                 ["priority" priority]
                 ["type" "git_handoff"]
                 ["role" "coder"]
                 ["task" "toy-task"]
                 ["commit" commit-abbrev]
                 ["created_at" "2026-07-18T12:00:00Z"]]]
    (str (->> headers
              (remove (fn [[k _]] (contains? drop-headers k)))
              (map (fn [[k v]] (str k ": " v)))
              (str/join "\n"))
         "\n\n"
         "Re-read your role and constitution.\n\n"
         "merge_and_process coder " commit-abbrev "\n")))

(defn outbox-dir [{:keys [dir]}]
  (fs/path dir ".swarmforge" "handoffs" "outbox"))

(defn inbox-dir [{:keys [dir]} sub]
  (fs/path dir ".swarmforge" "handoffs" "inbox" sub))

(def handoff-name "50_20260718T120000Z_000001_from_coder_to_verifier.handoff")

(defn fabricate-outbox! [wd text]
  (write-file (fs/path (outbox-dir wd) handoff-name) text))

;; --- edit ----------------------------------------------------------------

(deftest edit-assert
  (let [wd (make-workdir!)]
    (testing "pristine workdir: no edit yet"
      (let [res (scenario-assert wd "edit")]
        (is (not (passes? res)) "edit assert must fail before any edit")
        (is (str/includes? (output res) "sut.sh"))))
    (testing "uncommitted fix counts as an edit"
      (fix-sut! wd)
      (is (passes? (scenario-assert wd "edit"))))
    (testing "committed fix still counts"
      (commit-all! wd "fix sut")
      (is (passes? (scenario-assert wd "edit"))))))

;; --- test ----------------------------------------------------------------

(deftest test-assert
  (let [wd (make-workdir!)]
    (testing "broken sut: the runner runs ./test.sh itself and it fails"
      (is (not (passes? (scenario-assert wd "test")))))
    (testing "fixed sut: ./test.sh exits 0"
      (fix-sut! wd)
      (is (passes? (scenario-assert wd "test"))))))

;; --- commit --------------------------------------------------------------

(defn commit-assert [wd]
  (scenario-assert wd "commit" "--branch" "main"))

(deftest commit-assert-good-state
  (let [wd (make-workdir!)]
    (fix-sut! wd)
    (commit-all! wd "fix sut")
    (is (passes? (commit-assert wd))
        "clean committed fix on the enforced branch must pass")))

(deftest commit-assert-bad-states
  (testing "no new commit"
    (let [wd (make-workdir!)]
      (fix-sut! wd)
      (let [res (commit-assert wd)]
        (is (not (passes? res)))
        (is (re-find #"(?i)no new commit" (output res))))))
  (testing "fix committed but working tree left dirty"
    (let [wd (make-workdir!)]
      (fix-sut! wd)
      (commit-all! wd "fix sut")
      (write-file (fs/path (:dir wd) "sut.sh") (str fixed-sut "# stray\n"))
      (let [res (commit-assert wd)]
        (is (not (passes? res)))
        (is (re-find #"(?i)uncommitted|dirty" (output res))))))
  (testing "commit touching a blacklisted path (hook bypassed with --no-verify)"
    (let [wd (make-workdir!)]
      (fix-sut! wd)
      (write-file (fs/path (:dir wd) "task.md") "tampered by agent\n")
      (git! (:dir wd) "add" "-A")
      (git! (:dir wd) "commit" "-q" "--no-verify" "-m" "sneaky")
      (let [res (commit-assert wd)]
        (is (not (passes? res)) "the assert must catch what the hook missed")
        (is (str/includes? (output res) "task.md")
            "the failure must name the offending path"))))
  (testing "commit on the wrong branch"
    (let [wd (make-workdir!)]
      (git! (:dir wd) "switch" "-q" "-c" "rogue")
      (fix-sut! wd)
      (commit-all! wd "fix sut")
      (let [res (commit-assert wd)]
        (is (not (passes? res)))
        (is (re-find #"(?i)branch" (output res)))))))

(deftest blacklist-regex-matches-pre-commit-hook
  (let [extract (fn [file]
                  (some #(when (str/starts-with? % "BLACKLIST=") %)
                        (str/split-lines (slurp file))))]
    (is (= (extract (str (fs/path repo-root "hooks" "pre-commit")))
           (extract asserts-script))
        "scenario-asserts must use byte-identical BLACKLIST to hooks/pre-commit — fix the drift")))

;; --- handoff -------------------------------------------------------------

(deftest handoff-assert
  (testing "well-formed git_handoff referencing a real commit"
    (let [wd (make-workdir!)]
      (fix-sut! wd)
      (commit-all! wd "fix sut")
      (fabricate-outbox! wd (handoff-text (head-10 wd)))
      (is (passes? (scenario-assert wd "handoff")))))
  (testing "no handoff in the outbox"
    (let [wd (make-workdir!)]
      (let [res (scenario-assert wd "handoff")]
        (is (not (passes? res)))
        (is (re-find #"(?i)outbox" (output res))))))
  (testing "missing required header (task)"
    (let [wd (make-workdir!)]
      (fix-sut! wd)
      (commit-all! wd "fix sut")
      (fabricate-outbox! wd (handoff-text (head-10 wd) :drop-headers #{"task"}))
      (let [res (scenario-assert wd "handoff")]
        (is (not (passes? res)))
        (is (str/includes? (output res) "task")))))
  (testing "priority must be two digits"
    (let [wd (make-workdir!)]
      (fix-sut! wd)
      (commit-all! wd "fix sut")
      (fabricate-outbox! wd (handoff-text (head-10 wd) :priority "high"))
      (is (not (passes? (scenario-assert wd "handoff"))))))
  (testing "commit header must resolve to a commit in the workdir"
    (let [wd (make-workdir!)]
      (fabricate-outbox! wd (handoff-text "deadbeefde"))
      (let [res (scenario-assert wd "handoff")]
        (is (not (passes? res)))
        (is (str/includes? (output res) "deadbeefde")))))
  (testing "more than one handoff is ambiguous"
    (let [wd (make-workdir!)]
      (fix-sut! wd)
      (commit-all! wd "fix sut")
      (fabricate-outbox! wd (handoff-text (head-10 wd)))
      (write-file (fs/path (outbox-dir wd) "51_x_000002_from_coder_to_verifier.handoff")
                  (handoff-text (head-10 wd)))
      (is (not (passes? (scenario-assert wd "handoff")))))))

;; --- wakeup --------------------------------------------------------------

(defn dequeued-handoff-text [commit-abbrev]
  (str/replace (handoff-text commit-abbrev)
               "created_at: 2026-07-18T12:00:00Z"
               (str "created_at: 2026-07-18T12:00:00Z\n"
                    "dequeued_at: 2026-07-18T12:01:00Z")))

(deftest wakeup-assert
  (testing "handoff dequeued into in_process with dequeued_at set"
    (let [wd (make-workdir!)]
      (write-file (fs/path (inbox-dir wd "in_process") handoff-name)
                  (dequeued-handoff-text "abcdef1234"))
      (is (passes? (scenario-assert wd "wakeup")))))
  (testing "handoff completed also counts as consumed"
    (let [wd (make-workdir!)]
      (write-file (fs/path (inbox-dir wd "completed") handoff-name)
                  (dequeued-handoff-text "abcdef1234"))
      (is (passes? (scenario-assert wd "wakeup")))))
  (testing "handoff still sitting in inbox/new: wake-up not consumed"
    (let [wd (make-workdir!)]
      (write-file (fs/path (inbox-dir wd "new") handoff-name)
                  (handoff-text "abcdef1234"))
      (let [res (scenario-assert wd "wakeup")]
        (is (not (passes? res)))
        (is (re-find #"(?i)new" (output res))))))
  (testing "no handoff anywhere: nothing was delivered or consumed"
    (let [wd (make-workdir!)]
      (is (not (passes? (scenario-assert wd "wakeup"))))))
  (testing "in_process but never dequeued via ready_for_next (no dequeued_at)"
    (let [wd (make-workdir!)]
      (write-file (fs/path (inbox-dir wd "in_process") handoff-name)
                  (handoff-text "abcdef1234"))
      (let [res (scenario-assert wd "wakeup")]
        (is (not (passes? res)))
        (is (str/includes? (output res) "dequeued_at"))))))

;; --- interface hygiene ---------------------------------------------------

(deftest unknown-phase-fails-clearly
  (let [wd (make-workdir!)]
    (let [res (scenario-assert wd "no-such-phase")]
      (is (not (passes? res)))
      (is (re-find #"(?i)usage|unknown" (output res))))))
