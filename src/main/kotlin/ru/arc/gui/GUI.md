# Конфигурируемые GUI в ARC

Все игровые экраны ARC подключаются к общему каталогу
`src/main/resources/guis/menus.yml`. Он работает поверх
`arc-core-menu` и `arc-core-paper-menu` (Inventory Framework остаётся внутренней
деталью рендера).

## Кто чем управляет

| Часть | Владелец |
|---|---|
| Число рядов, фон, слоты кнопок, порядок динамических ячеек, пагинация | `menus.layouts` в YAML |
| Material, ItemsAdder fallback, model data, glint, flags, name и шаблон lore | `menus.templates` в YAML |
| Допустимые ID кнопок и областей | `ArcMenuSchema.contracts` |
| Допустимые `<tags>`, условия и повторяемые строки lore | `ArcMenuSchema.textContracts` |
| Значения тегов, права, цены, бизнес-действия и сохранение | Kotlin-код фичи |
| Отмена опасных кликов, защита от двойного dispatch, reload и lifecycle | `ArcMenus`/arc-core |

Конфиг не исполняет команды. Семантическая кнопка `buy` может переехать в
другой слот или полностью сменить вид, но право на покупку и само списание
остаются типизированным обработчиком в коде.

## Layout

Слоты нумеруются с нуля. Можно перечислять слоты, задавать диапазоны и менять
порядок — порядок региона является порядком вывода элементов.

```yaml
menus:
  layouts:
    example-shop:
      schema-version: 1
      rows: 6
      background: { template: background }
      elements:
        back: { slot: 45, template: back }
        previous: { slot: 48, template: previous }
        page: { slot: 49, template: page, kind: decoration }
        next: { slot: 50, template: next }
      regions:
        offers: { slots: [10, 11, 12, 14, 15, 16, '19-25'] }
      pagination:
        region: offers
        previous: previous
        next: next
        indicator: page
```

Каждый экран обязан иметь контракт в `ArcMenuSchema`. При пропавшей кнопке,
конфликте слотов, неизвестном шаблоне или лишнем теге новый каталог не
публикуется. Reload сначала целиком валидирует новую генерацию, затем закрывает
старые сессии; смешать старый layout с новыми предметами невозможно.

## Предметы и богатый lore

```yaml
menus:
  templates:
    offer:
      material: GOLD_INGOT
      name: '<gold><name>'
      lore:
        - '<gray>Цена: <white><price>'
        - '<gray>Баланс: <white><balance>'
        - { text: '<green>Нажмите — купить', when: affordable }
        - { text: '<red>Не хватает <missing>', unless: affordable }
        - { repeat: effects, text: '<dark_gray>• <effect> <level>' }
```

В `PaperMenuTextContract` для этого шаблона объявляются:

- values: `name`, `price`, `balance`, `missing`;
- flags: `affordable`;
- repeats: `effects` со значениями `effect`, `level`.

Любой другой тег — ошибка загрузки. Значения подставляются как Adventure
components, поэтому имя игрока или внешний текст не может внедрить MiniMessage
разметку. Условия `when`/`unless` позволяют держать альтернативные состояния в
одном lore, а `repeat` — выводить произвольное количество характеристик.

## Реализация экрана

```kotlin
ArcMenus.open(
    player = player,
    menu = SHOP,
    title = messages.component("shop.title"),
    elements = mapOf(
        "back" to ArcMenus.entry(ArcMenus.item(SHOP, "back")) { openParent(it) },
    ),
    regions = mapOf(
        OFFERS to domainOffers.map { offer ->
            ArcMenus.entry(renderOffer(offer)) { buy(it, offer.id) }
        },
    ),
)
```

Для нативного переноса из контейнера используется `ArcMenus.transferEntry`:
сначала атомарно резервируется доменный предмет, затем runtime разрешает pickup.
Лут данжей выдаётся прямо в инвентарь через отменённый клик: обработчик сначала
проверяет место для полной стопки и резервирует доменный предмет, затем применяет
план переноса и очищает GUI-слот. Служебная метка InventoryFramework не участвует
в сравнении и не попадает к игроку. Размещение, hotbar swap, drop, creative clone,
опасный double-click и drag остаются заблокированы.

Лут занимает случайные слоты минимум в трёх рядах. Перестановка зависит от UUID
игрока и сундука, поэтому оставшиеся предметы не перескакивают при повторном
открытии. Две свободные ячейки после исходного списка занимают декоративные
паутинки; они не записываются в добычу и не выдаются игроку.

Биржа, маунты и хранилище имеют специализированные live/transactional
контроллеры. Они всё равно получают размеры, управляющие слоты и регионы из
того же валидированного `ArcMenus`-каталога; материалы, name и lore у них
остаются в их подробных feature-конфигах.

## Добавление нового GUI

1. Добавить `MenuId`, `MenuRegionId` и строгий `MenuContract` в
   `ArcMenuSchema`.
2. Добавить layout и item templates в `guis/menus.yml`.
3. Для каждого шаблона с тегами объявить `PaperMenuTextContract`.
4. В коде передавать только безопасные значения тегов и типизированные actions.
5. Добавить контрактный тест: bundled-конфиг загружается, нужные слоты и
   регионы совпадают с семантикой, неизвестный тег отвергается.
6. Добавить тесты бизнес-переходов и особых click/transfer сценариев.

Полная спецификация платформы и примеры находятся в
`arc-core/docs/paper-menus.md`.
