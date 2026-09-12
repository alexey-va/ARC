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
operator entries. The schema is intentionally narrow:

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
```

Each entry has exactly one source: `treasure` (`pool` + `id`), `preset`,
`pouch`, `seal`, `itemsadder`, `mount`, `package`, or inert `planned`.
Categories are capped at 64, entries at 512 per category and 2,000 in total.
Names, descriptions and rarity retain authored MiniMessage colors. Stories and
native equipment metadata are preserved on actual prizes, including seal choices.
An optional `preview-itemsadder: namespace:item` selects a catalogue-only model;
missing models use the authored icon. It never changes the reward source or the
inert icon embedded in a physical voucher or its archived materialization.

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

## Verification

```bash
./gradlew test --tests 'ru.arc.itemcatalog.RewardCatalogModuleConfigTest' --tests 'ru.arc.itemcatalog.PhysicalRewardControllerTest' --tests 'ru.arc.itemcatalog.CollectionSealControllerTest'
./gradlew shadowJar -x test
```

Official integration contracts:

- <https://github.com/LoneDev6/API-ItemsAdder>
- <https://itemsadder.devs.beer/plugin-usage/plugin-configuration/recipes-menu>
