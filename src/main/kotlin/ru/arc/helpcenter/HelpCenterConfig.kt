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
            "root-title" to "<#ffc247><bold>Помощь и команды",
            "root-body" to "<#e5f1f5>Всё нужное для игры — в одном месте.<newline><#9aaab2>Выберите раздел или <#4f8cff>опишите задачу обычной фразой<#9aaab2>.",
            "my-label" to "<#4f8cff>Про меня",
            "my-tooltip" to "<#e5f1f5>Ваш профиль, дома, поселения и прогресс.",
            "guide-label" to "<#ffc247>С чего начать",
            "guide-tooltip" to "<#e5f1f5>Первые шаги без лишних команд.",
            "commands-label" to "<#4f8cff>Команды и поиск",
            "commands-tooltip" to "<#e5f1f5>Найти действие обычной фразой.",
            "travel-label" to "<#4dd8f0>Перемещения",
            "travel-tooltip" to "<#e5f1f5>Дома, миры, варпы и возврат.",
            "privat-label" to "<#5ee39c>Поселения",
            "privat-tooltip" to "<#e5f1f5>Создание, территория, участники и подробный гайд.",
            "main-menu-label" to "<#b6c4ca>Главное меню",
            "main-menu-tooltip" to "<#e5f1f5>Открыть остальные игровые разделы сервера.",
            "back-label" to "<#b6c4ca>Назад",
            "root-label" to "<#b6c4ca>К разделам",
            "not-available" to "—",
            "my-title" to "<#ffc247><bold>Про меня",
            "my-loading" to "<#e5f1f5>Собираю ваш профиль…",
            "my-identity" to "<#ffc247><bold><player></bold><newline><#9aaab2>Сервер: <#4dd8f0><server><newline><#9aaab2>Ранг: <#4f8cff><rank>",
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
            "guide-title" to "<#ffc247><bold>С чего начать",
            "guide-body" to "<#ffc247><bold>1 · Стартовый набор</bold><newline><#fff4df>Получите первые инструменты и припасы.<newline><newline><#ffc247><bold>2 · Выберите мир</bold><newline><#fff4df>Обычный мир, мир добычи или мир новых биомов.<newline><newline><#ffc247><bold>3 · Начните зарабатывать</bold><newline><#fff4df>Выберите работу и получайте деньги за привычные действия.<newline><newline><#ffc247><bold>4 · Дом и поселение</bold><newline><#fff4df>Сохраните точку дома и защитите территорию поселением.",
            "kit-label" to "<#ffc247><bold>Получить набор",
            "vanilla-label" to "<#4dd8f0>Обычный мир",
            "mining-label" to "<#4dd8f0>Мир добычи",
            "biomes-label" to "<#5ee39c>Мир новых биомов",
            "jobs-label" to "<#ffc247>Выбрать работу",
            "rules-label" to "<#4f8cff>Правила",
            "commands-title" to "<#ffc247><bold>Команды и поиск",
            "commands-body" to "<#fff4df>Опишите, что хотите сделать, обычной фразой.<newline><#b6c4ca>Поиск понимает формы слов, близкие значения и небольшие опечатки.",
            "search-input" to "<#fff0d8>Что вы хотите сделать?",
            "search-label" to "<#ffc247><bold>Найти",
            "search-tooltip" to "<#fff0d8>Например: удалить поселение, перенести дом, добавить друга.",
            "search-title" to "<#ffc247><bold>Результаты поиска",
            "search-body" to "<#b6c4ca>Запрос  <#fff4df><query><newline><#b6c4ca>Найдено  <#fff4df><count>",
            "search-empty" to "<#fff0d8>Не понял запрос. Опишите действие и объект: «перенести дом» или «добавить игрока».",
            "search-result-tooltip" to "<#fff0d8><description><newline><#8d7768>Открыть нужный раздел",
            "category-start-label" to "<#ffc247>Начало игры",
            "category-travel-label" to "<#4dd8f0>Перемещения",
            "category-protection-label" to "<#5ee39c>Защита",
            "category-trade-label" to "<#ff9f43>Торговля",
            "category-progress-label" to "<#a7e75f>Развитие",
            "category-social-label" to "<#38d9c3>Общение и сервер",
            "category-trade-tooltip" to "<#e5f1f5>Магазины, быстрая продажа и аукцион.",
            "category-progress-tooltip" to "<#e5f1f5>Ранг, работы, задания и навыки.",
            "category-social-tooltip" to "<#e5f1f5>Заметки и поддержка сервера.",
            "category-start-title" to "<#ffc247><bold>Начало игры",
            "category-travel-title" to "<#ffc247><bold>Команды перемещения",
            "category-protection-title" to "<#ffc247><bold>Защита",
            "category-trade-title" to "<#ffc247><bold>Торговля",
            "category-progress-title" to "<#ffc247><bold>Развитие",
            "category-social-title" to "<#ffc247><bold>Общение и сервер",
            "category-body" to "<#fff0d8>Нажмите действие — команда выполнится от вашего имени.",
            "command-label" to "<#e5f1f5><label>",
            "command-tooltip" to "<#fff0d8><description><newline><#8d7768>/<command>",
            "travel-title" to "<#ffc247><bold>Перемещения",
            "travel-loading" to "<#fff0d8>Загружаю ваши дома…",
            "travel-body" to "<#8d7768>Дома: <#ffc247><homes>/<max_homes><newline><#fff0d8>Выберите дом или другое направление.",
            "travel-empty" to "<#fff0d8>У вас пока нет домов. Создайте первый в текущей точке.",
            "travel-limit" to "<#8d7768>Свободных слотов нет. Удалите или перенесите существующий дом.",
            "travel-error" to "<#fff0d8>Дома сейчас не загрузились, но остальные перемещения доступны.",
            "home-label" to "<#4dd8f0><home>",
            "home-tooltip" to "<#fff0d8><server> · <world><newline><#8d7768><x>, <y>, <z>",
            "create-home-label" to "<#ffc247><bold>Новый дом",
            "create-home-tooltip" to "<#fff0d8>Сохранить текущую точку.",
            "home-create-title" to "<#ffc247><bold>Новый дом",
            "home-create-body" to "<#fff0d8>Введите короткое имя без пробелов.<newline><#8d7768>Дом будет создан в вашей текущей точке.",
            "home-name-input" to "<#fff0d8>Название дома",
            "home-create-submit" to "<#ffc247><bold>Создать",
            "home-title" to "<#ffc247><bold>Дом · <home>",
            "home-body" to "<#8d7768>Сервер: <#fff0d8><server><newline><#8d7768>Мир: <#fff0d8><world><newline><#8d7768>Координаты: <#fff0d8><x>, <y>, <z>",
            "home-teleport-label" to "<#ffc247><bold>Телепортироваться",
            "home-teleport-tooltip" to "<#fff0d8>Переместиться в сохранённую точку.",
            "home-relocate-label" to "<#fff0d8>Перенести сюда",
            "home-relocate-tooltip" to "<#fff0d8>Заменить точку вашим текущим положением. Потребуется подтверждение.",
            "home-delete-label" to "<#c42323><bold>Удалить дом",
            "home-delete-tooltip" to "<#fff0d8>Удалить сохранённую точку. Потребуется подтверждение.",
            "home-relocate-title" to "<#ffc247><bold>Перенести · <home>",
            "home-relocate-body" to "<#fff0d8>Заменить сохранённую точку дома <#ffc247><home> <#fff0d8>вашим текущим положением?",
            "home-relocate-confirm" to "<#ffc247><bold>Перенести точку",
            "home-delete-title" to "<#ffc247><bold>Удаление · <home>",
            "home-delete-body" to "<#c42323><bold>Это необратимое действие.<newline><#fff0d8>Дом <#ffc247><home> <#fff0d8>будет удалён.",
            "home-delete-confirm" to "<#c42323><bold>Удалить навсегда",
            "warps-label" to "<#4dd8f0>Варпы игроков",
            "spawn-label" to "<#4dd8f0>Спавн",
            "rtp-label" to "<#4dd8f0>Случайное место",
            "back-command-label" to "<#b6c4ca>Вернуться назад",
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
