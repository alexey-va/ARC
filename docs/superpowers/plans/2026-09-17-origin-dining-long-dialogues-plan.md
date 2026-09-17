# Origin Dining Long Dialogues Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the spawn restaurant's two-line ambient NPC exchanges with many coherent, Minecraft-themed conversations while preserving the existing ambient-only behavior, and reconcile the operator documentation with the current ARC ownership.

**Architecture:** Keep the current `OriginDiningModule` as the single active owner. Extend one dialogue record from two fixed strings to an ordered, even-length `lines` list. The runtime advances one line at a time, alternates the two configured NPCs, and owns a cancellable token for each active conversation so an audience/actor disappearing cannot leave delayed callbacks speaking out of context. Existing `first-line` and `second-line` fields remain a compatibility fallback for dormant or older records.

**Tech Stack:** Kotlin, ARC core `Config`, Citizens NPCs, Bukkit scheduler, YAML module config, Markdown/JSON operational documentation, Gradle tests, `scripts/mc` delivery workflow.

**Spec:** `docs/superpowers/specs/2026-09-17-origin-dining-long-dialogues-design.md` (commit `8eae09c` in this worktree).

## Global Constraints

- Active ambient ownership stays in `OriginDiningModule.kt`; do not add parallel Denizen behavior.
- The active catalog contains 30 chains: six variants for each of the five existing guest pairs.
- Every active chain contains eight or ten non-blank lines and alternates speaker by list index.
- Conversations remain ambient NPC-to-NPC speech: no click trigger, chat spam, quest, reward, economy, command, or permission change.
- Recheck the active token, actors, world, pair distance, and nearby audience before each delayed line.
- Do not log raw player-facing dialogue text.
- Preserve dormant service/staff dialogue records and the existing two-field fallback.
- Treat source YAML and the deployed `classic/plugins/ARC/modules/origin-dining.yml` as separate artifacts and verify byte identity after synchronization.
- Preserve unrelated dirty changes in `/Users/alexey23/RusCrafting` and `/Users/alexey23/mcserver`; stage only files owned by this task.
- Do not change prices, rewards, payouts, or other economy values.

---

## Task 1: Add a validated dialogue sequence model and parser

**Files:**

- Modify `src/main/kotlin/ru/arc/origin/OriginDiningModule.kt` near `OriginDiningDialogue` and `OriginDiningLayout.load`.
- Modify `src/test/kotlin/ru/arc/origin/OriginDiningLayoutTest.kt` for catalog-level parser assertions.
- Add `src/test/kotlin/ru/arc/origin/OriginDiningDialogueSequenceTest.kt` for pure sequence behavior.

### Step 1: Write failing parser/sequence tests

- [ ] Add a test that an eight-line record maps indexes `0, 2, 4, 6` to `firstNpcId` and indexes `1, 3, 5, 7` to `secondNpcId.
- [ ] Add a test that `line(index)` returns both the text and the resolved speaker NPC id.
- [ ] Add a test that a missing `lines` list falls back to non-blank `first-line` and `second-line`.
- [ ] Add tests that a configured list with fewer than two lines, an odd number of lines, a blank line, or two blank legacy fields is rejected with an actionable error.
- [ ] Update the layout fixture expectation from 10 active dialogue IDs to 30 active dialogue IDs and assert every active sequence has an even length of eight or ten.

### Step 2: Run the focused tests to establish the red state

```bash
./gradlew --no-daemon --console=plain test --tests 'ru.arc.origin.OriginDiningLayoutTest' --tests 'ru.arc.origin.OriginDiningDialogueSequenceTest'
```

Expected result before implementation: compilation/test failure because the sequence model and new expectations do not exist yet.

### Step 3: Implement the smallest model and parser

- [ ] Change `OriginDiningDialogue` to hold `lines: List<String>` instead of only the two rendered lines.
- [ ] Add an internal `OriginDiningDialogueLine(index: Int, npcId: Int, text: String)` value type.
- [ ] Add `speakerNpcId(index)` and `line(index)` helpers with bounds checks.
- [ ] Add `parseOriginDiningDialogueLines(id, configured, legacyFirst, legacySecond)` as a pure internal function.
- [ ] Normalize only the validation decision: reject `text.isBlank()` but preserve authored text exactly for display.
- [ ] Make the loader read `lines` with `stringListOrNull`; when the key is absent, construct the two-line compatibility sequence from the legacy fields.
- [ ] Keep service dialogue records on the existing two-field format through the same fallback path.

### Step 4: Run the focused tests to establish the green model

```bash
./gradlew --no-daemon --console=plain test --tests 'ru.arc.origin.OriginDiningDialogueSequenceTest'
```

### Step 5: Review the model before moving on

- [ ] Confirm no positional YAML access is used for dialogue content beyond the intentional alternating list index.
- [ ] Confirm errors identify the dialogue id and the invalid list shape.
- [ ] Confirm the parser has no scheduler, Bukkit, or player dependencies.

---

## Task 2: Replace the two-step ambient callback with a cancellable sequencer

**Files:**

- Modify `src/main/kotlin/ru/arc/origin/OriginDiningModule.kt` in the ambient state, `tickAmbient`, `showSpeech` helpers, and `stop` cleanup.
- Extend `src/test/kotlin/ru/arc/origin/OriginDiningDialogueSequenceTest.kt` with deterministic state-transition tests where the existing test seams permit it.

### Step 1: Write the runtime transition tests

- [ ] Cover start: line zero is shown by the first NPC and a tokenized continuation is scheduled.
- [ ] Cover continuation: each valid delayed transition advances exactly one line and resolves the alternating actor from the sequence index.
- [ ] Cover cancellation: removing the audience or either actor invalidates the token, removes only the owned speech displays, restores saved rotations when possible, and schedules a retry.
- [ ] Cover stale callback: a callback with an old token cannot speak, mutate the new conversation, or restore the new conversation's rotations.
- [ ] Cover completion: after the final line, the active state is cleared and the normal next-dialogue delay is scheduled.

### Step 2: Implement tokenized active state

- [ ] Add private active state containing a unique token, selected dialogue, saved NPC yaws, and the next line index.
- [ ] Ensure `tickAmbient` does not select another pair while active state exists.
- [ ] Extract the existing audience/pair validity checks into a helper reused at start and before every line.
- [ ] Preserve the current route and waiter-busy gates by continuing to resolve actors through `ambientActor`.

### Step 3: Implement one-line-at-a-time scheduling

- [ ] Show the first line immediately when a conversation starts.
- [ ] Schedule the next line using the existing ambient reply delay range; use the existing look-hold duration after the final line before restoring rotations.
- [ ] Resolve the speaker from `dialogue.line(nextIndex)` rather than assuming a fixed first/second callback.
- [ ] Face the current speaker toward the other NPC before showing its line.
- [ ] Keep the current ephemeral `TextDisplay` surface, duration, range, background, and shadow settings.

### Step 4: Implement cancellation and cleanup

- [ ] On every continuation, reject stale tokens and cancel when the world, spawn state, waiter-busy state, pair distance, or audience gate is invalid.
- [ ] Make cancellation idempotent and ensure delayed cleanup from an old token cannot delete a newer NPC display.
- [ ] Clear active state in `stop()` and remove conversation-owned displays during module shutdown.
- [ ] Keep raw authored lines out of logs; log only ids, phase, and reason/count metadata.

### Step 5: Run focused and existing layout tests

```bash
./gradlew --no-daemon --console=plain test --tests 'ru.arc.origin.OriginDiningLayoutTest' --tests 'ru.arc.origin.OriginDiningDialogueSequenceTest'
```

### Step 6: Self-review runtime invariants

- [ ] Verify a chain cannot overlap itself or another chain.
- [ ] Verify every delayed callback has a token and validity check before any speech, face, or state mutation.
- [ ] Verify a player leaving the audience never causes a later line to appear without a fresh audience check.
- [ ] Verify service dialogue scheduling and waiter animation behavior remain unchanged.

---

## Task 3: Author the large Minecraft-themed ambient catalog

**Files:**

- Modify `src/main/resources/modules/origin-dining.yml` in `dialogue-ids` and `dialogues`.
- Modify `src/test/kotlin/ru/arc/origin/OriginDiningLayoutTest.kt` with exact catalog and pair coverage checks.

### Step 1: Define the 30 active ids

- [ ] Add six chains for pair `416/417`: `brewery_north_creeper`, `brewery_north_redstone`, `brewery_north_nether`, `brewery_north_villager`, `brewery_north_warden`, `brewery_north_first_night`.
- [ ] Add six chains for pair `407/408`: `brewery_fire_minecart`, `brewery_fire_ender`, `brewery_fire_archaeology`, `brewery_fire_potions`, `brewery_fire_netherite`, `brewery_fire_raid`.
- [ ] Add six chains for pair `419/420`: `brewery_east_creeper`, `brewery_east_redstone`, `brewery_east_nether`, `brewery_east_villager`, `brewery_east_warden`, `brewery_east_first_night`.
- [ ] Add six chains for pair `433/434`: `restaurant_a_minecart`, `restaurant_a_ender`, `restaurant_a_archaeology`, `restaurant_a_potions`, `restaurant_a_netherite`, `restaurant_a_raid`.
- [ ] Add six chains for pair `435/436`: `restaurant_b_creeper`, `restaurant_b_redstone`, `restaurant_b_nether`, `restaurant_b_villager`, `restaurant_b_warden`, `restaurant_b_first_night`.
- [ ] Keep the six staff/bar records defined but out of the active `dialogue-ids` list.

### Step 2: Write the authored lines

- [ ] Give every active id eight or ten short lines with a clear setup, escalation, punchline or observation, and closing beat.
- [ ] Cover creepers/TNT, redstone/hoppers/comparators, Nether travel, villagers and emeralds, Warden/sculk/wool, minecarts/rails, first-night survival, Ender/elytra/shulkers, archaeology/sniffers, potions, raids, netherite, and farms.
- [ ] Keep the two speakers distinct through recurring attitudes: one practical/experienced voice and one curious, skeptical, or overconfident voice.
- [ ] Avoid claims that imply a live quest, guaranteed drop, reward, or server command.

### Step 3: Add catalog assertions

- [ ] Assert the active count is exactly 30.
- [ ] Assert all line counts are eight or ten, even, and non-blank.
- [ ] Assert the five expected NPC pairs each have exactly six active chains.
- [ ] Assert all active ids are unique and all referenced NPC ids are in the existing guest pair set.

### Step 4: Run content and YAML tests

```bash
./gradlew --no-daemon --console=plain test --tests 'ru.arc.origin.OriginDiningLayoutTest'
```

### Step 5: Inspect authored output

- [ ] Read the full active block once for repetition, broken continuity, accidental technical promises, and lines too long for the configured speech display.
- [ ] Run `git diff --check` and a YAML parse/loader test before publication.

---

## Task 4: Synchronize the runtime mirror and fix documentation conflicts

**Files:**

- Copy the validated source YAML to `classic/plugins/ARC/modules/origin-dining.yml` in the ops worktree and verify byte identity.
- Modify `docs/knowledge/npc-scene-origin-restaurant.md`.
- Modify `docs/knowledge/npc-scenes-origin-restaurant.json`.
- Modify `docs/knowledge/economy-origin-dining-20260912.json` only to clarify its historical ownership/source context; preserve all recorded amounts and findings.

### Step 1: Reconcile ownership language

- [ ] State that ARC `OriginDiningModule` is the active owner of seating/session, ambient dialogue, waiter motion, and paid dining lifecycle.
- [ ] State that Citizens remains the NPC identity/protection layer and CMI remains a configured marker/vehicle integration where applicable.
- [ ] Mark the old Denizen scripts as retired compatibility/history references rather than active source owners.
- [ ] Correct `.dsc` links to the actual `.dscc` filenames.

### Step 2: Document the long-dialogue contract

- [ ] Document the 30 active chains, alternating `lines` list, nearby-audience gate, ephemeral speech display, and cancellation behavior.
- [ ] Link source config, runtime mirror, and ARC Kotlin owner using repository-relative paths that resolve from the docs location.
- [ ] Keep historical verification dates and economic snapshot amounts unchanged; label historical facts as historical.

### Step 3: Validate docs and mirror

```bash
cmp src/main/resources/modules/origin-dining.yml \
  /private/tmp/ruscrafting-ops-origin-long-dialogues/classic/plugins/ARC/modules/origin-dining.yml
python3 -m json.tool docs/knowledge/npc-scenes-origin-restaurant.json >/dev/null
python3 -m json.tool docs/knowledge/economy-origin-dining-20260912.json >/dev/null
git diff --check
```

- [ ] Search docs for active-owner claims that still assign restaurant dining or ambient conversation to Denizen and either correct them or explicitly mark them historical.
- [ ] Confirm no player-facing line, price, reward, or economy configuration changed in the documentation reconciliation.

---

## Task 5: Verify, publish, activate, and perform bounded live QA

**Files/commands:**

- ARC worktree `/private/tmp/arc-origin-long-dialogues`.
- Ops worktree `/private/tmp/ruscrafting-ops-origin-long-dialogues`.
- `./gradlew --no-daemon --console=plain test ...` and `./gradlew --no-daemon --console=plain shadowJar` as permitted by project instructions.
- Supported `./scripts/mc` workflow from the ops worktree, following `ruscrafting-server-ops` documentation.

### Step 1: Run final source verification

- [ ] Run focused tests and the relevant ARC test suite.
- [ ] Build the ARC shadow JAR.
- [ ] Inspect `git diff --check`, final source diff, final config diff, and test/build output.
- [ ] Confirm source config and ops mirror are byte-identical.

### Step 2: Commit and push exact owned paths

- [ ] Commit the ARC source/config/tests/spec/plan changes on the detached master worktree and push with `git push origin HEAD:master`.
- [ ] Commit the ops mirror/docs changes on the detached main worktree and push with `git push origin HEAD:main`.
- [ ] Re-read both remote refs and record commit ids.
- [ ] Do not stage or modify the unrelated dirty skill files in the shared checkouts.

### Step 3: Deliver through supported server operations

- [ ] Read the server-ops deployment contract and use its supported publication/restart path for the ARC plugin/config.
- [ ] Verify the active JAR/config path, plugin load, and runtime module after activation.
- [ ] Treat server health and active artifact readback as deployment evidence, separate from player-visible evidence.

### Step 4: Perform bounded player-visible QA

- [ ] Use the existing supported player-bot/client path to observe the restaurant spawn area with at least one nearby player.
- [ ] Confirm multiple lines from one chain appear in order, alternate between the intended NPCs, and remain ambient NPC-to-NPC speech.
- [ ] Confirm a player leaving the area stops the chain without stale delayed speech; do not repeat successful cases unnecessarily.
- [ ] Record exact runtime/client evidence and clearly mark any surface that could not be observed.

### Step 5: Final review and handoff

- [ ] Review the actual final diff for spec coverage, parser/runtime consistency, catalog quality, docs ownership consistency, and accidental unrelated changes.
- [ ] Report source, tests, publication, activation, server readback, and client QA as separate evidence layers.
- [ ] Report both worktree paths and whether they were retained or cleaned up; leave the user's pre-existing dirty checkouts untouched.
