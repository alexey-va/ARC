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
            "close-label" to "<#aaa49a>Закрыть",
            "create-submit-label" to "<#9bd48d>Создать поселение",
            "rename-submit-label" to "<#9bd48d>Сохранить название",
            "add-submit-label" to "<#9bd48d>Добавить игрока",
            "invite-land-label" to "<#9bd48d>Пригласить в <land>",
            "table-land-label" to "Поселение",
            "created-table-help" to "<#e8dfd2>Ваш первый чанк уже защищён. Это поселение выбрано текущим.<newline><newline>Для расширения встаньте в соседний чанк и нажмите кнопку ниже.",
            "table-label-heading" to "Параметр",
            "table-value-heading" to "Значение",
            "table-role-label" to "Роль",
            "table-territory-label" to "Территория",
            "table-members-label" to "Участники",
            "table-balance-label" to "Баланс",
            "table-territory-value" to "<used> / <maximum> чанков",
            "table-slots-value" to "<used> / <maximum>",
            "table-coins-value" to "<value> <white>💰</white>",
            "root-title" to "<#9bd48d>Приват",
            "root-body" to "<#e8dfd2>Выберите поселение — оно станет текущим для действий.<newline><#aaa49a>Текущее: <#9bd48d><selected><newline><#aaa49a>Всего поселений: <#e8dfd2><count>",
            "root-empty" to "<#aaa49a>Поселений пока нет. Создайте первое в чанке, где стоите.",
            "selected-none" to "не выбрано",
            "land-label" to "<white>○ <land> <#c4a7e7>›",
            "land-selected-label" to "<#9bd48d>✔ <land> <#c4a7e7>›",
            "land-tooltip" to "<#e8dfd2><chunks>/<max_chunks> чанков · <members>/<max_members> участников · <balance> монет",
            "create-label" to "<#c4a7e7>Создать поселение ›",
            "create-tooltip" to "<#e8dfd2>Создать поселение в текущем чанке.",
            "guide-label" to "<#86dcf1>Как всё работает ›",
            "guide-tooltip" to "<#e8dfd2>Создание, расширение, участники и команды.",
            "my-lands-label" to "<#c4a7e7>Мои поселения ›",
            "back-label" to "<#aaa49a>‹ Назад",
            "invite-picker-title" to "<#9bd48d>Пригласить в поселение",
            "invite-picker-body" to "<#e8dfd2>В какое поселение пригласить игрока <#86dcf1><player><#e8dfd2>?<newline><#aaa49a>Команда будет выполнена именно для выбранного поселения.",
            "invite-none" to "<#ff6b61>Нет поселения, куда можно пригласить <player>.",

            "details-title" to "<#9bd48d><land>",
            "details-body" to "<#9bd48d>● <#aaa49a>Роль  <#e8dfd2><role><newline><#86dcf1>◆ <#aaa49a>Территория  <#e8dfd2><chunks>/<max_chunks> чанков<newline><#9bd48d>◆ <#aaa49a>Участники  <#e8dfd2><members>/<max_members><newline><#d7b486>◆ <#aaa49a>Баланс  <#e8dfd2><balance> монет",
            "role-owner" to "владелец",
            "role-member" to "участник",
            "open-lands-label" to "<#c4a7e7>Расширенные настройки ›",
            "open-lands-tooltip" to "<#e8dfd2>Открыть штатные настройки выбранного поселения.",
            "members-label" to "<#f3a2c9>Участники ›",
            "members-tooltip" to "<#e8dfd2>Добавить или исключить игроков.",
            "territory-label" to "<#9bd48d>Территория и дом ›",
            "territory-tooltip" to "<#e8dfd2>Чанки, точка дома и главный блок.",
            "region-tool-label" to "<#86dcf1>Мультитул регионов ›",
            "region-tool-tooltip" to "<#e8dfd2>Выделить регион внутри этого поселения.",
            "region-tool-name" to "<#86dcf1>Мультитул регионов",
            "region-tool-lore-first" to "<#e8dfd2>ЛКМ по земле — первый угол",
            "region-tool-lore-second" to "<#e8dfd2>ПКМ по земле — второй угол",
            "region-tool-lore-menu" to "<#aaa49a>Shift + ЛКМ по кнопке — создать",
            "region-tool-lore-reset" to "<#aaa49a>Shift + ПКМ — сбросить выделение",
            "region-inventory-full" to "<#ff6b61>Нет места в инвентаре для мультитула регионов.",
            "region-equipped" to "<#86dcf1>Мультитул регионов выдан. Выберите два угла на земле.",
            "region-select-land" to "<#ff6b61>Сначала выберите поселение в меню привата.",
            "region-controls" to "<#aaa49a>ЛКМ/ПКМ — углы • Shift+ЛКМ по кнопке • Shift+ПКМ — сброс",
            "region-reset" to "<#aaa49a>Выделение сброшено.",
            "region-outside" to "<#ff6b61>Всё выделение должно быть внутри поселения <land>.",
            "region-no-permission" to "<#ff6b61>У вас нет права создавать регионы в этом поселении.",
            "region-working" to "<#d7b486>Сохраняем регион в Lands…",
            "region-native-pending" to "<#d7b486>Создание ещё не подтверждено.<newline>  Проверьте список регионов и сообщения Lands.",
            "region-first" to "<#86dcf1>ЛКМ по земле — выберите первый угол",
            "region-second" to "<#86dcf1>ПКМ по земле — выберите второй угол",
            "region-ready" to "<#9bd48d>Готово: <width>×<depth> блоков<newline><land> • на всю высоту мира",
            "region-current" to "<#86dcf1>Регион: <area>",
            "region-create-button" to "<#9bd48d>Shift+ЛКМ — создать регион",
            "region-list-button" to "<#86dcf1>Shift+ЛКМ — список регионов",
            "region-create-title" to "<#c4a7e7>Новый регион",
            "region-create-body" to "<#e8dfd2>Регион будет создан в поселении <#d7b486><land><#e8dfd2>.<newline><#aaa49a>Размер: <#e8dfd2><width>×<depth> блоков<newline><#aaa49a>Высота: <#e8dfd2>весь диапазон мира<newline><#aaa49a>Рамка показывает горизонтальное выделение.",
            "region-name-input" to "<#e8dfd2>Название региона",
            "region-confirm" to "<#9bd48d>Создать регион",
            "region-invalid-name" to "<#ff6b61>Название: 1–24 символа, буквы, цифры, _ или - без пробелов.",
            "region-stale" to "<#ff6b61>Выделение устарело. Выберите углы заново.",
            "region-name-used" to "<#ff6b61>Регион с таким названием уже существует.",
            "region-native-failed" to "<#ff6b61>Не удалось отправить запрос в Lands.<newline>  Попробуйте ещё раз.",
            "region-created" to "<#9bd48d>Регион <area> создан в поселении <land>.",
            "region-list-title" to "<#86dcf1>Регионы · <land>",
            "region-list-body" to "<#e8dfd2>Выберите регион, чтобы открыть его настройки Lands.",
            "region-list-empty" to "<#aaa49a>В этом поселении пока нет дополнительных регионов.",
            "region-entry" to "<#86dcf1><area> ›",
            "region-previous" to "<#aaa49a>‹ Назад",
            "region-next" to "<#aaa49a>Вперёд ›",
            "rename-label" to "<#c4a7e7>Переименовать ›",
            "rename-tooltip" to "<#e8dfd2>Изменить название выбранного поселения.",
            "delete-label" to "<#ff6b61>Удалить поселение ›",
            "delete-tooltip" to "<#e8dfd2>Удалить именно выбранное поселение с подтверждением.",

            "name-input" to "<#e8dfd2>Название",
            "player-input" to "<#e8dfd2>Ник игрока",
            "create-title" to "<#c4a7e7>Новое поселение",
            "create-body" to "<#e8dfd2>Поселение появится там, где вы стоите.<newline><#aaa49a>Первый чанк защищается автоматически и бесплатно. Новое поселение сразу станет текущим.<newline><newline><#e8dfd2>Название: 5–24 символа, буквы, цифры, _ или - без пробелов.",
            "created-title" to "<#9bd48d>Поселение создано",
            "created-body" to "<#9bd48d><land><newline><#e8dfd2>Ваш первый чанк уже защищён. Это поселение выбрано текущим.<newline><#aaa49a>Территория: <#e8dfd2><chunks>/<max_chunks> чанков<newline><newline><#e8dfd2>Для расширения встаньте в соседний чанк и нажмите кнопку ниже.",
            "created-claim-label" to "<#9bd48d>Присоединить текущий чанк",
            "created-details-label" to "<#c4a7e7>Открыть поселение ›",
            "create-not-found" to "<#ff6b61>Поселение могло создаться, но меню не успело его определить. Список обновлён.",
            "rename-title" to "<#c4a7e7>Переименовать · <land>",
            "rename-body" to "<#e8dfd2>Введите новое название: 5–24 символа без пробелов.",

            "members-title" to "<#f3a2c9>Участники · <land>",
            "members-body" to "<#aaa49a>Занято мест: <#e8dfd2><members>/<max_members><newline><#e8dfd2>Выберите игрока, чтобы удалить его с подтверждением.",
            "add-member-label" to "<#c4a7e7>Добавить игрока ›",
            "member-label" to "<#f3a2c9><player> ›",
            "member-tooltip" to "<#e8dfd2>Убрать <player> из этого поселения.",
            "add-title" to "<#c4a7e7>Добавить · <land>",
            "add-body" to "<#e8dfd2>Выберите игрока онлайн или введите точный ник.<newline><#aaa49a>Показано до <limit> доступных игроков.",
            "candidate-label" to "<#9bd48d>Добавить <player>",
            "remove-title" to "<#ff6b61>Убрать участника",
            "remove-body" to "<#e8dfd2>Убрать <#d7b486><player> <#e8dfd2>из поселения <#d7b486><land><#e8dfd2>?",
            "remove-confirm-label" to "<#ff6b61><bold>Убрать <player>",

            "territory-title" to "<#9bd48d>Территория · <land>",
            "territory-body" to "<#e8dfd2>Все кнопки ниже относятся только к <#d7b486><land><#e8dfd2>.<newline><#aaa49a>Для присоединения встаньте в соседний свободный чанк.",
            "claim-label" to "<#9bd48d>Присоединить текущий чанк",
            "claim-tooltip" to "<#e8dfd2>Защитить чанк, в котором вы стоите.",
            "unclaim-label" to "<#ff6b61>Освободить текущий чанк",
            "unclaim-tooltip" to "<#e8dfd2>Снять защиту с чанка, в котором вы стоите.",
            "setspawn-label" to "<#9bd48d>Поставить точку дома",
            "setspawn-tooltip" to "<#e8dfd2>Сохранить здесь точку дома поселения.",
            "spawn-label" to "<#92bed8>Телепорт домой ›",
            "spawn-tooltip" to "<#e8dfd2>Переместиться к точке дома поселения.",
            "areas-label" to "<#9bd48d>Дополнительные регионы ›",
            "areas-tooltip" to "<#e8dfd2>Открыть дополнительные регионы Lands.",
            "mainblock-label" to "<#86dcf1>Главный блок и голограмма ›",
            "mainblock-tooltip" to "<#e8dfd2>Инструкция по переносу блока и настройке голограммы.",
            "mainblock-title" to "<#86dcf1>Главный блок · <land>",
            "mainblock-body" to "<#e8dfd2>Чтобы перенести главный блок:<newline><#d7b486>1. <#e8dfd2>Откройте расширенные настройки.<newline><#d7b486>2. <#e8dfd2>Нажмите ЛКМ по колоколу и уберите блок.<newline><#d7b486>3. <#e8dfd2>В новом месте снова откройте меню и установите его.<newline><newline><#aaa49a>На сервере перенос бесплатный. ПКМ по колоколу переключает голограмму.",

            "guide-title" to "<#86dcf1>Как работают поселения",
            "guide-body" to "<#e8dfd2>Текущее поселение — это цель команд Lands.<newline><#aaa49a>В этом меню оно всегда отмечено галочкой ✔, а при открытии карточки выбирается явно. Поэтому действие не уйдёт в другое поселение.<newline><newline><#e8dfd2>Выберите тему — внутри есть пошаговое объяснение и нужные кнопки.",
            "guide-create-label" to "<#86dcf1>Создание ›",
            "guide-create-tooltip" to "<#e8dfd2>Как основать новое поселение.",
            "guide-expand-label" to "<#86dcf1>Расширение территории ›",
            "guide-expand-tooltip" to "<#e8dfd2>Как правильно присоединять новые чанки.",
            "guide-members-label" to "<#86dcf1>Участники ›",
            "guide-members-tooltip" to "<#e8dfd2>Как добавлять и исключать игроков.",
            "guide-commands-label" to "<#86dcf1>Команды Lands ›",
            "guide-commands-tooltip" to "<#e8dfd2>Короткая памятка по ручным командам.",
            "guide-create-title" to "<#86dcf1>Создание поселения",
            "guide-create-body" to "<#d7b486>1. <#e8dfd2>Встаньте в чанк будущей базы.<newline><#d7b486>2. <#e8dfd2>Нажмите «Создать поселение» и введите название.<newline><#d7b486>3. <#e8dfd2>Первый чанк присоединится бесплатно.<newline><#d7b486>4. <#e8dfd2>Новое поселение станет текущим и откроется отдельная карточка результата.<newline><newline><#aaa49a>Другие чанки не присоединяются автоматически.",
            "guide-expand-title" to "<#86dcf1>Расширение территории",
            "guide-expand-body" to "<#d7b486>1. <#e8dfd2>Откройте нужное поселение — оно станет текущим.<newline><#d7b486>2. <#e8dfd2>Встаньте в соседний свободный чанк.<newline><#d7b486>3. <#e8dfd2>Откройте «Территория и дом» и присоедините чанк.<newline><newline><#aaa49a>Первый чанк бесплатный. Следующие стоят монет, цена постепенно растёт. Лимит виден в карточке поселения.<newline><newline><#e8dfd2>Ручная команда: <#d7b486>/lands land claim<#e8dfd2>. Обычная /claim для расширения не подходит.",
            "guide-members-title" to "<#86dcf1>Участники",
            "guide-members-body" to "<#d7b486>Добавить:<#e8dfd2> откройте поселение → «Участники» → «Добавить игрока». Можно выбрать игрока онлайн или ввести точный ник.<newline><newline><#d7b486>Удалить:<#e8dfd2> нажмите участника в списке и подтвердите действие.<newline><newline><#aaa49a>Лимит участников показан рядом со списком.",
            "guide-commands-title" to "<#86dcf1>Команды Lands",
            "guide-commands-body" to "<#aaa49a>Выбрать поселение<newline><#e8dfd2>/lands edit НАЗВАНИЕ<newline><newline><#aaa49a>Присоединить текущий чанк<newline><#e8dfd2>/lands land claim<newline><newline><#aaa49a>Добавить игрока<newline><#e8dfd2>/lands land member add НИК<newline><newline><#aaa49a>Удалить выбранное поселение<newline><#e8dfd2>/lands land delete<newline><newline><#ff6b61>Перед удалением обязательно проверьте текущее поселение.",

            "danger-title" to "<#ff6b61>Удаление · <land>",
            "danger-body" to "<#ff6b61><bold>Это необратимое действие.<newline><#e8dfd2>Будет удалено именно поселение <#d7b486><land><#e8dfd2>. Территория перестанет быть защищённой.",
            "delete-confirm-label" to "<#ff6b61><bold>Удалить навсегда",
            "invalid-name" to "<#ff6b61>Название: 5–24 символа, буквы, цифры, _ или - без пробелов.",
            "invalid-player" to "<#ff6b61>Ник должен содержать 3–16 латинских букв, цифр или _.",
            "land-gone" to "<#ff6b61>Поселение больше недоступно. Список обновлён.",
            "action-failed" to "<#ff6b61>Команда Lands не выполнилась.",
        )
    }
}
