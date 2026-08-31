# Команды ARC Plugin

Полный справочник команд `/arc` и `/x`. Все команды и сообщения настраиваются в `config/commands.yml`.

---

## 📋 Оглавление

- [Быстрый старт](#быстрый-старт)
- [Справка](#arc-help)
- [Покупка из магазина](#arc-buy--buy)
- [Доска контрактов](#arc-contracts--arc-заказы)
- [Ревизорская проба](#arc-investigation)
- [Охота на сокровища](#arc-hunt)
- [Пулы наград](#arc-treasures)
- [Пулы локаций](#arc-locationpool)
- [Биржа и инвестиции](#arc-invest)
- [Elite Loot](#arc-eliteloot)
- [Администрирование](#администрирование)
- [GUI команды](#gui-команды)
- [Другие команды](#другие-команды)
- [Кросс-серверные команды](#команда-x)
- [Конфигурация](#конфигурация)
- [Права доступа](#права-доступа)

---

## Быстрый старт

```bash
# Показать справку по всем командам
/arc help

# Справка по конкретной команде
/arc help hunt

# Посмотреть статус охоты
/arc hunt

# Запустить охоту по типу
/arc hunt daily

# Добавить предмет в пул наград
/arc treasures common add

# Редактировать пул локаций
/arc locationpool my_pool

# Открыть биржу
/arc invest

# Посмотреть ресурсные заказы Economy V2
/arc contracts
```

---

## /arc help

Показывает список всех доступных команд с описаниями.

| Параметр  | Значение            |
|-----------|---------------------|
| **Право** | Нет (доступно всем) |

### Использование

```bash
/arc help              # список всех команд
/arc help hunt         # подробная справка по hunt
/arc help treasures    # подробная справка по treasures
```

### Особенности

- Кликабельные команды для вставки в чат
- Показывает только команды, на которые у вас есть права
- Для каждой команды показывает: описание, синтаксис, права, алиасы

---

## /arc buy / /buy

Покупает через EconomyShopGUI ровно указанное количество товара. Операция атомарная: если не хватает денег,
места, остатка или нарушен лимит товара, частичной покупки и списания не будет.

| Параметр         | Значение                  |
|------------------|---------------------------|
| **Право**        | Нет (доступно всем)       |
| **Только игрок** | Да                        |
| **Максимум**     | 2304 за одну команду      |

```bash
/buy Blocks TNT 64
/buy Blocks 12 64
/arc buy Redstone pages.page1.items.1 128
```

Раздел и товар дополняются клавишей Tab. Для обычных товаров предлагается понятное имя материала, а для
повторяющихся или кастомных товаров — короткий уникальный ID. Полный внутренний путь также поддерживается.
В результате покупки игрок видит русское название материала из `plugins/ARC/lang.json`, а не внутренний путь магазина.
Команда использует нативные проверки EconomyShopGUI:
права на раздел и товар, требования, минимумы/максимумы, склад, баланс и свободное место в инвентаре.

---

## /arc contracts / /arc заказы

`status` показывает публичную текстовую сводку: точный предмет, оставшуюся
квоту, цену за единицу и срок окна. NPC открывают общий GUI командой
`open <группа>`; одна группа соответствует одному набору заказов, поэтому разные
NPC используют один код меню и независимый каталог/текст оформления.

В режиме `enforce` сдача принимает только точные обычные vanilla-предметы без
meta/NBT. Перед удалением предметов, выплатой и возвратом intent долговечно
фиксируется в journal, quantity/budget резервируются вместе, а RedisEconomy
balance сверяется в точных minor units. Неоднозначный результат не повторяется
автоматически и уходит в ручную сверку.

```bash
/arc contracts
/arc contracts status
/arc contracts open forge_orders
/arc contracts submit forge_raw_iron 64
```

## /arc investigation

Внутренняя команда шести NPC бюро расследований в Origin. Фома открывает
оплачиваемое расследование, а Ставр, Прохор, Гордей, Агата и Тихон добавляют
в него свои показания. ARC проверяет точный мир и расстояние до NPC;
деньги, таймер, перерыв и сгенерированное дело сохраняются в durable journal.

```bash
/arc investigation open
/arc investigation clue stavr
/arc investigation clue prokhor
/arc investigation clue gordey
/arc investigation clue agata
/arc investigation clue tikhon
/arc investigation verdict amount
/arc investigation verdict seal
/arc investigation verdict cargo
/arc investigation verdict duplicate
/arc investigation verdict clean
```

---

## /arc hunt

Управление охотой на сокровища. Охота создаёт сундуки в случайных локациях из пула, игроки находят и открывают их для
получения наград.

| Параметр         | Значение            |
|------------------|---------------------|
| **Право**        | `arc.treasure.hunt.admin` |
| **Только игрок** | Нет                 |

### Базовые команды

| Команда            | Описание                                        |
|--------------------|-------------------------------------------------|
| `/arc hunt`        | Показать статус: активные охоты, доступные типы |
| `/arc hunt status` | То же самое                                     |
| `/arc hunt types`  | Список доступных типов охот с их пулами         |
| `/arc hunt <type>` | Запустить охоту по типу (короткая форма)        |

### Запуск охоты

```bash
# По типу (рекомендуется)
/arc hunt start daily
/arc hunt start daily 50   # переопределить кол-во сундуков

# Короткая форма (без "start")
/arc hunt daily
/arc hunt daily 50

# Полная форма (для кастомных охот без предустановки)
/arc hunt start forest_pool 30 vanilla common_loot
# Параметры: пул_локаций кол-во_сундуков namespace пул_наград
```

### Остановка охоты

```bash
/arc hunt stop          # показать активные охоты для выбора
/arc hunt stop my_pool  # остановить конкретную охоту по пулу
/arc hunt stopall       # остановить ВСЕ активные охоты
```

### Типы охот

Типы настраиваются в `treasure-hunt.yml`:

```yaml
treasure-hunt-types:
  daily:
    location-pool-id: "forest_spawns"
    chest-types:
      common:
        type: VANILLA  # или IA для ItemsAdder
        treasure-pool-id: "common_loot"
        weight: 10
      rare:
        type: IA
        ia-namespace-id: "golden_chest"
        treasure-pool-id: "rare_loot"
        weight: 1
```

---

## /arc treasures

Управление пулами наград. Пулы содержат предметы с весами для случайной выдачи.

| Параметр         | Значение                     |
|------------------|------------------------------|
| **Право**        | `arc.treasure.pool.admin`        |
| **Только игрок** | Нет (GUI только для игроков) |

### Базовые команды

| Команда                      | Описание                                               |
|------------------------------|--------------------------------------------------------|
| `/arc treasures`             | Открыть GUI со всеми пулами (игрок) / список (консоль) |
| `/arc treasures list`        | Список пулов с количеством предметов                   |
| `/arc treasures reload`      | Перезагрузить все пулы из файлов                       |
| `/arc treasures <pool>`      | Открыть GUI пула / показать info                       |
| `/arc treasures <pool> info` | Информация о пуле                                      |

### Добавление предметов

```bash
# Из руки (самый простой способ)
/arc treasures common add
/arc treasures common add -weight:5        # установить вес
/arc treasures common add -weight:5 -quantity:3  # вес и количество

# Добавить как addhand (полная форма)
/arc treasures common addhand -weight:5

# Из сундука (смотрите на сундук)
/arc treasures common addchest              # все предметы из сундука
/arc treasures common addchest -weight:2    # с весом 2

# Вложенный пул (sub-pool)
/arc treasures epic addsubpool legendary
/arc treasures epic addsubpool legendary -weight:1
```

Если предмет из руки или сундука распознан Slimefun, ARC сохраняет в YAML
канонический `type: slimefun` и `item-id`, а не сырой ItemStack/NBT.

### Флаги

| Флаг          | Описание                           | Пример        |
|---------------|------------------------------------|---------------|
| `-weight:N`   | Вес предмета (по умолчанию 1)      | `-weight:5`   |
| `-quantity:N` | Количество (по умолчанию из стака) | `-quantity:3` |

### Выдача награды

```bash
/arc treasures common give        # выдать себе
/arc treasures common give Steve  # выдать игроку Steve
```

---

## /arc pouch

Выдаёт большой YAML-мешочек. Один мешочек может гарантированно прокрутить
несколько treasure-пулов несколько раз и дополнительно проверить шанс-бонусы.

| Параметр         | Значение      |
|------------------|---------------|
| **Право**        | `arc.pouch.give`   |
| **Только игрок** | Нет           |

```bash
/arc pouch list
/arc pouch Steve slimefun_starter
/arc pouch Steve royal 3
```

Каталог находится в `plugins/ARC/modules/pouches.yml`. Дизайн предмета использует
ItemSpec (`material`, `display`, `lore`, `customModelData`, `glowing`, enchants и
скалярный `customData`). `arc:pouch_id` ARC добавляет автоматически.

```yaml
pouches:
  engineer:
    item:
      material: BLAST_FURNACE
      display: '<aqua><bold>Мешочек инженера'
      lore: ['<yellow>ПКМ — открыть']
      glowing: true
    rewards:
      - { pool: sf_components, rolls: 3-5 }
      - { pool: sf_energy, rolls: 2 }
      - { pool: sf_advanced, rolls: 1, chance: 12% }
```

У каждого мешочка должен быть хотя бы один гарантированный источник (`chance`
не задан или равен `100%`). Общий максимум — 64 прокрутки за одно открытие.

---

## /arc locationpool

Управление пулами локаций. Пулы хранят точки спауна для охот, мобов и других систем.

| Параметр         | Значение            |
|------------------|---------------------|
| **Право**        | `arc.location.pool.admin` |
| **Только игрок** | Да                  |

### Базовые команды

| Команда                           | Описание                                         |
|-----------------------------------|--------------------------------------------------|
| `/arc locationpool`               | Показать статус: редактируемый пул, список пулов |
| `/arc locationpool list`          | Список всех пулов с размерами                    |
| `/arc locationpool <pool>`        | Начать/остановить редактирование пула            |
| `/arc locationpool delete <pool>` | Удалить пул                                      |

### Редактирование пула

```bash
# 1. Начать редактирование (получите блоки)
/arc locationpool my_pool

# 2. Редактирование:
#    - ЛКМ Золотым блоком → добавить локацию
#    - ЛКМ Красным блоком → удалить ближайшую локацию

# 3. Остановить редактирование
/arc locationpool my_pool  # повторная команда останавливает

# Переключиться на другой пул
/arc locationpool other_pool  # автоматически остановит текущий и начнёт новый
```

---

## /arc invest

Биржа и инвестиции. Покупка и продажа акций, просмотр портфеля.

| Параметр         | Значение     |
|------------------|--------------|
| **Право**        | `arc.invest` |
| **Только игрок** | Да           |

### Использование

```bash
/arc invest              # открыть GUI биржи
/arc invest gui          # то же самое
/arc invest history      # история торгов
/arc invest list         # список акций
/arc invest buy AAPL 10  # купить 10 акций AAPL
/arc invest sell AAPL 5  # продать 5 акций
/arc invest portfolio    # портфель
```

---

## /arc eliteloot

Управление Elite Loot (декоративные предметы).

| Параметр         | Значение        |
|------------------|-----------------|
| **Право**        | `arc.elite.loot.admin` |
| **Только игрок** | Да              |

### Использование

```bash
/arc eliteloot list     # список всех декоративных предметов
/arc eliteloot add      # добавить предмет из руки в пул
```

---

## Администрирование

### /arc commandhide

Быстро выдаёт или снимает у точного онлайн-игрока постоянный прямой
`arc.command.hide.bypass`. Изменение сохраняется через LuckPerms по UUID и
сразу обновляет список и tab-complete команд игрока.

| Параметр  | Значение                 |
|-----------|--------------------------|
| **Право** | `arc.command.hide.admin` |

```bash
/arc commandhide allow Steve     # открыть все команды
/arc commandhide restrict Steve  # вернуть настроенные ограничения
/arc commandhide status Steve    # показать источник полного доступа
```

`restrict` удаляет только прямую выдачу этой команды. Если bypass остаётся от
группы, OP или другого контекстного/временного права, ARC не удаляет его молча,
а сообщает администратору, что полный доступ всё ещё действует.
Собственная выдача ARC помечается meta-маркером в LuckPerms. Чужие прямые
выдачи и запреты команда не перезаписывает: `status` показывает внешний
источник, а `allow`/`restrict` возвращают безопасный конфликт.

### /arc reload

Перезагрузка всей конфигурации плагина.

| Параметр  | Значение    |
|-----------|-------------|
| **Право** | `arc.admin` |

```bash
/arc reload   # показывает время выполнения
```

### /arc repo

Управление Redis репозиториями.

| Параметр  | Значение    |
|-----------|-------------|
| **Право** | `arc.admin` |

```bash
/arc repo          # статус и размеры
/arc repo status   # подробный статус
/arc repo save     # принудительно сохранить все
/arc repo size     # размеры репозиториев в байтах
```

### /arc logger

Управление уровнем логирования в рантайме.

| Параметр  | Значение    |
|-----------|-------------|
| **Право** | `arc.admin` |

```bash
/arc logger         # показать текущий уровень
/arc logger DEBUG   # все сообщения (для отладки)
/arc logger INFO    # стандартный уровень
/arc logger WARN    # только предупреждения и ошибки
/arc logger ERROR   # только ошибки
```

### /arc audit

Просмотр экономического аудита игроков.

| Параметр  | Значение    |
|-----------|-------------|
| **Право** | `arc.audit` |

```bash
/arc audit Steve          # аудит игрока (страница 1)
/arc audit Steve 2        # страница 2
/arc audit Steve 1 income # только доходы
/arc audit Steve clear    # очистить аудит игрока
/arc audit clearall       # очистить ВЕСЬ аудит (осторожно!)
```

**Доступные фильтры:**

- `all` - все записи
- `income` - только доходы
- `expense` - только расходы
- `shop` - магазины
- `job` - работы
- `pay` - переводы

### /arc emshop

Управление магазином EliteMobs.

| Параметр  | Значение    |
|-----------|-------------|
| **Право** | `arc.admin` |

```bash
/arc emshop reset           # сбросить магазин
/arc emshop Steve           # открыть gear магазин для игрока
/arc emshop Steve gear      # то же самое
/arc emshop Steve trinket   # открыть trinket магазин
```

### /arc jobsboosts

Управление бустами работ (Jobs Reborn).

| Параметр          | Значение         |
|-------------------|------------------|
| **Право**         | `arc.jobs.boost.use` |
| **Reset требует** | `arc.admin`      |

```bash
/arc jobsboosts             # открыть GUI для себя
/arc jobsboosts Steve       # открыть для игрока
/arc jobsboosts reset Steve # сбросить бусты игрока (требует arc.admin)
```

### /arc giveboost

Выдать буст Jobs Reborn игроку.

| Параметр  | Значение                  |
|-----------|---------------------------|
| **Право** | `arc.jobs.boost.give` |

```bash
/arc giveboost <player> <job|all> <multiplier> <type> <duration>

# Примеры:
/arc giveboost Steve Miner 1.5 EXP 1h      # 1.5x exp на 1 час для Miner
/arc giveboost Steve all 2.0 MONEY 1d      # 2x деньги на 1 день для всех работ
```

**Типы бустов:** `EXP`, `MONEY`, `POINTS`

**Длительности:** `1h`, `6h`, `12h`, `1d`, `3d`, `7d`, `30d`

### /arc respawnonrtp

Пометить следующий RTP игрока: после успешной случайной телепортации ARC
установит точку возрождения в месте приземления. Запись автоматически истекает
через 1 минуту.

| Параметр  | Значение          |
|-----------|-------------------|
| **Право** | `arc.rtp.respawn` |

```bash
/arc respawnonrtp Steve
```

### /arc firstrtp

Административный вход в нативный first-RTP flow. Публичной `/rtp` владеет
ProxyARC: после перехода игрока на survival он передаёт типизированный
`ruscrafting:rtp` plugin message в ARC. ARC хранит в
`data/rtp-players.json` UUID игроков, уже прошедших RTP, и отдельные списки по
мирам. При первом посещении мира выбирает BetterRTP или LeafRTP по `modules/misc.yml`;
при повторном — возвращает игрока на spawn мира.

```bash
/arc firstrtp Steve survival
```

Для повторного теста можно удалить UUID из всего реестра или только из
пер-мирового списка. Reset дополнительно требует `arc.admin` и принимает
закэшированное имя игрока либо UUID:

```bash
/arc firstrtp reset Steve
/arc firstrtp reset Steve survival
```

Полный reset удаляет глобальную отметку и все отметки миров. Reset конкретного
мира сохраняет глобальную отметку, поэтому повторяется RTP этого мира, но не
логика самого первого RTP аккаунта.

### /arc onboarding

Сбрасывает сохранённый продуктовый onboarding одного закэшированного игрока,
не меняя first-RTP, дома, инвентарь, права или другие данные игрока.

```text
/arc onboarding reset Steve
/arc onboarding reset 00000000-0000-0000-0000-000000000000
```

### /arc rtp

Игровой backend-вход в публичный сетевой RTP. Команда не вызывает LeafRTP
напрямую: ARC отправляет типизированный запрос в ProxyARC, а тот применяет
переход на survival, cooldown и защиту от повторного запроса. Без мира целью
служит `survival`. Первый обычный запрос в каждом мире автоматически проходит
через tracked first-RTP flow; после успешного completion все следующие обычные
запросы снова всегда запускают RTP. Флаг `--only-if-first` сохраняет поведение
порталов: RTP запускается только при первом посещении мира, а повторный вход
возвращает в этот мир без RTP. Пока first completion ожидается, новый first
запрос отклоняется; disconnect снимает pending marker для безопасного retry.
После межсерверного перехода ARC повторно накладывает портальную слепоту уже на
целевом backend.

```bash
/arc rtp
/arc rtp survival
/arc rtp mining
/arc rtp vanilla
/arc rtp survival --only-if-first
```

---

## GUI команды

| Команда               | Право                  | Описание                       |
|-----------------------|------------------------|--------------------------------|
| `/arc board`          | `arc.board`            | Настройки скорборда            |
| `/arc baltop`         | `arc.balance.top`           | Таблица лидеров по балансу     |
| `/arc joinmessage`    | `arc.join.message.gui` | Настроить сообщение при входе  |
| `/arc quitmessage`    | `arc.join.message.gui` | Настроить сообщение при выходе |
| `/arc store [player]` | `arc.store`            | Открыть магазин игрока         |

`/arc store` не регистрируется как отдельная команда `/arcstore` и недоступна
на сервере parkour.

---

## Другие команды

### /arc test

Тестовые команды для разработки.

| Параметр  | Значение    |
|-----------|-------------|
| **Право** | `arc.admin` |

```bash
/arc test nbt           # показать NBT предмета в руке
/arc test leaf          # тест системы распада листвы
/arc test ploot         # тест персонального лута
/arc test blockdata     # показать данные блока
```

### /arc soundfollow

Воспроизвести звук для игрока.

| Параметр  | Значение           |
|-----------|--------------------|
| **Право** | `arc.sound.follow` |

```bash
/arc soundfollow Steve block.note_block.harp
/arc soundfollow Steve minecraft:entity.player.levelup
```

---

## Команда /x

Кросс-серверное выполнение команд через Redis.

| Параметр  | Значение |
|-----------|----------|
| **Право** | `arc.x`  |

### Использование

```bash
/x [-параметры] <команда>
```

### Параметры

| Параметр    | Описание               | Пример                                       |
|-------------|------------------------|----------------------------------------------|
| `-servers:` | Серверы для выполнения | `-servers:lobby,survival` или `-servers:all` |
| `-player:`  | Имя целевого игрока    | `-player:Steve`                              |
| `-uuid:`    | UUID целевого игрока   | `-uuid:123e4567-...`                         |
| `-sender:`  | Кто выполняет команду  | `-sender:player` или `-sender:console`       |
| `-timeout:` | Таймаут в тиках        | `-timeout:200`                               |
| `-delay:`   | Задержка в тиках       | `-delay:40`                                  |

### Примеры

```bash
# Выполнить на всех серверах
/x -servers:all say Hello World!

# Выполнить для конкретного игрока (ждёт его появления)
/x -player:Steve -servers:all give %player_name% diamond 1

# Выполнить с задержкой
/x -delay:100 -player:Steve gamemode creative

# Выполнить от имени игрока
/x -sender:player -player:Steve spawn
```

---

## Конфигурация

Файл: `plugins/ARC/config/commands.yml`

### Структура команды

```yaml
commands:
  hunt:
    name: "hunt"                              # название команды
    permission: "arc.treasure.hunt.admin"           # право (пусто = для всех)
    description: "Управление охотой"          # описание для /arc help
    usage: "/arc hunt [status|types|start|stop|stopall]"
    player-only: false                        # требуется ли игрок
    aliases: ["th", "охота"]                  # альтернативные названия
```

### Структура сообщений

```yaml
messages:
  common:
    no-permission: "<red>У вас нет прав!"
    player-only: "<red>Только для игроков!"
    player-not-found: "<red>Игрок %player% не найден!"
    
  hunt:
    started: "<green>Охота запущена!"
    stopped: "<gray>Охота остановлена"
    status-header: "<gold>═══ Охота на сокровища ═══"
```

### Примеры кастомизации

**Добавить алиасы:**

```yaml
commands:
  treasures:
    aliases: ["tr", "награды", "loot"]
```

**Кастомные цвета и градиенты:**

```yaml
messages:
  hunt:
    status-header: "<gradient:gold:yellow>⚔ Охота на сокровища ⚔</gradient>"
    started: "<green>✔ <white>Охота <gold>%type%</gold> запущена!"
```

**Отключить право (доступно всем):**

```yaml
commands:
  baltop:
    permission: ""
```

**Сделать команду только для игроков:**

```yaml
commands:
  mycommand:
    player-only: true
```

---

## Алиасы по умолчанию

| Команда       | Алиасы           |
|---------------|------------------|
| `reload`      | `rl`             |
| `logger`      | `log`            |
| `treasures`   | `tr`             |
| `jobsboosts`  | `jb`             |
| `joinmessage` | `jmsg`           |
| `quitmessage` | `qmsg`           |
| `invest`      | `stock`, `биржа` |
| `help`        | `?`, `h`         |

---

## Права доступа

### 🎮 Для обычных игроков

Рекомендуемые права для обычных игроков:

| Право                  | Описание                      |
|------------------------|-------------------------------|
| `arc.board`            | Настройки скорборда           |
| `arc.balance.top`           | Таблица лидеров               |
| `arc.join.message.gui` | Настройка join/quit сообщений |
| `arc.invest`           | Доступ к бирже                |
| `arc.store`            | Открытие магазина             |

**LuckPerms команда:**

```bash
lp group default permission set arc.board true
lp group default permission set arc.balance.top true
lp group default permission set arc.join.message.gui true
lp group default permission set arc.invest true
lp group default permission set arc.store true
```

---

### 👑 Для VIP игроков

Дополнительные права для VIP:

| Право            | Описание                     |
|------------------|------------------------------|
| `arc.jobs.boost.use` | Просмотр своих бустов работ  |

**LuckPerms команда:**

```bash
lp group vip parent add default
lp group vip permission set arc.jobs.boost.use true
```

---

### 🔧 Для модераторов

Права для модерации:

| Право               | Описание                       |
|---------------------|--------------------------------|
| `arc.audit`         | Просмотр экономического аудита |
| `arc.treasure.hunt.admin` | Управление охотами             |
| `arc.sound.follow`  | Воспроизведение звуков         |
| `arc.rtp.respawn`   | RTP при респауне               |

**LuckPerms команда:**

```bash
lp group moderator parent add vip
lp group moderator permission set arc.audit true
lp group moderator permission set arc.treasure.hunt.admin true
lp group moderator permission set arc.sound.follow true
lp group moderator permission set arc.rtp.respawn true
```

---

### ⚙️ Для администраторов

Полный доступ ко всем командам:

| Право                     | Описание                           |
|---------------------------|------------------------------------|
| `arc.admin`               | Reload, repo, logger, emshop, test |
| `arc.command.hide.admin`  | Управление полным доступом к командам |
| `arc.jobs.boost.give` | Выдача бустов                      |
| `arc.treasure.pool.admin`     | Управление наградами               |
| `arc.location.pool.admin`       | Управление пулами локаций          |
| `arc.elite.loot.admin`           | Управление Elite Loot              |
| `arc.x`                   | Кросс-серверные команды            |

**LuckPerms команда:**

```bash
lp group admin parent add moderator
lp group admin permission set arc.admin true
lp group admin permission set arc.command.hide.admin true
lp group admin permission set arc.jobs.boost.give true
lp group admin permission set arc.treasure.pool.admin true
lp group admin permission set arc.location.pool.admin true
lp group admin permission set arc.elite.loot.admin true
lp group admin permission set arc.x true
```

---

### 📋 Полный список прав

| Право                     | Команда                                                               | Уровень   |
|---------------------------|-----------------------------------------------------------------------|-----------|
| -                         | `/arc help`                                                           | Все       |
| `arc.board`               | `/arc board`                                                          | Игрок     |
| `arc.balance.top`              | `/arc baltop`                                                         | Игрок     |
| `arc.join.message.gui`    | `/arc joinmessage`, `/arc quitmessage`                                | Игрок     |
| `arc.invest`              | `/arc invest`                                                         | Игрок     |
| `arc.store`               | `/arc store`                                                          | Игрок     |
| `arc.jobs.boost.use`          | `/arc jobsboosts`                                                     | VIP       |
| `arc.audit`               | `/arc audit`                                                          | Модератор |
| `arc.treasure.hunt.admin`       | `/arc hunt`                                                           | Модератор |
| `arc.sound.follow`        | `/arc soundfollow`                                                    | Модератор |
| `arc.rtp.respawn`         | `/arc respawnonrtp`                                                   | Модератор |
| `arc.admin`               | `/arc reload`, `/arc repo`, `/arc logger`, `/arc emshop`, `/arc test` | Админ     |
| `arc.command.hide.admin`  | `/arc commandhide`                                                 | Админ     |
| `arc.jobs.boost.give` | `/arc giveboost`                                                      | Админ     |
| `arc.treasure.pool.admin`     | `/arc treasures`                                                      | Админ     |
| `arc.pouch.give`               | `/arc pouch`                                                          | Админ     |
| `arc.location.pool.admin`       | `/arc locationpool`                                                    | Админ     |
| `arc.elite.loot.admin`           | `/arc eliteloot`                                                      | Админ     |
| `arc.x`                   | `/x`                                                                  | Админ     |

---

## Поддержка

При возникновении проблем:

1. Проверьте права с помощью `/lp user <имя> permission check <право>`
2. Включите debug логирование: `/arc logger DEBUG`
3. Проверьте конфигурацию в `config/commands.yml`
4. Перезагрузите плагин: `/arc reload`
