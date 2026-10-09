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
        ): HelpCenterEnchantment {
            val cleanDescription = description.plainApiText()
            val cleanMaxDescription = maxLevelDescription.plainApiText()
                .ifBlank { cleanDescription }
                .ifBlank { "Описание для этого зачарования не задано." }
            return HelpCenterEnchantment(
                id = id,
                name = displayLore.firstOrNull().orEmpty().plainApiText().ifBlank { prettyId(id) },
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
            "ui.overview" to "<#e8dfd2>Узнайте, к какому снаряжению подходят особые чары, что они делают и где искать свитки и пыль.",
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
            "ui.special-tooltip" to "<#e8dfd2>Свитки, пыль и защита снаряжения.",
            "ui.acquisition-label" to "<#9bd48d>Где получить",
            "ui.acquisition-tooltip" to "<#e8dfd2>Зачарователь, обмены, объединение и тайники.",
            "ui.enchanter-label" to "<#f4bd6a>Открыть зачарователь",
            "ui.enchanter-tooltip" to "<#e8dfd2>Каталог книг по группам редкости.",
            "ui.tinkerer-label" to "<#f4bd6a>Открыть тинкера",
            "ui.tinkerer-tooltip" to "<#e8dfd2>Обменять ненужные книги на награду.",
            "ui.alchemist-label" to "<#f4bd6a>Открыть алхимика",
            "ui.alchemist-tooltip" to "<#e8dfd2>Объединить совместимые книги и пыль.",
            "ui.special-title" to "<#f4bd6a>Особые предметы",
            "ui.special-body" to "<#e8dfd2>Выберите предмет, чтобы узнать, для чего он нужен и где его можно получить.",
            "ui.special-white-label" to "<#ffffff>Белый свиток",
            "ui.special-white-body" to "<#f4bd6a>Что делает<newline><#e8dfd2>Предотвращает разрушение предмета, если наложение зачарованной книги не удалось.<newline><newline><#f4bd6a>Как использовать<newline><#e8dfd2>Перетащите свиток на предмет в инвентаре.<newline><newline><#f4bd6a>Где получить<newline><#9bd48d>В наградах ежедневного тайника и недельной реликвии. У ящика нажмите ЛКМ, чтобы увидеть пул и шансы; ПКМ с ключом открывает его.",
            "ui.special-black-label" to "<#f4f4f4>Чёрный свиток",
            "ui.special-black-body" to "<#f4bd6a>Что делает<newline><#e8dfd2>Снимает случайное доступное для снятия зачарование и возвращает его в книгу. Указанный на свитке шанс станет шансом успешного применения этой книги.<newline><newline><#f4bd6a>Как использовать<newline><#e8dfd2>Перетащите свиток на предмет, с которого хотите снять зачарование.<newline><newline><#f4bd6a>Где получить<newline><#9bd48d>В наградах ежедневного тайника и недельной реликвии. У ящика нажмите ЛКМ, чтобы увидеть пул и шансы; ПКМ с ключом открывает его.",
            "ui.special-magic-dust-label" to "<#d6a5ff>Магическая пыль",
            "ui.special-magic-dust-body" to "<#f4bd6a>Что делает<newline><#e8dfd2>Повышает шанс успеха на указанный процент, максимум до 100%. Подходит книге AE той же редкости или любой книге EliteMobs.<newline><newline><#f4bd6a>Как использовать<newline><#e8dfd2>Перетащите пыль на одну книгу. При усилении тратится одна пыль; риск разрушения предмета становится нулевым.<newline><newline><#f4bd6a>Где получить<newline><#9bd48d>Из секретной пыли за обмен книг у /tinkerer, а также в наградах ежедневного тайника и недельной реликвии. У ящика нажмите ЛКМ, чтобы увидеть пул и шансы; ПКМ с ключом открывает его.",
            "ui.special-holy-white-label" to "<#f4bd6a>Святой белый свиток",
            "ui.special-holy-white-body" to "<#f4bd6a>Что делает<newline><#e8dfd2>Защищает применённый предмет от потери при смерти.<newline><newline><#f4bd6a>Как использовать<newline><#e8dfd2>Примените свиток к предмету, который хотите сохранить.<newline><newline><#f4bd6a>Где получить<newline><#9bd48d>В наградах недельной реликвии. У ящика нажмите ЛКМ, чтобы увидеть пул и шансы; ПКМ с ключом открывает его.",
            "ui.special-dust-packets-label" to "<#d6a5ff>Секретная и мистическая пыль",
            "ui.special-dust-packets-body" to "<#f4bd6a>Что делают<newline><#e8dfd2>Секретная пыль — закрытый набор, который может содержать магическую или мистическую пыль. Мистическая пыль — бесполезный остаток неудачной магии, а не усилитель.<newline><newline><#f4bd6a>Как использовать<newline><#e8dfd2>Нажмите ПКМ по секретной пыли, чтобы открыть её. Мистическую пыль нельзя применить к книге или предмету.<newline><newline><#f4bd6a>Где получить<newline><#9bd48d>Тинкер выдаёт секретную пыль за зачарованные книги: откройте /tinkerer. В ежедневном тайнике и недельной реликвии она не выпадает.",
            "ui.special-randomizer-label" to "<#d6a5ff>Свиток рандомизации",
            "ui.special-randomizer-body" to "<#f4bd6a>Что делает<newline><#e8dfd2>Задаёт новые шансы успеха книги особых зачарований.<newline><newline><#f4bd6a>Как использовать<newline><#e8dfd2>Перетащите свиток на зачарованную книгу подходящей группы.<newline><newline><#f4bd6a>Где получить<newline><#9bd48d>В наградах недельной реликвии. У ящика нажмите ЛКМ, чтобы увидеть пул и шансы; ПКМ с ключом открывает его.",
            "ui.special-transmog-label" to "<#f4bd6a>Свиток трансмогрификации",
            "ui.special-transmog-body" to "<#f4bd6a>Что делает<newline><#e8dfd2>Сортирует зачарования на предмете по редкости и добавляет счётчик к его названию.<newline><newline><#f4bd6a>Как использовать<newline><#e8dfd2>Примените свиток к нужному предмету.<newline><newline><#f4bd6a>Где получить<newline><#e8dfd2>В ежедневном тайнике и недельной реликвии не выпадает.",
            "ui.special-soul-gem-label" to "<#86dcf1>Камень душ",
            "ui.special-soul-gem-body" to "<#f4bd6a>Что делает<newline><#e8dfd2>Переносит души из камня на совместимый предмет.<newline><newline><#f4bd6a>Как использовать<newline><#e8dfd2>Перетащите камень на предмет. Чтобы извлечь накопленные души в камень, держите предмет в руке и выполните /withdrawsouls число.<newline><newline><#f4bd6a>Где получить<newline><#e8dfd2>Получите камень командой /withdrawsouls из душ, уже накопленных на предмете.",
            "ui.special-orbs-label" to "<#f4bd6a>Сфера и увеличитель слотов",
            "ui.special-orbs-body" to "<#f4bd6a>Что делают<newline><#e8dfd2>Сферы для оружия, брони и инструментов увеличивают число слотов зачарований. Увеличитель слотов добавляет указанное на нём количество.<newline><newline><#f4bd6a>Как использовать<newline><#e8dfd2>Перетащите подходящую сферу или увеличитель на предмет.<newline><newline><#f4bd6a>Где получить<newline><#e8dfd2>В ежедневном тайнике и недельной реликвии не выпадают.",
            "ui.special-trackers-label" to "<#86dcf1>Счётчики предмета",
            "ui.special-trackers-body" to "<#f4bd6a>Что делают<newline><#e8dfd2>Счётчики отмечают сломанные блоки, убийства и пойманную рыбу.<newline><newline><#f4bd6a>Как использовать<newline><#e8dfd2>Перетащите нужный счётчик на инструмент, оружие или удочку, указанные в его подсказке.<newline><newline><#f4bd6a>Где получить<newline><#e8dfd2>В ежедневном тайнике и недельной реликвии не выпадают.",
            "ui.acquisition-title" to "<#9bd48d>Где получить",
            "ui.acquisition-body" to "<#e8dfd2><#f4bd6a>Книги<newline><#e8dfd2>/enchanter открывает книги шести групп: простая, уникальная, сильная, мощная, легендарная и лучшая. Книги читерской группы в этом меню не указаны.<newline><newline><#f4bd6a>Тинкер и алхимик<newline><#e8dfd2>/tinkerer принимает ненужные книги в обмен на награду. /alchemist объединяет поддерживаемые книги и пыль; проверьте предметы и итог в его интерфейсе.<newline><newline><#f4bd6a>Тайники<newline><#e8dfd2>Свитки и пыль указаны среди наград ежедневного тайника или недельной реликвии. У нужного ящика нажмите ЛКМ, чтобы увидеть пул и шансы; ПКМ с ключом открывает его.",
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
    val getDescription: Method,
    val getLevelDescription: Method,
    val isAvailableFromEnchanter: Method,
) {
    data class Details(val general: String?, val atMaxLevel: String?, val availableFromEnchanter: Boolean)

    fun read(id: String, level: Int): Details {
        val enchantment = instance(id)
        return Details(
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
