package ru.arc.helpcenter

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

data class HelpCenterCommandText(
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
) {
    fun text(key: String): String = text.getValue(key)

    fun command(id: String): HelpCenterCommandText = commands.getValue(id)
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
            "root-title" to "<#20252b><bold>Помощь и команды",
            "root-body" to "<#fff0d8>Все основные действия собраны здесь.<newline><#8d7768>Выберите раздел — нужная команда выполнится прямо из меню.",
            "guide-label" to "<#d9864f><bold>С чего начать",
            "guide-tooltip" to "<#fff0d8>Короткий маршрут для нового игрока.",
            "commands-label" to "<#f4bd6a><bold>Команды",
            "commands-tooltip" to "<#fff0d8>Каталог по задачам и поиск.",
            "travel-label" to "<#f4bd6a><bold>Перемещения",
            "travel-tooltip" to "<#fff0d8>Ваши дома, миры, варпы и возврат.",
            "privat-label" to "<#f4bd6a><bold>Приват",
            "privat-tooltip" to "<#fff0d8>Поселения, участники и территория.",
            "main-menu-label" to "<#fff0d8>Главное меню",
            "main-menu-tooltip" to "<#fff0d8>Открыть все игровые разделы сервера.",
            "back-label" to "<#8d7768>Назад",
            "root-label" to "<#8d7768>К разделам",
            "guide-title" to "<#20252b><bold>С чего начать",
            "guide-body" to "<#d9864f><bold>1. Набор</bold><newline><#fff0d8>Получите стартовые предметы.<newline><newline><#d9864f><bold>2. Мир</bold><newline><#fff0d8>Выберите строительство или добычу.<newline><newline><#d9864f><bold>3. Дом и приват</bold><newline><#fff0d8>Поставьте точку дома и защитите поселением свою базу.",
            "kit-label" to "<#f4bd6a><bold>Получить набор",
            "build-label" to "<#fff0d8>Мир строительства",
            "mining-label" to "<#fff0d8>Мир добычи",
            "rules-label" to "<#fff0d8>Правила",
            "commands-title" to "<#20252b><bold>Каталог команд",
            "commands-body" to "<#fff0d8>Выберите задачу или найдите команду по смыслу.<newline><#8d7768>Например: дом, продать, ранг, варпы.",
            "search-input" to "<#fff0d8>Что вы хотите сделать?",
            "search-label" to "<#f4bd6a><bold>Найти",
            "search-tooltip" to "<#fff0d8>Искать по названию команды и задаче.",
            "search-title" to "<#20252b><bold>Результаты поиска",
            "search-body" to "<#8d7768>Запрос: <#fff0d8><query><newline><#8d7768>Найдено: <#f4bd6a><count>",
            "search-empty" to "<#fff0d8>Ничего не найдено. Попробуйте: дом, приват, магазин, ранг.",
            "category-start-label" to "<#fff0d8>Начало игры",
            "category-travel-label" to "<#fff0d8>Перемещения",
            "category-protection-label" to "<#fff0d8>Защита",
            "category-trade-label" to "<#fff0d8>Торговля",
            "category-progress-label" to "<#fff0d8>Развитие",
            "category-social-label" to "<#fff0d8>Общение и сервер",
            "category-start-title" to "<#20252b><bold>Начало игры",
            "category-travel-title" to "<#20252b><bold>Команды перемещения",
            "category-protection-title" to "<#20252b><bold>Защита",
            "category-trade-title" to "<#20252b><bold>Торговля",
            "category-progress-title" to "<#20252b><bold>Развитие",
            "category-social-title" to "<#20252b><bold>Общение и сервер",
            "category-body" to "<#fff0d8>Нажмите действие — команда выполнится от вашего имени.",
            "command-label" to "<#f4bd6a><bold><label>",
            "command-tooltip" to "<#fff0d8><description><newline><#8d7768>/<command>",
            "travel-title" to "<#20252b><bold>Перемещения",
            "travel-loading" to "<#fff0d8>Загружаю ваши дома…",
            "travel-body" to "<#8d7768>Дома: <#f4bd6a><homes>/<max_homes><newline><#fff0d8>Выберите дом или другое направление.",
            "travel-empty" to "<#fff0d8>У вас пока нет домов. Создайте первый в текущей точке.",
            "travel-limit" to "<#8d7768>Свободных слотов нет. Удалите или перенесите существующий дом.",
            "travel-error" to "<#fff0d8>Дома сейчас не загрузились, но остальные перемещения доступны.",
            "home-label" to "<#f4bd6a><bold><home>",
            "home-tooltip" to "<#fff0d8><server> · <world><newline><#8d7768><x>, <y>, <z>",
            "create-home-label" to "<#d9864f><bold>Новый дом",
            "create-home-tooltip" to "<#fff0d8>Сохранить текущую точку.",
            "home-create-title" to "<#20252b><bold>Новый дом",
            "home-create-body" to "<#fff0d8>Введите короткое имя без пробелов.<newline><#8d7768>Дом будет создан в вашей текущей точке.",
            "home-name-input" to "<#fff0d8>Название дома",
            "home-create-submit" to "<#f4bd6a><bold>Создать",
            "home-title" to "<#20252b><bold>Дом · <home>",
            "home-body" to "<#8d7768>Сервер: <#fff0d8><server><newline><#8d7768>Мир: <#fff0d8><world><newline><#8d7768>Координаты: <#fff0d8><x>, <y>, <z>",
            "home-teleport-label" to "<#f4bd6a><bold>Телепортироваться",
            "home-teleport-tooltip" to "<#fff0d8>Переместиться в сохранённую точку.",
            "home-relocate-label" to "<#fff0d8>Перенести сюда",
            "home-relocate-tooltip" to "<#fff0d8>Заменить точку вашим текущим положением. Потребуется подтверждение.",
            "home-delete-label" to "<#c42323><bold>Удалить дом",
            "home-delete-tooltip" to "<#fff0d8>Удалить сохранённую точку. Потребуется подтверждение.",
            "home-relocate-title" to "<#20252b><bold>Перенести · <home>",
            "home-relocate-body" to "<#fff0d8>Заменить сохранённую точку дома <#f4bd6a><home> <#fff0d8>вашим текущим положением?",
            "home-relocate-confirm" to "<#d9864f><bold>Перенести точку",
            "home-delete-title" to "<#20252b><bold>Удаление · <home>",
            "home-delete-body" to "<#c42323><bold>Это необратимое действие.<newline><#fff0d8>Дом <#f4bd6a><home> <#fff0d8>будет удалён.",
            "home-delete-confirm" to "<#c42323><bold>Удалить навсегда",
            "warps-label" to "<#fff0d8>Варпы игроков",
            "spawn-label" to "<#fff0d8>Спавн",
            "rtp-label" to "<#fff0d8>Случайное место",
            "back-command-label" to "<#fff0d8>Вернуться назад",
            "stuck-label" to "<#fff0d8>Выбраться на спавн",
            "public-homes-label" to "<#fff0d8>Публичные дома",
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
            "build" to HelpCenterCommandText("Мир строительства", "Перейти в постоянный строительный мир", "база строить"),
            "mining" to HelpCenterCommandText("Мир добычи", "Перейти в обновляемый ресурсный мир", "шахта ресурсы"),
            "privat" to HelpCenterCommandText("Приват", "Поселения и защита территории", "земля поселение lands защита"),
            "shops" to HelpCenterCommandText("Магазины", "Магазины игроков и сервера", "рынок купить продать"),
            "sell" to HelpCenterCommandText("Быстрая продажа", "Продать подходящие предметы", "деньги рынок"),
            "auction" to HelpCenterCommandText("Аукцион", "Торговля предметами между игроками", "ah рынок купить продать"),
            "rank" to HelpCenterCommandText("Ранги", "Текущий ранг и следующие цели", "уровень развитие"),
            "rankup" to HelpCenterCommandText("Повысить ранг", "Проверить требования повышения", "уровень развитие"),
            "jobs" to HelpCenterCommandText("Работы", "Профессии и заработок", "деньги профессия"),
            "quests" to HelpCenterCommandText("Задания", "Активные задания и награды", "квест развитие"),
            "stats" to HelpCenterCommandText("Навыки", "Характеристики и способности", "auraskills развитие"),
            "notes" to HelpCenterCommandText("Заметки", "Ваши серверные заметки", "список записи"),
            "donate" to HelpCenterCommandText("Поддержка и VIP", "Возможности поддержки сервера", "донат привилегии"),
        )
    }
}
