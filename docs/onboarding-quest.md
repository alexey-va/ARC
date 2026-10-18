# Initial quest

`ru.arc.onboarding` owns the optional initial quest, independently of the Lands
claim-block guide. The previous delayed chat queue and local `onboarding-v2.json`
reader are removed; old files are not imported into the new opt-in state.

- `/arc onboarding` opens the native journal.
- `/arc onboarding test` and `restart` require `arc.admin` or operator and target
  only the sender. They create a fresh offer; the player explicitly accepts it.
- `/arc onboarding stop` stops only the sender. Administrator test controls
  stay in commands; the player screen shows only its current goal and action.
- `quest.public-enabled` defaults to false, independently of legacy `enabled`.
  Joins load existing state and never create enrollment.
- NPC 447 retains its normal command for everyone outside the quest audience.
  Only an offered participant sees the private packet `!` above its anchor.

Progress is a bounded Redis CAS record in `arc.onboarding.quest.v1`, shared by
Spawn and Survival. Home and land must refer to the same chunk. Existing world
presence counts as arrival for repeatable admin testing; actual RTP/home/land
and inventory state are never reset. Async home verification captures the run.

The Java-shaped `arc-onboarding-api` service is owned and registered by ARC.
ArcBuilder compiles against that API without packaging it and reports book
preview plus durable construction start/completion. Completion must match the
stored construction operation UUID; a restart cannot inherit an old build.
Missing service does not affect building, and failed quest persistence never
rolls back committed construction. No new rewards or kit cooldown bypass exists.

Focused checks: `./gradlew :test --tests ru.arc.onboarding.OnboardingJourneyTest
--tests ru.arc.onboarding.OnboardingStoreTest --tests ru.arc.onboarding.OnboardingConfigTest
--tests ru.arc.onboarding.OnboardingQuestDialogTest`. The source-driven visual
preview is `python3 -B scripts/render-onboarding-quest-preview.py`; it is an
offline preview, not native client acceptance.
