package ru.arc.parkour

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil

class ArcParkourMenuController(
    private val settings: ArcParkourSettings,
    private val gateway: ArcParkourGateway,
    private val tasks: LifecycleTaskScope,
) : Listener {
    fun openRoot(player: Player) {
        val grouped = catalog(player) ?: return
        val entries = grouped.map { category ->
            val definition = category.definition
            val item = ArcMenus.item(
                "parkour-category",
                PaperMenuItemRenderContext(
                    values = mapOf(
                        "category" to parkourItemText(definition.display),
                        "ready" to Component.text(category.courses.size),
                    ),
                    repeats = mapOf(
                        "description" to definition.description.map { mapOf("line" to parkourItemText(it)) },
                    ),
                ),
            ).withType(definition.icon)
            ArcMenus.entry(item) {
                click(it)
                openCategory(it, definition.id)
            }
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.PARKOUR_ROOT,
            parkourItemText(settings.gui.string("titles.root", "Паркур")),
            regions = mapOf(ArcMenuSchema.PARKOUR_CATEGORIES to entries),
        )
        click(player)
    }

    private fun openCategory(player: Player, categoryId: String) {
        val category = catalog(player)?.firstOrNull { it.definition.id == categoryId } ?: return openRoot(player)
        if (category.courses.isEmpty()) {
            player.sendMessage(TextUtil.mm(settings.noReadyCoursesMessage, true))
            bass(player)
            return
        }
        val definition = category.definition
        val entries: List<PaperMenuEntry> = category.courses.map { card ->
            val item = ArcMenus.item(
                "parkour-course",
                PaperMenuItemRenderContext(
                    values = mapOf(
                        "course" to Component.text(card.displayName),
                        "checkpoints" to Component.text(card.course.checkpoints),
                        "players" to Component.text(card.course.players),
                    ),
                    flags = if (card.course.completed) setOf("completed") else emptySet(),
                ),
            ).withType(definition.courseIcons[(card.sequence - 1) % definition.courseIcons.size]).also { stack ->
                if (card.course.completed) stack.editMeta { it.setEnchantmentGlintOverride(true) }
            }
            ArcMenus.entry(item) {
                it.closeInventory()
                if (!gateway.join(it, card.course.id)) bass(it)
            }
        }
        val titleTemplate = settings.gui.string("titles.category", "Паркур — <category>")
        val title = titleTemplate.replace("<category>", definition.name)
        ArcMenus.open(
            player,
            ArcMenuSchema.PARKOUR_CATEGORY,
            parkourItemText(title),
            elements = mapOf("back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.PARKOUR_CATEGORY, "back")) {
                click(it)
                openRoot(it)
            }),
            regions = mapOf(ArcMenuSchema.PARKOUR_COURSES to entries),
        )
        click(player)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!settings.interceptJoinAllCommand || !ParkourJoinAllCommand.matches(event.message)) return
        if (!event.player.hasPermission(settings.joinAllPermission)) return
        event.isCancelled = true
        openRoot(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLegacyMenu(event: InventoryOpenEvent) {
        val visibleTitle = PlainTextComponentSerializer.plainText().serialize(event.view.title())
        if (!settings.interceptLegacyMenu || !visibleTitle.equals(settings.legacyMenuTitle, ignoreCase = true)) return
        val player = event.player as? Player ?: return
        if (!player.hasPermission(settings.joinAllPermission)) return
        event.isCancelled = true
        tasks.runLater(1) { if (player.isOnline) openRoot(player) }
    }

    private fun catalog(player: Player): List<ParkourCategorySnapshot>? =
        runCatching { ArcParkourCatalog.group(gateway.readyCourses(player), settings.categories) }
            .onFailure { error("Unable to render ARC Parkour catalog", it) }
            .getOrElse {
                player.sendMessage(TextUtil.mm(settings.unavailableMessage, true))
                bass(player)
                return null
            }

    private fun click(player: Player) = player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.1f)

    private fun bass(player: Player) = player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f)
}

internal fun parkourItemText(value: String): Component =
    TextUtil.mm(value, true).decoration(TextDecoration.ITALIC, false)
