# EliteMobs compatibility

ArcDungeons supports the audited stock releases **10.9.8 and 10.9.10**. Other
version labels and modified passive catalogs remain rejected before installation.
Compile against 10.9.8, the oldest supported API, and test the same consumer
bytecode against both native runtime JARs.

## Audited artifacts

The official `com.magmaguy:EliteMobs` runtime JARs from
`https://repo.magmaguy.com/releases/` were compared on 2026-10-18:

| Version | SHA-256 |
| --- | --- |
| 10.9.8 | `7c01d9fc2375d7f23d3a875f6e63f905672bbb73ab735c2672f747f4d79b2b24` |
| 10.9.10 | `99f4d6595739fc8d59a9e4c2cd558c0fa61b4294df202973a6cf4e7265577bb2` |

All 425 `advancedcombat` classes, nine `AdvancedClass*` command classes and
13 presentation classes are byte-identical. This includes the private fields,
registry constructor and menu renderer interface used by `EliteMobsPassiveAccess`,
the 76-form catalog, progression, trials and the class input router.
The accepted passive balance and Russian menu content therefore stay unchanged;
`passive-classes-10.9.8.json` identifies the balance's source catalog, not the only
supported runtime. Both complete-catalog fingerprints are still checked.

The relevant binary break is `QuestTracking.getPlayerTrackingQuests()`:
its return descriptor changes from `HashMap` to `ConcurrentHashMap`. The isolated
`NativeQuestTracking` adapter resolves the public getter once and exposes its
existing native map as `Map`. Native initialization validates it before mutation.
All quest menu and compass reads use that adapter. The borrowed `compassBar`,
chest timer/blacklist fields and wormhole destination/arrival-radius fields retain
their audited types. No native storage, weapon ability or progression is replaced.

## Focused verification

Run `:arc-dungeons:test` with the five filters `PassiveClassContentTest`,
`PassiveClassCompatibilityTest`, `NativeQuestTrackingTest`, `DungeonQuestsTest`
and `QuestCompassTest` in package `ru.arc.hooks.elitemobs`. The default test runtime
is 10.9.10; repeat with `-PeliteMobsTestVersion=10.9.8` for retained compatibility.
The fixture reads the version from the actual runtime JAR's plugin descriptor.
Keep unsupported MockBukkit platform observations separate from real Paper/client
acceptance; never alter production behavior to satisfy a test double.
