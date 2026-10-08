package ru.arc.chestpreview

import net.kyori.adventure.text.Component
import org.bukkit.block.BlockState
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.Container
import org.bukkit.block.EnderChest
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import ru.arc.bschests.PersonalLootModule
import ru.arc.bschests.PersonalLootPreview
import ru.arc.bschests.personalLootWithDebris
import ru.arc.paper.api.ArcInspectionFrame
import ru.arc.paper.api.ArcInspectionProvider
import ru.arc.paper.api.InspectionHologramAnchor
import java.util.UUID

/** Both halves have already passed access checks, in physical slot order. */
internal data class ChestPreviewTarget(
    val states: List<BlockState>,
    val anchor: InspectionHologramAnchor,
    val containerBounds: BoundingBox? = null,
    val blocks: List<Block> = emptyList(),
    val personalLootChestUuid: UUID? = null,
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
    private val personalLootPreview: (UUID, UUID?, List<Block>) -> PersonalLootPreview = PersonalLootModule::preview,
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
            val (items, counts) = previewItems(player, target, effectiveSettings.maxItems) ?: return null
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

    private fun previewItems(
        player: Player,
        target: ChestPreviewTarget,
        maxItems: Int,
    ): Pair<List<ItemStack>, List<Int>>? {
        val contents = personalLootPreview(player.uniqueId, target.personalLootChestUuid, target.blocks)
        return when (contents) {
            PersonalLootPreview.NotPersonal -> aggregateItems(player, target.states, maxItems)
            PersonalLootPreview.Unavailable -> null
            is PersonalLootPreview.Contents -> {
                val items = if (contents.usePhysicalTemplate) {
                    if (target.personalLootChestUuid == null) return null
                    val physical = readItems(player, target.states) ?: return null
                    // processChestOpen uses extractItems(), which removes empty slots before
                    // persisting the BetterStructures template. An entirely empty template
                    // becomes exhausted on first open, so it receives no debris.
                    val template = physical.filterNotNull()
                    if (template.isEmpty()) emptyList()
                    else personalLootWithDebris(template, player.uniqueId, target.personalLootChestUuid)
                } else {
                    contents.items
                }
                aggregateItems(items, maxItems)
            }
        }
    }

    private fun aggregateItems(player: Player, states: List<BlockState>, maxItems: Int): Pair<List<ItemStack>, List<Int>>? {
        val contents = readItems(player, states) ?: return null
        return aggregateItems(contents, maxItems)
    }

    /** Shared bounded inventory reader for ordinary containers and personal templates. */
    private fun readItems(player: Player, states: List<BlockState>): List<ItemStack?>? {
        val contents = mutableListOf<ItemStack?>()
        var inspectedSlots = 0
        for (state in states) {
            if (inspectedSlots >= 54) break
            // Ender-chest block states are access points; only the viewer's own
            // private inventory may be inspected.
            val inventory = when (state) {
                is EnderChest -> player.enderChest
                is Chest -> state.blockInventory
                is Container -> state.inventory
                else -> return null
            }
            val inventoryContents = inventory.contents
            val slots = minOf(inventoryContents.size, 54 - inspectedSlots)
            for (slot in 0 until slots) contents += inventoryContents[slot]?.clone()
            inspectedSlots += slots
        }
        return contents
    }

    private fun aggregateItems(contents: List<ItemStack?>, maxItems: Int): Pair<List<ItemStack>, List<Int>> {
        val icons = mutableListOf<ItemStack>()
        val counts = mutableListOf<Int>()
        for (stack in contents.take(54)) {
            if (stack == null || stack.type.isAir || stack.amount <= 0) continue
            val index = icons.indexOfFirst { it.isSimilar(stack) }
            if (index >= 0) {
                counts[index] += stack.amount
            } else if (icons.size < maxItems) {
                icons += stack.clone().apply { amount = 1 }
                counts += stack.amount
            }
        }
        return icons to counts
    }
}
