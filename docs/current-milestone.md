# Milestone 4.5 — Owned-path contract (closes D17)

Goal: turn "work only inside <paths>" from prose the LLM may ignore into an
executable contract the harness enforces on the candidate commit. Surfaced by
the myCQRS value checkpoint: run-solo records HEAD without scoping paths
(bin/run-solo:293-294), and no machine-readable owned-path set exists anywhere
(pre-commit is blacklist-only; conf has no path field; project.prompt is read
by zero executable code). Reference: `docs/design-v2.2.md` §1 (R4, R9), D17.

## Design decisions to make first (record as D19)
1. **Where owned paths are declared.** Recommendation: a new structured field
   in project.prompt (`owns:` — one glob per line), since scope is a project
   property. This gives project.prompt its first machine-readable field, so
   define a tiny, strict parser (ignore prose, read only the fenced/keyed
   block) rather than parsing the whole file.
2. **Enforcement points (both, not either):**
   - run-solo: after the code phase, the candidate commit must touch only
     owned paths; if it touches anything outside, the run fails with
     attribution (naming the offending paths) and does not proceed to verify.
   - pre-commit hook: upgraded from blacklist-only to also enforce the
     allowlist when an owns-set is present — so a bypassed hook is caught by
     run-solo, and a bypassed run-solo is caught by the hook. Defence in depth,
     per D2.

## Behaviors to implement (in order)
1. **Owns-set parser** (`bin/parse-owns` or a shared helper). Reads the `owns:`
   block from project.prompt into a glob list; empty/missing = no allowlist
   (backward compatible — existing packs unaffected). Tests: valid block
   parsed; malformed block fails loudly; prose elsewhere ignored.
2. **Candidate-commit scope check** in run-solo. Compute the commit's touched
   paths (vs the phase baseline), assert every path matches an owned glob,
   else fail before verify with the offending paths named. Tests against
   fabricated commits: in-scope passes; a planted out-of-scope file turns the
   run red (this is the D17 negative test).
3. **Hook allowlist mode.** When an owns-set is present, pre-commit rejects
   staged paths outside it (keeping the existing blacklist). Tests: staged
   out-of-scope path rejected; in-scope allowed; no owns-set → old behavior.
4. **Wire myCQRS.** Add `owns: src/core/**` to myCQRS's project.prompt (the
   real consumer that surfaced this). No code change to myCQRS itself.

## Exit criteria
- bb suite green, including the planted out-of-scope negative test (D17) and
  the hook allowlist tests.
- A dirty working tree no longer contaminates the candidate commit: a run
  with unrelated dirt present commits only owned paths (or fails naming the
  dirt) — the exact myCQRS condition, reproduced in a test.
- D19 recorded (declaration location + parser + dual enforcement).
- Merged to main; branch pushed (by owner, from Windows).

## Out of scope
The value checkpoint itself (runs AFTER this lands) · CRLF/.gitattributes in
myCQRS (that's D18, owner-side, independent) · real language toolchains
(m5-6) · any change to what the packs' role prompts say (prose stays; we're
adding executable enforcement beneath it, not rewriting it).
