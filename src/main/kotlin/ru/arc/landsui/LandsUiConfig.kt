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
            "root-title" to "<#7fe38b>Поселения",
            "root-body" to "<#f5fbff>Выберите поселение — оно станет текущим для действий.<newline><#7fe38b>● <#9db0ba>Текущее  <#f5fbff><selected><newline><#46d9ee>◆ <#9db0ba>Всего  <#f5fbff><count>",
            "root-empty" to "<#9aaab2>Поселений пока нет. Создайте первое в чанке, где стоите.",
            "selected-none" to "не выбрано",
            "land-label" to "<#4dd8f0>○ <land>",
            "land-selected-label" to "<#5ee39c>● <land>",
            "land-tooltip" to "<#e5f1f5><chunks>/<max_chunks> чанков · <members>/<max_members> участников · <balance> монет",
            "create-label" to "<#ffc247>Создать поселение",
            "create-tooltip" to "<#e5f1f5>Создать поселение в текущем чанке.",
            "guide-label" to "<#4dd8f0>Как всё работает",
            "guide-tooltip" to "<#e5f1f5>Создание, расширение, участники и команды.",
            "help-label" to "<#9aaab2>К общей помощи",
            "help-tooltip" to "<#e5f1f5>Вернуться в главное меню помощи.",
            "my-lands-label" to "<#4dd8f0>Мои поселения",
            "back-label" to "<#9aaab2>Назад",
            "invite-picker-title" to "<#7fe38b>Пригласить в поселение",
            "invite-picker-body" to "<#f5fbff>В какое поселение пригласить игрока <#46d9ee><player><#f5fbff>?<newline><#9db0ba>Команда будет выполнена именно для выбранного поселения.",
            "invite-none" to "<#ff6b61>Нет поселения, куда можно пригласить <player>.",

            "details-title" to "<#7fe38b><land>",
            "details-body" to "<#7fe38b>● <#9db0ba>Роль  <#f5fbff><role><newline><#46d9ee>◆ <#9db0ba>Территория  <#f5fbff><chunks>/<max_chunks> чанков<newline><#7fe38b>◆ <#9db0ba>Участники  <#f5fbff><members>/<max_members><newline><#ffad52>◆ <#9db0ba>Баланс  <#f5fbff><balance> монет",
            "role-owner" to "владелец",
            "role-member" to "участник",
            "open-lands-label" to "<#4dd8f0>Расширенные настройки",
            "open-lands-tooltip" to "<#e5f1f5>Открыть штатные настройки выбранного поселения.",
            "members-label" to "<#4dd8f0>Участники",
            "members-tooltip" to "<#e5f1f5>Добавить или исключить игроков.",
            "territory-label" to "<#4dd8f0>Территория и дом",
            "territory-tooltip" to "<#e5f1f5>Чанки, точка дома и главный блок.",
            "rename-label" to "<#e5f1f5>Переименовать",
            "rename-tooltip" to "<#e5f1f5>Изменить название выбранного поселения.",
            "delete-label" to "<#d96b63><bold>Удалить поселение",
            "delete-tooltip" to "<#e5f1f5>Удалить именно выбранное поселение с подтверждением.",

            "name-input" to "<#e5f1f5>Название",
            "player-input" to "<#e5f1f5>Ник игрока",
            "submit-label" to "<#ffc247>Продолжить",
            "create-title" to "<#ffca55>Новое поселение",
            "create-body" to "<#e5f1f5>Поселение появится там, где вы стоите.<newline><#9aaab2>Первый чанк защищается автоматически и бесплатно. Новое поселение сразу станет текущим.<newline><newline><#e5f1f5>Название: 5–24 символа, буквы, цифры, _ или - без пробелов.",
            "created-title" to "<#7fe38b>Поселение создано",
            "created-body" to "<#5ee39c><land><newline><#e5f1f5>Ваш первый чанк уже защищён. Это поселение выбрано текущим.<newline><#9aaab2>Территория: <#e5f1f5><chunks>/<max_chunks> чанков<newline><newline><#e5f1f5>Для расширения встаньте в соседний чанк и нажмите кнопку ниже.",
            "created-claim-label" to "<#ffc247>Присоединить текущий чанк",
            "created-details-label" to "<#4dd8f0>Открыть поселение",
            "create-not-found" to "<#d96b63>Поселение могло создаться, но меню не успело его определить. Список обновлён.",
            "rename-title" to "<#ffca55>Переименовать · <land>",
            "rename-body" to "<#e5f1f5>Введите новое название: 5–24 символа без пробелов.",

            "members-title" to "<#46d9ee>Участники · <land>",
            "members-body" to "<#9aaab2>Занято мест: <#e5f1f5><members>/<max_members><newline><#e5f1f5>Выберите игрока, чтобы удалить его с подтверждением.",
            "add-member-label" to "<#ffc247>Добавить игрока",
            "member-label" to "<#4dd8f0><player>",
            "member-tooltip" to "<#e5f1f5>Убрать <player> из этого поселения.",
            "add-title" to "<#7fe38b>Добавить · <land>",
            "add-body" to "<#e5f1f5>Выберите игрока онлайн или введите точный ник.<newline><#9aaab2>Показано до <limit> доступных игроков.",
            "candidate-label" to "<#4dd8f0><player>",
            "remove-title" to "<#ff6b61>Убрать участника",
            "remove-body" to "<#e5f1f5>Убрать <#ffc247><player> <#e5f1f5>из поселения <#ffc247><land><#e5f1f5>?",
            "remove-confirm-label" to "<#d96b63><bold>Убрать <player>",

            "territory-title" to "<#7fe38b>Территория · <land>",
            "territory-body" to "<#e5f1f5>Все кнопки ниже относятся только к <#ffc247><land><#e5f1f5>.<newline><#9aaab2>Для присоединения встаньте в соседний свободный чанк.",
            "claim-label" to "<#ffc247>Присоединить текущий чанк",
            "claim-tooltip" to "<#e5f1f5>Защитить чанк, в котором вы стоите.",
            "unclaim-label" to "<#4dd8f0>Освободить текущий чанк",
            "unclaim-tooltip" to "<#e5f1f5>Снять защиту с чанка, в котором вы стоите.",
            "setspawn-label" to "<#4dd8f0>Поставить точку дома",
            "setspawn-tooltip" to "<#e5f1f5>Сохранить здесь точку дома поселения.",
            "spawn-label" to "<#4dd8f0>Телепорт домой",
            "spawn-tooltip" to "<#e5f1f5>Переместиться к точке дома поселения.",
            "areas-label" to "<#4dd8f0>Дополнительные регионы",
            "areas-tooltip" to "<#e5f1f5>Открыть дополнительные регионы Lands.",
            "mainblock-label" to "<#4dd8f0>Главный блок и голограмма",
            "mainblock-tooltip" to "<#e5f1f5>Инструкция по переносу блока и настройке голограммы.",
            "mainblock-title" to "<#46d9ee>Главный блок · <land>",
            "mainblock-body" to "<#e5f1f5>Чтобы перенести главный блок:<newline><#ffc247>1. <#e5f1f5>Откройте расширенные настройки.<newline><#ffc247>2. <#e5f1f5>Нажмите ЛКМ по колоколу и уберите блок.<newline><#ffc247>3. <#e5f1f5>В новом месте снова откройте меню и установите его.<newline><newline><#9aaab2>На сервере перенос бесплатный. ПКМ по колоколу переключает голограмму.",

            "guide-title" to "<#ffca55>Как работают поселения",
            "guide-body" to "<#e5f1f5>Текущее поселение — это цель команд Lands.<newline><#9aaab2>В этом меню оно всегда отмечено точкой ●, а при открытии карточки выбирается явно. Поэтому действие не уйдёт в другое поселение.<newline><newline><#e5f1f5>Выберите тему — внутри есть пошаговое объяснение и нужные кнопки.",
            "guide-create-label" to "<#4dd8f0>Создание",
            "guide-create-tooltip" to "<#e5f1f5>Как основать новое поселение.",
            "guide-expand-label" to "<#4dd8f0>Расширение территории",
            "guide-expand-tooltip" to "<#e5f1f5>Как правильно присоединять новые чанки.",
            "guide-members-label" to "<#4dd8f0>Участники",
            "guide-members-tooltip" to "<#e5f1f5>Как добавлять и исключать игроков.",
            "guide-commands-label" to "<#4dd8f0>Команды Lands",
            "guide-commands-tooltip" to "<#e5f1f5>Короткая памятка по ручным командам.",
            "guide-create-title" to "<#ffca55>Создание поселения",
            "guide-create-body" to "<#ffc247>1. <#e5f1f5>Встаньте в чанк будущей базы.<newline><#ffc247>2. <#e5f1f5>Нажмите «Создать поселение» и введите название.<newline><#ffc247>3. <#e5f1f5>Первый чанк присоединится бесплатно.<newline><#ffc247>4. <#e5f1f5>Новое поселение станет текущим и откроется отдельная карточка результата.<newline><newline><#9aaab2>Другие чанки не присоединяются автоматически.",
            "guide-expand-title" to "<#7fe38b>Расширение территории",
            "guide-expand-body" to "<#ffc247>1. <#e5f1f5>Откройте нужное поселение — оно станет текущим.<newline><#ffc247>2. <#e5f1f5>Встаньте в соседний свободный чанк.<newline><#ffc247>3. <#e5f1f5>Откройте «Территория и дом» и присоедините чанк.<newline><newline><#9aaab2>Первый чанк бесплатный. Следующие стоят монет, цена постепенно растёт. Лимит виден в карточке поселения.<newline><newline><#e5f1f5>Ручная команда: <#ffc247>/lands land claim<#e5f1f5>. Обычная /claim для расширения не подходит.",
            "guide-members-title" to "<#46d9ee>Участники",
            "guide-members-body" to "<#ffc247>Добавить:<#e5f1f5> откройте поселение → «Участники» → «Добавить игрока». Можно выбрать игрока онлайн или ввести точный ник.<newline><newline><#ffc247>Удалить:<#e5f1f5> нажмите участника в списке и подтвердите действие.<newline><newline><#9aaab2>Лимит участников показан рядом со списком.",
            "guide-commands-title" to "<#ffad52>Команды Lands",
            "guide-commands-body" to "<#9aaab2>Выбрать поселение<newline><#e5f1f5>/lands edit НАЗВАНИЕ<newline><newline><#9aaab2>Присоединить текущий чанк<newline><#e5f1f5>/lands land claim<newline><newline><#9aaab2>Добавить игрока<newline><#e5f1f5>/lands land member add НИК<newline><newline><#9aaab2>Удалить выбранное поселение<newline><#e5f1f5>/lands land delete<newline><newline><#d96b63>Перед удалением обязательно проверьте текущее поселение.",

            "danger-title" to "<#ff6b61>Удаление · <land>",
            "danger-body" to "<#d96b63><bold>Это необратимое действие.<newline><#e5f1f5>Будет удалено именно поселение <#ffc247><land><#e5f1f5>. Территория перестанет быть защищённой.",
            "delete-confirm-label" to "<#d96b63><bold>Удалить навсегда",
            "invalid-name" to "<#d96b63>Название: 5–24 символа, буквы, цифры, _ или - без пробелов.",
            "invalid-player" to "<#d96b63>Ник должен содержать 3–16 латинских букв, цифр или _.",
            "land-gone" to "<#d96b63>Поселение больше недоступно. Список обновлён.",
            "action-failed" to "<#d96b63>Команда Lands не выполнилась.",
        )
    }
}
