package ru.arc.helpcenter

import net.advancedplugins.ae.api.AEAPI
import org.bukkit.Bukkit
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.Locale
import java.util.logging.Level

internal data class HelpCenterEnchantment(
    val id: String,
    val name: String,
    val description: String,
    val maxLevelDescription: String,
    val materials: Set<String>,
    val group: String,
    val maxLevel: Int,
    val availableFromEnchanter: Boolean,
) {
    internal val searchText: String = listOf(id, name, description, maxLevelDescription, group, materials.joinToString(" "))
        .joinToString(" ").lowercase(Locale.ROOT)
}

internal data class HelpCenterEnchantmentsCatalog(
    val available: Boolean,
    val entries: List<HelpCenterEnchantment>,
) {
    fun search(query: String, equipment: HelpCenterEnchantmentsEquipment = HelpCenterEnchantmentsEquipment.ALL): List<HelpCenterEnchantment> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        return entries.asSequence()
            .filter { equipment.matches(it.materials) }
            .filter { entry -> terms.all(entry.searchText::contains) }
            .sortedWith(compareBy<HelpCenterEnchantment>({ it.name.lowercase(Locale.ROOT) }, { it.id.lowercase(Locale.ROOT) }))
            .toList()
    }

    companion object {
        private var apiFailureLogged = false

        fun unavailable() = HelpCenterEnchantmentsCatalog(available = false, entries = emptyList())

        /**
         * Capture the loaded AE registry once when the player opens the guide. This deliberately
         * does not read AE YAML files or construct fake items while rendering a page.
         */
        fun fromAeApi(): HelpCenterEnchantmentsCatalog {
            if (!Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")) return unavailable()

            return try {
                val descriptionAccess = AeEnchantDescriptionAccess.binding
                val entries = AEAPI.getAllEnchantments().orEmpty().distinct().map { id ->
                    val maxLevel = AEAPI.getHighestEnchantmentLevel(id).coerceAtLeast(1)
                    val displayLore = AEAPI.getEnchantLore(id, maxLevel).orEmpty()
                    val description = descriptionAccess.read(id, maxLevel)
                    val materials = runCatching { AEAPI.getMaterialsForEnchantment(id).orEmpty() }
                        .getOrDefault(emptyList())
                    fromApiData(
                        id = id,
                        displayLore = displayLore,
                        description = description.general.orEmpty(),
                        maxLevelDescription = description.atMaxLevel.orEmpty(),
                        materials = materials.toSet(),
                        group = AEAPI.getGroup(id).orEmpty().plainApiText(),
                        maxLevel = maxLevel,
                        availableFromEnchanter = description.availableFromEnchanter,
                        baseDisplayName = description.baseDisplayName,
                    )
                }
                apiFailureLogged = false
                HelpCenterEnchantmentsCatalog(available = true, entries = entries)
            } catch (failure: Throwable) {
                logApiFailure(failure)
                unavailable()
            }
        }

        internal fun fromApiData(
            id: String,
            displayLore: List<String>,
            description: String,
            maxLevelDescription: String,
            materials: Set<String>,
            group: String,
            maxLevel: Int,
            availableFromEnchanter: Boolean = true,
            baseDisplayName: String? = null,
        ): HelpCenterEnchantment {
            val cleanDescription = description.plainApiText()
            val cleanBaseName = baseDisplayName.plainApiText()
                .replace(Regex("(?i)%group-color%"), "")
                .trim()
            val cleanLoreName = displayLore.firstOrNull().orEmpty().plainApiText()
            val cleanMaxDescription = maxLevelDescription.plainApiText()
                .ifBlank { cleanDescription }
                .ifBlank { "Описание для этого зачарования не задано." }
            return HelpCenterEnchantment(
                id = id,
                name = cleanBaseName.ifBlank { cleanLoreName }.ifBlank { prettyId(id) },
                description = cleanDescription.ifBlank { cleanMaxDescription },
                maxLevelDescription = cleanMaxDescription,
                materials = materials.mapNotNull(::canonicalMaterial).toSet(),
                group = group.plainApiText(),
                maxLevel = maxLevel.coerceAtLeast(1),
                availableFromEnchanter = availableFromEnchanter,
            )
        }

        private fun canonicalMaterial(raw: String): String? = raw.trim()
            .substringAfter(':')
            .uppercase(Locale.ROOT)
            .takeIf(String::isNotBlank)

        private fun prettyId(id: String): String = id
            .replace('_', ' ')
            .split(Regex("\\s+"))
            .joinToString(" ") { word -> word.lowercase(Locale.forLanguageTag("ru")).replaceFirstChar { it.uppercaseChar() } }

        @Synchronized
        private fun logApiFailure(failure: Throwable) {
            if (apiFailureLogged) return
            apiFailureLogged = true
            Bukkit.getPluginManager().getPlugin("ARC")?.logger?.log(
                Level.WARNING,
                "AdvancedEnchantments catalogue is unavailable; dynamic enchantment entries are hidden until the API recovers.",
                failure,
            )
        }
    }
}

internal enum class HelpCenterEnchantmentsEquipment(val configKey: String) {
    ALL("equipment-all"),
    HELMET("equipment-helmet"),
    CHESTPLATE("equipment-chestplate"),
    LEGGINGS("equipment-leggings"),
    BOOTS("equipment-boots"),
    SWORD("equipment-sword"),
    AXE("equipment-axe"),
    MACE("equipment-mace"),
    TRIDENT("equipment-trident"),
    PICKAXE("equipment-pickaxe"),
    SHOVEL("equipment-shovel"),
    HOE("equipment-hoe"),
    FISHING_ROD("equipment-fishing-rod"),
    BOW("equipment-bow"),
    CROSSBOW("equipment-crossbow"),
    SHIELD("equipment-shield"),
    ELYTRA("equipment-elytra"),
    OTHER("equipment-other");

    fun matches(materials: Set<String>): Boolean = when (this) {
        ALL -> true
        HELMET -> materials.any { it.endsWith("_HELMET") || it == "CARVED_PUMPKIN" || it == "PLAYER_HEAD" || it == "TURTLE_HELMET" }
        CHESTPLATE -> materials.any { it.endsWith("_CHESTPLATE") }
        LEGGINGS -> materials.any { it.endsWith("_LEGGINGS") }
        BOOTS -> materials.any { it.endsWith("_BOOTS") }
        SWORD -> materials.any { it.endsWith("_SWORD") }
        AXE -> materials.any { it.endsWith("_AXE") }
        MACE -> "MACE" in materials
        TRIDENT -> "TRIDENT" in materials
        PICKAXE -> materials.any { it.endsWith("_PICKAXE") }
        SHOVEL -> materials.any { it.endsWith("_SHOVEL") }
        HOE -> materials.any { it.endsWith("_HOE") }
        FISHING_ROD -> "FISHING_ROD" in materials
        BOW -> "BOW" in materials
        CROSSBOW -> "CROSSBOW" in materials
        SHIELD -> "SHIELD" in materials
        ELYTRA -> "ELYTRA" in materials
        OTHER -> materials.isNotEmpty() && entriesOfKnownEquipment.none { known -> known.matches(materials) }
    }

    private val entriesOfKnownEquipment: List<HelpCenterEnchantmentsEquipment>
        get() = entries.filter { it != ALL && it != OTHER }
}

internal class HelpCenterEnchantmentsGuideSettings(private val values: Map<String, String>) {
    fun text(key: String): String = values.getValue(key)

    fun optionalText(key: String): String? = values[key]
}

internal class HelpCenterEnchantmentsGuideConfig(private val config: Config) {
    fun snapshot(): HelpCenterEnchantmentsGuideSettings = HelpCenterEnchantmentsGuideSettings(
        DEFAULT_TEXT.mapValues { (key, fallback) ->
            config.string(key, fallback).also { value ->
                require(value.isNotBlank()) { "Enchantments guide value '$key' cannot be blank" }
                require(value.length <= 4_000) { "Enchantments guide value '$key' is too long" }
            }
        },
    )

    companion object {
        private const val RESOURCE = "enchantments-guide.yml"

        private val DEFAULT_TEXT = linkedMapOf(
            "ui.title" to "<#d6a5ff>Зачарования",
            "ui.overview" to "<#e8dfd2>Особые чары AE усиливают совместимое снаряжение. В каталоге указаны эффект, максимальный уровень и подходящие предметы.<newline><newline>Перетащите книгу на предмет: попытка начнётся сразу, книга расходуется. Успех и риск разрушения указаны на книге. Пыль улучшает шансы; белый свиток защищает предмет при неудаче.",
            "ui.unavailable-title" to "<#f4bd6a>Каталог временно недоступен",
            "ui.unavailable-body" to "<#e8dfd2>Каталог зачарований сейчас недоступен. Памятка по особым предметам и способам получения остаётся открыта.",
            "ui.empty-catalog" to "<#e8dfd2>Пока нет доступных зачарований.",
            "ui.catalog-label" to "<#d6a5ff>По снаряжению",
            "ui.catalog-tooltip" to "<#e8dfd2>Шлемы, броня, оружие, инструменты и прочее.",
            "ui.search-label" to "<#d6a5ff>Найти зачарование",
            "ui.search-tooltip" to "<#e8dfd2>Поиск по названию и описанию эффекта.",
            "ui.search-input" to "<#fff0d8>Название или эффект",
            "ui.search-submit" to "<#9bd48d>Показать результаты",
            "ui.search-summary" to "<#e8dfd2>Найдено зачарований: <count> · страница <page>/<pages>",
            "ui.search-empty" to "<#e8dfd2>Ничего не найдено. Попробуйте другое слово или проверьте список для предмета.",
            "ui.entry-label" to "<#d6a5ff><name>",
            "ui.entry-tooltip" to "<#e8dfd2>Группа: <group><newline>Максимальный уровень: <level><newline>Применяется к: <materials>",
            "ui.details-title" to "<#d6a5ff><name>",
            "ui.details-summary" to "<#e8dfd2>Редкость: <group><newline>Максимальный уровень: <level><newline>Подходит: <materials>",
            "ui.details-acquisition-enchanter" to "<#9bd48d>Способ получения: зачарователь",
            "ui.details-acquisition-unconfirmed" to "<#f4bd6a>Эта книга не продаётся у зачарователя.",
            "ui.details-group-label" to "<#fff0d8>Группа",
            "ui.details-level-label" to "<#fff0d8>Максимальный уровень",
            "ui.details-compatible-label" to "<#fff0d8>Подходит для",
            "ui.details-source-label" to "<#fff0d8>Источник",
            "ui.details-source-enchanter" to "<#9bd48d>Только зачарователь на спавне",
            "ui.details-source-loot-and-enchanter" to "<#9bd48d>Зачарователь на спавне · охота · рыбалка",
            "ui.details-source-unavailable" to "<#e8dfd2>Не входит в ассортимент зачарователя",
            "ui.details-application" to "<#e8dfd2>Перетащите одну книгу на совместимый предмет: попытка начнётся сразу, книга расходуется. Проверьте шансы на книге. Магическая пыль повышает успех и снижает риск разрушения; белый свиток защищает предмет при неудаче.",
            "ui.details-purchase-spawn-only" to "<#f4bd6a>Купить книгу можно у зачарователя на спавне.",
            "ui.purchase-label" to "<#9bd48d>Купить книгу",
            "ui.purchase-tooltip" to "<#e8dfd2>Выберите уровень; цена и условия будут показаны до подтверждения.",
            "ui.description" to "<#f4bd6a>Описание<newline><#e8dfd2><description>",
            "ui.level-description" to "<#f4bd6a>Эффект на уровне <level><newline><#e8dfd2><description>",
            "ui.group-simple" to "Простая",
            "ui.group-unique" to "Уникальная",
            "ui.group-elite" to "Сильная",
            "ui.group-ultimate" to "Мощная",
            "ui.group-legendary" to "Легендарная",
            "ui.group-fabled" to "Лучшая",
            "ui.group-cheater" to "Читерская",
            "ui.group-other" to "Особая группа",
            "ui.unknown-materials" to "не указано",
            "ui.page-previous" to "<#92bed8>‹ Предыдущая страница",
            "ui.page-next" to "<#92bed8>Следующая страница ›",
            "ui.special-label" to "<#f4bd6a>Особые предметы",
            "ui.special-tooltip" to "<#e8dfd2>Свитки, пыль, сферы, увеличители слотов, души и счётчики.",
            "ui.acquisition-label" to "<#9bd48d>Где получить",
            "ui.acquisition-tooltip" to "<#e8dfd2>Где взять книгу, пыль и свитки.",
            "ui.enchanter-label" to "<#f4bd6a>Открыть зачарователь",
            "ui.enchanter-tooltip" to "<#e8dfd2>На спавне купить случайную книгу выбранной группы.",
            "ui.tinkerer-label" to "<#f4bd6a>Открыть тинкера",
            "ui.tinkerer-tooltip" to "<#e8dfd2>Обменять ненужные книги на награду.",
            "ui.alchemist-label" to "<#f4bd6a>Открыть алхимика",
            "ui.alchemist-tooltip" to "<#e8dfd2>Объединить совместимые книги и пыль.",
            "ui.recycle-label" to "<#f4bd6a>Переработать книги",
            "ui.recycle-tooltip" to "<#e8dfd2>Открыть тинкера и обменять ненужные книги.",
            "ui.alchemy-label" to "<#d6a5ff>Объединить книги",
            "ui.alchemy-tooltip" to "<#e8dfd2>Открыть алхимика, проверить сочетание и итог.",
            "ui.special-title" to "<#f4bd6a>Особые предметы",
            "ui.special-body" to "<#e8dfd2>Назначение, применение и действующие источники каждого предмета. Страница <page>/<pages>.",
            "ui.special-white-label" to "<#f4bd6a>Белый свиток",
            "ui.special-white-summary" to "<#e8dfd2>Защищает снаряжение от разрушения при неудачном наложении книги.",
            "ui.special-white-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Защищает предмет от разрушения, если применение книги AE не удалось. Шанс успеха самой книги не повышает.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Возьмите свиток на курсор в инвентаре и нажмите ЛКМ по подходящему предмету. Свиток расходуется при установке; проверьте отметку защиты перед применением книги.",
            "ui.special-black-label" to "<#f4bd6a>Чёрный свиток",
            "ui.special-black-summary" to "<#e8dfd2>Снимает одно случайное зачарование AE и возвращает его книгой.",
            "ui.special-black-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Снимает одно случайное доступное зачарование AE. Указанный на свитке процент станет шансом успешного применения этой книги. Обычные чары Minecraft не снимает.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Оставьте свободную ячейку. Возьмите свиток на курсор и нажмите ЛКМ по одиночному предмету с чарами AE. Расходуется один свиток.",
            "ui.special-magic-dust-label" to "<#d6a5ff>Магическая пыль",
            "ui.special-magic-dust-summary" to "<#e8dfd2>Повышает успех книги и снижает риск разрушения.",
            "ui.special-magic-dust-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Добавляет к успеху книги указанное число процентных пунктов, до 100%, и на столько же уменьшает риск разрушения, до 0%. Для AE нужна та же редкость; для EliteMobs подходит любая редкость пыли.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Отделите одну книгу от стопки. Возьмите пыль на курсор и нажмите ЛКМ по книге: расходуется одна порция. Обычные книги Minecraft не подходят.",
            "ui.special-holy-white-label" to "<#f4bd6a>Легендарный камень возврата",
            "ui.special-holy-white-summary" to "<#e8dfd2>Святой белый свиток: сохраняет предмет при смерти.",
            "ui.special-holy-white-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Заранее защищает снаряжение от потери при смерти. Защита исчезает после одного срабатывания; это другой предмет, чем белый свиток от неудачного зачарования.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Оставьте свободную ячейку. Возьмите камень на курсор и нажмите ЛКМ по подходящему предмету. Чары AE заранее не требуются; расходуется один камень.",
            "ui.special-secret-dust-label" to "<#d6a5ff>Секретная пыль",
            "ui.special-secret-dust-summary" to "<#e8dfd2>Закрытая порция: магическая пыль или бесполезный остаток.",
            "ui.special-secret-dust-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>При открытии превращается в магическую либо мистическую пыль. Результат и сила магической пыли зависят от редкости; текущие вероятности — в разделе «Источники и шансы».<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Возьмите секретную пыль в основную руку и нажмите ПКМ. Расходуется одна порция. Магическую пыль затем можно применить к книге; мистическая её не усиливает.",
            "ui.special-mystery-dust-label" to "<#d6a5ff>Мистическая пыль",
            "ui.special-mystery-dust-summary" to "<#e8dfd2>Неудачный результат открытия секретной пыли.",
            "ui.special-mystery-dust-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Бесполезный остаток открытия секретной пыли. Не повышает шанс книги, не добавляет слоты и не заменяет магическую пыль.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>К книгам и снаряжению не применяется. Не тратьте её на попытки усиления; полезная пыль называется «Магическая пыль».",
            "ui.special-randomizer-label" to "<#d6a5ff>Книга рандома",
            "ui.special-randomizer-summary" to "<#e8dfd2>Заново выбирает шансы книги AE; чары и уровень сохраняются.",
            "ui.special-randomizer-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Меняет шансы успеха и разрушения книги AE. Результат может стать лучше или хуже. Само зачарование и его уровень сохраняются.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Отделите одну книгу подходящей редкости. Возьмите свиток рандомизации на курсор и нажмите ЛКМ по книге. Расходуется один свиток.",
            "ui.special-transmog-label" to "<#f4bd6a>Свиток трансмогрификации",
            "ui.special-transmog-summary" to "<#e8dfd2>Упорядочивает чары AE и добавляет их количество к названию.",
            "ui.special-transmog-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Сортирует особые зачарования по редкости и добавляет их количество к названию предмета. Новые чары и слоты не добавляет.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>На предмете уже должны быть чары AE; обычных чар Minecraft недостаточно. Возьмите свиток на курсор и нажмите ЛКМ по предмету. Расходуется один свиток.",
            "ui.special-soul-gem-label" to "<#86dcf1>Камень душ",
            "ui.special-soul-gem-summary" to "<#e8dfd2>Пополняет запас душ на предмете со счётчиком душ.",
            "ui.special-soul-gem-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Переносит души из камня на предмет. Это заряд конкретного предмета для чар с расходом душ. При нехватке заряда такие чары не срабатывают.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Сначала установите счётчик душ. Затем возьмите камень на курсор и нажмите ЛКМ по одному совместимому предмету: расходуется один камень. Чтобы извлечь заряд, держите предмет в руке и выполните /withdrawsouls число.",
            "ui.special-soul-tracker-label" to "<#86dcf1>Счётчик душ",
            "ui.special-soul-tracker-summary" to "<#e8dfd2>Открывает накопление душ на предмете для особых чар.",
            "ui.special-soul-tracker-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Добавляет предмету запас душ. Камень душ можно применить только после установки этого счётчика. Запас принадлежит предмету; это не счётчик убийств игроков или мобов.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Отделите один совместимый предмет от стопки. Возьмите счётчик на курсор и нажмите ЛКМ по предмету без счётчика душ. Расходуется один счётчик; затем можно пополнять заряд камнями душ.",
            "ui.special-orb-weapon-label" to "<#f4bd6a>Сфера зачарования оружия",
            "ui.special-orb-weapon-summary" to "<#e8dfd2>Задаёт новый общий лимит чар AE на оружии.",
            "ui.special-orb-weapon-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>При успехе устанавливает общий лимит, написанный на сфере; это не прибавка указанного числа слотов. Шанс применения написан на самой сфере.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Подходит мечу, топору, луку, арбалету, трезубцу и булаве. На предмете уже нужна строка AE «Осталось зачарований». Возьмите сферу на курсор и нажмите ЛКМ по предмету. При неудаче сфера тоже расходуется.",
            "ui.special-orb-armor-label" to "<#f4bd6a>Сфера зачарования брони",
            "ui.special-orb-armor-summary" to "<#e8dfd2>Задаёт новый общий лимит чар AE на броне.",
            "ui.special-orb-armor-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>При успехе устанавливает общий лимит, написанный на сфере; это не прибавка указанного числа слотов. Шанс применения написан на самой сфере.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Подходит шлему, нагруднику, поножам и ботинкам со строкой AE «Осталось зачарований». Возьмите сферу на курсор и нажмите ЛКМ по предмету. При неудаче сфера тоже расходуется.",
            "ui.special-orb-tool-label" to "<#f4bd6a>Сфера зачарования инструмента",
            "ui.special-orb-tool-summary" to "<#e8dfd2>Задаёт новый общий лимит чар AE на инструменте.",
            "ui.special-orb-tool-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>При успехе устанавливает общий лимит, написанный на сфере; это не прибавка указанного числа слотов. Шанс применения написан на самой сфере.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Подходит кирке, лопате, мотыге и удочке со строкой AE «Осталось зачарований». Для топора нужна сфера оружия. Возьмите сферу на курсор и нажмите ЛКМ по предмету. При неудаче сфера тоже расходуется.",
            "ui.special-slot-increaser-label" to "<#f4bd6a>Увеличитель слотов",
            "ui.special-slot-increaser-summary" to "<#e8dfd2>Добавляет свободные слоты AE до разрешённого предела.",
            "ui.special-slot-increaser-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Добавляет указанное на нём количество свободных слотов до текущего предела улучшения. В отличие от сферы, это прибавка к слотам.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>На предмете нужна строка AE «Осталось зачарований». Возьмите увеличитель на курсор и нажмите ЛКМ по предмету. Расходуется один увеличитель. Если достигнут предел, увеличить слоты этим предметом нельзя.",
            "ui.special-block-tracker-label" to "<#86dcf1>Счётчик блоков",
            "ui.special-block-tracker-summary" to "<#e8dfd2>Считает блоки, сломанные этим инструментом.",
            "ui.special-block-tracker-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Записывает число блоков, сломанных инструментом. Не добавляет чары или души.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Подходит кирке, топору, лопате и мотыге. Возьмите счётчик на курсор и нажмите ЛКМ по инструменту. Чары AE заранее не требуются. Можно установить один счётчик этого типа; расходуется один предмет.",
            "ui.special-player-tracker-label" to "<#86dcf1>Счётчик убийств игроков",
            "ui.special-player-tracker-summary" to "<#e8dfd2>Записывает убийства игроков этим оружием.",
            "ui.special-player-tracker-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Считает убийства игроков этим предметом. Убийства мобов учитывает отдельный счётчик; души этот счётчик не хранит.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Возьмите счётчик на курсор и нажмите ЛКМ по оружию, которым будете сражаться. Чары AE заранее не требуются. Можно установить один счётчик этого типа; расходуется один предмет.",
            "ui.special-mob-tracker-label" to "<#86dcf1>Счётчик мобов",
            "ui.special-mob-tracker-summary" to "<#e8dfd2>Записывает убийства мобов этим оружием.",
            "ui.special-mob-tracker-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Считает убийства мобов этим предметом. Убийства игроков учитывает отдельный счётчик; души этот счётчик не хранит.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Подходит мечу, топору, булаве, мотыге, луку, арбалету и трезубцу. Возьмите счётчик на курсор и нажмите ЛКМ по оружию. Чары AE заранее не требуются. Устанавливается один счётчик этого типа; он расходуется.",
            "ui.special-fish-tracker-label" to "<#86dcf1>Счётчик рыбы",
            "ui.special-fish-tracker-summary" to "<#e8dfd2>Считает улов этой удочкой.",
            "ui.special-fish-tracker-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Добавляет удочке счётчик пойманной рыбы. В предмете может называться FishTrak Creator.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Возьмите счётчик на курсор и нажмите ЛКМ по удочке. Счётчик расходуется при установке. Действующие способы получения — в разделе «Источники и шансы».",
            "ui.special-item-name-tag-label" to "<#f4bd6a>Название предмета",
            "ui.special-item-name-tag-summary" to "<#e8dfd2>Бирка для переименования предмета через чат.",
            "ui.special-item-name-tag-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Позволяет переименовать предмет с чарами AE или Minecraft. Одно переименование расходует одну бирку.<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Нажмите ПКМ с биркой в основной руке. Затем возьмите нужный предмет в основную руку и напишите новое имя в чат. Для отмены напишите cancel.",
            "ui.special-box-base-label" to "<#d6a5ff>Таинственная шкатулка чар",
            "ui.special-box-base-summary" to "<#e8dfd2>Смешанные награды: пыль, книги и особые предметы.",
            "ui.special-box-base-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Содержит случайные награды для зачарования. Каждый результат выбирается отдельно, поэтому награды могут повторяться. Состав и шансы — в разделе «Источники и шансы».<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Возьмите шкатулку в основную руку и нажмите ПКМ. Расходуется одна шкатулка. У ранее выданной шкатулки число наград может отличаться — проверьте её описание.",
            "ui.special-box-enchanted-label" to "<#d6a5ff>Зачарованная шкатулка AE",
            "ui.special-box-enchanted-summary" to "<#e8dfd2>Несколько случайных книг зачарований.",
            "ui.special-box-enchanted-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Содержит несколько независимо выбранных наград из своего набора. Состав, число наград и источники — в разделе «Источники и шансы».<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Возьмите шкатулку в основную руку и нажмите ПКМ. Расходуется одна шкатулка. У ранее выданного предмета число наград может отличаться — проверьте его описание.",
            "ui.special-box-master-label" to "<#d6a5ff>Мастерская шкатулка AE",
            "ui.special-box-master-summary" to "<#e8dfd2>Усиленный запас случайных книг зачарований.",
            "ui.special-box-master-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Содержит несколько независимо выбранных наград из своего набора. Состав, число наград и источники — в разделе «Источники и шансы».<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Возьмите шкатулку в основную руку и нажмите ПКМ. Расходуется одна шкатулка. У ранее выданного предмета число наград может отличаться — проверьте его описание.",
            "ui.special-box-supply-label" to "<#d6a5ff>Припасы чародея",
            "ui.special-box-supply-summary" to "<#e8dfd2>Награда для усиления книг и снаряжения.",
            "ui.special-box-supply-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Набор с наградами для зачарования. Источники, состав и шансы — в разделе «Источники и шансы».<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Возьмите припасы в основную руку и нажмите ПКМ. Открытие расходует один набор; освободите место для награды.",
            "ui.special-box-boss-supply-label" to "<#d6a5ff>Трофей чародея",
            "ui.special-box-boss-supply-summary" to "<#e8dfd2>Награда с добычей для зачарования.",
            "ui.special-box-boss-supply-body" to "<#f4bd6a><bold>Назначение</bold><newline><newline><#e8dfd2>Набор с наградами для зачарования. Источники, состав и шансы — в разделе «Источники и шансы».<newline><newline><#f4bd6a><bold>Как использовать</bold><newline><newline><#e8dfd2>Возьмите трофей в основную руку и нажмите ПКМ. Открытие расходует один набор; освободите место для награды.",
            "ui.sources-heading" to "<#f4bd6a><bold>Получение и параметры</bold>",
            "ui.sources-page" to "<#e8dfd2>Источники и содержимое · страница <page>/<pages>",
            "ui.source-pool-item" to "<#d6a5ff><box><newline><#e8dfd2>Шанс за одну награду: <per_roll>%. Количество при выпадении: <amount>.",
            "ui.source-per-box" to "<#e8dfd2>Хотя бы один раз за новую шкатулку: <per_box>%.",
            "ui.source-pool-item-value" to "<#d6a5ff>Параметры награды · <box>",
            "ui.source-box-rolls" to "<#e8dfd2>Сейчас новые шкатулки дают <range> независимых наград. Результаты могут повторяться; шанс ниже указан за одну награду. Ранее выданные шкатулки могут иметь другое число наград.",
            "ui.source-box-content" to "<#d6a5ff><item><newline><#e8dfd2>Шанс за одну награду: <per_roll>%. Количество: <amount>.",
            "ui.source-pool-source" to "<#d6a5ff>В составе набора «<pool>»<newline><#e8dfd2>Шанс за один выбор награды: <per_roll>%. Количество: <amount>. Этот состав используется только там, где выдаётся такой набор.",
            "ui.source-boss-source" to "<#d6a5ff>Элитные боссы<newline><#e8dfd2>За подходящее убийство босса: <chance>%. Количество: <amount>.",
            "ui.source-natural-dust" to "<#d6a5ff>Охота и рыбалка<newline><#e8dfd2>Естественный враждебный моб, убитый игроком: <kill>%; рыбалка в открытой воде: <fishing>%. Пыль усиливает книгу на <min>–<max> п. п. Миры: <worlds>. Не более одного результата за событие.",
            "ui.source-natural-books" to "<#d6a5ff>Книги за охоту и рыбалку<newline><#e8dfd2>Шанс розыгрыша книги: естественный враждебный моб, убитый игроком — <kill>%; рыбалка в открытой воде — <fishing>%. Миры: <worlds>. Если подходящих чар или уровней нет, книга не выдаётся. Не более одного результата за событие.",
            "ui.source-no-drop" to "<#e8dfd2>В действующих наборах наград этого сервера такой предмет не найден.",
            "ui.source-unavailable" to "<#e8dfd2>Данные об источнике «<source>» сейчас недоступны. Это не означает нулевой шанс.",
            "ui.source-content-group" to "<#e8dfd2>Редкость: <group>.",
            "ui.source-content-level" to "<#e8dfd2>Максимальный уровень чар: <level>.",
            "ui.source-content-dust" to "<#e8dfd2>Сила пыли: +<range> п. п.",
            "ui.source-content-souls" to "<#e8dfd2>Заряд камня: <range> душ.",
            "ui.source-content-blackscroll" to "<#e8dfd2>Шанс полученной книги: <range>%.",
            "ui.source-content-orb-limit" to "<#e8dfd2>Лимит на сфере: <range> слотов.",
            "ui.source-content-slots" to "<#e8dfd2>Прибавка: <range> слотов.",
            "ui.source-slot-settings" to "<#e8dfd2>Базовый лимит: <base> слотов. Увеличитель добавляет <increment>; предел этого улучшения — <max> слотов.",
            "ui.source-secret-result" to "<#d6a5ff>Секретная пыль · <group><newline><#e8dfd2>Магическая пыль +<min>–<max> п. п.: <magic>%. Мистическая пыль: <mystery>%.",
            "ui.source-native-mob" to "<#d6a5ff><mob><newline><#e8dfd2>Прямая добыча: <chance>%. Количество: <amount>.",
            "ui.source-native-disabled" to "<#e8dfd2>Источник «<source>» сейчас отключён.",
            "ui.source-name-ae" to "<#e8dfd2>Таинственная шкатулка чар",
            "ui.source-name-ae-enchanted" to "<#e8dfd2>Зачарованная шкатулка AE",
            "ui.source-name-ae-master" to "<#e8dfd2>Мастерская шкатулка AE",
            "ui.source-name-enchant-supply" to "<#e8dfd2>Припасы чародея",
            "ui.source-name-enchant-boss-supply" to "<#e8dfd2>Трофей чародея",
            "ui.source-name-random-book" to "<#e8dfd2>Случайная книга AE",
            "ui.source-name-book" to "<#e8dfd2>Случайная книга AE",
            "ui.source-name-orb" to "<#e8dfd2>Случайная сфера зачарования",
            "ui.source-name-daily" to "<#e8dfd2>Ежедневный тайник",
            "ui.source-name-weekly" to "<#e8dfd2>Недельная реликвия",
            "ui.source-name-case-daily" to "<#e8dfd2>Ежедневный тайник",
            "ui.source-name-case-weekly" to "<#e8dfd2>Недельная реликвия",
            "ui.source-name-drops" to "<#e8dfd2>Добыча",
            "ui.source-name-drops-mining" to "<#e8dfd2>Добыча при копании",
            "ui.source-name-drops-fishing" to "<#e8dfd2>Добыча на рыбалке",
            "ui.source-name-mob-drops" to "<#e8dfd2>Добыча с мобов",
            "ui.source-name-ae-native" to "<#e8dfd2>Особые предметы AE",
            "ui.source-name-ae-world" to "<#e8dfd2>Генерация книг AE в мире",
            "ui.source-name-ae-villagers" to "<#e8dfd2>Торговля AE у жителей",
            "ui.source-name-presets" to "<#e8dfd2>Шкатулки",
            "ui.source-name-treasures" to "<#e8dfd2>Наборы наград",
            "ui.source-name-enchanting" to "<#e8dfd2>Охота и рыбалка",
            "ui.source-name-simple" to "<#e8dfd2>Обычная",
            "ui.source-name-unique" to "<#e8dfd2>Необычная",
            "ui.source-name-elite" to "<#e8dfd2>Элитная",
            "ui.source-name-ultimate" to "<#e8dfd2>Эпическая",
            "ui.source-name-legendary" to "<#e8dfd2>Легендарная",
            "ui.source-name-fabled" to "<#e8dfd2>Мифическая",
            "ui.source-name-random" to "<#e8dfd2>Случайная",
            "ui.sources-label" to "<#9bd48d>Источники и шансы ›",
            "ui.sources-tooltip" to "<#e8dfd2>Где получить предмет, текущие вероятности и содержимое шкатулок.",
            "ui.sources-loading" to "<#e8dfd2>Проверяем действующие источники наград…",
            "ui.source-scope" to "<#e8dfd2>Награды на сервере: <server>",
            "ui.source-natural-inactive" to "<#e8dfd2>На этом сервере нет наград за обычную охоту и рыбалку из системы зачарований.",
            "ui.source-personal-loot-source" to "<#d6a5ff>Личные сундуки в постройках<newline><#e8dfd2>Для новых подходящих личных сундуков шанс предмета «<item>»: <chance>%. Обычные награды: <min_rolls>–<max_rolls>; дополнительный розыгрыш: <bonus>%. Уже заполненные сундуки сохраняют свою добычу.",
            "ui.source-denizen-monsters" to "<#d6a5ff>Монстры, убитые игроком<newline><#e8dfd2>Шанс: <base>% × (максимальное здоровье / <health>), не выше <cap>%. Для эндерменов в мире «<world>»: ×<end>; для мобов из спавнера: ×<spawner>. Ограничение на игрока: один расчёт за <cooldown>.",
            "ui.source-denizen-bosses" to "<#d6a5ff>Боссы<newline><#e8dfd2>Иссушитель: <wither> шкатулок. Дракон Края: <dragon> шкатулок.",
            "ui.source-name-rewards" to "<#e8dfd2>Награды",
            "ui.source-name-treasure-pools" to "<#e8dfd2>Наборы случайных наград",
            "ui.source-name-item-presets" to "<#e8dfd2>Шкатулки",
            "ui.source-name-natural-loot" to "<#e8dfd2>Охота и рыбалка",
            "ui.source-name-boss-rewards" to "<#e8dfd2>Награды за боссов",
            "ui.source-name-rank-quests" to "<#e8dfd2>Ежедневные задания",
            "ui.source-name-crate-rewards" to "<#e8dfd2>Тайники с ключами",
            "ui.source-name-spawn" to "<#e8dfd2>Спавн",
            "ui.source-name-classic" to "<#e8dfd2>Спавн",
            "ui.source-name-survival" to "<#e8dfd2>Выживание",
            "ui.source-name-classic-survival" to "<#e8dfd2>Выживание",
            "ui.source-name-parkour" to "<#e8dfd2>Паркур",
            "ui.source-name-bs-loot" to "<#e8dfd2>Личные сундуки",
            "ui.source-name-chest" to "<#e8dfd2>Сундуки",
            "ui.source-name-basic" to "<#e8dfd2>Обычные награды",
            "ui.source-name-bonus" to "<#e8dfd2>Дополнительные награды",
            "ui.source-crate-source" to "<#d6a5ff>Кейс «<crate>»<newline><#e8dfd2><item> · <amount> шт.<newline>Шанс одного розыгрыша этой награды: <chance>%. Для открытия нужен подходящий ключ. Это шанс одного розыгрыша, а не итоговая вероятность за открытие.",
            "ui.source-quest-source" to "<#d6a5ff>Задание «<quest>»<newline><#e8dfd2><item> · <amount> шт.<newline>Выполнить это задание можно, когда оно есть в ваших ежедневных заданиях. Проверьте раздел «Задания» в главном меню.",
            "ui.source-name-reward-catalog" to "Каталог наград",
            "ui.source-name-personal-loot" to "Личные сундуки в постройках",
            "ui.source-name-denizen" to "Добыча монстров и боссов",
            "ui.source-tinkerer-source" to "<#d6a5ff>Тинкер · обмен книг<newline><#e8dfd2>За <input> книгу AE — <output> секретная пыль той же редкости. Это обмен зачарованных книг, не экипировки.",
            "ui.source-name-external-sources" to "<#e8dfd2>Источники в кейсах и заданиях",
            "ui.source-name-legacy-mob-drops" to "<#e8dfd2>Добыча монстров и боссов",
            "ui.acquisition-title" to "<#9bd48d>Где получить",
            "ui.acquisition-summary" to "<#e8dfd2>Зачарователь на спавне продаёт книги. Ниже — действующие награды за игру; в карточке каждой шкатулки указаны её содержимое и источники.",
            "ui.route-enchanter-label" to "<#9bd48d>Зачарователь · Spawn",
            "ui.route-enchanter" to "<#e8dfd2>Продаёт случайную книгу выбранной группы. В карточке каталога можно выбрать конкретное зачарование и уровень.",
            "ui.route-hunt-label" to "<#f4bd6a>Охота · Survival",
            "ui.route-hunt" to "<#e8dfd2>Действующие шансы книг и пыли за охоту показаны в разделе получения.",
            "ui.route-fishing-label" to "<#86dcf1>Рыбалка · Survival",
            "ui.route-fishing" to "<#e8dfd2>Действующие шансы книг и пыли за рыбалку показаны в разделе получения.",
            "ui.route-caches-label" to "<#f4bd6a>Тайники",
            "ui.route-caches" to "<#e8dfd2>Откройте карточку шкатулки, чтобы увидеть её содержимое и источники.",
            "ui.acquisition-services" to "<#e8dfd2>Тинкер перерабатывает книги; алхимик объединяет поддерживаемые книги и пыль. Их меню доступны из раздела зачарований.",
            "ui.equipment-summary" to "<#e8dfd2>Выберите точный тип предмета. Страница <page>/<pages>.",
            "ui.equipment-title" to "<#d6a5ff>Выберите снаряжение",
            "ui.equipment-all" to "<#d6a5ff>Все зачарования",
            "ui.equipment-helmet" to "<#86dcf1>Шлемы",
            "ui.equipment-chestplate" to "<#86dcf1>Нагрудники",
            "ui.equipment-leggings" to "<#86dcf1>Поножи",
            "ui.equipment-boots" to "<#86dcf1>Ботинки",
            "ui.equipment-sword" to "<#ffb277>Мечи",
            "ui.equipment-axe" to "<#ffb277>Топоры",
            "ui.equipment-mace" to "<#ffb277>Булавы",
            "ui.equipment-trident" to "<#ffb277>Трезубцы",
            "ui.equipment-pickaxe" to "<#9bd48d>Кирки",
            "ui.equipment-shovel" to "<#9bd48d>Лопаты",
            "ui.equipment-hoe" to "<#9bd48d>Мотыги",
            "ui.equipment-fishing-rod" to "<#86dcf1>Удочки",
            "ui.equipment-bow" to "<#ffb277>Луки",
            "ui.equipment-crossbow" to "<#ffb277>Арбалеты",
            "ui.equipment-shield" to "<#86dcf1>Щиты",
            "ui.equipment-elytra" to "<#86dcf1>Элитры",
            "ui.equipment-other" to "<#e8dfd2>Прочие предметы",
        )

        fun load(dataPath: Path): HelpCenterEnchantmentsGuideConfig {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            source.mergeMissingFromBundled("modules/$RESOURCE")
            return HelpCenterEnchantmentsGuideConfig(source)
        }
    }
}

private data class AeEnchantDescriptionAccess(
    val getInstance: Method,
    val getDisplayNoColor: Method,
    val getDescription: Method,
    val getLevelDescription: Method,
    val isAvailableFromEnchanter: Method,
) {
    data class Details(
        val baseDisplayName: String?,
        val general: String?,
        val atMaxLevel: String?,
        val availableFromEnchanter: Boolean,
    )

    fun read(id: String, level: Int): Details {
        val enchantment = instance(id)
        return Details(
            baseDisplayName = invoke(getDisplayNoColor, enchantment) as? String,
            general = invoke(getDescription, enchantment) as? String,
            atMaxLevel = invoke(getLevelDescription, enchantment, level) as? String,
            availableFromEnchanter = invoke(isAvailableFromEnchanter, enchantment) as? Boolean ?: false,
        )
    }

    private fun instance(id: String): Any = invoke(getInstance, null, id)
        ?: error("AdvancedEnchantments returned no enchantment instance for '$id'")

    private fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
        method.invoke(receiver, *args)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }

    companion object {
        val binding: AeEnchantDescriptionAccess by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val instanceMethod = AEAPI::class.java.getMethod("getEnchantmentInstance", String::class.java)
            val implementation = instanceMethod.returnType
            AeEnchantDescriptionAccess(
                getInstance = instanceMethod,
                getDisplayNoColor = implementation.getMethod("getDisplayNoColor"),
                getDescription = implementation.getMethod("getDescription"),
                getLevelDescription = implementation.getMethod("getLevelDescriptionOrFallback", Int::class.javaPrimitiveType),
                isAvailableFromEnchanter = implementation.getMethod("isAvailableFromEnchanter"),
            )
        }
    }
}

private val LEGACY_COLOR = Regex("(?i)[§&][0-9A-FK-ORX]")
private val MINI_MESSAGE_TAG = Regex("</?(?:[a-z][a-z0-9_-]*|#[0-9a-f]{3,8})(?::[^<>]*)?>", RegexOption.IGNORE_CASE)

private fun String?.plainApiText(): String = this.orEmpty()
    .replace(Regex("(?i)<newline\\s*/?>"), "\n")
    .replace(LEGACY_COLOR, "")
    .replace(MINI_MESSAGE_TAG, "")
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .joinToString("\n")
