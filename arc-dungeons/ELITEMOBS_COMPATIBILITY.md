# EliteMobs compatibility

ArcDungeons compiles against EliteMobs **10.9.8** and checks the actual native
contract before it installs the passive-class adapter. The rollout from
**10.9.11** to **10.9.17** keeps the 76 native forms, the RusCrafting balance and
the passive-only combat rules.

## Audited artifacts

Official `com.magmaguy:EliteMobs` runtime JARs from
`https://repo.magmaguy.com/releases/` used for compatibility checks.

| Version | SHA-256 |
| --- | --- |
| 10.9.8 | `7c01d9fc2375d7f23d3a875f6e63f905672bbb73ab735c2672f747f4d79b2b24` |
| 10.9.10 | `99f4d6595739fc8d59a9e4c2cd558c0fa61b4294df202973a6cf4e7265577bb2` |
| 10.9.11 | `807c43c515662e798b8eb47b16cb965be3cca182ba3f746f2d4ef8f49b5afec1` |
| 10.9.17 | `5722aaab379b7818fdda2c1f7fca5a9a074016f4af2109570449c8f564877309` |

EliteMobs 10.9.15 removed `FixedPassiveRegistry.mappedFormIds()` and the default
three-argument `ClassMenuRenderer.showForm`. `EliteMobsPassiveAccess` reads the
same registry keys the getter used and checks the three required renderer
methods. It also validates the legacy overload when present. The surviving
four-argument form uses a boolean to distinguish browsing from trainer entry.
The native passive profiles, traits and mechanics have the same complete
fingerprint on 10.9.11 and 10.9.17. The existing catalog and balance checks still
run before replacing the registry.

The relevant binary break is `QuestTracking.getPlayerTrackingQuests()`:
its return descriptor changes from `HashMap` to `ConcurrentHashMap`. The isolated
`NativeQuestTracking` adapter resolves the public getter once and exposes its
existing native map as `Map`. Native initialization validates it before mutation.
All quest menu and compass reads use that adapter. The borrowed `compassBar`,
chest timer/blacklist fields and wormhole destination/arrival-radius fields retain
their audited types. No native storage, weapon ability or progression is replaced.

## Empty event announcements

EliteMobs 10.9.8 and 10.9.10 convert an absent event message from `null` to `""`
through `TranslationsConfig.add` and `ChatColorConverter.convert`. Native
`CustomEvent.start/end` check only for null; announcement priority zero still
sends the resulting empty string to every player in the event world.

`EliteMobsEventAnnouncementGuard` handles the public `CustomEventStartEvent`
before either timed or action events start. It restores null only for visually
empty start/end messages, retaining visible text and all gameplay state. The
normal native-listener lifecycle owns registration and cleanup. The focused
`EliteMobsEventAnnouncementGuardTest` exercises the native announcement sender
against the selected runtime version with the same compiled addon.

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
and `QuestCompassTest` in package `ru.arc.hooks.elitemobs`. Run the rollout matrix
with `-PeliteMobsTestVersion=10.9.17` and `-PeliteMobsTestVersion=10.9.11`.
The fixture reads the version from the actual runtime JAR's plugin descriptor.
Keep unsupported MockBukkit platform observations separate from real Paper/client
acceptance; never alter production behavior to satisfy a test double.
