package ru.arc.landsui

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

data class LandsUiSettings(
    val enabled: Boolean,
    val maxListedPlayers: Int,
    private val text: Map<String, String>,
) {
    fun text(key: String): String = text.getValue(key)
}

class LandsUiConfig(private val config: Config) {
    fun snapshot(): LandsUiSettings {
        val maxListedPlayers = config.integer("limits.max-listed-players", 12)
        require(maxListedPlayers in 1..32) { "Lands UI max-listed-players must be in 1..32" }
        return LandsUiSettings(
            enabled = config.bool("enabled", true),
            maxListedPlayers = maxListedPlayers,
            text = DEFAULT_TEXT.mapValues { (key, fallback) ->
                config.string("text.$key", fallback).also { value ->
                    require(value.isNotBlank()) { "Lands UI text '$key' cannot be blank" }
                    require(value.length <= 2_000) { "Lands UI text '$key' is too long" }
                }
            },
        )
    }

    companion object {
        private const val RESOURCE = "lands-ui.yml"

        fun load(dataPath: Path): LandsUiConfig {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            source.mergeMissingFromBundled("modules/$RESOURCE")
            return LandsUiConfig(source)
        }

        private val DEFAULT_TEXT = linkedMapOf(
            "root-title" to "<#f4bd6a><bold>Поселения",
            "root-body" to "<#fff4df>Выберите поселение — оно станет текущим для действий.<newline><#b7a899>Поселений: <#fff4df><count>   <#b7a899>Текущее: <#f4bd6a><selected>",
            "root-empty" to "<#b7a899>Поселений пока нет. Создайте первое в чанке, где стоите.",
            "selected-none" to "не выбрано",
            "land-label" to "<#fff4df><bold>○ <land>",
            "land-selected-label" to "<#f4bd6a><bold>● <land>",
            "land-tooltip" to "<#fff4df><chunks>/<max_chunks> чанков · <members>/<max_members> участников · <balance> монет",
            "create-label" to "<#f4bd6a><bold>Создать поселение",
            "create-tooltip" to "<#fff4df>Создать поселение в текущем чанке.",
            "guide-label" to "<#fff4df><bold>Как всё работает",
            "guide-tooltip" to "<#fff4df>Создание, расширение, участники и команды.",
            "help-label" to "<#fff4df><bold>К общей помощи",
            "help-tooltip" to "<#fff4df>Вернуться в главное меню помощи.",
            "my-lands-label" to "<#fff4df><bold>Мои поселения",
            "back-label" to "<#b7a899>Назад",

            "details-title" to "<#f4bd6a><bold><land>",
            "details-body" to "<#f4bd6a>● Текущее поселение<newline><#b7a899>Роль  <#fff4df><role><newline><#b7a899>Территория  <#fff4df><chunks>/<max_chunks> чанков<newline><#b7a899>Участники  <#fff4df><members>/<max_members><newline><#b7a899>Баланс  <#fff4df><balance> монет",
            "role-owner" to "владелец",
            "role-member" to "участник",
            "open-lands-label" to "<#fff4df><bold>Расширенные настройки",
            "open-lands-tooltip" to "<#fff4df>Открыть штатные настройки выбранного поселения.",
            "members-label" to "<#fff4df><bold>Участники",
            "territory-label" to "<#fff4df><bold>Территория и дом",
            "rename-label" to "<#fff4df><bold>Переименовать",
            "delete-label" to "<#d96b63><bold>Удалить поселение",

            "name-input" to "<#fff4df>Название",
            "player-input" to "<#fff4df>Ник игрока",
            "submit-label" to "<#f4bd6a><bold>Продолжить",
            "create-title" to "<#f4bd6a><bold>Новое поселение",
            "create-body" to "<#fff4df>Поселение появится там, где вы стоите.<newline><#b7a899>Первый чанк защищается автоматически и бесплатно. Новое поселение сразу станет текущим.<newline><newline><#fff4df>Название: 5–24 символа, буквы, цифры, _ или - без пробелов.",
            "created-title" to "<#f4bd6a><bold>Поселение создано",
            "created-body" to "<#f4bd6a><bold><land></bold><newline><#fff4df>Ваш первый чанк уже защищён. Это поселение выбрано текущим.<newline><#b7a899>Территория: <#fff4df><chunks>/<max_chunks> чанков<newline><newline><#fff4df>Для расширения встаньте в соседний чанк и нажмите кнопку ниже.",
            "created-claim-label" to "<#f4bd6a><bold>Присоединить текущий чанк",
            "created-details-label" to "<#fff4df><bold>Открыть поселение",
            "create-not-found" to "<#d96b63>Поселение могло создаться, но меню не успело его определить. Список обновлён.",
            "rename-title" to "<#f4bd6a><bold>Переименовать · <land>",
            "rename-body" to "<#fff4df>Введите новое название: 5–24 символа без пробелов.",

            "members-title" to "<#f4bd6a><bold>Участники · <land>",
            "members-body" to "<#b7a899>Занято мест: <#fff4df><members>/<max_members><newline><#fff4df>Выберите игрока, чтобы удалить его с подтверждением.",
            "add-member-label" to "<#f4bd6a><bold>Добавить игрока",
            "member-label" to "<#fff4df><bold><player>",
            "member-tooltip" to "<#fff4df>Убрать <player> из этого поселения.",
            "add-title" to "<#f4bd6a><bold>Добавить · <land>",
            "add-body" to "<#fff4df>Выберите игрока онлайн или введите точный ник.<newline><#b7a899>Показано до <limit> доступных игроков.",
            "candidate-label" to "<#fff4df><bold><player>",
            "remove-title" to "<#f4bd6a><bold>Убрать участника",
            "remove-body" to "<#fff4df>Убрать <#f4bd6a><player> <#fff4df>из поселения <#f4bd6a><land><#fff4df>?",
            "remove-confirm-label" to "<#d96b63><bold>Убрать <player>",

            "territory-title" to "<#f4bd6a><bold>Территория · <land>",
            "territory-body" to "<#fff4df>Все кнопки ниже относятся только к <#f4bd6a><land><#fff4df>.<newline><#b7a899>Для присоединения встаньте в соседний свободный чанк.",
            "claim-label" to "<#f4bd6a><bold>Присоединить текущий чанк",
            "unclaim-label" to "<#fff4df><bold>Освободить текущий чанк",
            "setspawn-label" to "<#fff4df><bold>Поставить точку дома",
            "spawn-label" to "<#fff4df><bold>Телепорт домой",
            "areas-label" to "<#fff4df><bold>Дополнительные регионы",
            "mainblock-label" to "<#fff4df><bold>Главный блок и голограмма",
            "mainblock-title" to "<#f4bd6a><bold>Главный блок · <land>",
            "mainblock-body" to "<#fff4df>Чтобы перенести главный блок:<newline><#f4bd6a>1. <#fff4df>Откройте расширенные настройки.<newline><#f4bd6a>2. <#fff4df>Нажмите ЛКМ по колоколу и уберите блок.<newline><#f4bd6a>3. <#fff4df>В новом месте снова откройте меню и установите его.<newline><newline><#b7a899>На сервере перенос бесплатный. ПКМ по колоколу переключает голограмму.",

            "guide-title" to "<#f4bd6a><bold>Как работают поселения",
            "guide-body" to "<#fff4df>Текущее поселение — это цель команд Lands.<newline><#b7a899>В этом меню оно всегда отмечено точкой ●, а при открытии карточки выбирается явно. Поэтому действие не уйдёт в другое поселение.<newline><newline><#fff4df>Выберите тему — внутри есть пошаговое объяснение и нужные кнопки.",
            "guide-create-label" to "<#fff4df><bold>Создание",
            "guide-expand-label" to "<#fff4df><bold>Расширение территории",
            "guide-members-label" to "<#fff4df><bold>Участники",
            "guide-commands-label" to "<#fff4df><bold>Команды Lands",
            "guide-create-title" to "<#f4bd6a><bold>Создание поселения",
            "guide-create-body" to "<#f4bd6a>1. <#fff4df>Встаньте в чанк будущей базы.<newline><#f4bd6a>2. <#fff4df>Нажмите «Создать поселение» и введите название.<newline><#f4bd6a>3. <#fff4df>Первый чанк присоединится бесплатно.<newline><#f4bd6a>4. <#fff4df>Новое поселение станет текущим и откроется отдельная карточка результата.<newline><newline><#b7a899>Другие чанки не присоединяются автоматически.",
            "guide-expand-title" to "<#f4bd6a><bold>Расширение территории",
            "guide-expand-body" to "<#f4bd6a>1. <#fff4df>Откройте нужное поселение — оно станет текущим.<newline><#f4bd6a>2. <#fff4df>Встаньте в соседний свободный чанк.<newline><#f4bd6a>3. <#fff4df>Откройте «Территория и дом» и присоедините чанк.<newline><newline><#b7a899>Первый чанк бесплатный. Следующие стоят монет, цена постепенно растёт. Лимит виден в карточке поселения.<newline><newline><#fff4df>Ручная команда: <#f4bd6a>/lands land claim<#fff4df>. Обычная /claim для расширения не подходит.",
            "guide-members-title" to "<#f4bd6a><bold>Участники",
            "guide-members-body" to "<#f4bd6a>Добавить:<#fff4df> откройте поселение → «Участники» → «Добавить игрока». Можно выбрать игрока онлайн или ввести точный ник.<newline><newline><#f4bd6a>Удалить:<#fff4df> нажмите участника в списке и подтвердите действие.<newline><newline><#b7a899>Лимит участников показан рядом со списком.",
            "guide-commands-title" to "<#f4bd6a><bold>Команды Lands",
            "guide-commands-body" to "<#b7a899>Выбрать поселение<newline><#fff4df>/lands edit НАЗВАНИЕ<newline><newline><#b7a899>Присоединить текущий чанк<newline><#fff4df>/lands land claim<newline><newline><#b7a899>Добавить игрока<newline><#fff4df>/lands land member add НИК<newline><newline><#b7a899>Удалить выбранное поселение<newline><#fff4df>/lands land delete<newline><newline><#d96b63>Перед удалением обязательно проверьте текущее поселение.",

            "danger-title" to "<#f4bd6a><bold>Удаление · <land>",
            "danger-body" to "<#d96b63><bold>Это необратимое действие.<newline><#fff4df>Будет удалено именно поселение <#f4bd6a><land><#fff4df>. Территория перестанет быть защищённой.",
            "delete-confirm-label" to "<#d96b63><bold>Удалить навсегда",
            "invalid-name" to "<#d96b63>Название: 5–24 символа, буквы, цифры, _ или - без пробелов.",
            "invalid-player" to "<#d96b63>Ник должен содержать 3–16 латинских букв, цифр или _.",
            "land-gone" to "<#d96b63>Поселение больше недоступно. Список обновлён.",
            "action-failed" to "<#d96b63>Команда Lands не выполнилась.",
        )
    }
}
