package ru.arc.helpcenter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.enchanting.EnchantingModule
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.hooks.HookRegistry
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** Player-facing catalogue and verified usage guide for the active AE registry. */
internal class HelpCenterEnchantmentsController(
    private val settings: HelpCenterSettings,
    private val guide: HelpCenterEnchantmentsGuideSettings,
    private val navigation: HelpCenterNavigation,
    private val show: (Player, PaperDialogScreen) -> Unit,
    private val executeInventory: (Player, String) -> Boolean,
    private val catalog: () -> HelpCenterEnchantmentsCatalog = HelpCenterEnchantmentsCatalog::fromAeApi,
    private val canPurchase: (Player, String) -> Boolean = EnchantingModule::canPurchase,
    private val openPurchase: (Player, String, () -> Unit) -> Boolean = EnchantingModule::openPurchase,
    private val acquisition: (String) -> CompletableFuture<HelpCenterEnchantmentAcquisition> = HelpCenterEnchantmentSources::describeAsync,
) : AutoCloseable {
    private val miniMessage = MiniMessage.miniMessage()
    private val plainText = PlainTextComponentSerializer.plainText()
    private val presentation = HelpCenterPresentation(settings)
    private val tasks = LifecycleTaskScope()

    fun open(player: Player, returnTo: () -> Unit) {
        openOverview(player, returnTo, catalog())
    }

    private fun openOverview(player: Player, returnTo: () -> Unit, snapshot: HelpCenterEnchantmentsCatalog) {
        navigation.visit(player) { openOverview(player, returnTo, snapshot) }
        val overview = when {
            !snapshot.available -> "ui.unavailable-body"
            snapshot.entries.isEmpty() -> "ui.empty-catalog"
            else -> "ui.overview"
        }
        val buttons = buildList {
            if (snapshot.available && snapshot.entries.isNotEmpty()) {
                add(button("equipment", "ui.catalog-label", "ui.catalog-tooltip") {
                    openEquipment(player, snapshot, returnTo)
                })
                add(button("search", "ui.search-label", "ui.search-tooltip") {
                    openResults(player, snapshot, returnTo, HelpCenterEnchantmentsEquipment.ALL, "", 0) {
                        openOverview(player, returnTo, snapshot)
                    }
                })
            }
            add(button("special", "ui.special-label", "ui.special-tooltip") {
                openSpecialItems(player, snapshot, returnTo)
            })
            add(button("acquisition", "ui.acquisition-label", "ui.acquisition-tooltip") {
                openAcquisition(player, snapshot, returnTo)
            })
            if (canOpenEnchanter(player, snapshot)) add(button("enchanter", "ui.enchanter-label", "ui.enchanter-tooltip") {
                if (canOpenEnchanter(player, snapshot)) executeInventory(player, "enchanter")
                else openOverview(player, returnTo, snapshot)
            })
            add(button("recycle", "ui.recycle-label", "ui.recycle-tooltip") {
                executeInventory(player, "tinkerer")
            })
            add(button("alchemy", "ui.alchemy-label", "ui.alchemy-tooltip") {
                executeInventory(player, "alchemist")
            })
        }
        show(
            player,
            PaperDialogScreen(
                id = "help.enchantments",
                title = text(if (snapshot.available) "ui.title" else "ui.unavailable-title"),
                body = listOf(PaperDialogBody(text(overview), 468)),
                buttons = buttons,
                exitButton = back(returnTo),
                columns = 2,
            ),
        )
    }

    private fun openEquipment(
        player: Player,
        snapshot: HelpCenterEnchantmentsCatalog,
        returnTo: () -> Unit,
        requestedPage: Int = 0,
    ) {
        val categories = HelpCenterEnchantmentsEquipment.entries
        val pages = ((categories.size + EQUIPMENT_PAGE_SIZE - 1) / EQUIPMENT_PAGE_SIZE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val pageCategories = categories.drop(page * EQUIPMENT_PAGE_SIZE).take(EQUIPMENT_PAGE_SIZE)
        navigation.visit(player) { openEquipment(player, snapshot, returnTo, page) }
        val buttons = pageCategories.map { equipment ->
            button("equip_${equipment.ordinal}", "ui.${equipment.configKey}") {
                openResults(player, snapshot, returnTo, equipment, "", 0) {
                    openEquipment(player, snapshot, returnTo, page)
                }
            }
        } + buildList {
            if (page > 0) add(button("previous", "ui.page-previous") { openEquipment(player, snapshot, returnTo, page - 1) })
            if (page + 1 < pages) add(button("next", "ui.page-next") { openEquipment(player, snapshot, returnTo, page + 1) })
        }
        show(
            player,
            PaperDialogScreen(
                id = "help.enchantments.equipment",
                title = text("ui.equipment-title"),
                body = listOf(PaperDialogBody(text("ui.equipment-summary", "page" to (page + 1).toString(), "pages" to pages.toString()), 468)),
                buttons = buttons,
                exitButton = back { openOverview(player, returnTo, snapshot) },
                columns = 2,
            ),
        )
    }

    private fun openResults(
        player: Player,
        snapshot: HelpCenterEnchantmentsCatalog,
        returnTo: () -> Unit,
        equipment: HelpCenterEnchantmentsEquipment,
        query: String,
        requestedPage: Int,
        backTo: () -> Unit,
    ) {
        val normalizedQuery = query.trim().take(MAX_QUERY_LENGTH)
        val results = snapshot.search(normalizedQuery, equipment)
        val pages = ((results.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val from = page * PAGE_SIZE
        val pageEntries = results.drop(from).take(PAGE_SIZE)
        navigation.visit(player) {
            openResults(player, snapshot, returnTo, equipment, normalizedQuery, page, backTo)
        }
        val buttons = buildList {
            add(contextButton("search_submit", "ui.search-submit") { context ->
                openResults(player, snapshot, returnTo, equipment, context.text(SEARCH_INPUT).orEmpty(), 0, backTo)
            })
            pageEntries.forEachIndexed { index, enchantment ->
                add(button(
                    "enchant_$index",
                    "ui.entry-label",
                    "ui.entry-tooltip",
                    "name" to enchantment.name,
                    "group" to groupLabel(enchantment.group),
                    "level" to enchantment.maxLevel.toString(),
                    "materials" to materialSummary(enchantment),
                ) {
                    openDetails(player, snapshot, returnTo, enchantment) {
                        openResults(player, snapshot, returnTo, equipment, normalizedQuery, page, backTo)
                    }
                })
            }
            if (page > 0) {
                add(button("previous", "ui.page-previous") {
                    openResults(player, snapshot, returnTo, equipment, normalizedQuery, page - 1, backTo)
                })
            }
            if (page + 1 < pages) {
                add(button("next", "ui.page-next") {
                    openResults(player, snapshot, returnTo, equipment, normalizedQuery, page + 1, backTo)
                })
            }
        }
        val summary = if (results.isEmpty()) "ui.search-empty" else "ui.search-summary"
        show(
            player,
            PaperDialogScreen(
                id = "help.enchantments.results",
                title = text("ui.title"),
                body = listOf(PaperDialogBody(text(summary, "count" to results.size.toString(), "page" to (page + 1).toString(), "pages" to pages.toString()), 468)),
                inputs = listOf(PaperDialogTextInput(
                    SEARCH_INPUT,
                    text("ui.search-input"),
                    initial = normalizedQuery,
                    maxLength = MAX_QUERY_LENGTH,
                    width = 360,
                )),
                buttons = buttons,
                exitButton = back(backTo),
                columns = 2,
            ),
        )
    }

    private fun openDetails(
        player: Player,
        snapshot: HelpCenterEnchantmentsCatalog,
        returnTo: () -> Unit,
        enchantment: HelpCenterEnchantment,
        backTo: () -> Unit,
    ) {
        navigation.visit(player) { openDetails(player, snapshot, returnTo, enchantment, backTo) }
        val soldByEnchanter = isSoldByEnchanter(enchantment)
        val purchaseAvailable = soldByEnchanter && canPurchase(player, enchantment.id)
        val body = buildList {
            add(presentation.filledFacts(
                rows = listOf(
                    text("ui.details-group-label") to Component.text(groupLabel(enchantment.group)),
                    text("ui.details-level-label") to Component.text(enchantment.maxLevel.toString()),
                    text("ui.details-compatible-label") to Component.text(materialSummary(enchantment)),
                ).map { listOf(it) },
                width = 400,
            ))
            add(PaperDialogBody(text("ui.description", "description" to enchantment.description), 468))
            if (enchantment.maxLevelDescription != enchantment.description) {
                add(PaperDialogBody(text(
                    "ui.level-description",
                    "level" to enchantment.maxLevel.toString(),
                    "description" to enchantment.maxLevelDescription,
                ), 468))
            }
            add(PaperDialogBody(text("ui.details-application"), 468))
            if (soldByEnchanter && !purchaseAvailable) {
                add(PaperDialogBody(text("ui.details-purchase-spawn-only"), 468))
            }
        }
        show(
            player,
            PaperDialogScreen(
                id = "help.enchantments.details",
                title = text("ui.details-title", "name" to enchantment.name),
                body = body,
                buttons = buildList {
                    if (purchaseAvailable) add(button("purchase_book", "ui.purchase-label", "ui.purchase-tooltip") {
                        val opened = openPurchase(player, enchantment.id) {
                            openDetails(player, snapshot, returnTo, enchantment, backTo)
                        }
                        if (!opened) openDetails(player, snapshot, returnTo, enchantment, backTo)
                    })
                    add(button("acquisition", "ui.acquisition-label", "ui.acquisition-tooltip") {
                        openAcquisition(player, snapshot, returnTo) { openDetails(player, snapshot, returnTo, enchantment, backTo) }
                    })
                },
                exitButton = back(backTo),
                columns = 2,
            ),
        )
    }

    private fun openAcquisition(
        player: Player,
        snapshot: HelpCenterEnchantmentsCatalog,
        returnTo: () -> Unit,
        backTo: () -> Unit = { openOverview(player, returnTo, snapshot) },
    ) {
        openSources(player, null, backTo)
    }

    private fun openSpecialItems(
        player: Player,
        snapshot: HelpCenterEnchantmentsCatalog,
        returnTo: () -> Unit,
        requestedPage: Int = 0,
    ) {
        val topics = HelpCenterEnchantmentGuideTopic.entries.filterNot { it.container }
        val pages = ((topics.size + SPECIAL_PAGE_SIZE - 1) / SPECIAL_PAGE_SIZE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        navigation.visit(player) { openSpecialItems(player, snapshot, returnTo, page) }
        show(player, PaperDialogScreen(
            id = "help.enchantments.special",
            title = text("ui.special-title"),
            body = listOf(PaperDialogBody(text("ui.special-body",
                "page" to (page + 1).toString(), "pages" to pages.toString()), 468)),
            buttons = buildList {
                topics.drop(page * SPECIAL_PAGE_SIZE).take(SPECIAL_PAGE_SIZE).forEach { topic ->
                    add(topicButton(player, topic) { openSpecialItems(player, snapshot, returnTo, page) })
                }
                if (page > 0) add(button("previous", "ui.page-previous") {
                    openSpecialItems(player, snapshot, returnTo, page - 1)
                })
                if (page + 1 < pages) add(button("next", "ui.page-next") {
                    openSpecialItems(player, snapshot, returnTo, page + 1)
                })
            },
            exitButton = back { openOverview(player, returnTo, snapshot) },
            columns = 2,
        ))
    }

    private fun openBoxes(player: Player, returnTo: () -> Unit) {
        navigation.visit(player) { openBoxes(player, returnTo) }
        show(player, PaperDialogScreen(
            id = "help.enchantments.boxes",
            title = text("ui.boxes-title"),
            body = listOf(PaperDialogBody(text("ui.boxes-body"), 468)),
            buttons = HelpCenterEnchantmentGuideTopic.entries.filter { it.container }.map { topic ->
                topicButton(player, topic) { openBoxes(player, returnTo) }
            },
            exitButton = back(returnTo),
            columns = 2,
        ))
    }

    fun openTopic(player: Player, topic: HelpCenterEnchantmentGuideTopic, returnTo: () -> Unit) {
        navigation.visit(player) { openTopic(player, topic, returnTo) }
        val related = when (topic) {
            HelpCenterEnchantmentGuideTopic.SOUL_GEM -> listOf(HelpCenterEnchantmentGuideTopic.SOUL_TRACKER)
            HelpCenterEnchantmentGuideTopic.SOUL_TRACKER -> listOf(HelpCenterEnchantmentGuideTopic.SOUL_GEM)
            HelpCenterEnchantmentGuideTopic.SECRET_DUST -> listOf(HelpCenterEnchantmentGuideTopic.MAGIC_DUST, HelpCenterEnchantmentGuideTopic.MYSTERY_DUST)
            HelpCenterEnchantmentGuideTopic.MYSTERY_DUST -> listOf(HelpCenterEnchantmentGuideTopic.SECRET_DUST)
            else -> emptyList()
        }
        show(player, PaperDialogScreen(
            id = "help.enchantments.topic.${topic.id}",
            title = text(topic.labelKey),
            body = listOf(presentation.panel(text(topic.bodyKey), 468, DialogTables.PanelSkin.PLUM)),
            buttons = listOf(button("sources", "ui.sources-label", "ui.sources-tooltip") {
                openSources(player, topic, { openTopic(player, topic, returnTo) })
            }) + related.map { target -> topicButton(player, target) { openTopic(player, topic, returnTo) } },
            exitButton = back(returnTo),
            columns = 2,
        ))
    }

    private fun openSources(
        player: Player,
        topic: HelpCenterEnchantmentGuideTopic?,
        returnTo: () -> Unit,
        requestedPage: Int = 0,
    ) {
        val token = navigation.visit(player) { openSources(player, topic, returnTo, requestedPage) }
        val screenId = if (topic == null) "help.enchantments.acquisition" else "help.enchantments.sources.${topic.id}"
        val title = text(topic?.labelKey ?: "ui.acquisition-title")
        show(player, PaperDialogScreen(
            id = screenId,
            title = title,
            body = listOf(presentation.panel(text("ui.sources-loading"), 468)),
            buttons = emptyList(),
            exitButton = back(returnTo),
        ))
        acquisition(topic?.id ?: "overview").whenCompleteSync(tasks) { result, failure ->
            if (!player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
            val sources = if (failure == null && result != null) result else HelpCenterEnchantmentAcquisition(
                listOf(HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "rewards"))),
            )
            val detailFacts = sources.facts.filterNot { fact ->
                fact.key == "scope" || (fact.key == "pool-item-value" &&
                    fact.values["valueKind"] in setOf(null, "", "none") &&
                    fact.values["group"].isNullOrBlank() && fact.values["level"].isNullOrBlank())
            }.sortedBy { fact ->
                when (fact.key) {
                    "world-cache" -> 0
                    "mining-cache" -> 1
                    "farming-cache" -> 2
                    "farming-cache-materials" -> 3
                    "mining-cache-materials" -> 4
                    "pool-item", "personal-loot-source", "crate-source", "quest-source", "denizen-monsters",
                    "denizen-bosses", "tinkerer-source", "native-mob", "natural-books", "natural-dust", "boss-source" -> 5
                    "cmi-kit-source", "gkit-source" -> 6
                    "box-rolls" -> 7
                    "box-content", "pool-item-value", "slot-settings", "secret-result" -> 8
                    "pool-source" -> 9
                    "unavailable" -> 11
                    else -> 10
                }
            }.ifEmpty { listOf(HelpCenterEnchantmentSourceFact("no-drop")) }
            val discoveryActivities = listOf("world-cache", "mining-cache", "farming-cache")
                .filter { key -> detailFacts.any { it.key == key } }
                .map { plainText.serialize(text("ui.source-discovery-$it")) }
            val facts = if (discoveryActivities.isEmpty()) detailFacts else listOf(
                HelpCenterEnchantmentSourceFact("discovery-index", mapOf(
                    "activities" to discoveryActivities.joinToString(" · ").replaceFirstChar { it.uppercaseChar() },
                )),
            ) + detailFacts
            val pages = ((facts.size + SOURCE_PAGE_SIZE - 1) / SOURCE_PAGE_SIZE).coerceAtLeast(1)
            val page = requestedPage.coerceIn(0, pages - 1)
            val pageFacts = facts.drop(page * SOURCE_PAGE_SIZE).take(SOURCE_PAGE_SIZE)
            val onPage = pageFacts.flatMap { it.values.values }.toSet()
            val related = if (topic == null) emptyList()
            else sources.relatedTopics.distinct().mapNotNull(HelpCenterEnchantmentGuideTopic.byId::get)
                .filter { it != topic && (!topic.container || it.id in onPage || ("orb" in onPage && it.id.startsWith("orb_"))) }
            show(player, PaperDialogScreen(
                id = screenId,
                title = title,
                body = buildList {
                    sources.facts.firstOrNull { it.key == "scope" }?.let { add(PaperDialogBody(sourceFact(it), 468)) }
                    add(sourcePanel(pageFacts))
                    if (pages > 1) add(PaperDialogBody(text("ui.sources-page",
                        "page" to (page + 1).toString(), "pages" to pages.toString()), 468))
                },
                buttons = buildList {
                    if (topic?.id in setOf("secret", "magic", "mystery") && facts.any { it.key == "tinkerer-source" }) {
                        add(button("tinkerer", "ui.tinkerer-label", "ui.tinkerer-tooltip") { executeInventory(player, "tinkerer") })
                    }
                    if (topic == null) add(button("boxes", "ui.boxes-label", "ui.boxes-tooltip") {
                        openBoxes(player) { openSources(player, null, returnTo, page) }
                    })
                    related.forEach { target -> add(topicButton(player, target) {
                        openSources(player, topic, returnTo, page)
                    }) }
                    if (page > 0) add(button("previous", "ui.page-previous") {
                        openSources(player, topic, returnTo, page - 1)
                    })
                    if (page + 1 < pages) add(button("next", "ui.page-next") {
                        openSources(player, topic, returnTo, page + 1)
                    })
                },
                exitButton = back(returnTo),
                columns = 2,
            ))
        }
    }

    private fun topicButton(player: Player, topic: HelpCenterEnchantmentGuideTopic, returnTo: () -> Unit): PaperDialogButton =
        button("item_${topic.id}", topic.labelKey, topic.summaryKey) { openTopic(player, topic, returnTo) }
            .let { it.copy(label = it.label.append(Component.text(" ›"))) }

    private fun sourcePanel(facts: List<HelpCenterEnchantmentSourceFact>): PaperDialogBody {
        val rows = facts.flatMap { fact ->
            listOf(Component.newline(), Component.newline(), sourceFact(fact))
        }
        return presentation.panel(Component.empty().children(listOf(text("ui.sources-heading")) + rows), 468)
    }

    private fun sourceFact(fact: HelpCenterEnchantmentSourceFact): Component {
        val minimum = fact.values["min"].orEmpty()
        val maximum = fact.values["max"].orEmpty()
        val range = if (minimum == maximum) minimum else "$minimum–$maximum"
        // valueKind selects a sentence; it is metadata, not a MiniMessage placeholder.
        val placeholders = (fact.values.filterKeys { it != "valueKind" } + ("range" to range))
            .map { (key, value) -> key to sourceValue(key, value) }.toTypedArray()
        var result = text("ui.source-${fact.key}", *placeholders)
        if (fact.key == "pool-item" && !fact.values["per_box"].isNullOrBlank()) {
            result = result.append(Component.newline()).append(text("ui.source-per-box", *placeholders))
        }
        if (fact.key in setOf("box-content", "pool-item-value")) {
            if (!fact.values["group"].isNullOrBlank()) result = result.append(Component.newline()).append(text("ui.source-content-group", *placeholders))
            if (!fact.values["level"].isNullOrBlank()) result = result.append(Component.newline()).append(text("ui.source-content-level", *placeholders))
            val valueKind = fact.values["valueKind"].orEmpty()
            if (valueKind.isNotBlank() && valueKind != "none") {
                result = result.append(Component.newline()).append(text("ui.source-content-$valueKind", *placeholders))
            }
        }
        return result
    }

    private fun sourceValue(key: String, value: String): String = when (key) {
        "materials" -> value.split(',').asSequence().map(String::trim).filter(String::isNotEmpty).take(4)
            .joinToString(", ", transform = ::sourceMaterialName)
        "cooldown" -> DURATION.matchEntire(value)?.let { match ->
            val unit = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "t" -> "тиков"
                "s" -> "с"
                "m" -> "мин"
                "h" -> "ч"
                "d" -> "дн."
                else -> return@let value
            }
            "${match.groupValues[1].replace('.', ',')} $unit"
        } ?: value
        "group" -> if (value.isBlank()) value else if (value == "random") plainText.serialize(text("ui.source-name-random")) else groupLabel(value)
        "box", "item", "source", "pool", "common_pool", "bonus_pool", "server" -> {
            val topic = HelpCenterEnchantmentGuideTopic.byId[value]
            if (topic != null) plainText.serialize(text(topic.labelKey))
            else guide.optionalText("ui.source-name-${value.removePrefix("crate:").lowercase(Locale.ROOT).replace('_', '-')}")
                ?.let { plainText.serialize(miniMessage.deserialize(it)) } ?: value
        }
        else -> value
    }

    /** Uses only the already loaded translation map; unknown or unavailable translations stay literal. */
    private fun sourceMaterialName(id: String): String {
        val material = try { Material.valueOf(id.uppercase(Locale.ROOT)) }
        catch (_: IllegalArgumentException) { return id }
        val translated = try { HookRegistry.translatorHook?.translate(material) }
        catch (_: Exception) { null }
        catch (_: LinkageError) { null }
        return translated?.trim()?.takeIf(String::isNotEmpty)
            ?: material.name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    }

    private fun materialSummary(enchantment: HelpCenterEnchantment): String {
        val specific = HelpCenterEnchantmentsEquipment.entries
            .filter { it != HelpCenterEnchantmentsEquipment.ALL && it != HelpCenterEnchantmentsEquipment.OTHER }
            .filter { it.matches(enchantment.materials) }
            .map { plainText.serialize(text("ui.${it.configKey}")) }
            .distinct()
        return specific.ifEmpty {
            if (HelpCenterEnchantmentsEquipment.OTHER.matches(enchantment.materials)) {
                listOf(plainText.serialize(text("ui.equipment-other")))
            } else emptyList()
        }.joinToString(", ").ifBlank { guide.text("ui.unknown-materials") }
    }

    private fun groupLabel(group: String): String {
        val key = when (group.uppercase(Locale.ROOT)) {
            "SIMPLE" -> "ui.group-simple"
            "UNIQUE" -> "ui.group-unique"
            "ELITE" -> "ui.group-elite"
            "ULTIMATE" -> "ui.group-ultimate"
            "LEGENDARY" -> "ui.group-legendary"
            "FABLED" -> "ui.group-fabled"
            "CHEATER" -> "ui.group-cheater"
            else -> "ui.group-other"
        }
        return plainText.serialize(text(key))
    }

    private fun isSoldByEnchanter(enchantment: HelpCenterEnchantment): Boolean =
        enchantment.availableFromEnchanter && enchantment.group.uppercase(Locale.ROOT) in ENCHANTER_GROUPS

    private fun canOpenEnchanter(player: Player, snapshot: HelpCenterEnchantmentsCatalog): Boolean =
        snapshot.available && snapshot.entries.any { isSoldByEnchanter(it) && canPurchase(player, it.id) }

    private fun back(action: () -> Unit) = PaperDialogButton(
        PaperDialogActionId.of("back"), miniMessage.deserialize(settings.text("back-label")).decoration(TextDecoration.ITALIC, false), width = 200, onClick = { action() },
    )

    private fun button(
        id: String,
        labelKey: String,
        tooltipKey: String? = null,
        vararg placeholders: Pair<String, String>,
        action: () -> Unit,
    ) = PaperDialogButton(
        PaperDialogActionId.of(id.replace('-', '_')),
        text(labelKey, *placeholders),
        tooltipKey?.let { text(it, *placeholders) } ?: Component.empty(),
        width = 230,
        onClick = { action() },
    )

    private fun contextButton(
        id: String,
        labelKey: String,
        action: (PaperDialogClickContext) -> Unit,
    ) = PaperDialogButton(
        PaperDialogActionId.of(id), text(labelKey), width = 230, onClick = action,
    )

    private fun text(key: String, vararg placeholders: Pair<String, String>): Component = miniMessage.deserialize(
        guide.text(key),
        TagResolver.resolver(placeholders.map { (name, value) -> Placeholder.unparsed(name, value) }),
    ).decoration(TextDecoration.ITALIC, false)

    override fun close() {
        tasks.close()
    }

    companion object {
        private val SEARCH_INPUT = PaperDialogInputId.of("enchant_search")
        private const val MAX_QUERY_LENGTH = 48
        private const val PAGE_SIZE = 8
        private const val SPECIAL_PAGE_SIZE = 10
        private const val SOURCE_PAGE_SIZE = 3
        private const val EQUIPMENT_PAGE_SIZE = 10
        private val DURATION = Regex("([0-9]+(?:\\.[0-9]+)?)([tsmhd])", RegexOption.IGNORE_CASE)
        private val ENCHANTER_GROUPS = setOf("SIMPLE", "UNIQUE", "ELITE", "ULTIMATE", "LEGENDARY", "FABLED")
    }
}
