package ru.arc.iteminfo

import dev.lone.itemsadder.api.CustomBlock
import dev.lone.itemsadder.api.CustomFurniture
import dev.lone.itemsadder.api.CustomStack
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.entity.Entity
import org.bukkit.inventory.ItemStack
import ru.arc.hooks.HookRegistry
import java.util.Locale

/** Resolves only block identities owned by supported custom-content plugins. */
internal class ItemInfoBlockResolver(
    private val itemsAdder: (Block) -> ItemInfoTarget?,
    private val slimefun: (Block) -> ItemInfoTarget?,
    private val excluded: (Block) -> Boolean = { false },
) {
    fun resolve(block: Block): ItemInfoTarget? =
        if (excluded(block)) null else itemsAdder(block) ?: slimefun(block)
}

internal class ItemInfoTargetPolicy(
    private val managedCrate: (Location) -> Boolean,
) {
    fun filter(location: Location?, target: ItemInfoTarget?): ItemInfoTarget? =
        if (location != null && managedCrate(location)) null else target
}

internal class BukkitItemInfoTargetResolver(
    private val distance: Double,
    private val managedCrate: (Location) -> Boolean = ExcellentCratesItemInfoBridge::contains,
) {
    private val resolver = ItemInfoBlockResolver(::itemsAdder, ::slimefun) { managedCrate(it.location) }
    private val policy = ItemInfoTargetPolicy(managedCrate)

    fun resolve(player: Player): ItemInfoTarget? {
        val eye = player.eyeLocation
        val blockHit = player.rayTraceBlocks(distance, FluidCollisionMode.NEVER)
        val blockDistance = blockHit?.hitPosition?.distanceSquared(eye.toVector())
        val blockTarget = blockHit?.hitBlock?.let(resolver::resolve)?.let {
            LocatedTarget(it, requireNotNull(blockDistance))
        }
        var furnitureTarget: ItemInfoTarget? = null
        val furnitureHit = if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            player.world.rayTraceEntities(eye, eye.direction, distance, 0.20) { entity ->
                if (entity.uniqueId == player.uniqueId) return@rayTraceEntities false
                furniture(entity)?.also { furnitureTarget = it } != null
            }
        } else null
        val entityTarget = furnitureHit?.let { hit ->
            val distanceSquared = hit.hitPosition.distanceSquared(eye.toVector())
            furnitureTarget?.takeIf { blockDistance == null || distanceSquared <= blockDistance + 0.01 }
                ?.let { LocatedTarget(it, distanceSquared) }
        }
        return listOfNotNull(blockTarget, entityTarget).minByOrNull(LocatedTarget::distanceSquared)?.target
    }

    private fun itemsAdder(block: Block): ItemInfoTarget? {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null
        return runCatching {
            val custom: CustomStack = CustomBlock.byAlreadyPlaced(block)
                ?: CustomFurniture.byAlreadySpawned(block)
                ?: return null
            customTarget(custom)
        }.getOrNull()
    }

    private fun furniture(entity: Entity): ItemInfoTarget? = runCatching {
        val custom = CustomFurniture.byAlreadySpawned(entity) ?: return null
        policy.filter(custom.entity?.location, customTarget(custom))
    }.getOrNull()

    private fun customTarget(custom: CustomStack): ItemInfoTarget? {
        val id = custom.namespacedID?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return ItemInfoTarget(displayName(custom.itemStack, id), id)
    }

    private fun slimefun(block: Block): ItemInfoTarget? {
        val hook = HookRegistry.sfHook ?: return null
        return runCatching {
            val rawId = hook.getSlimefunBlockId(block)?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val namespacedId = "slimefun:${rawId.lowercase(Locale.ROOT)}"
            ItemInfoTarget(displayName(hook.getSlimefunItemStack(rawId), rawId), namespacedId)
        }.getOrNull()
    }

    private fun displayName(stack: ItemStack?, fallbackId: String): Component {
        val meta = stack?.itemMeta
        if (meta?.hasDisplayName() == true) return meta.displayName() ?: Component.text(humanize(fallbackId))
        return Component.text(humanize(fallbackId))
    }

    private fun humanize(id: String): String = id.substringAfter(':')
        .split('_', '-', ' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { part -> part.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } }
        .ifBlank { id }

    private data class LocatedTarget(val target: ItemInfoTarget, val distanceSquared: Double)
}
