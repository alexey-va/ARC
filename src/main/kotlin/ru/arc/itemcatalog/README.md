# ItemsAdder item catalog

`ItemsCatalogModule` owns the read-only `/arc items` catalog on Paper nodes
that enable `plugins/ARC/modules/items-catalog.yml` and run ItemsAdder.

## Contract

- `ItemsCatalogService` rebuilds an immutable snapshot off the Paper main
  thread at startup, ARC reload, and `ItemsAdderLoadDataEvent`.
- `ItemsAdderCategoryScanner` discovers enabled `categories` sections below
  the fixed ItemsAdder `contents/` root. It bounds file count, file size,
  category count, and item-pattern count, and never follows directory symlinks.
- `ItemsCatalogPlanner` merges repeated ItemsAdder category IDs, expands the
  exact/glob/regex item syntax supported by ItemsAdder, assigns the first
  ordered matching ARC group, and leaves every unmatched category visible at
  the catalog root.
- `ItemsCatalogGuiController` renders only one 45-entry page at a time. Every
  click and drag path is cancelled; preview items are clones and cannot be
  moved or granted.
- Category names from third-party YAML are normalized and inserted as literal
  Adventure text. MiniMessage is used only for ARC-owned configuration.

The curated hierarchy, hidden-category list, titles, messages, and portable
vanilla icon fallbacks live in `modules/items-catalog.yml`. RusCrafting enables
the same tracked profile on spawn and survival. A new ItemsAdder category that
does not match a curated group appears automatically as a root category.

## Verification

```bash
./gradlew test --tests 'ru.arc.itemcatalog.*'
./gradlew test shadowJar
```

Official integration contracts:

- <https://github.com/LoneDev6/API-ItemsAdder>
- <https://itemsadder.devs.beer/plugin-usage/plugin-configuration/recipes-menu>
