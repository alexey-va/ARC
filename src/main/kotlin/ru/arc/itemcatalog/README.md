# ItemsAdder item catalog

`ItemsCatalogModule` owns the interactive `/arc items` catalog on Paper nodes
that enable `plugins/ARC/modules/items-catalog.yml` and run ItemsAdder.

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
./gradlew test --tests 'ru.arc.itemcatalog.*'
./gradlew test shadowJar
```

Official integration contracts:

- <https://github.com/LoneDev6/API-ItemsAdder>
- <https://itemsadder.devs.beer/plugin-usage/plugin-configuration/recipes-menu>
