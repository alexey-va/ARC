package ru.arc.itemlore

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.util.UUID

internal data class ItemLoreSettings(
    val npcId: Int,
    val npcUuid: UUID?,
    val maxDistance: Double,
    val pricePerCharacterMinor: Long,
    val config: Config,
) {
    fun text(key: String): String = config.string("text.$key", DEFAULT_TEXT.getValue(key))

    companion object {
        private const val RESOURCE = "item-lore.yml"
        private val DEFAULT_TEXT = mapOf(
            "unavailable" to "<#e8dfd2>Лев сейчас не может открыть редактор. Попробуйте позже.",
            "missing-item" to "<#e8dfd2>Возьмите обычный предмет в основную руку и поговорите с Львом.",
            "protected-item" to "<#e8dfd2>Лев не меняет служебные и особые предметы.",
            "too-many-rows" to "<#e8dfd2>У предмета больше 32 строк описания. Лев не будет обрезать их.",
            "row-too-long" to "<#e8dfd2>Строка слишком длинная: до 80 видимых символов и 1024 знаков вместе с кодами.",
            "complex-edit" to "<#e8dfd2>Слишком много сложных изменений за один раз. Сохраните правки по нескольку строк. Деньги не списаны.",
            "input-invalid" to "<#e8dfd2>Используйте до 80 видимых символов в строке без переносов.",
            "stale-item" to "<#e8dfd2>Предмет или положение изменились. Начните у Льва заново.",
            "too-far" to "<#e8dfd2>Подойдите к Льву ближе, чтобы продолжить.",
            "editor-title" to "<#e8dfd2>Описание предмета",
            "editor-body" to "<#e8dfd2>Строки — кнопкой ниже, до 80 знаков.\n<white><price></white> за правку символа × предметы в стопке.\nКоды оформления тоже считаются.",
            "color-black" to "<white>&0 <black>Аа</black> чёрный</white>",
            "color-dark-blue" to "<white>&1 <dark_blue>Аа</dark_blue> тёмно-синий</white>",
            "color-dark-green" to "<white>&2 <dark_green>Аа</dark_green> тёмно-зелёный</white>",
            "color-dark-aqua" to "<white>&3 <dark_aqua>Аа</dark_aqua> бирюзовый</white>",
            "color-dark-red" to "<white>&4 <dark_red>Аа</dark_red> тёмно-красный</white>",
            "color-dark-purple" to "<white>&5 <dark_purple>Аа</dark_purple> фиолетовый</white>",
            "color-gold" to "<white>&6 <gold>Аа</gold> золотой</white>",
            "color-gray" to "<white>&7 <gray>Аа</gray> серый</white>",
            "color-dark-gray" to "<white>&8 <dark_gray>Аа</dark_gray> тёмно-серый</white>",
            "color-blue" to "<white>&9 <blue>Аа</blue> синий</white>",
            "color-green" to "<white>&a <green>Аа</green> зелёный</white>",
            "color-aqua" to "<white>&b <aqua>Аа</aqua> голубой</white>",
            "color-red" to "<white>&c <red>Аа</red> красный</white>",
            "color-light-purple" to "<white>&d <light_purple>Аа</light_purple> розовый</white>",
            "color-yellow" to "<white>&e <yellow>Аа</yellow> жёлтый</white>",
            "color-white" to "<white>&f <white>Аа</white> белый</white>",
            "color-obfuscated" to "<white>&k случайные знаки <obfuscated>Аа</obfuscated></white>",
            "color-bold" to "<white>&l <bold>жирный</bold></white>",
            "color-strikethrough" to "<white>&m <strikethrough>зачёркнутый</strikethrough></white>",
            "color-underlined" to "<white>&n <underlined>подчёркнутый</underlined></white>",
            "color-italic" to "<white>&o <italic>курсив</italic></white>",
            "color-reset" to "<white>&r сброс всего</white>",
            "color-hex" to "<white>&#RRGGBB HEX-цвет</white>",
            "color-ampersand" to "<white>&& знак &</white>",
            "format-example" to "<#e8dfd2>Код — перед текстом. Цвет и эффект можно сочетать.\n<white>&a&lМеч&r</white> → <green><bold>Меч</bold></green>\nНовый цвет снимает эффекты; &r сбрасывает всё.\n<white>&#FFAA00Текст</white> — свой HEX-цвет.",
            "line-label" to "<white>Строка <number>",
            "preview-label" to "<#c4a7e7>Предпросмотр ›",
            "editor-back" to "<#92bed8>Закрыть",
            "add-row" to "<#c4a7e7>Добавить строку",
            "remove-row" to "<#ff6b61>Удалить последнюю строку",
            "row-limit" to "<#e8dfd2>Добавлено 32 строки — это предел одного описания.",
            "preview-title" to "<#e8dfd2>Проверьте описание",
            "preview-body" to "<#e8dfd2>Стоимость: <white><price></white>.",
            "preview-empty" to "<#e8dfd2>После сохранения описание будет очищено.",
            "preview-back" to "<#92bed8>Изменить строки",
            "preview-confirm" to "<#9bd48d>Дальше",
            "confirm-title" to "<#e8dfd2>Подтвердить сохранение",
            "confirm-body" to "<#e8dfd2>Списание: <white><price></white>. Изменение будет внесено в предмет в основной руке.",
            "confirm-clear" to "<#e8dfd2>Описание будет полностью очищено. Списание: <white><price></white>. Продолжить?",
            "confirm-back" to "<#92bed8>Назад к просмотру",
            "save" to "<#9bd48d>Сохранить за <price>",
            "saving-title" to "<#e8dfd2>Сохраняем описание",
            "saving-body" to "<#e8dfd2>Лев проверяет оплату и предмет. Не убирайте предмет до завершения.",
            "saved-title" to "<#e8dfd2>Описание сохранено",
            "saved-body" to "<#e8dfd2>Списано: <white><price></white>.",
            "cancelled" to "<#e8dfd2>Сохранение отменено. Деньги не списаны.",
            "insufficient-funds" to "<#e8dfd2>Недостаточно монет. Деньги не списаны, описание не изменено.",
            "busy" to "<#e8dfd2>Предыдущая операция ещё сверяется. Повторно деньги не списываются.",
            "payment-unknown" to "<#e8dfd2>Оплата передана на ручную сверку. Повторно не подтверждайте её.",
            "payment-failed" to "<#e8dfd2>Не удалось безопасно сохранить описание. Деньги не списаны.",
            "unchanged" to "<#e8dfd2>В описании нет изменений.",
        )

        fun load(dataPath: Path): ItemLoreSettings {
            val config = ConfigManager.ofModule(dataPath, RESOURCE)
            config.mergeMissingFromBundled("modules/$RESOURCE")
            val npcId = config.integer("npc-id", -1)
            require(npcId == -1 || npcId > 0) { "Item lore npc-id must be -1 or a positive Citizens id" }
            val uuidText = config.string("npc-uuid", "").trim()
            val uuid = uuidText.takeIf(String::isNotEmpty)?.let(UUID::fromString)
            val maxDistance = config.double("max-distance", 5.0)
            require(maxDistance in 1.0..8.0) { "Item lore max-distance must be in 1..8 blocks" }
            val rate = BigDecimal(config.string("price-per-character", "5.00").trim())
                .movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
            require(rate in 1L..10_000_000L) { "Item lore price-per-character is outside the allowed range" }
            return ItemLoreSettings(npcId, uuid, maxDistance, rate, config)
        }
    }
}
