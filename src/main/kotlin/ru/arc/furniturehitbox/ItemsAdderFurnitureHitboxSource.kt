package ru.arc.furniturehitbox

import dev.lone.itemsadder.api.CustomFurniture
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.hooks.economyshop.FurnitureGalleryInteractionRuntime
import ru.arc.hooks.economyshop.FURNITURE_GALLERY_MAX_DIMENSION
import ru.arc.hooks.economyshop.furnitureGalleryRayEntryDistance
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** Reads ItemsAdder's existing per-viewer target cache without invoking its mutating ray resolver. */
internal class ItemsAdderFurnitureHitboxSource private constructor(
    private val owner: Plugin,
    private val itemsAdder: Plugin,
    private val bindings: Bindings,
    private val models: FurnitureGalleryInteractionRuntime?,
) : FurnitureHitboxSource {
    private val warned = AtomicBoolean()
    @Volatile private var unavailable = false
    private var readyReported = false

    override fun target(player: Player): FurnitureHitboxTarget? {
        if (unavailable || !itemsAdder.isEnabled || !player.isOnline || !Bukkit.isPrimaryThread()) return null
        return try {
            resolve(player)
        } catch (failure: InvocationTargetException) {
            warnOnce(failure.targetException ?: failure)
            null
        } catch (failure: ReflectiveOperationException) {
            warnOnce(failure)
            null
        } catch (failure: LinkageError) {
            warnOnce(failure)
            null
        } catch (failure: RuntimeException) {
            warnOnce(failure)
            null
        }
    }

    override fun refreshAvailability() {
        if (unavailable || !itemsAdder.isEnabled || readyReported) return
        try {
            val manager = bindings.managerSingleton.invoke(null) ?: return
            val furniture = bindings.managerField.get(manager) ?: return
            if (bindings.furnitureManagerField.get(furniture) == null || bindings.hitboxManagerField.get(furniture) == null) return
            readyReported = true
            owner.logger.info("Furniture hitbox hint ready: ItemsAdder furniture data is loaded")
        } catch (failure: ReflectiveOperationException) {
            warnOnce(failure)
        } catch (failure: LinkageError) {
            warnOnce(failure)
        }
    }

    private fun resolve(player: Player): FurnitureHitboxTarget? {
        val eye = player.eyeLocation
        val reach = interactionReach(player) ?: return null
        val blockHit = player.world.rayTraceBlocks(eye, eye.direction, reach, FluidCollisionMode.NEVER, true)
        val blockTarget = nativeFurnitureBlockTarget(blockHit?.hitBlock) { block ->
            CustomFurniture.byAlreadySpawned(block)?.entity
        }
        val interactionTarget = nativeInteractionTarget(player)
        // A selected non-solid prop in front of a different solid item owns the click.
        if (interactionTarget != null && interactionTarget.root.uniqueId != blockTarget?.root?.uniqueId) {
            return interactionTarget
        }
        if (interactionTarget == null) {
            visibleModelTarget(player, eye, reach, blockHit?.hitPosition?.distance(eye.toVector()))?.let { return it }
        }
        return blockTarget ?: interactionTarget
    }

    /** A model can protrude beyond its native click area. Show that exact area before the player finds it. */
    private fun visibleModelTarget(player: Player, eye: Location, reach: Double, blockedAt: Double?): FurnitureHitboxTarget? {
        val profiles = models ?: return null
        val origin = eye.toVector()
        val radius = reach + FURNITURE_GALLERY_MAX_DIMENSION
        val candidates = player.world.getNearbyEntities(eye, radius, radius, radius) { it is ItemDisplay }
            .mapNotNull { root ->
                val furniture = CustomFurniture.byAlreadySpawned(root) ?: return@mapNotNull null
                if (!root.isValid || furniture.entity?.uniqueId != root.uniqueId) return@mapNotNull null
                val bounds = profiles.modelBoundsForNativeRoot(root, furniture.namespacedID) ?: return@mapNotNull null
                val distance = furnitureGalleryRayEntryDistance(origin, eye.direction, bounds, reach) ?: return@mapNotNull null
                if (blockedAt != null && distance > blockedAt + 0.01) return@mapNotNull null
                root to distance
            }.sortedBy { it.second }
        if (candidates.isEmpty()) return null
        val manager = bindings.managerSingleton.invoke(null) ?: return null
        val api = bindings.managerField.get(manager) ?: return null
        val furnitureManager = bindings.furnitureManagerField.get(api) ?: return null
        val context = bindings.viewerContextMethod.invoke(furnitureManager, player) ?: return null
        for ((root, _) in candidates) {
            val native = bindings.furnitureAtLocationMethod.invoke(context, root.location) ?: continue
            val exactRoot = bindings.rootDisplayField.get(native) as? ItemDisplay ?: continue
            if (exactRoot.uniqueId != root.uniqueId) continue
            val geometry = bindings.modelBoxMethod.invoke(native) ?: continue
            val bounds = bindings.nativeModelBoundsMethod.invoke(geometry) as? BoundingBox ?: continue
            val interaction = nativeInteractionBounds(bounds) ?: continue
            val clickBox = nativeFurnitureClickBounds(bounds, interaction) ?: continue
            return FurnitureHitboxTarget(root, clickBox)
        }
        return null
    }

    private fun nativeInteractionTarget(player: Player): FurnitureHitboxTarget? {
        // ItemsAdder can rebuild its manager during iazip reloads. Resolve the live manager for each
        // query; only class/method/field objects are retained by Bindings.
        val manager = bindings.managerSingleton.invoke(null) ?: return null
        val itemsAdderApi = bindings.managerField.get(manager) ?: return null
        val furnitureManager = bindings.furnitureManagerField.get(itemsAdderApi) ?: return null
        val hitboxManager = bindings.hitboxManagerField.get(itemsAdderApi) ?: return null
        val viewerHitbox = bindings.viewerHitboxMethod.invoke(hitboxManager, player) ?: return null
        val viewerContext = bindings.viewerContextMethod.invoke(furnitureManager, player) ?: return null
        val nativeRayCache = bindings.lastTargetField.get(viewerContext) ?: return null
        val nativeTarget = bindings.cachedFurnitureMethod.invoke(nativeRayCache) ?: return null
        val root = bindings.rootDisplayField.get(nativeTarget) as? ItemDisplay ?: return null
        if (!root.isValid || root.world.uid != player.world.uid) return null

        val furniture = bindings.customFurnitureByEntity.invoke(null, root) ?: return null
        val apiRoot = bindings.customFurnitureEntityMethod.invoke(furniture) as? Entity ?: return null
        if (apiRoot.uniqueId != root.uniqueId) return null

        val nativeModelBox = bindings.modelBoxMethod.invoke(nativeTarget) ?: return null
        val modelBox = bindings.nativeModelBoundsMethod.invoke(nativeModelBox) as? BoundingBox ?: return null
        val storedViewerLocation = bindings.viewerLocationField.get(viewerHitbox) as? Location ?: return null
        val storedViewerModelBox = bindings.viewerModelBoxField.get(viewerHitbox) as? BoundingBox ?: return null
        val interaction = bindings.viewerInteractionField.get(viewerHitbox) as? Interaction
        val nativeBlock = bindings.lastBlockField.get(viewerContext) as? Block

        val rootBounds = if (storedViewerLocation.world?.uid == player.world.uid &&
            storedViewerLocation.block == root.location.block && storedViewerModelBox == modelBox &&
            interaction != null && interaction.isValid &&
            interaction.world.uid == player.world.uid &&
            bindings.viewerOwnsInteractionMethod.invoke(viewerHitbox, interaction) == true
        ) interaction.boundingBox.clone().takeIf { it.widthX > 0 && it.height > 0 && it.widthZ > 0 } else null

        val currentRay = player.eyeLocation
        val currentReach = interactionReach(player) ?: return null
        val reach = nativeRayCacheFreshness(
            cachedTick = bindings.cacheTickMethod.invoke(nativeRayCache) as Long,
            currentTick = player.world.fullTime,
            cachedOrigin = bindings.cacheOriginMethod.invoke(nativeRayCache) as? Vector ?: return null,
            cachedDirection = bindings.cacheDirectionMethod.invoke(nativeRayCache) as? Vector ?: return null,
            cachedReach = bindings.cacheReachMethod.invoke(nativeRayCache) as Double,
            currentOrigin = currentRay.toVector(),
            currentDirection = currentRay.direction,
            currentReach = currentReach,
        ) ?: return null

        val blockHit = player.world.rayTraceBlocks(
            currentRay,
            currentRay.direction,
            reach,
            FluidCollisionMode.NEVER,
            true,
        )
        val hitBlock = blockHit?.hitBlock
        if (nativeBlock != hitBlock) return null

        val occlusionDistanceSquared = blockHit?.hitPosition?.distanceSquared(currentRay.toVector()) ?: reach * reach
        val modelHit = bindings.modelRayTraceMethod.invoke(
            nativeModelBox,
            currentRay.toVector(),
            currentRay.direction,
            occlusionDistanceSquared,
        ) as? Vector
        val visibleFromCurrentRay = modelHit != null || rootBounds?.let {
            nativeHitboxVisibleFromRay(it, it, currentRay.toVector(), currentRay.direction, reach, occlusionDistanceSquared)
        } == true
        if (!visibleFromCurrentRay) return null

        return rootBounds?.let { nativeFurnitureClickBounds(modelBox, it) }?.let { FurnitureHitboxTarget(root, it) }
    }

    private fun interactionReach(player: Player): Double? = when (player.gameMode) {
        GameMode.CREATIVE -> player.getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE)?.value
        else -> SURVIVAL_REACH
    }

    private fun warnOnce(failure: Throwable) {
        unavailable = true
        if (warned.compareAndSet(false, true)) {
            owner.logger.warning(
                "ItemsAdder furniture hitbox hint disabled until restart after an ItemsAdder binding failure: ${failure.javaClass.simpleName}: ${failure.message ?: "no details"}",
            )
        }
    }

    internal class Bindings private constructor(
        val managerSingleton: Method,
        val managerField: Field,
        val furnitureManagerField: Field,
        val hitboxManagerField: Field,
        val viewerContextMethod: Method,
        val furnitureAtLocationMethod: Method,
        val viewerHitboxMethod: Method,
        val lastTargetField: Field,
        val lastBlockField: Field,
        val cachedFurnitureMethod: Method,
        val cacheOriginMethod: Method,
        val cacheDirectionMethod: Method,
        val cacheReachMethod: Method,
        val cacheTickMethod: Method,
        val rootDisplayField: Field,
        val modelBoxMethod: Method,
        val nativeModelBoundsMethod: Method,
        val modelRayTraceMethod: Method,
        val viewerLocationField: Field,
        val viewerModelBoxField: Field,
        val viewerInteractionField: Field,
        val viewerOwnsInteractionMethod: Method,
        val customFurnitureByEntity: Method,
        val customFurnitureEntityMethod: Method,
    ) {
        companion object {
            /** Exact ItemsAdder 4.0.18 names and signatures, resolved once from its plugin classloader. */
            internal fun bind(classLoader: ClassLoader): Bindings {
                val managerClass = classLoader.loadClass("itemsadder.m.d")
                val itemsAdderClass = classLoader.loadClass("itemsadder.m.co")
                val furnitureManagerClass = classLoader.loadClass("itemsadder.m.br")
                val hitboxManagerClass = classLoader.loadClass("itemsadder.m.ce")
                val viewerContextClass = classLoader.loadClass("itemsadder.m.ci")
                val nativeTargetCacheClass = classLoader.loadClass("itemsadder.m.cl")
                val nativeFurnitureClass = classLoader.loadClass("itemsadder.m.cj")
                val nativeModelBoxClass = classLoader.loadClass("itemsadder.m.ck")
                val viewerHitboxClass = classLoader.loadClass("itemsadder.m.cg")
                val customFurnitureClass = classLoader.loadClass("dev.lone.itemsadder.api.CustomFurniture")

                return Bindings(
                    managerSingleton = managerClass.getMethod("g"),
                    managerField = managerClass.declaredField("be"),
                    furnitureManagerField = itemsAdderClass.getField("kO"),
                    hitboxManagerField = itemsAdderClass.getField("kQ"),
                    viewerContextMethod = furnitureManagerClass.getMethod("A", Player::class.java),
                    furnitureAtLocationMethod = viewerContextClass.getMethod("j", Location::class.java),
                    viewerHitboxMethod = hitboxManagerClass.declaredMethod("F", Player::class.java),
                    lastTargetField = viewerContextClass.declaredField("kd"),
                    lastBlockField = viewerContextClass.declaredField("ke"),
                    cachedFurnitureMethod = nativeTargetCacheClass.accessibleMethod("cP"),
                    cacheOriginMethod = nativeTargetCacheClass.accessibleMethod("cM"),
                    cacheDirectionMethod = nativeTargetCacheClass.accessibleMethod("cN"),
                    cacheReachMethod = nativeTargetCacheClass.accessibleMethod("cO"),
                    cacheTickMethod = nativeTargetCacheClass.accessibleMethod("cQ"),
                    rootDisplayField = nativeFurnitureClass.declaredField("kg"),
                    modelBoxMethod = nativeFurnitureClass.accessibleMethod("cI"),
                    nativeModelBoundsMethod = nativeModelBoxClass.accessibleMethod("cJ"),
                    modelRayTraceMethod = nativeModelBoxClass.declaredMethod(
                        "a",
                        Vector::class.java,
                        Vector::class.java,
                        Double::class.javaPrimitiveType!!,
                    ),
                    viewerLocationField = viewerHitboxClass.getField("jl"),
                    viewerModelBoxField = viewerHitboxClass.getField("jm"),
                    viewerInteractionField = viewerHitboxClass.declaredField("jn"),
                    viewerOwnsInteractionMethod = viewerHitboxClass.accessibleMethod("a", Interaction::class.java),
                    customFurnitureByEntity = customFurnitureClass.accessibleMethod("byAlreadySpawned", Entity::class.java),
                    customFurnitureEntityMethod = customFurnitureClass.accessibleMethod("getEntity"),
                )
            }
        }
    }

    companion object {
        private const val REQUIRED_ITEMSADDER_VERSION = "4.0.18"
        private const val SURVIVAL_REACH = 4.5

        fun create(plugin: Plugin, models: FurnitureGalleryInteractionRuntime?): FurnitureHitboxSource? {
            val itemsAdder = Bukkit.getPluginManager().getPlugin("ItemsAdder") ?: return null
            if (!itemsAdder.isEnabled) return null
            if (itemsAdder.description.version != REQUIRED_ITEMSADDER_VERSION) {
                plugin.logger.warning(
                    "Furniture hitbox hint disabled: supported ItemsAdder version is $REQUIRED_ITEMSADDER_VERSION, found ${itemsAdder.description.version}",
                )
                return null
            }
            return try {
                val bindings = Bindings.bind(itemsAdder.javaClass.classLoader)
                // IA constructs the furniture manager only after its asynchronous data load.
                // d.v() throws while that field is null; absence is normal startup/reload state.
                ItemsAdderFurnitureHitboxSource(plugin, itemsAdder, bindings, models)
            } catch (failure: InvocationTargetException) {
                plugin.logger.warning(
                    "Furniture hitbox hint disabled: ItemsAdder 4.0.18 bindings are unavailable (${failure.targetException?.message ?: failure.message})",
                )
                null
            } catch (failure: ReflectiveOperationException) {
                plugin.logger.warning("Furniture hitbox hint disabled: ItemsAdder 4.0.18 bindings are unavailable (${failure.message})")
                null
            } catch (failure: LinkageError) {
                plugin.logger.warning("Furniture hitbox hint disabled: ItemsAdder 4.0.18 bindings are unavailable (${failure.message})")
                null
            } catch (failure: RuntimeException) {
                plugin.logger.warning("Furniture hitbox hint disabled: ItemsAdder 4.0.18 bindings are unavailable (${failure.message})")
                null
            }
        }
    }
}

/** Mirrors the exact freshness window IA uses before trusting its per-player target cache. */
internal fun nativeRayCacheFreshness(
    cachedTick: Long,
    currentTick: Long,
    cachedOrigin: Vector,
    cachedDirection: Vector,
    cachedReach: Double,
    currentOrigin: Vector,
    currentDirection: Vector,
    currentReach: Double,
): Double? {
    val age = currentTick - cachedTick
    if (age !in 0L..2L || cachedReach <= 0.0 || !cachedReach.isFinite() || cachedReach != currentReach) return null
    if (cachedOrigin.distanceSquared(currentOrigin) >= NATIVE_CACHE_ORIGIN_DISTANCE_SQUARED) return null
    if (cachedDirection.lengthSquared() == 0.0 || currentDirection.lengthSquared() == 0.0) return null
    if (cachedDirection.angle(currentDirection) >= NATIVE_CACHE_DIRECTION_TOLERANCE_RADIANS) return null
    return cachedReach
}

/** Whether the ray is on IA's selected model bounds or its actual viewer-only Interaction box. */
internal fun nativeHitboxVisibleFromRay(
    modelBounds: BoundingBox,
    interactionBounds: BoundingBox,
    origin: Vector,
    direction: Vector,
    reach: Double,
    blockDistanceSquared: Double,
): Boolean {
    val modelHit = modelBounds.rayTrace(origin, direction, reach)?.hitPosition
    val interactionHit = interactionBounds.rayTrace(origin, direction, reach)?.hitPosition
    return listOfNotNull(modelHit, interactionHit).any { hit ->
        hit.distanceSquared(origin) <= blockDistanceSquared + RAY_TIE_TOLERANCE_SQUARED
    }
}

/** IA validates an Interaction click against cj's ray box again; the square excess is not clickable. */
internal fun nativeFurnitureClickBounds(model: BoundingBox, interaction: BoundingBox): BoundingBox? {
    if (!model.overlaps(interaction)) return null
    return model.clone().intersection(interaction)
}

/** Exact cg.a(BoundingBox, boolean) geometry: float width=max(X,Z), float height, centered at minY. */
internal fun nativeInteractionBounds(model: BoundingBox): BoundingBox? {
    val width = maxOf(model.widthX, model.widthZ).toFloat().toDouble()
    val height = model.height.toFloat().toDouble()
    if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 || width > 16 || height > 16) return null
    val half = width / 2.0
    return BoundingBox(model.centerX - half, model.minY, model.centerZ - half,
        model.centerX + half, model.minY + height, model.centerZ + half)
}

/** Only a physical IA-owned block is outlined; a nearby floor/support block is never guessed. */
internal fun nativeFurnitureBlockTarget(block: Block?, rootForBlock: (Block) -> Entity?): FurnitureHitboxTarget? {
    if (block == null || block.isPassable) return null
    val root = rootForBlock(block) ?: return null
    if (!root.isValid || root.world.uid != block.world.uid) return null
    val box = block.boundingBox
    if (box.widthX <= 0 || box.height <= 0 || box.widthZ <= 0) return null
    return FurnitureHitboxTarget(root, box)
}

private fun Class<*>.declaredField(name: String): Field = getDeclaredField(name).also {
    check(it.trySetAccessible()) { "Cannot access ${declaringClass.name}.$name" }
}

private fun Class<*>.declaredMethod(name: String, vararg parameters: Class<*>): Method = getDeclaredMethod(name, *parameters).also {
    check(it.trySetAccessible()) { "Cannot access ${declaringClass.name}.$name" }
}

private fun Class<*>.accessibleMethod(name: String, vararg parameters: Class<*>): Method = getMethod(name, *parameters).also {
    check(it.trySetAccessible()) { "Cannot access ${declaringClass.name}.$name" }
}

private const val NATIVE_CACHE_ORIGIN_DISTANCE_SQUARED = 0.04
private val NATIVE_CACHE_DIRECTION_TOLERANCE_RADIANS = Math.toRadians(2.5)
private const val RAY_TIE_TOLERANCE_SQUARED = 0.01
