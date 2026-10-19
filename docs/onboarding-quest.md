# Initial quest

`ru.arc.onboarding` owns the optional initial quest, independently of the Lands
claim-block guide. The previous delayed chat queue and local `onboarding-v2.json`
reader are removed; old files are not imported into the new opt-in state.

- `/arc onboarding` opens the native journal.
- `/arc onboarding test` and `restart` require `arc.admin` or operator and target
  only the sender. They create a fresh offer; the player explicitly accepts it.
  `test` preserves inventory. `restart` durably backs up the sender inventory with
  the Core player-state codec, then replaces it with the native free CMI `start`
  kit. CMI owns its two item commands scheduled on the next tick.
- `/arc onboarding stop` stops only the sender. Administrator test controls
  stay in commands; the player screen shows only its current goal and action.
- `quest.public-enabled` defaults to false, independently of legacy `enabled`.
  Joins load existing state and never create enrollment.
- NPC 447 retains its normal command for everyone outside the quest audience.
  Only an offered participant sees the private packet `!` and NPC glow. The cue
  is 4x scale at 2.65 blocks above the NPC's feet; native labels remain intact.
- The quest world picker has two balanced table rows and routes ordinary world
  to `arc rtp vanilla`, new biomes to `arc rtp survival`. Mining stays in the
  general teleport menu and independent claim guide, and never advances this quest.
- Accepting an offer immediately replaces the live quest dialog with the world
  picker while travel is still the current goal. The asynchronous completion
  must not clear dialog history before opening its successor.
- Progress and completion are silent in chat; their state remains in the journal.

Progress is a bounded Redis CAS record in `arc.onboarding.quest.v1`, shared by
Spawn and Survival. Home and land must refer to the same chunk. Existing world
presence counts as arrival for repeatable admin testing. Quest controls do not
reset RTP, homes or land; only administrative `restart` replaces inventory as
described above. Async home verification captures the run.

The Java-shaped `arc-onboarding-api` service is owned and registered by ARC.
ArcBuilder compiles against that API without packaging it and reports book
preview plus durable construction start/completion. Completion must match the
stored construction operation UUID; a restart cannot inherit an old build.
Missing service does not affect building, and failed quest persistence never
rolls back committed construction. Builder callbacks confirm milestones; reward
settlement remains owned by ARC. The administrative native-kit reset does not
change ordinary kit availability, cooldown records, currency or XP.

Focused checks: `./gradlew :test --tests ru.arc.onboarding.OnboardingJourneyTest
--tests ru.arc.onboarding.OnboardingStoreTest --tests ru.arc.onboarding.OnboardingConfigTest
--tests ru.arc.onboarding.OnboardingQuestDialogTest`. The source-driven visual
preview is `python3 -B scripts/render-onboarding-quest-preview.py`; it is an
offline preview, not native client acceptance.

## Persistent guide and rewards

An active participant sees a four-line guide in the existing base sidebar:
the stage heading, current goal, one instruction and step reward. Only balance
and location sections remain beside it. Player section preferences are preserved;
completion, stopping or timeout restores their normal sidebar. More important
activity panels retain their shared priority. Nothing opens a dialog on progress.

A personal bossbar names the current command or gesture. Home uses the native
`/cmi sethome` route (the short CMI alias is disabled in the runtime mirror).
Claim and build hints offer `/kit start` only while the appropriate usable item
is absent; modern ArcBuilder books are identified by their BOOK material and
stable PDC ID/schema, never display name. The book hint is the single gesture
`ПКМ книгой строительства по земле`; a historical preview milestone does not change it.
The Runtime owns one bar per player, refreshes it after durable progress and
inside the existing five-second main-thread loop, and hides it on every guide
exit, quit, timer invalidation and shutdown. It never executes a command or
changes inventory, rewards, sidebar rows, enrollment or expiry.


The guide lasts three hours of native `PLAY_ONE_MINUTE` ticks (216000). A bounded
Redis CAS record in `arc.onboarding.playtime.v1` retains per-backend baseline and
high-water counters per run. Sampling starts after acceptance, repeats every
five seconds, and flushes on quit/shutdown. Offline time never advances native
statistics. Late old-run samples cannot overwrite a new run. Counter rollback
fails closed and is logged rather than resetting the allowance. Expiry stops
only the matching active run, preserving already completed work.

Four confirmed milestones each award **200 Vault and 1 premium token**: arrival
in a home world, a saved home, matching protected land, and completed construction.
The lifetime total is **800 Vault and 4 tokens per UUID**, separate currency units.
`arc_onboarding_rewards` uses Core's shared MySQL one-time-use ledger and the
existing private `reward-redemption.yml` connection settings. Stage/currency
identities exclude the run and backend: administrative restarts and transfers
cannot repeat a payment. Reserve precedes the provider call; ambiguous outcomes
remain held for recovery and are never blindly paid again. Rewards do not send
chat messages. Source, mocked provider checks, disk delivery and actual payment
observations are separate evidence.
