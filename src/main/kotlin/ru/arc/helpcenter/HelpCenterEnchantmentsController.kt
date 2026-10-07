package ru.arc.helpcenter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.util.Locale

/** Player-facing catalogue and verified usage guide for the active AE registry. */
internal class HelpCenterEnchantmentsController(
    private val settings: HelpCenterSettings,
    private val guide: HelpCenterEnchantmentsGuideSettings,
    private val navigation: HelpCenterNavigation,
    private val show: (Player, PaperDialogScreen) -> Unit,
    private val executeInventory: (Player, String) -> Boolean,
    private val catalog: () -> HelpCenterEnchantmentsCatalog = HelpCenterEnchantmentsCatalog::fromAeApi,
) {
    private val miniMessage = MiniMessage.miniMessage()
    private val plainText = PlainTextComponentSerializer.plainText()

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
        val body = buildList {
            add(PaperDialogBody(text(
                "ui.details-summary",
                "group" to groupLabel(enchantment.group),
                "level" to enchantment.maxLevel.toString(),
                "materials" to materialSummary(enchantment),
            ), 468))
            add(PaperDialogBody(text("ui.description", "description" to enchantment.description), 468))
            if (enchantment.maxLevelDescription != enchantment.description) {
                add(PaperDialogBody(text(
                    "ui.level-description",
                    "level" to enchantment.maxLevel.toString(),
                    "description" to enchantment.maxLevelDescription,
                ), 468))
            }
            add(PaperDialogBody(text(
                if (isSoldByEnchanter(enchantment)) "ui.details-acquisition-enchanter" else "ui.details-acquisition-unconfirmed",
            ), 468))
        }
        show(
            player,
            PaperDialogScreen(
                id = "help.enchantments.details",
                title = text("ui.details-title", "name" to enchantment.name),
                body = body,
                buttons = emptyList(),
                exitButton = back(backTo),
                columns = 2,
            ),
        )
    }

    private fun openAcquisition(
        player: Player,
        snapshot: HelpCenterEnchantmentsCatalog,
        returnTo: () -> Unit,
    ) {
        navigation.visit(player) { openAcquisition(player, snapshot, returnTo) }
        show(
            player,
            PaperDialogScreen(
                id = "help.enchantments.acquisition",
                title = text("ui.acquisition-title"),
                body = listOf(PaperDialogBody(text("ui.acquisition-body"), 468)),
                buttons = if (snapshot.available) listOf(
                    button("enchanter", "ui.enchanter-label", "ui.enchanter-tooltip") { executeInventory(player, "enchanter") },
                    button("tinkerer", "ui.tinkerer-label", "ui.tinkerer-tooltip") { executeInventory(player, "tinkerer") },
                    button("alchemist", "ui.alchemist-label", "ui.alchemist-tooltip") { executeInventory(player, "alchemist") },
                ) else emptyList(),
                exitButton = back { openOverview(player, returnTo, snapshot) },
                columns = 2,
            ),
        )
    }

    private fun openSpecialItems(
        player: Player,
        snapshot: HelpCenterEnchantmentsCatalog,
        returnTo: () -> Unit,
    ) {
        navigation.visit(player) { openSpecialItems(player, snapshot, returnTo) }
        val cards = listOf(
            listOf("white", "ui.special-white-label", "ui.special-white-body"),
            listOf("black", "ui.special-black-label", "ui.special-black-body"),
            listOf("magic_dust", "ui.special-magic-dust-label", "ui.special-magic-dust-body"),
            listOf("holy_white", "ui.special-holy-white-label", "ui.special-holy-white-body"),
            listOf("dust_packets", "ui.special-dust-packets-label", "ui.special-dust-packets-body"),
            listOf("randomizer", "ui.special-randomizer-label", "ui.special-randomizer-body"),
            listOf("transmog", "ui.special-transmog-label", "ui.special-transmog-body"),
            listOf("soul_gem", "ui.special-soul-gem-label", "ui.special-soul-gem-body"),
            listOf("orbs", "ui.special-orbs-label", "ui.special-orbs-body"),
            listOf("trackers", "ui.special-trackers-label", "ui.special-trackers-body"),
        )
        show(
            player,
            PaperDialogScreen(
                id = "help.enchantments.special",
                title = text("ui.special-title"),
                body = listOf(PaperDialogBody(text("ui.special-body"), 468)),
                buttons = cards.map { card ->
                    val (id, title, body) = card
                    button("item_$id", title) {
                        openSpecialItem(player, snapshot, returnTo, title, body) {
                            openSpecialItems(player, snapshot, returnTo)
                        }
                    }
                },
                exitButton = back { openOverview(player, returnTo, snapshot) },
                columns = 2,
            ),
        )
    }

    private fun openSpecialItem(
        player: Player,
        snapshot: HelpCenterEnchantmentsCatalog,
        returnTo: () -> Unit,
        title: String,
        body: String,
        backTo: () -> Unit,
    ) {
        navigation.visit(player) { openSpecialItem(player, snapshot, returnTo, title, body, backTo) }
        show(
            player,
            PaperDialogScreen(
                id = "help.enchantments.special.item",
                title = text(title),
                body = listOf(PaperDialogBody(text(body), 468)),
                buttons = emptyList(),
                exitButton = back(backTo),
                columns = 2,
            ),
        )
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

    companion object {
        private val SEARCH_INPUT = PaperDialogInputId.of("enchant_search")
        private const val MAX_QUERY_LENGTH = 48
        private const val PAGE_SIZE = 8
        private const val EQUIPMENT_PAGE_SIZE = 10
        private val ENCHANTER_GROUPS = setOf("SIMPLE", "UNIQUE", "ELITE", "ULTIMATE", "LEGENDARY", "FABLED")
    }
}
