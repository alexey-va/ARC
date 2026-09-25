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
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import ru.arc.hooks.economyshop.FURNITURE_GALLERY_WORLD
import ru.arc.hooks.economyshop.FurnitureGalleryTargetMarkers
import ru.arc.hooks.slimefun.SlimefunItemAccess
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

internal data class ItemInfoLocatedTarget(val target: ItemInfoTarget, val distanceSquared: Double)

/** Resolves the entity actually returned by Paper's ray trace, never predicate iteration state. */
internal object ItemInfoHitTargetSelection {
    fun fromRayHit(
        hitEntity: Entity?,
        distanceSquared: Double,
        blockDistanceSquared: Double?,
        resolve: (Entity) -> ItemInfoTarget?,
    ): ItemInfoLocatedTarget? {
        val entity = hitEntity ?: return null
        if (!distanceSquared.isFinite() || distanceSquared < 0.0) return null
        if (blockDistanceSquared != null && distanceSquared > blockDistanceSquared + ENTITY_BLOCK_TIE_TOLERANCE) return null
        return resolve(entity)?.let { ItemInfoLocatedTarget(it, distanceSquared) }
    }

    fun closest(vararg candidates: ItemInfoLocatedTarget?): ItemInfoTarget? =
        candidates.filterNotNull().minByOrNull(ItemInfoLocatedTarget::distanceSquared)?.target

    private const val ENTITY_BLOCK_TIE_TOLERANCE = 0.01
}

internal class BukkitItemInfoTargetResolver(
    private val distance: Double,
    private val managedCrate: (Location) -> Boolean = ExcellentCratesItemInfoBridge::contains,
    private val galleryMarkers: FurnitureGalleryTargetMarkers? = null,
) {
    private val resolver = ItemInfoBlockResolver(::itemsAdder, ::slimefun) { managedCrate(it.location) }
    private val policy = ItemInfoTargetPolicy(managedCrate)

    fun resolve(player: Player): ItemInfoTarget? {
        val eye = player.eyeLocation
        val blockHit = player.rayTraceBlocks(distance, FluidCollisionMode.NEVER)
        val blockDistance = blockHit?.hitPosition?.distanceSquared(eye.toVector())
        val blockTarget = blockHit?.hitBlock?.let(resolver::resolve)?.let {
            ItemInfoLocatedTarget(it, requireNotNull(blockDistance))
        }
        val gallery = player.world.name == FURNITURE_GALLERY_WORLD
        val furnitureHit = if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            player.world.rayTraceEntities(eye, eye.direction, distance, if (gallery) 0.0 else 0.20) { entity ->
                if (entity.uniqueId == player.uniqueId) return@rayTraceEntities false
                if (gallery) {
                    when (entity) {
                        is Interaction -> galleryMarkers?.read(entity) != null
                        is ItemDisplay, is ArmorStand -> furniture(entity, gallery = true) != null
                        else -> false
                    }
                } else {
                    (entity is ItemDisplay || entity is ArmorStand) && furniture(entity, false) != null
                }
            }
        } else null
        val entityTarget = furnitureHit?.let { hit ->
            val distanceSquared = hit.hitPosition.distanceSquared(eye.toVector())
            ItemInfoHitTargetSelection.fromRayHit(
                hitEntity = hit.hitEntity,
                distanceSquared = distanceSquared,
                blockDistanceSquared = blockDistance,
            ) { entity -> furniture(entity, gallery) }
        }
        return ItemInfoHitTargetSelection.closest(blockTarget, entityTarget)
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

    private fun furniture(entity: Entity, gallery: Boolean): ItemInfoTarget? = runCatching {
        if (gallery && entity is Interaction) {
            val marker = galleryMarkers?.read(entity) ?: return null
            if (entity.world.name != FURNITURE_GALLERY_WORLD) return null
            val custom = CustomStack.getInstance(marker.furnitureId) ?: return null
            if (custom.namespacedID != marker.furnitureId) return null
            return policy.filter(entity.location, customTarget(custom))
        }
        val custom = CustomFurniture.byAlreadySpawned(entity) ?: return null
        if (gallery && (entity.world.name != FURNITURE_GALLERY_WORLD || custom.entity?.uniqueId != entity.uniqueId)) return null
        policy.filter(custom.entity?.location, customTarget(custom))
    }.getOrNull()

    private fun customTarget(custom: CustomStack): ItemInfoTarget? {
        val id = custom.namespacedID?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return ItemInfoTarget(displayName(custom.itemStack, id), id)
    }

    private fun slimefun(block: Block): ItemInfoTarget? {
        if (!Bukkit.getPluginManager().isPluginEnabled("Slimefun")) return null
        return runCatching {
            val rawId = SlimefunItemAccess.blockId(block)?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val namespacedId = "slimefun:${rawId.lowercase(Locale.ROOT)}"
            ItemInfoTarget(displayName(SlimefunItemAccess.itemStack(rawId), rawId), namespacedId)
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
}
