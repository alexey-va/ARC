package ru.arc.helpcenter

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

data class HelpCenterCommandText(
    val label: String,
    val description: String,
    val keywords: String,
)

data class HelpCenterIntentText(
    val label: String,
    val description: String,
    val keywords: String,
)

data class HelpCenterSettings(
    val enabled: Boolean,
    val maxHomes: Int,
    val maxSearchResults: Int,
    val loadTimeoutSeconds: Long,
    private val text: Map<String, String>,
    private val commands: Map<String, HelpCenterCommandText>,
    private val intents: Map<String, HelpCenterIntentText>,
) {
    fun text(key: String): String = text.getValue(key)

    fun command(id: String): HelpCenterCommandText = commands.getValue(id)

    fun intent(id: String): HelpCenterIntentText = intents.getValue(id)
}

class HelpCenterConfig(private val config: Config) {
    fun snapshot(): HelpCenterSettings {
        val maxHomes = config.integer("limits.max-homes", 12)
        val maxSearchResults = config.integer("limits.max-search-results", 8)
        val loadTimeoutSeconds = config.long("limits.load-timeout-seconds", 3)
        require(maxHomes in 1..32) { "Help center max-homes must be in 1..32" }
        require(maxSearchResults in 1..32) { "Help center max-search-results must be in 1..32" }
        require(loadTimeoutSeconds in 1..10) { "Help center load-timeout-seconds must be in 1..10" }
        return HelpCenterSettings(
            enabled = config.bool("enabled", true),
            maxHomes = maxHomes,
            maxSearchResults = maxSearchResults,
            loadTimeoutSeconds = loadTimeoutSeconds,
            text = DEFAULT_TEXT.mapValues { (key, fallback) -> required("text.$key", fallback) },
            commands = COMMANDS.mapValues { (id, fallback) ->
                HelpCenterCommandText(
                    label = required("commands.$id.label", fallback.label),
                    description = required("commands.$id.description", fallback.description),
                    keywords = required("commands.$id.keywords", fallback.keywords),
                )
            },
            intents = INTENTS.mapValues { (id, fallback) ->
                HelpCenterIntentText(
                    label = required("intents.$id.label", fallback.label),
                    description = required("intents.$id.description", fallback.description),
                    keywords = required("intents.$id.keywords", fallback.keywords),
                )
            },
        )
    }

    private fun required(path: String, fallback: String): String = config.string(path, fallback).also { value ->
        require(value.isNotBlank()) { "Help center value '$path' cannot be blank" }
        require(value.length <= 2_000) { "Help center value '$path' is too long" }
    }

    companion object {
        private const val RESOURCE = "help-center.yml"

        fun load(dataPath: Path): HelpCenterConfig {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            source.mergeMissingFromBundled("modules/$RESOURCE")
            return HelpCenterConfig(source)
        }

        private val DEFAULT_TEXT = linkedMapOf(
            "root-title" to "<#ffca55>Главное меню",
            "root-body" to "<#f5fbff>Куда идём?<newline><#9db0ba>Десять понятных разделов — без списка из сотни команд.",
            "now-label" to "<#ffca55>Про меня",
            "now-tooltip" to "<#dcecf2>Ваше состояние и полезные следующие шаги.",
            "players-label" to "<#46d9ee>Игроки",
            "players-tooltip" to "<#dcecf2>Телепорт, сообщение, перевод, дуэль или приглашение.",
            "my-label" to "<#4f8cff>Про меня",
            "my-tooltip" to "<#e5f1f5>Ваш профиль, дома, поселения и прогресс.",
            "guide-label" to "<#ffc247>С чего начать",
            "guide-tooltip" to "<#e5f1f5>Первые шаги без лишних команд.",
            "commands-label" to "<#4f8cff>Команды и поиск",
            "commands-tooltip" to "<#e5f1f5>Найти действие обычной фразой.",
            "travel-label" to "<#4dd8f0>Телепортация",
            "travel-tooltip" to "<#e5f1f5>Дома, миры, варпы и возврат.",
            "privat-label" to "<#5ee39c>Приват",
            "privat-tooltip" to "<#e5f1f5>Создание, территория, участники и подробный гайд.",
            "main-menu-label" to "<#b6c4ca>Главное меню",
            "main-menu-tooltip" to "<#e5f1f5>Открыть остальные игровые разделы сервера.",
            "back-label" to "<#b6c4ca>Назад",
            "root-label" to "<#b6c4ca>К разделам",
            "not-available" to "—",
            "now-title" to "<#ffca55>Про меня",
            "now-identity" to "<#ffca55><player><newline><#46d9ee>◆ <#9db0ba>Сервер  <#f5fbff><server><newline><#46d9ee>◆ <#9db0ba>Онлайн  <#f5fbff><online>",
            "now-progress" to "<#7fe38b>◆ <#9db0ba>Ранг  <#f5fbff><rank><newline><#ffad52>◆ <#9db0ba>Баланс  <#f5fbff><balance><newline><#46d9ee>◆ <#9db0ba>Дома  <#f5fbff><homes>/<max_homes><newline><#7fe38b>◆ <#9db0ba>Поселения  <#f5fbff><lands>",
            "now-location" to "<#46d9ee>◆ <#9db0ba>Мир  <#f5fbff><world><newline><#46d9ee>◆ <#9db0ba>Координаты  <#f5fbff><x>, <y>, <z><newline><#7fe38b>◆ <#9db0ba>Чат  <#f5fbff><chat>",
            "rec-home-label" to "<#ffca55>Поставить первый дом",
            "rec-land-label" to "<#7fe38b>Защитить территорию",
            "rec-rank-label" to "<#7fe38b>Следующая цель ранга",
            "rec-bp-label" to "<#ffad52>Боевой пропуск",
            "rec-events-label" to "<#ffca55>Текущие события",
            "my-title" to "<#ffca55>Про меня",
            "my-loading" to "<#e5f1f5>Собираю ваш профиль…",
            "my-identity" to "<#ffca55><player><newline><#9aaab2>Сервер: <#4dd8f0><server><newline><#9aaab2>Ранг: <#7fe38b><rank>",
            "my-summary" to "<#9aaab2>Баланс: <#ff9f43><balance><newline><#9aaab2>Дома: <#4dd8f0><homes>/<max_homes><newline><#9aaab2>Поселения: <#5ee39c><lands>",
            "my-location" to "<#9aaab2>Мир: <#4dd8f0><world><newline><#9aaab2>Координаты: <#e5f1f5><x>, <y>, <z>",
            "my-error" to "<#e5f1f5>Часть профиля сейчас недоступна. Быстрые разделы всё равно работают.",
            "my-homes-label" to "<#4dd8f0>Мои дома",
            "my-homes-tooltip" to "<#e5f1f5>Сохранённые точки и перемещения.",
            "my-lands-label" to "<#5ee39c>Мои поселения",
            "my-lands-tooltip" to "<#e5f1f5>Территория, участники и настройки.",
            "my-rank-label" to "<#4f8cff>Мой ранг",
            "my-rank-tooltip" to "<#e5f1f5>Текущий ранг и следующие цели.",
            "my-jobs-label" to "<#ffc247>Мои работы",
            "my-jobs-tooltip" to "<#e5f1f5>Профессии, уровни и заработок.",
            "my-quests-label" to "<#ff9f43>Мои задания",
            "my-quests-tooltip" to "<#e5f1f5>Активные задания и награды.",
            "my-skills-label" to "<#4f8cff>Мои навыки",
            "my-skills-tooltip" to "<#e5f1f5>Открыть навыки и их развитие.",
            "guide-title" to "<#ffca55>С чего начать",
            "guide-body" to "<#ffca55>1 · Стартовый набор<newline><#f5fbff>Получите первые инструменты и припасы.<newline><newline><#46d9ee>2 · Выберите мир<newline><#f5fbff>Обычный мир, мир добычи или мир новых биомов.<newline><newline><#ffad52>3 · Начните зарабатывать<newline><#f5fbff>Выберите работу и получайте деньги за привычные действия.<newline><newline><#7fe38b>4 · Дом и поселение<newline><#f5fbff>Сохраните точку дома и защитите территорию поселением.",
            "kit-label" to "<#ffc247>Получить набор",
            "vanilla-label" to "<#4dd8f0>Обычный мир",
            "mining-label" to "<#4dd8f0>Мир добычи",
            "biomes-label" to "<#4dd8f0>Мир новых биомов",
            "jobs-label" to "<#ffc247>Выбрать работу",
            "rules-label" to "<#4f8cff>Правила",
            "commands-title" to "<#ffca55>Поиск",
            "commands-body" to "<#fff4df>Опишите, что хотите сделать, обычной фразой.<newline><#b6c4ca>Поиск понимает формы слов, близкие значения и небольшие опечатки.",
            "search-input" to "<#fff0d8>Что вы хотите сделать?",
            "search-label" to "<#ffc247>Найти",
            "search-tooltip" to "<#fff0d8>Например: удалить поселение, перенести дом, добавить друга.",
            "search-title" to "<#ffca55>Результаты поиска",
            "search-body" to "<#b6c4ca>Запрос  <#fff4df><query><newline><#b6c4ca>Найдено  <#fff4df><count>",
            "search-empty" to "<#fff0d8>Не понял запрос. Опишите действие и объект: «перенести дом» или «добавить игрока».",
            "search-result-tooltip" to "<#fff0d8><description><newline><#8d7768>Открыть нужный раздел",
            "category-start-label" to "<#ffc247>Начало игры",
            "category-travel-label" to "<#4dd8f0>Телепортация",
            "category-protection-label" to "<#5ee39c>Защита",
            "category-trade-label" to "<#ff9f43>Торговля",
            "category-progress-label" to "<#a7e75f>Развитие",
            "category-social-label" to "<#38d9c3>Общение и сервер",
            "category-activities-label" to "<#ffad52>Активности",
            "category-technology-label" to "<#46d9ee>Технологии",
            "category-settings-label" to "<#7fe38b>Настройки",
            "category-activities-tooltip" to "<#dcecf2>События, дуэли, данжи и фермы.",
            "category-technology-tooltip" to "<#dcecf2>Предметы, зачарования, механизмы и транспорт.",
            "category-settings-tooltip" to "<#dcecf2>Чат, эффекты и личные переключатели.",
            "category-trade-tooltip" to "<#e5f1f5>Магазины, быстрая продажа и аукцион.",
            "category-progress-tooltip" to "<#e5f1f5>Ранг, работы, задания и навыки.",
            "category-social-tooltip" to "<#e5f1f5>Голосование и выбор режима чата.",
            "category-start-title" to "<#ffca55>Начало игры",
            "category-travel-title" to "<#46d9ee>Телепортация",
            "category-protection-title" to "<#7fe38b>Защита",
            "category-trade-title" to "<#ffad52>Торговля",
            "category-progress-title" to "<#7fe38b>Развитие",
            "category-social-title" to "<#46d9ee>Общение и сервер",
            "category-activities-title" to "<#ffad52>Активности",
            "category-technology-title" to "<#46d9ee>Технологии",
            "category-settings-title" to "<#7fe38b>Настройки",
            "settings-body" to "<#f5fbff>Текущий чат: <#46d9ee><chat><newline><#9db0ba>Ниже — только доступные вам переключатели.",
            "players-title" to "<#46d9ee>Игроки",
            "players-body" to "<#f5fbff>Найдите игрока на любом сервере сети или выберите его из списка.<newline><#9db0ba>Показано: <count>",
            "players-empty" to "<#9db0ba>Подходящих игроков сейчас не найдено.",
            "players-input" to "<#f5fbff>Ник игрока",
            "players-search-label" to "<#ffca55>Найти игрока",
            "players-search-tooltip" to "<#9db0ba>Применить фильтр",
            "player-label" to "<#46d9ee><player>",
            "player-tooltip" to "<#9db0ba>Сервер: <#46d9ee><server>",
            "player-server-unknown" to "неизвестен",
            "player-title" to "<#46d9ee><player>",
            "player-body" to "<#f5fbff><player><newline><#9db0ba>Сейчас на сервере <#46d9ee><server><#9db0ba>.<newline><newline><#f5fbff>Выберите действие.",
            "player-tpa-label" to "<#46d9ee>Телепортироваться к нему",
            "player-tpahere-label" to "<#46d9ee>Позвать к себе",
            "player-message-label" to "<#7fe38b>Написать сообщение",
            "player-pay-label" to "<#ffad52>Перевести деньги",
            "player-duel-label" to "<#ff6b61>Вызвать на дуэль",
            "player-invite-label" to "<#7fe38b>Пригласить в поселение",
            "message-title" to "<#7fe38b>Сообщение · <player>",
            "message-body" to "<#f5fbff>Напишите короткое личное сообщение для <#46d9ee><player><#f5fbff>.",
            "message-input" to "<#f5fbff>Текст сообщения",
            "message-send-label" to "<#7fe38b>Отправить",
            "invalid-message" to "<#ff6b61>Сообщение должно занимать от 1 до 128 символов и быть в одну строку.",
            "pay-title" to "<#ffad52>Перевод · <player>",
            "pay-body" to "<#f5fbff>Укажите сумму. Перед переводом будет подтверждение.",
            "pay-input" to "<#f5fbff>Сумма",
            "pay-continue-label" to "<#ffad52>Проверить перевод",
            "pay-confirm-title" to "<#ffad52>Подтверждение перевода",
            "pay-confirm-body" to "<#f5fbff>Перевести <#ffad52><amount> <#f5fbff>игроку <#46d9ee><player><#f5fbff>?",
            "pay-confirm-label" to "<#ff6b61>Подтвердить перевод",
            "invalid-amount" to "<#ff6b61>Введите положительную сумму, не больше двух знаков после запятой.",
            "recovery-label" to "<#ff6b61>Что случилось?",
            "recovery-tooltip" to "<#dcecf2>Быстрые выходы, если вы застряли или потерялись.",
            "recovery-title" to "<#ff6b61>Что случилось?",
            "recovery-body" to "<#f5fbff>Выберите самое похожее действие.<newline><#9db0ba>Если не помогло — откройте поиск и опишите проблему своими словами.",
            "category-body" to "<#fff0d8>Нажмите действие — команда выполнится от вашего имени.",
            "command-label" to "<#e5f1f5><label>",
            "command-vote-label" to "<#ffc247>Голосование",
            "command-chat-global-label" to "<#4dd8f0>Глобальный чат",
            "command-chat-local-label" to "<#4dd8f0>Локальный чат",
            "command-tooltip" to "<#fff0d8><description><newline><#8d7768>/<command>",
            "travel-title" to "<#46d9ee>Телепортация",
            "travel-loading" to "<#fff0d8>Загружаю ваши дома…",
            "travel-body" to "<#8d7768>Дома: <#ffc247><homes>/<max_homes><newline><#fff0d8>Выберите дом или другое направление.",
            "travel-empty" to "<#fff0d8>У вас пока нет домов. Создайте первый в текущей точке.",
            "travel-limit" to "<#8d7768>Свободных слотов нет. Удалите или перенесите существующий дом.",
            "travel-error" to "<#fff0d8>Дома сейчас не загрузились, но остальные способы телепортации доступны.",
            "home-label" to "<#4dd8f0><home>",
            "home-tooltip" to "<#fff0d8><server> · <world><newline><#8d7768><x>, <y>, <z>",
            "create-home-label" to "<#ffc247>Новый дом",
            "create-home-tooltip" to "<#fff0d8>Сохранить текущую точку.",
            "home-create-title" to "<#ffca55>Новый дом",
            "home-create-body" to "<#fff0d8>Введите короткое имя без пробелов.<newline><#8d7768>Дом будет создан в вашей текущей точке.",
            "home-name-input" to "<#fff0d8>Название дома",
            "home-create-submit" to "<#ffca55>Создать",
            "home-title" to "<#46d9ee>Дом · <home>",
            "home-body" to "<#8d7768>Сервер: <#fff0d8><server><newline><#8d7768>Мир: <#fff0d8><world><newline><#8d7768>Координаты: <#fff0d8><x>, <y>, <z>",
            "home-teleport-label" to "<#ffc247>Телепортироваться",
            "home-teleport-tooltip" to "<#fff0d8>Переместиться в сохранённую точку.",
            "home-relocate-label" to "<#fff0d8>Перенести сюда",
            "home-relocate-tooltip" to "<#fff0d8>Заменить точку вашим текущим положением. Потребуется подтверждение.",
            "home-delete-label" to "<#c42323><bold>Удалить дом",
            "home-delete-tooltip" to "<#fff0d8>Удалить сохранённую точку. Потребуется подтверждение.",
            "home-relocate-title" to "<#ffca55>Перенести · <home>",
            "home-relocate-body" to "<#fff0d8>Заменить сохранённую точку дома <#ffc247><home> <#fff0d8>вашим текущим положением?",
            "home-relocate-confirm" to "<#ffca55>Перенести точку",
            "home-delete-title" to "<#ff6b61>Удаление · <home>",
            "home-delete-body" to "<#c42323><bold>Это необратимое действие.<newline><#fff0d8>Дом <#ffc247><home> <#fff0d8>будет удалён.",
            "home-delete-confirm" to "<#c42323><bold>Удалить навсегда",
            "warps-label" to "<#4dd8f0>Варпы игроков",
            "spawn-label" to "<#4dd8f0>Спавн",
            "rtp-label" to "<#4dd8f0>Случайное место",
            "back-command-label" to "<#4dd8f0>На прежнее место",
            "stuck-label" to "<#ffc247>Выбраться на спавн",
            "public-homes-label" to "<#4dd8f0>Публичные дома",
            "public-homes-tooltip" to "<#fff0d8>Посмотреть открытые дома игроков.",
            "invalid-home" to "<#c42323>Имя дома: до 32 букв, цифр, _ или - без пробелов.",
            "action-failed" to "<#c42323>Команда не выполнилась. Попробуйте ещё раз или сообщите администрации.",
        )

        internal val COMMANDS = linkedMapOf(
            "menu" to HelpCenterCommandText("Главное меню", "Все игровые разделы", "разделы сервер mm"),
            "kit" to HelpCenterCommandText("Набор новичка", "Получить стартовые предметы", "старт начало вещи"),
            "rules" to HelpCenterCommandText("Правила", "Правила игрового сервера", "запрещено можно"),
            "tutorial" to HelpCenterCommandText("Обучение", "Вернуться к обучению", "гайд начало"),
            "warps" to HelpCenterCommandText("Варпы игроков", "Публичные точки игроков", "телепорт точки"),
            "spawn" to HelpCenterCommandText("Спавн", "Вернуться на спавн", "телепорт начало"),
            "rtp" to HelpCenterCommandText("Случайное место", "Телепорт в случайную безопасную точку", "рандом ресурсы"),
            "back" to HelpCenterCommandText("Вернуться назад", "Вернуться к прошлой позиции", "телепорт смерть"),
            "stuck" to HelpCenterCommandText("Выбраться на спавн", "Аварийный возврат со сложного места", "застрял помощь"),
            "vanilla" to HelpCenterCommandText("Обычный мир", "Перейти в обычный мир выживания", "ванильный стандартный мир ресурсы база"),
            "mining" to HelpCenterCommandText("Мир добычи", "Перейти в обновляемый ресурсный мир", "шахта ресурсы"),
            "biomes" to HelpCenterCommandText("Мир новых биомов", "Перейти в мир выживания с новыми биомами", "новые биомы необычный мир база"),
            "privat" to HelpCenterCommandText("Приват", "Создание и защита территории", "земля поселение lands приват защита"),
            "events" to HelpCenterCommandText("События", "Активные серверные события", "ивенты события награды"),
            "duels" to HelpCenterCommandText("Дуэли", "Сразиться с другим игроком", "пвп бой поединок"),
            "battle-pass" to HelpCenterCommandText("Боевой пропуск", "Задания и награды сезона", "бп сезон пропуск награды"),
            "giveaways" to HelpCenterCommandText("Розыгрыши", "Активные розыгрыши сервера", "giveaway конкурс приз"),
            "dungeons" to HelpCenterCommandText("Данжи", "Меню данжей и боссов", "данж подземелье боссы элитные мобы"),
            "dungeon-portals" to HelpCenterCommandText("Порталы данжей", "Телепорт к порталам приключений", "данж подземелье портал гильдия"),
            "farms" to HelpCenterCommandText("Фермы", "Управление игровыми фермами", "ферма урожай животные"),
            "shops" to HelpCenterCommandText("Магазины", "Магазины игроков и сервера", "рынок купить продать"),
            "sell" to HelpCenterCommandText("Быстрая продажа", "Продать подходящие предметы", "деньги рынок"),
            "auction" to HelpCenterCommandText("Аукцион", "Торговля предметами между игроками", "ah рынок купить продать"),
            "bank" to HelpCenterCommandText("Банк", "Открыть личный банковский счёт", "деньги счет вклад"),
            "investments" to HelpCenterCommandText("Инвестиции", "Портфель и рынок акций", "акции биржа портфель"),
            "rank" to HelpCenterCommandText("Ранги", "Текущий ранг и следующие цели", "уровень развитие"),
            "rankup" to HelpCenterCommandText("Повысить ранг", "Проверить требования повышения", "уровень развитие"),
            "jobs" to HelpCenterCommandText("Работы", "Профессии и заработок", "деньги профессия"),
            "quests" to HelpCenterCommandText("Задания", "Активные задания и награды", "квест развитие"),
            "skills" to HelpCenterCommandText("Навыки", "Навыки и их развитие", "auraskills способности развитие"),
            "slimefun" to HelpCenterCommandText("Книга технологий", "Открыть справочник Slimefun", "slimefun сф механизмы рецепты"),
            "items" to HelpCenterCommandText("Новые предметы", "Каталог дополнительных предметов", "itemsadder предметы рецепты"),
            "enchants" to HelpCenterCommandText("Зачарования", "Список особых зачарований", "энчанты чары магия"),
            "enchanter" to HelpCenterCommandText("Зачарователь", "Магазин особых зачарований", "купить чары энчанты"),
            "builder" to HelpCenterCommandText("Инструменты строителя", "Получить книгу инструментов", "стройка билдер блоки"),
            "mounts" to HelpCenterCommandText("Транспорт", "Коллекция ездовых существ", "маунты транспорт питомцы"),
            "lands-borders" to HelpCenterCommandText("Границы поселения", "Показать границы участка", "чанки границы приват"),
            "trails-on" to HelpCenterCommandText("Включить след", "Показывать выбранный визуальный след", "trail частицы эффект"),
            "trails-off" to HelpCenterCommandText("Выключить след", "Скрыть визуальный след", "trail частицы эффект"),
            "trails-boost-on" to HelpCenterCommandText("След при ускорении", "Включить эффект быстрого движения", "trail boost ускорение"),
            "trails-boost-off" to HelpCenterCommandText("Убрать след ускорения", "Выключить эффект быстрого движения", "trail boost ускорение"),
            "particles" to HelpCenterCommandText("Частицы", "Включить или выключить личные частицы", "эффекты партиклы"),
            "tpa-ignore" to HelpCenterCommandText("Запросы телепорта", "Включить или выключить входящие запросы", "tpa запросы игнор"),
            "vote" to HelpCenterCommandText(
                "Голосование",
                "Открыть ссылки для голосования за сервер",
                "vote votes голос голосование рейтинг награда мониторинг",
            ),
            "chat-global" to HelpCenterCommandText(
                "Глобальный чат",
                "Переключить режим на глобальный чат",
                "чат глобальный общий g всем писать",
            ),
            "chat-local" to HelpCenterCommandText(
                "Локальный чат",
                "Переключить режим на локальный чат рядом",
                "чат локальный рядом l местный писать",
            ),
        )

        internal val INTENTS = linkedMapOf(
            "my" to HelpCenterIntentText(
                "Про меня",
                "Открыть личный профиль, дома, земли и прогресс",
                "мой профиль баланс деньги координаты где я мои данные",
            ),
            "player-find" to HelpCenterIntentText(
                "Найти игрока",
                "Открыть действия с игроками онлайн на всей сети",
                "игрок друг написать другу личка лс msg телепорт сообщение перевод дуэль пригласить",
            ),
            "recovery" to HelpCenterIntentText(
                "Что случилось?",
                "Быстрые действия, если вы застряли или потерялись",
                "застрял потерялся умер не работает проблема помощь",
            ),
            "home-create" to HelpCenterIntentText(
                "Создать дом",
                "Сохранить текущую точку как новый дом",
                "поставить дом создать хом sethome сохранить точку",
            ),
            "home-move" to HelpCenterIntentText(
                "Перенести дом",
                "Выбрать дом и заменить его сохранённую точку",
                "передвинуть переместить перенести точку дома edithome",
            ),
            "home-delete" to HelpCenterIntentText(
                "Удалить дом",
                "Выбрать сохранённый дом для удаления",
                "удалить стереть снести дом хом delhome",
            ),
            "land-create" to HelpCenterIntentText(
                "Создать поселение",
                "Открыть приват и создать новое поселение",
                "создать основать сделать поселение землю приват",
            ),
            "land-delete" to HelpCenterIntentText(
                "Удалить поселение",
                "Открыть приват и выбрать поселение для удаления",
                "как удалить распустить закрыть снести поселение землю приват lands",
            ),
            "land-invite" to HelpCenterIntentText(
                "Добавить игрока в поселение",
                "Выбрать поселение и пригласить участника",
                "добавить пригласить позвать друга игрока участника доверить trust",
            ),
            "land-remove" to HelpCenterIntentText(
                "Исключить игрока из поселения",
                "Выбрать поселение и убрать участника",
                "удалить выгнать исключить убрать игрока участника untrust",
            ),
            "land-main-block" to HelpCenterIntentText(
                "Перенести блок поселения",
                "Открыть управление главным блоком поселения",
                "перенести передвинуть переместить блок колокол костер mainblock",
            ),
            "land-claim" to HelpCenterIntentText(
                "Расширить территорию",
                "Открыть приват и управление участками",
                "заприватить занять добавить чанк участок территорию claim",
            ),
        )
    }
}
