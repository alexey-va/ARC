package ru.arc.hooks.economyshop

import dev.lone.itemsadder.api.CustomFurniture
import dev.lone.itemsadder.api.Events.FurnitureBreakEvent
import dev.lone.itemsadder.api.Events.FurniturePlaceSuccessEvent
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.util.Logging.warn
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID

/** Recognizes only the old ARC-owned markers so loaded gallery chunks can be cleaned. */
private class FurnitureGalleryLegacyMarkerCleanup(plugin: Plugin) {
    private val ownerKey = NamespacedKey(plugin, "furniture-gallery-hitbox")

    fun isOwned(entity: Entity): Boolean =
        entity.persistentDataContainer.get(ownerKey, PersistentDataType.BYTE) in OWNED_MARKER_VERSIONS

    private companion object {
        val OWNED_MARKER_VERSIONS = setOf(1.toByte(), 2.toByte())
    }
}

internal fun interface FurnitureGalleryNativeRootResolver {
    /** Returns an ID only when [entity] itself is the exact live IA furniture root. */
    fun furnitureId(entity: Entity): String?

    /** Returns the exact root for a native IA furniture block, when the API exposes it. */
    fun furnitureRoot(block: Block): Entity? = null
}

private object ItemsAdderFurnitureGalleryNativeRootResolver : FurnitureGalleryNativeRootResolver {
    override fun furnitureId(entity: Entity): String? = runCatching {
        val furniture = CustomFurniture.byAlreadySpawned(entity) ?: return null
        if (furniture.entity?.uniqueId != entity.uniqueId) return null
        furniture.namespacedID?.trim()?.takeIf(String::isNotEmpty)
    }.getOrNull()

    override fun furnitureRoot(block: Block): Entity? = runCatching {
        CustomFurniture.byAlreadySpawned(block)?.entity
    }.getOrNull()
}

internal data class FurnitureGallerySightTarget(
    val root: Entity,
    val furnitureId: String,
    val targetKey: String,
    val distance: Double,
)

/** Returns the first ray/AABB intersection in world-distance units. */
internal fun furnitureGalleryRayEntryDistance(
    origin: Vector,
    direction: Vector,
    bounds: FurnitureGalleryBounds,
    maxDistance: Double,
): Double? {
    if (!maxDistance.isFinite() || maxDistance <= 0.0) return null
    val ray = direction.clone().normalize()
    if (!ray.x.isFinite() || !ray.y.isFinite() || !ray.z.isFinite() || ray.lengthSquared() < 0.99) return null

    var entry = 0.0
    var exit = maxDistance
    val origins = doubleArrayOf(origin.x, origin.y, origin.z)
    val directions = doubleArrayOf(ray.x, ray.y, ray.z)
    val minimums = doubleArrayOf(bounds.minX, bounds.minY, bounds.minZ)
    val maximums = doubleArrayOf(bounds.maxX, bounds.maxY, bounds.maxZ)
    for (axis in 0..2) {
        val component = directions[axis]
        val coordinate = origins[axis]
        if (kotlin.math.abs(component) < RAY_AXIS_EPSILON) {
            if (coordinate < minimums[axis] || coordinate > maximums[axis]) return null
            continue
        }
        val first = (minimums[axis] - coordinate) / component
        val second = (maximums[axis] - coordinate) / component
        entry = maxOf(entry, minOf(first, second))
        exit = minOf(exit, maxOf(first, second))
        if (entry > exit) return null
    }
    return entry.takeIf { it in 0.0..maxDistance && exit >= 0.0 }
}

private const val RAY_AXIS_EPSILON = 1.0e-12

/**
 * Discovers exact ItemsAdder roots in already-loaded gallery chunks and keeps
 * model-derived query geometry. Legacy ARC Interaction markers are removed,
 * never created: ItemsAdder must remain the only owner of furniture hit-testing.
 * Discovery is sliced; no task loads chunks or scans the ESG catalog.
 */
internal class FurnitureGalleryInteractionRuntime(
    private val plugin: Plugin,
    profiles: Map<String, FurnitureGalleryProfile>,
    private val rootResolver: FurnitureGalleryNativeRootResolver = ItemsAdderFurnitureGalleryNativeRootResolver,
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    /** Read-only ownership tag used solely to remove pre-1.4.223 markers. */
    private val legacyMarkers = FurnitureGalleryLegacyMarkerCleanup(plugin)
    private val profilesById = profiles.toMap()
    private val roots = LinkedHashMap<UUID, RootState>()
    private val pendingChunks = LinkedHashSet<Pair<Int, Int>>()
    private val issueIds = LinkedHashSet<String>()
    private var scannerScheduled = false
    private var closed = false

    init {
        require(profiles.size <= FURNITURE_GALLERY_MAX_PROFILES) {
            "Furniture gallery profile count exceeds $FURNITURE_GALLERY_MAX_PROFILES"
        }
        require(profiles.all { (id, profile) -> id == profile.furnitureId }) {
            "Furniture gallery profile keys must match their exact furniture IDs"
        }
    }

    fun start() {
        check(!closed) { "Furniture gallery interaction runtime is closed" }
        val token = tasks.restart()
        Bukkit.getWorld(FURNITURE_GALLERY_WORLD)?.let(::removeAllOwnedMarkers)
        roots.clear()
        pendingChunks.clear()
        galleryWorld()?.loadedChunks?.forEach { enqueueChunk(it.x, it.z) }
        scheduleChunkScan(token)
        tasks.runTimer(token, RECONCILE_PERIOD_TICKS, RECONCILE_PERIOD_TICKS) {
            reconcileTrackedRoots()
            galleryWorld()?.loadedChunks?.forEach { enqueueChunk(it.x, it.z) }
            scheduleChunkScan(tasks.token())
        }
    }

    /** Selects the first model/root bound intersected by the player's sight ray. */
    fun targetInSight(player: Player, maxDistance: Double): FurnitureGallerySightTarget? {
        if (closed || player.world.name != FURNITURE_GALLERY_WORLD) return null
        if (!maxDistance.isFinite() || maxDistance <= 0.0 || maxDistance > MAX_TARGET_DISTANCE) return null

        val eye = runCatching { player.eyeLocation }.getOrNull() ?: return null
        val origin = eye.toVector()
        val direction = runCatching { eye.direction.normalize() }.getOrNull() ?: return null
        if (!direction.x.isFinite() || !direction.y.isFinite() || !direction.z.isFinite() || direction.lengthSquared() < 0.99) {
            return null
        }

        val blockHit = runCatching {
            player.rayTraceBlocks(maxDistance, FluidCollisionMode.NEVER)
        }.getOrNull()
        val block = blockHit?.hitBlock
        val blockDistance = blockHit?.hitPosition?.distance(origin)?.takeIf(Double::isFinite)

        val candidates = roots.values.toList().mapNotNull { tracked ->
            val state = refreshRoot(tracked) ?: return@mapNotNull null
            val distance = furnitureGalleryRayEntryDistance(origin, direction, state.bounds, maxDistance)
                ?: return@mapNotNull null
            if (block != null && blockDistance != null && distance > blockDistance + BLOCK_HIT_TOLERANCE &&
                !blockBelongsTo(block, state)
            ) return@mapNotNull null
            FurnitureGallerySightTarget(
                root = state.root,
                furnitureId = state.furnitureId,
                targetKey = state.targetKey,
                distance = distance,
            )
        }
        return candidates.minByOrNull(FurnitureGallerySightTarget::distance)
    }

    /** Validates native IA clicks independently of profile availability. */
    fun nativeFurnitureIdentity(furnitureId: String?, root: Entity?): String? {
        if (root == null || root.world.name != FURNITURE_GALLERY_WORLD || !root.isValid) return null
        val id = furnitureId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (rootResolver.furnitureId(root) != id) return null
        return "native:${root.uniqueId}"
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onFurniturePlaced(event: FurniturePlaceSuccessEvent) {
        if (closed) return
        val furniture = runCatching { event.furniture }.getOrNull()
        val entity = runCatching { furniture?.entity }.getOrNull()
            ?: runCatching { event.bukkitEntity }.getOrNull()
            ?: return
        if (entity.world.name != FURNITURE_GALLERY_WORLD) return
        tasks.runLater(1L) {
            if (entity.isValid && entity.world.name == FURNITURE_GALLERY_WORLD) trackRoot(entity)
            enqueueChunk(entity.location.chunk.x, entity.location.chunk.z)
            scheduleChunkScan(tasks.token())
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnitureBroken(event: FurnitureBreakEvent) {
        if (closed || event.isCancelled) return
        val furniture = runCatching { event.furniture }.getOrNull()
        val entity = runCatching { furniture?.entity }.getOrNull()
            ?: runCatching { event.bukkitEntity }.getOrNull()
            ?: return
        if (entity.world.name == FURNITURE_GALLERY_WORLD) removeRoot(entity.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (event.world.name != FURNITURE_GALLERY_WORLD || closed) return
        removeOwnedMarkers(event.chunk)
        enqueueChunk(event.chunk.x, event.chunk.z)
        scheduleChunkScan(tasks.token())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        if (event.world.name != FURNITURE_GALLERY_WORLD || closed) return
        roots.values.asSequence()
            .filter { it.rootChunkX == event.chunk.x && it.rootChunkZ == event.chunk.z }
            .map { it.root.uniqueId }
            .toList()
            .forEach(::removeRoot)
        pendingChunks.remove(event.chunk.x to event.chunk.z)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldLoad(event: WorldLoadEvent) {
        if (event.world.name != FURNITURE_GALLERY_WORLD || closed) return
        tasks.runLater(1L) {
            event.world.loadedChunks.forEach {
                removeOwnedMarkers(it)
                enqueueChunk(it.x, it.z)
            }
            scheduleChunkScan(tasks.token())
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        pendingChunks.clear()
        scannerScheduled = false
        Bukkit.getWorld(FURNITURE_GALLERY_WORLD)?.let(::removeAllOwnedMarkers)
        roots.clear()
    }

    private fun enqueueChunk(chunkX: Int, chunkZ: Int) {
        if (!closed) pendingChunks += chunkX to chunkZ
    }

    private fun scheduleChunkScan(token: LifecycleTaskScope.Token) {
        if (closed || scannerScheduled || pendingChunks.isEmpty()) return
        scannerScheduled = true
        tasks.runLater(token, 1L) {
            scannerScheduled = false
            val world = galleryWorld()
            if (world != null) {
                repeat(CHUNKS_PER_SCAN_TICK) {
                    val key = pendingChunks.firstOrNull() ?: return@repeat
                    pendingChunks.remove(key)
                    if (world.isChunkLoaded(key.first, key.second)) scanChunk(world, key.first, key.second)
                }
            } else {
                pendingChunks.clear()
            }
            scheduleChunkScan(tasks.token())
        }
    }

    private fun scanChunk(world: World, chunkX: Int, chunkZ: Int) {
        if (world.name != FURNITURE_GALLERY_WORLD || !world.isChunkLoaded(chunkX, chunkZ)) return
        val found = HashSet<UUID>()
        val chunk = world.getChunkAt(chunkX, chunkZ)
        removeOwnedMarkers(chunk)
        chunk.entities.forEach { entity ->
            if (legacyMarkers.isOwned(entity)) return@forEach
            val id = runCatching { rootResolver.furnitureId(entity) }.getOrNull() ?: return@forEach
            found += entity.uniqueId
            trackRoot(entity)
        }
        roots.values.asSequence()
            .filter { it.rootChunkX == chunkX && it.rootChunkZ == chunkZ && it.root.uniqueId !in found }
            .map { it.root.uniqueId }
            .toList()
            .forEach(::removeRoot)
    }

    private fun trackRoot(entity: Entity): RootState? {
        if (entity.world.name != FURNITURE_GALLERY_WORLD || !entity.isValid) {
            removeRoot(entity.uniqueId)
            return null
        }
        val id = runCatching { rootResolver.furnitureId(entity) }.getOrNull()
        if (id.isNullOrBlank()) {
            removeRoot(entity.uniqueId)
            return null
        }
        val anchor = entity.location.toGalleryAnchor()
        val current = roots[entity.uniqueId]
        if (current != null && current.furnitureId == id && current.anchor == anchor) {
            current.root = entity
            return current
        }

        val plan = profilesById[id]?.let { profile ->
            runCatching { FurnitureGalleryTargetPlanner.plan(profile, entity.uniqueId, anchor) }
                .onFailure { logIssue(id, "profile geometry is invalid; using native root bounds") }
                .getOrNull()
        }
        val bounds = plan?.bounds ?: nativeRootBounds(entity)
        if (bounds == null) {
            removeRoot(entity.uniqueId)
            return null
        }
        if (roots.size >= FURNITURE_GALLERY_MAX_ROOTS) {
            if (current == null) {
                logIssue("<capacity>", "tracked furniture roots exceed $FURNITURE_GALLERY_MAX_ROOTS")
                return null
            }
        }
        val state = RootState(
            root = entity,
            furnitureId = id,
            targetKey = "native:${entity.uniqueId}",
            anchor = anchor,
            bounds = bounds,
            rootChunkX = entity.location.chunk.x,
            rootChunkZ = entity.location.chunk.z,
        )
        roots[entity.uniqueId] = state
        return state
    }

    private fun reconcileTrackedRoots() {
        roots.values.toList().forEach { refreshRoot(it) }
    }

    private fun refreshRoot(state: RootState): RootState? {
        val root = state.root
        if (!root.isValid || root.world.name != FURNITURE_GALLERY_WORLD) {
            removeRoot(root.uniqueId)
            return null
        }
        val id = runCatching { rootResolver.furnitureId(root) }.getOrNull()
        if (id.isNullOrBlank()) {
            removeRoot(root.uniqueId)
            return null
        }
        if (state.furnitureId != id || state.anchor != root.location.toGalleryAnchor()) {
            return trackRoot(root)
        }
        return state
    }

    private fun removeRoot(rootId: UUID) {
        roots.remove(rootId)
    }

    private fun removeAllOwnedMarkers(world: World) {
        world.loadedChunks.forEach { chunk ->
            removeOwnedMarkers(chunk)
        }
    }

    private fun removeOwnedMarkers(chunk: org.bukkit.Chunk) {
        chunk.entities.filter(legacyMarkers::isOwned).forEach(Entity::remove)
    }

    private fun nativeRootBounds(entity: Entity): FurnitureGalleryBounds? = runCatching {
        val box = entity.boundingBox
        val bounds = FurnitureGalleryBounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
        val dimensions = listOf(bounds.widthX, bounds.height, bounds.widthZ)
        bounds.takeIf {
            listOf(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ).all(Double::isFinite) &&
                dimensions.all { size -> size > MIN_NATIVE_BOUNDS_DIMENSION && size <= FURNITURE_GALLERY_MAX_DIMENSION }
        }
    }.getOrNull()

    private fun blockBelongsTo(block: Block, state: RootState): Boolean {
        val nativeRoot = runCatching { rootResolver.furnitureRoot(block) }.getOrNull()
        return nativeRoot?.uniqueId == state.root.uniqueId
    }

    private fun logIssue(id: String, detail: String) {
        val key = id.take(256)
        if (!issueIds.add(key)) return
        if (issueIds.size > MAX_LOGGED_ISSUES) issueIds.remove(issueIds.first())
        plugin.logger.warning("Furniture gallery $detail for $key; sight targeting uses the exact native root")
    }

    private fun galleryWorld(): World? = Bukkit.getWorld(FURNITURE_GALLERY_WORLD)

    private fun Location.toGalleryAnchor() = FurnitureGalleryAnchor(x, y, z, yaw.toDouble())

    private data class RootState(
        var root: Entity,
        val furnitureId: String,
        val targetKey: String,
        val anchor: FurnitureGalleryAnchor,
        val bounds: FurnitureGalleryBounds,
        val rootChunkX: Int,
        val rootChunkZ: Int,
    )

    private companion object {
        const val CHUNKS_PER_SCAN_TICK = 8
        const val RECONCILE_PERIOD_TICKS = 100L
        const val BLOCK_HIT_TOLERANCE = 0.01
        const val MAX_TARGET_DISTANCE = 64.0
        const val MIN_NATIVE_BOUNDS_DIMENSION = 1.0e-6
        const val MAX_LOGGED_ISSUES = 64
    }
}

internal fun loadFurnitureGalleryTargets(dataPath: Path): Map<String, FurnitureGalleryProfile>? = try {
    val config = ConfigManager.ofModule(dataPath, FURNITURE_GALLERY_TARGET_RESOURCE)
    config.mergeMissingFromBundled("modules/$FURNITURE_GALLERY_TARGET_RESOURCE")
    var invalidCount = 0
    val invalidIds = mutableListOf<String>()
    val parsed = FurnitureGalleryTargetConfig(config).snapshot { id ->
        invalidCount++
        if (invalidIds.size < MAX_INVALID_PROFILE_EXAMPLES) {
            invalidIds += id.filterNot(Char::isISOControl).take(100)
        }
    }
    if (invalidCount > 0) {
        ARC.instance.logger.warning(
            "Furniture gallery skipped $invalidCount invalid profile(s); examples=${invalidIds.joinToString(",")}",
        )
    }
    parsed
} catch (failure: Exception) {
    warn("Furniture gallery model profiles are unavailable; native ItemsAdder root bounds will be used", failure)
    null
}

private const val MAX_INVALID_PROFILE_EXAMPLES = 5
