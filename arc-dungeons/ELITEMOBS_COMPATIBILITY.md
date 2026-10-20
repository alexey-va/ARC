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

## Empty event announcements

Both supported releases convert an absent event message from `null` to `""`
through `TranslationsConfig.add` and `ChatColorConverter.convert`. Native
`CustomEvent.start/end` check only for null; announcement priority zero still
sends the resulting empty string to every player in the event world.

`EliteMobsEventAnnouncementGuard` handles the public `CustomEventStartEvent`
before either timed or action events start. It restores null only for visually
empty start/end messages, retaining visible text and all gameplay state. The
normal native-listener lifecycle owns registration and cleanup. The focused
`EliteMobsEventAnnouncementGuardTest` exercises the native announcement sender
against both runtime versions with the same compiled addon.

## Grindstone enchantment removal

`EliteEnchantingModule` registers `EliteGrindstoneController`. The native
`ItemSettings.yml` setting `preventEliteItemDisenchantment` must be false on the
target runtime; changing it requires native listener re-registration at restart.
The addon prepares one elite item's result through `PrepareGrindstoneEvent`,
removes noncurse native enchantments and their exact PDC mirrors, replaces
noncurse custom records through `EliteEnchantmentItems.ITEMS.previewCustom`,
and refreshes native value before presentation. Curses and item identity remain.
Two-input elite repairs, enchanted books, and native scrolls have no result.

Result clicks validate the saved input snapshots and exact prepared output;
they must not regenerate presentation because legacy staff migration can choose
a random spell. Native inventory handling owns consumption, movement and XP.
The focused `ru.arc.enchanting.EliteGrindstoneControllerTest` covers this path.
Its fixture mocks only `ItemTagger.isEliteItem`, whose legacy tag-container
operation is unsupported by MockBukkit. Native enchantment reads, custom record
replacement and vanilla/PDC cleanup execute against the actual runtime JAR.
Native identity detection, item extraction and XP still need player acceptance.

## Focused verification

Run `:arc-dungeons:test` with the five filters `PassiveClassContentTest`,
`PassiveClassCompatibilityTest`, `NativeQuestTrackingTest`, `DungeonQuestsTest`
and `QuestCompassTest` in package `ru.arc.hooks.elitemobs`. The default test runtime
is 10.9.10; repeat with `-PeliteMobsTestVersion=10.9.8` for retained compatibility.
The fixture reads the version from the actual runtime JAR's plugin descriptor.
Keep unsupported MockBukkit platform observations separate from real Paper/client
acceptance; never alter production behavior to satisfy a test double.
