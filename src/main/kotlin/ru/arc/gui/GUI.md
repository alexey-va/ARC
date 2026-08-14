# GUI в ARC

Краткая инструкция по созданию игровых меню в стиле проекта (board, scheduled commands, stock).

## Два паттерна

### 1. Список с пагинацией — `GuiDsl`

Для каталогов, списков записей, магазинов.

```kotlin
fun buildListGui(player: Player): ChestGui {
    val cfg = ConfigManager.of(dataPath, "guis/my-feature.yml")
    return gui(cfg.string("list.title"), 6, player, cfg) {
        // Ванильные fallback-материалы; production overlay может добавить model data.
        contentBackground(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
        navBackground()

        pagination(0 until 5) {
            items(entries) { entry ->
                stack(buildEntryItem(cfg, entry))
                onClick {
                    it.isCancelled = true
                    openEditor(player, entry.id)
                }
            }
        }

        navBar {
            // Кнопка «Назад» — НЕ Material.ARROW, а как в board:
            back(action = { openParent(player) })
            // или с configKey из yml:
            // back(configKey = "list-menu.back") { openParent(player) }
        }
    }
}
```

**Показывать через:** `GuiUtils.constructAndShowAsync({ buildListGui(player) }, player)`

### 2. Форма редактирования — `ChestGui` + `Inputable` (как `AddBoardGui`)

Для экранов с полями, которые игрок меняет кликом или вводом в чат.

Структура **2 ряда (18 слотов)**:

| Ряд | Слоты         | Содержимое                                                                    |
|-----|---------------|-------------------------------------------------------------------------------|
| 0   | 1, 3, 4, 5, 7 | Редактируемые поля (команда, расписание, тип, серверы, вкл/выкл)              |
| 1   | 0             | **Назад** — ванильный `BLUE_STAINED_GLASS_PANE`, затем `fromConfig`          |
| 1   | 4             | Доп. действие (запуск сейчас, удаление)                                       |
| 1   | 8             | **Сохранить** — ванильный `GREEN_STAINED_GLASS_PANE`, затем `fromConfig`      |

```kotlin
class EditMyGui(player: Player) : ChestGui(2, title), Inputable {
    init {
        setupBackground() // OutlinePane + GuiUtils.background() на весь GUI
        val pane = StaticPane(9, 2)
        pane.addItem(fieldItem(), 1, 0)
        pane.addItem(backItem(), 0, 1)
        pane.addItem(saveItem(), 8, 1)
        addPane(Slot.fromXY(0, 0), pane)
    }

    // Текстовый ввод — TitleInput + Inputable
    private fun fieldItem() = guiItem(Material.PAPER) {
        onClick { click ->
            click.isCancelled = true
            TitleInput(player, this@EditMyGui, 0)
            click.whoClicked.closeInventory()
        }
        display("<green>Название")
        lore(listOf("<gray>Нажмите, чтобы изменить"))
        fromConfig(cfg, "edit-menu.field")
    }

    override fun setParameter(n: Int, s: String) { /* сохранить в draft */ }
    override fun proceed() { /* обновить GuiItem и show(player) */ }
}
```

**Циклические поля** (тип, сервер, цвет) — `onClick` меняет enum и пересобирает `GuiItem`.

## Универсальный default и серверный overlay

- Bundled `src/main/resources` обязан работать с ванильным клиентом: обычные
  `Material`, тексты и lore, без ItemsAdder ID и без ненулевого
  `customModelData`.
- Код задаёт безопасный ванильный fallback и читает item-spec из конфига. Не
  хардкодить RusCrafting model data в Kotlin.
- Серверные Material + `customModelData` хранятся только в tracked runtime
  mirror (`classic*/plugins/ARC/...`). Это overlay конкретного resource pack,
  а не часть переносимого ARC.
- Один item-spec должен переопределять пару Material + model data вместе.
  Источник истины — активный ItemsAdder `contents/arc/configs/items.yml`.
  Lands можно смотреть только как пример потребителя уже объявленных общих
  моделей: модели и ID не принадлежат Lands. Не угадывать ID по похожей иконке.
- Spawn и survival получают одинаковый overlay, если используют один pack.
- Образец: `guis/board.yml`, `guis/scheduled-commands.yml`.
- Подключение: `fromConfig(cfg, "edit-menu.save")` в `guiItem { }`.
- Bundled resource: добавить путь в `ARC.BUNDLED_RESOURCES`.

Bundled default:

```yaml
list-menu:
  back:
    material: BLUE_STAINED_GLASS_PANE
    display: "<gray>« Назад"
```

RusCrafting runtime overlay:

```yaml
list-menu:
  back:
    material: BLUE_STAINED_GLASS_PANE
    customModelData: 11013
```

## Кнопка «Назад» (обязательно как board)

```kotlin
guiItem(Material.BLUE_STAINED_GLASS_PANE) {
    display("<gray>« Назад")
    fromConfig(cfg, "list-menu.back")
    onClick { /* вернуться на предыдущий GUI */ }
}
```

Или через `GuiDefaults.BackButton` / `navBar { back(action = { ... }) }`.

**Не использовать** `Material.ARROW` для навигации — это не стиль ARC.

## Фон

| Зона                   | Универсальный fallback                  | RusCrafting overlay                         |
|------------------------|------------------------------------------|---------------------------------------------|
| Контент (ряды 0…n-2)   | `LIGHT_GRAY_STAINED_GLASS_PANE`          | только если pack объявляет отдельную модель |
| Навбар / общий фон     | `GRAY_STAINED_GLASS_PANE`                | `arc:background`, CMD `11000`                |
| Назад / страницы       | обычные панели или стрелки               | CMD `11013`, `11009`, `11008`                |

Полный проверенный каталог кнопок находится в
`classic/plugins/ItemsAdder/AGENTS.md`. Lands
`Locale/definitions_gui.yml` — живой пример применения фона и навигации, но не
источник новых ID.

Пустые ряды только с фоном без кнопок — **ошибка UX**: редактируемые элементы должны быть в **верхнем ряду**,
навигация — в **нижнем**.

## Плейсхолдеры в lore

```kotlin
tagResolver(
    TagResolver.resolver("command", Tag.inserting(Component.text(draft.command)))
)
lore(listOf("<white><command>", "<gray>Нажмите, чтобы изменить"))
```

## Сохранение

1. Draft (mutable) в GUI-сессии.
2. По «Сохранить» — запись в YAML через `Config.set*` + `save()`.
3. `config.reload()` и сброс runtime-состояния сервиса при необходимости.

## Чеклист нового GUI

- [ ] Bundled `guis/<feature>.yml` с ванильными материалами и без pack-specific model data
- [ ] Отдельный tracked production overlay с проверенными Material + CMD на каждом нужном узле
- [ ] Список: `GuiDsl` + pagination + `LIGHT_GRAY` content
- [ ] Редактор: 2 ряда, поля сверху, back/save снизу
- [ ] Назад: ванильный blue pane; RusCrafting overlay — CMD 11013, не хардкод в Kotlin
- [ ] Текстовые поля: `TitleInput` + `Inputable`
- [ ] Валидация в `satisfy()`; отмена ввода — `exit` через `isCancelInput` / `onInputCancel`
- [ ] Показ: `GuiUtils.constructAndShowAsync`
- [ ] Resource в `BUNDLED_RESOURCES`
- [ ] Тест доказывает resource-pack-neutral bundled default и чтение runtime overlay
- [ ] Тесты на бизнес-логику (draft, save, schedule) — GUI через MockBukkit по необходимости

## Ссылки в коде

- `ru.arc.gui.GuiDsl` — DSL списков
- `ru.arc.gui.GuiDefaults` — дефолты кнопок из `guis/defaults.yml`
- `ru.arc.board.guis.AddBoardGui` — эталон формы
- `ru.arc.board.guis.BoardGuiFactory` — эталон списка
- `ru.arc.scheduled.guis.EditScheduledCommandGui` — форма с расписанием

**Важно:** пакет GUI не должен называться `*.gui` — конфликтует с `import ru.arc.gui.gui`.
