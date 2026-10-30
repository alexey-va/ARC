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
described above. Async home verification captures the run. HuskHomes 4.9.10
emits `HomeCreateEvent` before starting persistence; ARC confirms the exact named
home with at most 20 serial async reads, 10 ticks between completed reads and a
3-second timeout per read. A newer same-name event replaces the pending attempt;
quit, player replacement and adapter shutdown discard late results. Exhaustion
logs one diagnostic. Legacy home respawn remains independent of quest enrollment.

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

A personal bossbar names the current command or gesture. Home uses HuskHomes
`/sethome`, consistent with the existing HelpCenterCommands route.
Claim and build hints offer `/kit start` only while the appropriate usable item
is absent; modern ArcBuilder books are identified by their BOOK material and
stable PDC ID/schema, never display name. The book hint is the single gesture
`ПКМ книгой строительства по земле`; a historical preview milestone does not change it.
Before home, claim, or unstarted build instructions, the bar uses the same
`allowsQuestWorld` rule as quest credit. In the resource world it says
`Перейдите из Мира добычи в Мир выживания`; in other ineligible worlds it says
`Перейдите в Мир выживания`. Unknown world IDs never enter player-facing text.
Returning to an eligible world restores the current instruction without changing
progress. A construction already in progress retains its completion-wait hint.
These two prerequisite messages are configurable under `quest.hud`.
The Runtime owns one bar per player, refreshes it after durable progress and
inside the existing five-second main-thread loop, immediately on world change,
and on the tick after a non-cancelled teleport. It hides it on every guide
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

## Optional Spawn introductions

`OptionalOnboardingService` owns three independent opt-in journeys. They do not
replace, reset, or reward the initial home quest. `OptionalQuestJourney` defines
the steps, `OptionalQuestStore` persists append-only Redis CAS records in
`arc.onboarding.optional.v1` under `<player UUID>:<adventure|casino|farm>`, and
`OptionalQuestDialog` / `OptionalQuestHud` render the journal and current goal.
Reaccepting or reopening a completed journey retains its original completion.
Join and world-change handling load existing records without enrolling anyone.
The initial home guide has priority over the optional bossbar and sidebar rows.

`optional-quests` in `modules/onboarding.yml` configures the enable switch, guide
NPCs, arrival areas, and text. It defaults to disabled outside the configured
Spawn runtime. NPC 426 (Garret), 452 (casino guide), and 464 (farm guide) use the
existing personal quest marker and open the corresponding journal on click.
Their Citizens command traits must be removed when enabling these guides.
The same journals are available at `/arc onboarding adventure`, `casino`, and
`farm`; the existing administrative home-test commands remain independent.
Safe native travel stays available before accepting and after completing the
introductions: Garret runs `/ag`, the casino guide runs `/rccasino`, and the
farm guide runs `/arcfarms travel farm`. These routes never enroll a player or
grant a trial. Primis travel remains available after the guild step; trial
arming requires an accepted, unfinished casino journey.

- Adventure: visit `em_adventurers_guild` through `/ag`, arrive in `em_primis`,
  and turn in native `primis_main_quest_1.yml`. `PrimisOnboardingListener` in
  ArcDungeons observes `QuestRewardEvent` after native reward/turn-in and can
  recover a missed acknowledgement from EliteMobs' own restored
  `elitequest.primis_main_quest_1.yml` metadata. An operator or wildcard
  permission is not completion evidence. Native Primis rewards are unchanged.
- Casino: check in near poker on the lower floor `(19.5, 33, 26.5)`, billiards
  on the middle floor `(14.5, 49, 23.5)`, and slots on the upper floor
  `(2.5, 65, 35.7)` in `rc_atelier_casino`. Each authored area is ±6 blocks in
  X/Z and from its floor Y through Y+3; these are check-in areas, not building
  wall bounds. Only actual presence after acceptance credits a visit. Complete
  the one-time slots, blackjack, and Plinko trials through their physical game
  controls. ArcCasino owns admissions, money, and durable terminal receipts.
- Farm: enter the `sp11` farm footprint, X `109..298`, Y `-64..139`, Z
  `363..575`, and contribute to a completed commercial farm shift.
  `FarmShiftCoordinator` retains the first commercial event ID for each positive
  contributor in nullable `PlayerActivityStats.farmIntroductionEventId`, before
  saving that completion. Historical aggregate completion counts and forced
  administrative finishes never create this receipt. The ordinary save path
  replays the successfully saved receipts of online players through its captured
  lifecycle; a dropped callback, cooldown reset, reload, or temporary Redis failure
  therefore cannot erase completion evidence. Lumbermill
  and mine completion never advances this quest. Native shift rewards are unchanged.

`arc-onboarding-api:0.1.1` is owned and packaged by ARC; ArcDungeons, ArcCasino,
and ArcFarms consume it without embedding its classes. `CasinoIntroService`
arms the next physical trial and reports `AVAILABLE`, `ARMED`, `PLAYING`, or
durable `COMPLETE` (`UNAVAILABLE` when the provider cannot admit it).
`OptionalOnboardingProgressService` acknowledges real Primis, farm, and casino
results after quest persistence. It never enrolls, pays, or resets a player.
Failed acknowledgements receive bounded provider retries. Casino state readback
repairs missed terminal callbacks from the retained casino receipts. Accepted
Redis writes drain at ARC shutdown; stale runtime callbacks cannot reopen UI.

Focused checks: `./gradlew :test --tests 'ru.arc.onboarding.OptionalQuest*Test'`
and `:arc-dungeons:test --tests 'ru.arc.hooks.elitemobs.PrimisOnboardingListenerTest'`.
ArcFarms checks its typed dispatch with
`./gradlew test --tests 'ru.ruscrafting.farms.paper.farm.shift.FarmOnboardingProgressTest'`.


## Shared story journal and guidance (2026-10-30)

ArcRanks `/quests` and `/rank quests` open the unified journal; `/rank daily` and
`/quests daily` retain the recurring board. ARC owns story acceptance/progress and
exports `StoryQuestService` from `arc-onboarding-api:0.1.5`; consumers never shade
that API. The journal shows cached enabled-owner snapshots and opens their real
details without resetting dialog history. The four authored stories are first
home, adventure, casino introduction and first work shift. No rewards changed.

`StoryQuestJournal` stores one explicit selection in Redis
`arc.onboarding.tracking.v1`, separately from all append-only progress hashes.
Acceptance selects the story; tracking never accepts, resets or rewards it.
An explicit empty selection suppresses story guidance and lets the recurring
tracker resume. A failed write preserves the previous visible selection.
Accepted writes drain independently of Paper callbacks before a fresh generation.
Story details show the full chain plus tracking controls. Sidebar and bossbar
coexist; the opt-in experimental hologram no longer removes the bossbar.

`OptionalQuestGuidance` uses the persisted `origin-spawn.yml` entrance anchors.
`StoryQuestWaypointHud` owns one viewer-only packet label and a bounded particle
cue; distant targets are projected locally with actual distance, without loading
chunks. `PrimisQuestGuidanceService`, provided by ArcDungeons, reads only loaded
native state: captain -> village leader -> first mate. Only EliteMobs' actual
introductory quest turn-in credits completion. Native rewards remain unchanged.
The configured native anchors were read back from Spawn on 2026-10-30.

A backend with `optional-quests.enabled=false` keeps those stories disabled:
its journal does not expose the optional owner there. Shared Redis progress and
selection remain intact and restore on an enabled backend; this change does not
turn on new enrollment, trial admission or travel on disabled backends.

Focused checks: `StoryQuestTrackingStoreTest`, `StoryQuestWaypointHudTest`,
`OptionalQuestDialogTest`, `OnboardingQuestDialogTest`, `QuestHologramHudTest`,
and ArcDungeons `PrimisOnboardingListenerTest`. Runtime activation and native
client appearance remain distinct from source/unit/preview evidence.
