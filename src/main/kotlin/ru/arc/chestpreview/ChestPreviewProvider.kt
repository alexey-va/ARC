package ru.arc.chestpreview

import net.kyori.adventure.text.Component
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.Container
import org.bukkit.block.EnderChest
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import ru.arc.paper.api.ArcInspectionFrame
import ru.arc.paper.api.ArcInspectionProvider
import ru.arc.paper.api.InspectionHologramAnchor

/** Both halves have already passed access checks, in physical slot order. */
internal data class ChestPreviewTarget(
    val states: List<BlockState>,
    val anchor: InspectionHologramAnchor,
    val containerBounds: BoundingBox? = null,
)

internal data class ChestPreviewFrame(
    val anchor: InspectionHologramAnchor,
    val items: List<ItemStack>,
    val containerBounds: BoundingBox? = null,
    val counts: List<Int> = items.map { it.amount },
)

/** Keeps icon selection in the shared inspector's priority arbitration. */
internal class ChestPreviewProvider(
    private val settings: ChestPreviewSettings,
    private val resolveTarget: (Player, Double) -> ChestPreviewTarget?,
    private val settingsFor: (Player) -> ChestPreviewSettings = { settings },
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
            val effectiveSettings = settingsFor(player)
            if (!effectiveSettings.enabled) return null
            val target = resolveTarget(player, effectiveSettings.maxDistance) ?: return null
            if (target.states.size !in 1..2 || target.anchor.worldId != player.world.uid) return null
            val (items, counts) = aggregateItems(player, target.states, effectiveSettings.maxItems) ?: return null
            val frame = ChestPreviewFrame(
                anchor = target.anchor.copy(y = target.anchor.y + effectiveSettings.verticalGap),
                items = items,
                containerBounds = target.containerBounds?.clone(),
                counts = counts,
            )
            capture?.invoke(player, frame)
            // Occupy this source without emitting a text card or a bossbar. The host
            // renders the captured icons only when this provider wins arbitration.
            ArcInspectionFrame(Component.empty(), Component.empty(), null)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun hasPermission(player: Player): Boolean =
        try { player.hasPermission("arc.chest-preview") } catch (_: Exception) { false }

    private fun aggregateItems(player: Player, states: List<BlockState>, maxItems: Int): Pair<List<ItemStack>, List<Int>>? {
        val items = mutableListOf<ItemStack>()
        val counts = mutableListOf<Int>()
        var inspectedSlots = 0
        for (state in states) {
            if (inspectedSlots >= 54) break
            // Ender-chest block states are only access points. Preview the viewer's own
            // private ender inventory, never an inventory attached to that world block.
            val inventory = when (state) {
                is EnderChest -> player.enderChest
                is Chest -> state.blockInventory
                is Container -> state.inventory
                else -> return null
            }
            val contents = inventory.contents
            val slots = minOf(contents.size, 54 - inspectedSlots)
            for (slot in 0 until slots) {
                val stack = contents[slot] ?: continue
                if (stack.type.isAir || stack.amount <= 0) continue
                val index = items.indexOfFirst { it.isSimilar(stack) }
                if (index >= 0) {
                    counts[index] += stack.amount
                } else if (items.size < maxItems) {
                    items += stack.clone().apply { amount = 1 }
                    counts += stack.amount
                }
            }
            inspectedSlots += slots
        }
        return items to counts
    }
}
