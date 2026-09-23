# ItemsAdder item catalog

`ItemsCatalogModule` owns the interactive `/arc items` catalog on Paper nodes
that enable `plugins/ARC/modules/items-catalog.yml` and run ItemsAdder.
When `modules/reward-catalog.yml` is enabled, the same command exposes a
`Награды лутбоксов` root tab and `/arc items rewards` direct shortcut. The
reward route stays available while the ItemsAdder index is loading.

## Contract

- `ItemsCatalogService` rebuilds an immutable snapshot off the Paper main
  thread at startup, ARC reload, and `ItemsAdderLoadDataEvent`.
- `ItemsAdderCategoryScanner` discovers enabled `categories` sections below
  the fixed ItemsAdder `contents/` root. It bounds file count, file size,
  category count, item-pattern count, and enabled recipe-result count, and never
  follows directory symlinks.
- `ItemsCatalogPlanner` merges repeated ItemsAdder category IDs, expands the
  exact/glob/regex item syntax supported by ItemsAdder, assigns the first
  ordered matching ARC group, and leaves every unmatched category visible at
  the catalog root.
- `ItemsCatalogGuiController` renders only one 45-entry page at a time. Every
  inventory click and drag path is cancelled. An item click grants one fresh
  clone only to players with `arc.items.catalog.give` (default: op). A full
  inventory fails without dropping the item.
- For ordinary players an indexed enabled recipe opens through ItemsAdder's
  supported player command `/iarecipe <item>`. If no recipe exists, ARC uses
  the category `item-action` override or its group's `item-action`. Only a
  bounded static `player-command` grammar is accepted; ARC never dispatches a
  catalog action as console.
- Category names from third-party YAML are normalized and inserted as literal
  Adventure text. MiniMessage is used only for ARC-owned configuration.

## Lootbox reward catalogue

`RewardCatalogModuleConfig` reads the separate portable
`modules/reward-catalog.yml`. Its bundled default is disabled and has no
operator entries. Missing presentation settings use bounded code defaults without
rewriting operator-owned categories or packages. Unknown presentation
extensions are ignored, while reward-source, probability, hierarchy and delivery
invariants remain strict. If the reward catalogue is invalid, only its tab is
disabled: the main ItemsAdder catalogue still starts.

```yaml
enabled: false
title: '<gold><bold>Сокровищница наград'
root-icon: { material: CHEST, custom-model-data: 0 }
categories:
  common:
    name: 'Обычные награды'
    description: ['Предметы из лутбоксов.']
    icon: { material: CHEST, custom-model-data: 0 }
    entries:
      voucher:
        name: '<white>Ваучер'
        description: ['Описание награды.']
        rarity: 'Обычная'
        requires: [ItemsAdder]
        treasure: { pool: common, id: voucher }
        icon: { material: PAPER, custom-model-data: 0 }
        preview-itemsadder: iageneric:coin # optional display model; icon/source is the fallback
```

Each entry has exactly one source: `treasure` (`pool` + `id`), `preset`,
`pouch`, `seal`, `itemsadder`, `mount`, `package`, `dungeon-case`,
`travel-anchors`, `particle-preset`, or inert `planned`.
Categories are capped at 64, entries at 512 per category and 2,000 in total.
Names, descriptions and rarity retain authored MiniMessage colors. Stories and
native equipment metadata are preserved on actual prizes, including seal choices.
An optional `preview-itemsadder: namespace:item` selects a catalogue-only model;
missing models use the authored icon. It never changes the reward source or the
inert icon embedded in a physical voucher or its archived materialization.

An optional `tooltip-style: namespace:tooltip/tier` sets the resource-pack frame
on the catalogue preview and the issued native item or voucher. It only changes
that component: source names/lore, enchantments, model and bearer identity stay
intact. Missing settings preserve the native frame. Archived rewards retain the
frame captured when prepared; visual changes never invalidate a bearer claim.
Focused frame checks use `RewardItemPresentationTest` and the native AE preparation
test. MockBukkit 4.116.3 drops its modern component map on `ItemStack.clone()`;
those tests prove the adapter assignment and preserved native metadata, not the
Paper serialized-frame round trip or client rendering.

`parent` forms folders up to four levels deep. A case has `rolls: 1` and positive
entry `weight`; displayed odds come from the same normalized weights. These
are future case compositions, not automatic daily/rank acquisition. With
`require-case-coverage: true`, every non-planned base reward (source plus
explicit enchantments) must occur in a case or the configuration is rejected.

Furniture uses root `packages: {id: {name: '...', items: ['namespace:item']}}`
and entry `package: id`. A package contains 1–216 distinct native IDs, one copy
of each. Redemption delivers the whole pack in numbered shulker boxes with 27
items each. Missing native items or insufficient space preserves the voucher.

Browsing requires `arc.items.catalog.use`; the final click rechecks
`clicks.give-permission` (`arc.items.catalog.give` by default). Every click
produces only physical inventory items. Equipment, potions, books, Slimefun,
presets and pouches retain native factories; currency, mount, package and
opaque native item sources become unique physical vouchers. Rendering never
mints a redeemable voucher. Right click in the main hand redeems it without an
operator permission. Currency names and nominal amounts remain explicit.

### Cosmetic certificates and direct AE consumables

`particle-preset` accepts only `arc_raincloud`, `arc_rainbow`, and `arc_angel`.
`ParticlePresetRewards` validates the active PlayerParticles 8.13 preset and
the exact `arc.cosmetics.particles.<id>` permission with native override enabled.
It grants one permanent, context-free LuckPerms node through `modifyUser` and
waits for the save before the existing shared voucher journal commits. It never
grants effect/style wildcards or changes rank groups. Already-owned certificates
are rejected without consumption and can be transferred. Unknown save outcomes
retain journal recovery ownership, not a retryable/free second grant.

These fixed entitlements use stable `particle-preset:<id>` keys and fingerprints,
not a backend-local recipe archive. A certificate can therefore be redeemed on
either configured backend even if that backend has never issued the preset.

PlayerParticles defaults must allow GUI entry without individual effect/style
permissions, and preset overlap must remain off. A claimed preset then works
even with `playerparticles.particles.max.0`: native preset loading bypasses the
manual-particle limit. Use `/pp` or `/pp group load <id>`; no flight is granted.
Original unrestricted-by-preset-permission rank presets remain unchanged.

`AeNativeItems` materializes magic dust, white/black/holy-white scrolls and
randomizers directly from AdvancedEnchantments' own factories. Case preparation
freezes their complete native stacks; delivery clones that result, never rolls
again and never creates an extra redemption voucher. A dust bundle rolls its
tier once and uses its configured per-item success percentage. Stack splitting
preserves native metadata and respects the actual item stack limit.

The declared AEAPI has no consumable factory. The narrow reflective adapter is
verified against AE **9.24.13**, root-JAR SHA-256
`202ee20ab303623d6ee41ec05a58c9a1c1e8aef18ca16d43b52b78c9a220d0ab`.
It fails closed on version/signature drift; review this seam before upgrading AE.
Do not replace it with `giveitem magic ... <percent>`: that command does not
implement the configured fixed dust percentage. Unchanged unsupported AE reward
kinds retain the existing native command path for backward compatibility.

Focused checks: `ParticlePresetRewardsTest`, `AeNativeItemsTest`,
`RewardCatalogGuiControllerTest`, `RewardCatalogModuleConfigTest`,
`FrozenPhysicalRewardsTest`, `CatalogPhysicalRewardsTest`, and
`RewardCatalogDeploymentCompatibilityTest`. Test doubles do not prove real
client particle rendering or an actual multi-server player claim.

`PhysicalRewardController` uses the shared `OneTimeUseLedger`, SQL partition
`arc.catalog-reward`, with private `reward-redemption.yml` SQL settings. Enable
it on each catalog backend with the same database; the portable default is
disabled and contains no credentials. It claims before any effect, commits proven success, and releases
only a proven refusal without a value mutation. Unknown outcomes retain the
item and durable recovery ownership; they never automatically retry. The
source key and immutable source fingerprint are bound to the voucher UUID.
Changing a source definition makes old vouchers unavailable for manual
reconciliation, rather than silently changing their reward.
On reload/shutdown, the module lets started claims settle before closing their
SQL pool. A bounded drain timeout preserves unresolved ownership for recovery.

The finite command adapter accepts only native ArcBuilder books, ArcEcoJobs
booster items, EliteMobs item issuers and the existing AE treasure type. It
checks actual inventory arrival. RedisEconomy token commands are interpreted
as typed currency deposits after claim; they are never dispatched as console
commands. Providers are rechecked at click and redemption. Capacity checks do
not drop overflow items. Collection seal and voucher listeners handle vanilla
pre-cancelled right-click-air events and ignore off-hand duplicates.

The curated hierarchy, per-category overrides, click actions, titles, messages, and portable
vanilla icon fallbacks live in `modules/items-catalog.yml`. A category can be
hidden with `categories.<id>.hidden: true`; the legacy `hidden-categories` list
is accepted only for compatibility. RusCrafting enables the same tracked
profile on spawn and survival. `categories.<id>.item-action` overrides a group
fallback, while `groups.<id>.item-action` configures the default for every item
in that group. A new ItemsAdder category that does not match a
curated group appears automatically as a root category.

The ARC deployment helper runs `RewardCatalogDeploymentCompatibilityTest` against
every selected tracked runtime `reward-catalog.yml` before building the candidate
JAR. This keeps the parser and deployed operator catalogue in one compatibility gate.

## Verification

```bash
./gradlew test --tests 'ru.arc.itemcatalog.RewardCatalogModuleConfigTest' --tests 'ru.arc.itemcatalog.PhysicalRewardControllerTest' --tests 'ru.arc.itemcatalog.CollectionSealControllerTest'
./gradlew shadowJar -x test
```

Official integration contracts:

- <https://github.com/LoneDev6/API-ItemsAdder>
- <https://itemsadder.devs.beer/plugin-usage/plugin-configuration/recipes-menu>
