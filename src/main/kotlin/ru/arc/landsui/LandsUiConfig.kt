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
            "root-title" to "<#20252b><bold>Поселения",
            "root-body" to "<#fff0d8>Здесь собраны ваши земли и основные действия.<newline><#8c8c8c>Поселений: <#f4bd6a><count>",
            "root-empty" to "<#8c8c8c>У вас пока нет поселений. Создайте первое ниже.",
            "land-label" to "<#f4bd6a><bold><land>",
            "land-tooltip" to "<#fff0d8><chunks> чанков · <members>/<max_members> участников · <balance> монет",
            "create-label" to "<#f4bd6a><bold>Создать поселение",
            "create-tooltip" to "<#fff0d8>Введите название прямо в диалоге.",
            "guide-label" to "<#fff0d8>Гайд и команды",
            "guide-tooltip" to "<#fff0d8>Короткий маршрут и ручные команды Lands.",
            "back-label" to "<#8c8c8c>Назад",
            "details-title" to "<#20252b><bold><land>",
            "details-body" to "<#fff0d8><role><newline><#8c8c8c>Территория: <#f4bd6a><chunks> чанков<newline><#8c8c8c>Участники: <#f4bd6a><members>/<max_members><newline><#8c8c8c>Баланс: <#f4bd6a><balance>",
            "role-owner" to "Владелец поселения",
            "role-member" to "Участник поселения",
            "open-lands-label" to "<#f4bd6a><bold>Настройки Lands",
            "open-lands-tooltip" to "<#fff0d8>Открыть штатное меню этого поселения.",
            "members-label" to "<#fff0d8>Участники",
            "territory-label" to "<#fff0d8>Территория и точка дома",
            "rename-label" to "<#fff0d8>Переименовать",
            "delete-label" to "<#c42323><bold>Удалить поселение",
            "name-input" to "<#fff0d8>Название",
            "player-input" to "<#fff0d8>Ник игрока",
            "submit-label" to "<#f4bd6a><bold>Продолжить",
            "create-title" to "<#20252b><bold>Новое поселение",
            "create-body" to "<#fff0d8>Название: буквы, цифры, <#f4bd6a>_ <#fff0d8>или <#f4bd6a>-<#fff0d8>.<newline><#8c8c8c>Пробелы не поддерживаются.",
            "rename-title" to "<#20252b><bold>Переименовать <land>",
            "rename-body" to "<#fff0d8>Введите новое название поселения.",
            "members-title" to "<#20252b><bold>Участники · <land>",
            "members-body" to "<#fff0d8>Сейчас в поселении: <#f4bd6a><members>/<max_members><#fff0d8>.<newline><#8c8c8c>Нажмите на игрока, чтобы перейти к подтверждению удаления.",
            "add-member-label" to "<#f4bd6a><bold>Добавить игрока",
            "member-label" to "<#fff0d8><player>",
            "member-tooltip" to "<#fff0d8>Убрать <player> из поселения.",
            "add-title" to "<#20252b><bold>Добавить · <land>",
            "add-body" to "<#fff0d8>Введите точный ник или выберите игрока онлайн.<newline><#8c8c8c>Показано до <limit> доступных игроков.",
            "candidate-label" to "<#fff0d8><player>",
            "remove-title" to "<#20252b><bold>Убрать участника",
            "remove-body" to "<#fff0d8>Убрать <#f4bd6a><player> <#fff0d8>из поселения <#f4bd6a><land><#fff0d8>?",
            "remove-confirm-label" to "<#c42323><bold>Убрать <player>",
            "territory-title" to "<#20252b><bold>Территория · <land>",
            "territory-body" to "<#fff0d8>Действия применяются к поселению <#f4bd6a><land><#fff0d8>.<newline><#8c8c8c>Для чанков стойте внутри нужного чанка.",
            "claim-label" to "<#f4bd6a><bold>Заприватить чанк",
            "unclaim-label" to "<#fff0d8>Освободить чанк",
            "setspawn-label" to "<#fff0d8>Поставить точку дома",
            "spawn-label" to "<#fff0d8>Телепорт домой",
            "areas-label" to "<#fff0d8>Дополнительные регионы",
            "mainblock-label" to "<#fff0d8>Главный блок и голограмма",
            "mainblock-title" to "<#20252b><bold>Главный блок · <land>",
            "mainblock-body" to "<#f4bd6a>Чтобы перенести:<newline><#fff0d8>1. Откройте главный блок поселения.<newline>2. Нажмите <#f4bd6a>ЛКМ <#fff0d8>по колоколу — убрать или перенести.<newline>3. Поставьте блок заново в меню поселения.<newline><#8c8c8c>Повторная установка может стоить денег.<newline><newline><#f4bd6a>ПКМ <#fff0d8>по колоколу включает или выключает голограмму.",
            "guide-title" to "<#20252b><bold>Приваты · короткий гайд",
            "guide-body" to "<#f4bd6a>Создать: <#fff0d8>/lands create НАЗВАНИЕ<newline><#f4bd6a>Выбрать вручную: <#fff0d8>/lands edit НАЗВАНИЕ<newline><#f4bd6a>Приват чанка: <#fff0d8>/lands land claim<newline><#f4bd6a>Пригласить: <#fff0d8>/lands land member add НИК<newline><#f4bd6a>Удалить: <#fff0d8>/lands land delete<newline><newline><#8c8c8c>В меню поселение выбирается автоматически. Перенос главного блока находится в разделе территории.",
            "danger-title" to "<#20252b><bold>Удаление · <land>",
            "danger-body" to "<#c42323><bold>Это необратимое действие.<newline><#fff0d8>Будет запущено штатное удаление Lands для <#f4bd6a><land><#fff0d8>.",
            "delete-confirm-label" to "<#c42323><bold>Удалить навсегда",
            "invalid-name" to "<#c42323>Название недопустимо. Используйте буквы, цифры, _ или - без пробелов.",
            "invalid-player" to "<#c42323>Ник должен содержать 3–16 латинских букв, цифр или _.",
            "land-gone" to "<#c42323>Поселение больше недоступно. Список обновлён.",
            "action-failed" to "<#c42323>Команда Lands не выполнилась.",
        )
    }
}
