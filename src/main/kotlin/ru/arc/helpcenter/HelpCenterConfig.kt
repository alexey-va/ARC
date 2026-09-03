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
            "root-title" to "<#f4bd6a><bold>Помощь и команды",
            "root-body" to "<#fff4df>Всё нужное для игры — в одном месте.<newline><#b7a899>Выберите раздел или опишите задачу в умном поиске.",
            "my-label" to "<#fff4df><bold>Про меня",
            "my-tooltip" to "<#fff4df>Ваш профиль, дома, поселения и прогресс.",
            "guide-label" to "<#fff4df><bold>С чего начать",
            "guide-tooltip" to "<#fff4df>Первые шаги без лишних команд.",
            "commands-label" to "<#fff4df><bold>Команды и поиск",
            "commands-tooltip" to "<#fff4df>Найти действие обычной фразой.",
            "travel-label" to "<#fff4df><bold>Перемещения",
            "travel-tooltip" to "<#fff4df>Дома, миры, варпы и возврат.",
            "privat-label" to "<#fff4df><bold>Поселения",
            "privat-tooltip" to "<#fff4df>Создание, территория, участники и подробный гайд.",
            "main-menu-label" to "<#fff4df><bold>Главное меню",
            "main-menu-tooltip" to "<#fff4df>Открыть остальные игровые разделы сервера.",
            "back-label" to "<#b7a899>Назад",
            "root-label" to "<#b7a899>К разделам",
            "not-available" to "—",
            "my-title" to "<#f4bd6a><bold>Про меня",
            "my-loading" to "<#fff4df>Собираю ваш профиль…",
            "my-body" to "<#f4bd6a><bold><player></bold><newline><#b7a899>Сервер       <#fff4df><server><newline><#b7a899>Ранг         <#fff4df><rank><newline><#b7a899>Баланс       <#fff4df><balance><newline><#b7a899>Дома         <#fff4df><homes>/<max_homes><newline><#b7a899>Поселения    <#fff4df><lands><newline><#b7a899>Где вы       <#fff4df><world> · <x>, <y>, <z>",
            "my-error" to "<#fff4df>Часть профиля сейчас недоступна. Быстрые разделы всё равно работают.",
            "my-homes-label" to "<#fff4df><bold>Мои дома",
            "my-homes-tooltip" to "<#fff4df>Сохранённые точки и перемещения.",
            "my-lands-label" to "<#fff4df><bold>Мои поселения",
            "my-lands-tooltip" to "<#fff4df>Территория, участники и настройки.",
            "my-rank-label" to "<#fff4df><bold>Мой ранг",
            "my-rank-tooltip" to "<#fff4df>Текущий ранг и следующие цели.",
            "my-jobs-label" to "<#fff4df><bold>Мои работы",
            "my-jobs-tooltip" to "<#fff4df>Профессии, уровни и заработок.",
            "my-quests-label" to "<#fff4df><bold>Мои задания",
            "my-quests-tooltip" to "<#fff4df>Активные задания и награды.",
            "my-skills-label" to "<#fff4df><bold>Мои навыки",
            "my-skills-tooltip" to "<#fff4df>Открыть навыки и их развитие.",
            "guide-title" to "<#f4bd6a><bold>С чего начать",
            "guide-body" to "<#f4bd6a><bold>1 · Стартовый набор</bold><newline><#fff4df>Получите первые инструменты и припасы.<newline><newline><#f4bd6a><bold>2 · Выберите мир</bold><newline><#fff4df>Обычный мир, мир добычи или мир новых биомов.<newline><newline><#f4bd6a><bold>3 · Начните зарабатывать</bold><newline><#fff4df>Выберите работу и получайте деньги за привычные действия.<newline><newline><#f4bd6a><bold>4 · Дом и поселение</bold><newline><#fff4df>Сохраните точку дома и защитите территорию поселением.",
            "kit-label" to "<#f4bd6a><bold>Получить набор",
            "vanilla-label" to "<#fff4df><bold>Обычный мир",
            "mining-label" to "<#fff4df><bold>Мир добычи",
            "biomes-label" to "<#fff4df><bold>Мир новых биомов",
            "jobs-label" to "<#fff4df><bold>Выбрать работу",
            "rules-label" to "<#fff4df><bold>Правила",
            "commands-title" to "<#f4bd6a><bold>Команды и поиск",
            "commands-body" to "<#fff4df>Опишите, что хотите сделать, обычной фразой.<newline><#b7a899>Поиск понимает формы слов, близкие значения и небольшие опечатки.",
            "search-input" to "<#fff0d8>Что вы хотите сделать?",
            "search-label" to "<#f4bd6a><bold>Найти",
            "search-tooltip" to "<#fff0d8>Например: удалить поселение, перенести дом, добавить друга.",
            "search-title" to "<#f4bd6a><bold>Результаты поиска",
            "search-body" to "<#b7a899>Запрос  <#fff4df><query><newline><#b7a899>Найдено  <#fff4df><count>",
            "search-empty" to "<#fff0d8>Не понял запрос. Опишите действие и объект: «перенести дом» или «добавить игрока».",
            "search-result-tooltip" to "<#fff0d8><description><newline><#8d7768>Открыть нужный раздел",
            "category-start-label" to "<#fff4df><bold>Начало игры",
            "category-travel-label" to "<#fff4df><bold>Перемещения",
            "category-protection-label" to "<#fff4df><bold>Защита",
            "category-trade-label" to "<#fff4df><bold>Торговля",
            "category-progress-label" to "<#fff4df><bold>Развитие",
            "category-social-label" to "<#fff4df><bold>Общение и сервер",
            "category-start-title" to "<#f4bd6a><bold>Начало игры",
            "category-travel-title" to "<#f4bd6a><bold>Команды перемещения",
            "category-protection-title" to "<#f4bd6a><bold>Защита",
            "category-trade-title" to "<#f4bd6a><bold>Торговля",
            "category-progress-title" to "<#f4bd6a><bold>Развитие",
            "category-social-title" to "<#f4bd6a><bold>Общение и сервер",
            "category-body" to "<#fff0d8>Нажмите действие — команда выполнится от вашего имени.",
            "command-label" to "<#f4bd6a><bold><label>",
            "command-tooltip" to "<#fff0d8><description><newline><#8d7768>/<command>",
            "travel-title" to "<#f4bd6a><bold>Перемещения",
            "travel-loading" to "<#fff0d8>Загружаю ваши дома…",
            "travel-body" to "<#8d7768>Дома: <#f4bd6a><homes>/<max_homes><newline><#fff0d8>Выберите дом или другое направление.",
            "travel-empty" to "<#fff0d8>У вас пока нет домов. Создайте первый в текущей точке.",
            "travel-limit" to "<#8d7768>Свободных слотов нет. Удалите или перенесите существующий дом.",
            "travel-error" to "<#fff0d8>Дома сейчас не загрузились, но остальные перемещения доступны.",
            "home-label" to "<#f4bd6a><bold><home>",
            "home-tooltip" to "<#fff0d8><server> · <world><newline><#8d7768><x>, <y>, <z>",
            "create-home-label" to "<#f4bd6a><bold>Новый дом",
            "create-home-tooltip" to "<#fff0d8>Сохранить текущую точку.",
            "home-create-title" to "<#f4bd6a><bold>Новый дом",
            "home-create-body" to "<#fff0d8>Введите короткое имя без пробелов.<newline><#8d7768>Дом будет создан в вашей текущей точке.",
            "home-name-input" to "<#fff0d8>Название дома",
            "home-create-submit" to "<#f4bd6a><bold>Создать",
            "home-title" to "<#f4bd6a><bold>Дом · <home>",
            "home-body" to "<#8d7768>Сервер: <#fff0d8><server><newline><#8d7768>Мир: <#fff0d8><world><newline><#8d7768>Координаты: <#fff0d8><x>, <y>, <z>",
            "home-teleport-label" to "<#f4bd6a><bold>Телепортироваться",
            "home-teleport-tooltip" to "<#fff0d8>Переместиться в сохранённую точку.",
            "home-relocate-label" to "<#fff0d8>Перенести сюда",
            "home-relocate-tooltip" to "<#fff0d8>Заменить точку вашим текущим положением. Потребуется подтверждение.",
            "home-delete-label" to "<#c42323><bold>Удалить дом",
            "home-delete-tooltip" to "<#fff0d8>Удалить сохранённую точку. Потребуется подтверждение.",
            "home-relocate-title" to "<#f4bd6a><bold>Перенести · <home>",
            "home-relocate-body" to "<#fff0d8>Заменить сохранённую точку дома <#f4bd6a><home> <#fff0d8>вашим текущим положением?",
            "home-relocate-confirm" to "<#f4bd6a><bold>Перенести точку",
            "home-delete-title" to "<#f4bd6a><bold>Удаление · <home>",
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
            "vanilla" to HelpCenterCommandText("Обычный мир", "Перейти в обычный мир выживания", "ванильный стандартный мир ресурсы база"),
            "mining" to HelpCenterCommandText("Мир добычи", "Перейти в обновляемый ресурсный мир", "шахта ресурсы"),
            "biomes" to HelpCenterCommandText("Мир новых биомов", "Перейти в мир выживания с новыми биомами", "новые биомы необычный мир база"),
            "privat" to HelpCenterCommandText("Приват", "Поселения и защита территории", "земля поселение lands защита"),
            "shops" to HelpCenterCommandText("Магазины", "Магазины игроков и сервера", "рынок купить продать"),
            "sell" to HelpCenterCommandText("Быстрая продажа", "Продать подходящие предметы", "деньги рынок"),
            "auction" to HelpCenterCommandText("Аукцион", "Торговля предметами между игроками", "ah рынок купить продать"),
            "rank" to HelpCenterCommandText("Ранги", "Текущий ранг и следующие цели", "уровень развитие"),
            "rankup" to HelpCenterCommandText("Повысить ранг", "Проверить требования повышения", "уровень развитие"),
            "jobs" to HelpCenterCommandText("Работы", "Профессии и заработок", "деньги профессия"),
            "quests" to HelpCenterCommandText("Задания", "Активные задания и награды", "квест развитие"),
            "skills" to HelpCenterCommandText("Навыки", "Навыки и их развитие", "auraskills способности развитие"),
            "notes" to HelpCenterCommandText("Заметки", "Ваши серверные заметки", "список записи"),
            "donate" to HelpCenterCommandText("Поддержка и VIP", "Возможности поддержки сервера", "донат привилегии"),
        )

        internal val INTENTS = linkedMapOf(
            "my" to HelpCenterIntentText(
                "Про меня",
                "Открыть личный профиль, дома, земли и прогресс",
                "мой профиль баланс деньги координаты где я мои данные",
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
