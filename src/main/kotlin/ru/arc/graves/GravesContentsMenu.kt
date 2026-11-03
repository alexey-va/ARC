package ru.arc.graves

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.menu.MenuElementId
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import java.util.UUID

/**
 * Read-only native chest. The owning service supplies detached, authorized snapshots on Paper.
 * Every page requests fresh data; the sole withdrawal action delegates to the durable grave engine.
 * No entry permits vanilla inventory/cursor transfers, including in administrator mode.
 */
internal class GravesContentsMenu(
    private val settings: GravesSettings,
    private val worldName: (String) -> Component,
    private val canClaim: (Player, NativeGraveRecord) -> Boolean,
    private val claim: (Player, UUID, Long) -> Unit,
    private val requestPage: (Player, UUID, Boolean, Int) -> Unit,
    private val show: (Player, PaperMenuContent) -> Unit = { player, content ->
        ArcMenus.open(player, ArcMenuSchema.GRAVE_CONTENTS, content.title,
            content.elements.mapKeys { it.key.value }, content.regions)
    },
    private val item: (String, PaperMenuItemRenderContext) -> ItemStack = { element, context ->
        ArcMenus.item(ArcMenuSchema.GRAVE_CONTENTS, element, context)
    },
    private val decode: (String) -> ItemStack = NativeGraveItems::decode,
    private val close: (Player) -> Unit = { it.closeInventory() },
    private val capacity: () -> Int = {
        ArcMenus.current().catalog.require(ArcMenuSchema.GRAVE_CONTENTS).region(ArcMenuSchema.GRAVE_ITEMS).size
    },
) {
    fun open(player: Player, record: NativeGraveRecord, readOnly: Boolean = false, page: Int = 0) {
        if (!authorized(player, record, readOnly)) return
        val pageSize = capacity().also { require(it in 1..54) }
        val pages = maxOf(1, (record.items.size + pageSize - 1) / pageSize)
        val currentPage = page.coerceIn(0, pages - 1)
        val stacks = runCatching {
            record.items.drop(currentPage * pageSize).take(pageSize).map { raw ->
                decode(raw).clone().also { require(!it.isEmpty) }
            }
        }.getOrElse {
            player.sendMessage(settings.text("contents-unavailable"))
            return
        }
        val available = !readOnly && canClaim(player, record)
        val context = PaperMenuItemRenderContext(
            values = mapOf(
                "owner" to Component.text(record.ownerName),
                "world" to worldName(record.world),
                "experience" to Component.text(record.experience.toString()),
                "expiry" to graveExpiryText(settings, record.expiresAt),
                "page" to Component.text((currentPage + 1).toString()), "pages" to Component.text(pages.toString()),
            ),
            flags = buildSet { if (readOnly) add("read-only"); if (available) add("claimable") },
        )
        val elements = linkedMapOf(
            MenuElementId.of("summary") to PaperMenuEntry(item("summary", context), enabled = false, acceptedClicks = emptySet()),
        )
        if (!readOnly) elements[MenuElementId.of("claim")] = ArcMenus.entry(item("claim", context), enabled = available) { viewer ->
            if (authorized(viewer, record, false)) {
                if (canClaim(viewer, record)) {
                    close(viewer)
                    claim(viewer, record.id, record.revision)
                } else requestPage(viewer, record.id, false, currentPage)
            }
        }
        if (currentPage > 0) elements[MenuElementId.of("previous")] = ArcMenus.entry(item("previous", context)) { viewer ->
            if (authorized(viewer, record, readOnly)) requestPage(viewer, record.id, readOnly, currentPage - 1)
        }
        if (currentPage < pages - 1) elements[MenuElementId.of("next")] = ArcMenus.entry(item("next", context)) { viewer ->
            if (authorized(viewer, record, readOnly)) requestPage(viewer, record.id, readOnly, currentPage + 1)
        }
        show(player, PaperMenuContent(
            title = if (readOnly) settings.text("admin-contents-title", "owner" to record.ownerName) else settings.text("contents-title"),
            elements = elements,
            regions = mapOf(ArcMenuSchema.GRAVE_ITEMS to stacks.map {
                PaperMenuEntry(it, enabled = false, acceptedClicks = emptySet())
            }),
        ))
    }

    private fun authorized(player: Player, record: NativeGraveRecord, readOnly: Boolean): Boolean {
        if (!player.isOnline || player.isDead) return false
        if (readOnly && !player.hasPermission(GRAVES_ADMIN_PERMISSION)) {
            close(player)
            player.sendMessage(settings.text("admin-denied"))
            return false
        }
        if ((!readOnly && record.ownerId != player.uniqueId) || record.phase != NativeGravePhase.ACTIVE ||
            (record.expiresAt >= 0 && record.expiresAt <= System.currentTimeMillis())) {
            close(player)
            player.sendMessage(settings.text("list-stale"))
            return false
        }
        return true
    }

}

internal const val GRAVES_ADMIN_PERMISSION = "arc.graves.admin"
