package ru.arc.chestpreview

import net.kyori.adventure.text.Component
import org.bukkit.block.Chest
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.paper.api.ArcInspectionFrame
import ru.arc.paper.api.ArcInspectionProvider
import ru.arc.paper.api.InspectionHologramAnchor

/** Both halves have already passed access checks, in physical slot order. */
internal data class ChestPreviewTarget(
    val halves: List<Chest>,
    val anchor: InspectionHologramAnchor,
)

internal data class ChestPreviewFrame(
    val anchor: InspectionHologramAnchor,
    val items: List<ItemStack>,
)

/** Keeps icon selection in the shared inspector's priority arbitration. */
internal class ChestPreviewProvider(
    private val settings: ChestPreviewSettings,
    private val resolveTarget: (Player, Double) -> ChestPreviewTarget?,
) : ArcInspectionProvider {
    private var capture: ((Player, ChestPreviewFrame) -> Unit)? = null

    /** A higher-priority winner, OFF mode or suppression leaves no captured icon scene. */
    fun selectedFrame(player: Player, arbitrate: () -> Unit): ChestPreviewFrame? {
        var selected: ChestPreviewFrame? = null
        val previous = capture
        capture = { viewer, frame -> if (viewer.uniqueId == player.uniqueId) selected = frame }
        return try {
            arbitrate()
            selected
        } finally {
            capture = previous
        }
    }

    override fun resolve(player: Player): ArcInspectionFrame? {
        if (!hasPermission(player)) return null
        return try {
            val target = resolveTarget(player, settings.maxDistance) ?: return null
            if (target.halves.size !in 1..2 || target.anchor.worldId != player.world.uid) return null
            val frame = ChestPreviewFrame(
                anchor = target.anchor.copy(y = target.anchor.y + settings.verticalGap),
                items = distinctItems(target.halves),
            )
            capture?.invoke(player, frame)
            // Occupy this source without emitting a text card or a bossbar. The host
            // renders the captured icons only when this provider wins arbitration.
            ArcInspectionFrame(Component.empty(), Component.empty(), null)
        } catch (_: Exception) {
            null
        }
    }

    private fun hasPermission(player: Player): Boolean =
        try { player.hasPermission("arc.chest-preview") } catch (_: Exception) { false }

    private fun distinctItems(halves: List<Chest>): List<ItemStack> {
        val items = mutableListOf<ItemStack>()
        var inspectedSlots = 0
        for (chest in halves) {
            if (inspectedSlots >= 54) break
            val contents = chest.blockInventory.contents
            val slots = minOf(contents.size, 54 - inspectedSlots)
            for (slot in 0 until slots) {
                val stack = contents[slot] ?: continue
                if (stack.type.isAir || stack.amount <= 0 || items.any { it.isSimilar(stack) }) continue
                items += stack.clone().apply { amount = 1 }
                if (items.size == settings.maxItems) return items
            }
            inspectedSlots += slots
        }
        return items
    }
}
