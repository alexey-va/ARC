# ArcDungeons

ArcDungeons is the separate plugin for dungeon gameplay, native EliteMobs
integration, staff spells and lost loot. EliteMobs remains an optional external
plugin. The Gradle subproject produces `ArcDungeons-0.1.1.jar`; ARC is the host.

## Ownership

ArcDungeons owns dungeon parties, quests, checkpoints, saves, bestiary,
scoreboard content, portals, class progression, shop and reward integration;
EliteMobs enchantment books, decorative and lost loot; and staff spell execution.
It owns `/dungeon`, its existing aliases, and `/loot`. Existing `/arc` adapters,
reward catalog and generic AdvancedEnchantments integration remain in ARC.
The contract ledger is owned by ArcContracts through a typed host adapter; see
[the independent domain plugins](arc-domain-plugins.md).

ARC exports `arc-dungeons-api` and `arc-staff-spells-api`. ArcDungeons compiles
against those contracts without packaging another copy. It obtains SQL, Redis
and metrics through arc-core 2.7.69 services registered by ARC. It does not own
another connection pool, Redis client group, Prometheus registry or exporter.
Distinct connection identities still require distinct host-owned pools.

The existing particle queue, staff cooldown timer and periodic player-save
round-robin remain in ARC. The child registers an owner-scoped save callback;
unregistering prevents delayed callbacks from calling a disabled plugin. It
retains the Redis lease until accepted EmSync writes settle on child shutdown.
This does not introduce a new whole-process crash durability guarantee.

## Compatibility and configuration

The host supplies the existing `plugins/ARC` data directory and server name.
File paths, `arc:*` item keys, Redis keys and payloads, SQL tables, lost-loot
receipts and reward fingerprints remain unchanged. No data migration is needed.
Each plugin parses only its own menu layouts while retaining operator settings
in the shared `guis/menus.yml`. `/arc reload` reloads host modules and then the
registered ArcDungeons service.

ArcDungeons requires ARC's `full` runtime profile. An absent host configuration
service disables the child before opening its resources. With EliteMobs absent,
the child retains staff functionality and exposes native dungeon features as
unavailable. ArcDuels 0.11.9 soft-depends on ArcDungeons so its staff service is
registered before duel integration starts. Load order is ARC, ArcDungeons,
ArcDuels; ARC has no dependency on ArcDungeons.

## Enchantment book descriptions

Verified 2026-10-16: `eliteloot/EliteEnchantmentBookPresentation.kt` owns the
EliteMobs book tooltip. Effect descriptions come from
`book.effect-descriptions` in `modules/enchanting.yml`, loaded by
`EliteEnchantingConfig`. Runtime overrides remain in
`plugins/ARC/modules/enchanting.yml` on Spawn and Survival. Keep MagmaCore's
generated enchantment-name block contiguous: its native refresh identifies
that block using the recorded line count and position.

AdvancedEnchantments books use AE 9.24.15's native `BookMaker` through ARC's
`enchanting/AdvancedBookPresentation.kt`. Their effect text belongs to
`plugins/AdvancedEnchantments/enchantments.yml`; the native book layout belongs
to `enchantment-book.lore` in AE's `config.yml`. Do not add a second AE effect
catalog to ArcDungeons.

`dungeons/ElitePickupListener.kt` refreshes existing personal-inventory books
after joining, opening an inventory or changing a slot, with coalesced delayed
work. Books stored in chests refresh after entering the player's inventory;
no offline inventory or world-wide container scan is needed.

Focused presentation coverage is in `EliteEnchantmentBookPresentationTest`
and `AdvancedBookPresentationTest`. Component/render checks establish tooltip
composition; they do not establish native-client appearance or live activation.

## Delivery and verification

The first extraction requires ARC 1.4.391 and ArcDungeons 0.1.0 to be delivered
and activated together, with ArcDuels 0.11.9 where installed. Installing only the
new ARC would leave the extracted features unavailable; installing ArcDungeons
alongside an older ARC could duplicate handlers. Rollback must remove the new
child JAR and restore the matching old host and duel JARs together.

GitHub Actions runs host and child unit tests, persistence integration tests,
the pinned arc-core consumer contract, a two-JAR ownership guard, and real Paper
checks. The artifact guard rejects duplicate shared API/provider classes and
extracted implementations left in the host. CI output does not establish live
activation or ordinary-player acceptance; deployment records track those
separately.

## Economy assessment

This is an ownership change with no new price, payout, probability, quantity,
cooldown or earning multiplier. Per identical accepted operation, the intended
before/after delta is separately **0 vault**, **0 tokens**, **0 EliteMobs
crystals**, **0 XP** and **0 items**. Existing class and permission grants remain
the same. Native lucky-ticket issuance retains item type/PDC validation and
clones the item before applying its existing item model.

Lost-loot SQL reservations, finalization, credit receipts and per-player gates
remain the existing protocol. Contract authorizations continue through the host adapter to ArcContracts. Changed provider availability fails before issuance. These invariants
are covered by the moved tests plus boundary tests; operational acceptance must
still check the activated plugin combination. EconomyShopGUI SELL and the
planned SELL-to-contract transition are unchanged and are not counted as a new
income stream here.

## Publishing the consumer API

`arc-dungeons-api` is an immutable library coordinate, separate from the runtime
plugin. Stage the Java API, sources, POM and Gradle metadata with JDK25:

```sh
./gradlew :arc-dungeons-api:publishMavenJavaPublicationToApiStagingRepository
./scripts/publish-staged-dungeons-api.sh --dry-run build/api-release-repository \
  ru/ruscrafting/arc/arc-dungeons-api/0.1.1/arc-dungeons-api-0.1.1.{jar,pom,module} \
  ru/ruscrafting/arc/arc-dungeons-api/0.1.1/arc-dungeons-api-0.1.1-sources.jar
```

After checking the exact committed API, repeat the helper without `--dry-run`.
It checks every immutable file before uploading and verifies anonymous download
hashes. It reads the existing publisher credential from the environment or
macOS Keychain without printing it. It does not publish runtime plugins.
