package ru.arc.furniturehitbox

import dev.lone.itemsadder.api.CustomFurniture
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import ru.arc.hooks.economyshop.FURNITURE_GALLERY_MAX_DIMENSION
import ru.arc.hooks.economyshop.FurnitureGalleryInteractionRuntime
import ru.arc.hooks.economyshop.furnitureGalleryRayEntryDistance

/** IA furniture stores its configured hit box on the native root entity, exposed by Bukkit. */
internal class ItemsAdderFurnitureHitboxSource private constructor(
    private val owner: Plugin,
    private val itemsAdder: Plugin,
    private val models: FurnitureGalleryInteractionRuntime?,
) : FurnitureHitboxSource {
    private var readyReported = false

    override fun refreshAvailability() {
        if (readyReported || !itemsAdder.isEnabled || CustomFurniture.getNamespacedIdsInRegistry().isEmpty()) return
        readyReported = true
        owner.logger.info("Furniture hitbox hint ready: ItemsAdder furniture registry is loaded; native root bounds")
    }

    override fun target(player: Player): FurnitureHitboxTarget? {
        if (!itemsAdder.isEnabled || !player.isOnline || !Bukkit.isPrimaryThread()) return null
        val eye = player.eyeLocation
        val origin = eye.toVector()
        val direction = eye.direction
        val blockHit = player.world.rayTraceBlocks(eye, direction, FURNITURE_REACH, FluidCollisionMode.NEVER, true)
        val block = blockHit?.hitBlock
        val blockedAt = blockHit?.hitPosition?.distance(origin)
        val blockTarget = nativeFurnitureBlockTarget(block) { CustomFurniture.byAlreadySpawned(it)?.entity }
        val radius = FURNITURE_REACH + FURNITURE_GALLERY_MAX_DIMENSION
        val candidates = player.world.getNearbyEntities(eye, radius, radius, radius) {
            it is ItemDisplay || it is ArmorStand || it is ItemFrame
        }.mapNotNull { root ->
            if (!root.isValid || root.world.uid != player.world.uid ||
                root.persistentDataContainer.get(FURNITURE_BEHAVIOUR_KEY, PersistentDataType.STRING) != "furniture") {
                return@mapNotNull null
            }
            val furniture = CustomFurniture.byAlreadySpawned(root) ?: return@mapNotNull null
            if (furniture.entity?.uniqueId != root.uniqueId) return@mapNotNull null
            val native = nativeFurnitureEntityBounds(root.boundingBox) ?: return@mapNotNull null
            val directDistance = (if (native.contains(origin)) 0.0
                else native.rayTrace(origin, direction, FURNITURE_REACH)?.hitPosition?.distance(origin))
                ?.takeIf { nativeFurnitureRayUnblocked(native, it, blockedAt, block?.boundingBox, block?.type == Material.BARRIER) }
            FurnitureRayCandidate(
                FurnitureHitboxTarget(root, native),
                directDistance,
                models?.modelBoundsForNativeRoot(root, furniture.namespacedID)?.let { visual ->
                    furnitureGalleryRayEntryDistance(origin, direction, visual, FURNITURE_REACH)
                        ?.takeIf { blockedAt == null || it <= blockedAt + RAY_TOLERANCE }
                },
            )
        }
        return nearestFurnitureHitboxTarget(candidates, blockTarget, blockedAt)
    }

    companion object {
        private val FURNITURE_BEHAVIOUR_KEY = NamespacedKey("itemsadder", "placeable_behaviour_type")

        fun create(plugin: Plugin, models: FurnitureGalleryInteractionRuntime?): FurnitureHitboxSource? {
            val itemsAdder = Bukkit.getPluginManager().getPlugin("ItemsAdder") ?: return null
            if (!itemsAdder.isEnabled) return null
            return ItemsAdderFurnitureHitboxSource(plugin, itemsAdder, models)
        }
    }
}

internal data class FurnitureRayCandidate(
    val target: FurnitureHitboxTarget,
    val nativeDistance: Double?,
    val visualDistance: Double?,
)

/** A visible foreground model must not lose to another item's native box or barrier behind it. */
internal fun nearestFurnitureHitboxTarget(
    candidates: List<FurnitureRayCandidate>,
    blockTarget: FurnitureHitboxTarget?,
    blockDistance: Double?,
): FurnitureHitboxTarget? = sequence {
    for (candidate in candidates) {
        val distance = listOfNotNull(candidate.nativeDistance, candidate.visualDistance).minOrNull() ?: continue
        yield(candidate.target to distance)
    }
    if (blockTarget != null && blockDistance != null) yield(blockTarget to blockDistance)
}.minByOrNull { it.second }?.first

/** No square expansion: furniture arm-swing targeting uses the root's configured rectangular box. */
internal fun nativeFurnitureEntityBounds(bounds: BoundingBox): BoundingBox? {
    val coordinates = listOf(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ)
    if (!coordinates.all(Double::isFinite) ||
        listOf(bounds.widthX, bounds.height, bounds.widthZ).any { it <= 0.0 || it > 16.0 }) return null
    return bounds.clone()
}

/** IA permits a support block enclosed by the root box and a barrier whose center is inside it. */
internal fun nativeFurnitureRayUnblocked(
    nativeBounds: BoundingBox,
    targetDistance: Double,
    blockDistance: Double?,
    blockBounds: BoundingBox?,
    barrier: Boolean,
): Boolean {
    if (barrier && blockBounds != null) return nativeBounds.contains(blockBounds.center)
    if (blockDistance == null || targetDistance <= blockDistance + RAY_TOLERANCE) return true
    if (blockBounds == null) return false
    return nativeBounds.contains(blockBounds)
}

/** Only an actual IA-owned solid block is outlined; a nearby support block is never guessed. */
internal fun nativeFurnitureBlockTarget(block: Block?, rootForBlock: (Block) -> Entity?): FurnitureHitboxTarget? {
    // The public block overload searches nearby roots; it does not establish block ownership.
    // Furniture collisions are barriers. Never advertise an ordinary floor/wall as breakable.
    if (block == null || block.isPassable || block.type != Material.BARRIER) return null
    val root = rootForBlock(block) ?: return null
    if (!root.isValid || root.world.uid != block.world.uid) return null
    val box = nativeFurnitureEntityBounds(block.boundingBox) ?: return null
    return FurnitureHitboxTarget(root, box)
}

// Exact IA 4.0.18 furniture arm-swing path: kv -> jl.ao/ap -> bfo, using five blocks.
private const val FURNITURE_REACH = 5.0
private const val RAY_TOLERANCE = 0.01
