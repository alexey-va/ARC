package ru.arc.hooks.economyshop

import dev.lone.itemsadder.api.CustomFurniture
import dev.lone.itemsadder.api.Events.FurnitureBreakEvent
import dev.lone.itemsadder.api.Events.FurniturePlaceSuccessEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.util.Logging.warn
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID

internal data class FurnitureGalleryMarker(
    val targetKey: String,
    val furnitureId: String,
    val segment: Int,
)

/** Owns marker keys so purchase and ItemInfo routes resolve one exact live root. */
internal class FurnitureGalleryTargetMarkers(plugin: Plugin) {
    private val ownerKey = NamespacedKey(plugin, "furniture-gallery-hitbox")
    private val targetKey = NamespacedKey(plugin, "furniture-gallery-target")
    private val furnitureKey = NamespacedKey(plugin, "furniture-gallery-furniture")
    private val segmentKey = NamespacedKey(plugin, "furniture-gallery-segment")

    fun read(entity: Entity): FurnitureGalleryMarker? = runCatching {
        val data = entity.persistentDataContainer
        if (data.get(ownerKey, PersistentDataType.BYTE) != MARKER_VERSION) return null
        val target = data.get(targetKey, PersistentDataType.STRING)?.takeIf(String::isNotBlank) ?: return null
        if (!target.startsWith(NATIVE_ROOT_PREFIX)) return null
        UUID.fromString(target.removePrefix(NATIVE_ROOT_PREFIX))
        val furniture = data.get(furnitureKey, PersistentDataType.STRING)
            ?.takeIf { it.matches(FURNITURE_ID_PATTERN) }
            ?: return null
        val segment = data.get(segmentKey, PersistentDataType.INTEGER)?.takeIf { it >= 0 } ?: return null
        FurnitureGalleryMarker(target, furniture, segment)
    }.getOrNull()

    fun isOwned(entity: Entity): Boolean =
        entity.persistentDataContainer.get(ownerKey, PersistentDataType.BYTE) in OWNED_MARKER_VERSIONS

    fun write(entity: Interaction, plan: FurnitureGalleryHitboxPlan, segment: FurnitureGallerySegment) {
        entity.persistentDataContainer.apply {
            set(ownerKey, PersistentDataType.BYTE, MARKER_VERSION)
            set(targetKey, PersistentDataType.STRING, plan.targetKey)
            set(furnitureKey, PersistentDataType.STRING, plan.furnitureId)
            set(segmentKey, PersistentDataType.INTEGER, segment.index)
        }
    }

    fun matchesTarget(entity: Entity, targetKey: String, furnitureId: String, segment: Int): Boolean =
        read(entity)?.let {
            it.targetKey == targetKey && it.furnitureId == furnitureId && it.segment == segment
        } == true

    private companion object {
        val MARKER_VERSION: Byte = 2
        val OWNED_MARKER_VERSIONS = setOf(1.toByte(), MARKER_VERSION)
        const val NATIVE_ROOT_PREFIX = "native:"
        val FURNITURE_ID_PATTERN = Regex("[a-z0-9._-]+:[a-z0-9._/-]+")
    }
}

internal fun interface FurnitureGalleryNativeRootResolver {
    /** Returns an ID only when [entity] itself is the exact live IA furniture root. */
    fun furnitureId(entity: Entity): String?
}

internal fun interface FurnitureGallerySwingTargetResolver {
    fun targetEntity(player: Player): Entity?
}

private object ItemsAdderFurnitureGalleryNativeRootResolver : FurnitureGalleryNativeRootResolver {
    override fun furnitureId(entity: Entity): String? = runCatching {
        val furniture = CustomFurniture.byAlreadySpawned(entity) ?: return null
        if (furniture.entity?.uniqueId != entity.uniqueId) return null
        furniture.namespacedID?.trim()?.takeIf(String::isNotEmpty)
    }.getOrNull()
}

/**
 * Discovers exact ItemsAdder roots in already-loaded gallery chunks and owns
 * transient Interaction boxes only for profile-backed IDs with a live ESG buy
 * entry. Discovery is sliced; no task loads chunks or scans the ESG catalog.
 */
internal class FurnitureGalleryInteractionRuntime(
    private val plugin: Plugin,
    profiles: Map<String, FurnitureGalleryProfile>,
    private val hasPurchaseOffer: (String) -> Boolean = { false },
    private val rootResolver: FurnitureGalleryNativeRootResolver = ItemsAdderFurnitureGalleryNativeRootResolver,
    private val swingTargetResolver: FurnitureGallerySwingTargetResolver =
        FurnitureGallerySwingTargetResolver { player -> player.getTargetEntity(NATIVE_BREAK_RAY_DISTANCE) },
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    val markers = FurnitureGalleryTargetMarkers(plugin)
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

    fun targetForMarker(entity: Entity): FurnitureGalleryHitboxPlan? {
        if (entity.world.name != FURNITURE_GALLERY_WORLD) return null
        val marker = markers.read(entity) ?: return null
        val rootId = marker.rootIdOrNull() ?: return null
        val state = roots[rootId] ?: return null
        val plan = state.plan
        val segment = plan.segments.getOrNull(marker.segment) ?: return null
        if (marker.targetKey != plan.targetKey || marker.furnitureId != plan.furnitureId) return null
        if (!entityMatchesSegment(entity, plan, segment)) return null
        if (!rootStillMatches(state)) return null
        return plan
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
        tasks.runLater(1L) { enqueueChunk(entity.location.chunk.x, entity.location.chunk.z); scheduleChunkScan(tasks.token()) }
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

    /**
     * Let ItemsAdder's own swing listener ray-trace the native furniture root
     * instead of ARC's larger synthetic Interaction. Its normal protection,
     * FurnitureBreakEvent, callbacks, and drop rules then remain in force.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onNativeFurnitureSwing(event: PlayerAnimationEvent) {
        if (closed || event.animationType != PlayerAnimationType.ARM_SWING) return
        val player = event.player
        if (player.world.name != FURNITURE_GALLERY_WORLD) return
        val marker = runCatching { swingTargetResolver.targetEntity(player) }.getOrNull() as? Interaction
            ?: return
        val plan = targetForMarker(marker) ?: return
        suppressRootMarkersForNativeSwing(plan)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (event.world.name != FURNITURE_GALLERY_WORLD || closed) return
        enqueueChunk(event.chunk.x, event.chunk.z)
        scheduleChunkScan(tasks.token())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        if (event.world.name != FURNITURE_GALLERY_WORLD || closed) return
        roots.values.asSequence()
            .filter { it.plan.rootChunkX == event.chunk.x && it.plan.rootChunkZ == event.chunk.z }
            .map { it.plan.rootId }
            .toList()
            .forEach(::removeRoot)
        pendingChunks.remove(event.chunk.x to event.chunk.z)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldLoad(event: WorldLoadEvent) {
        if (event.world.name != FURNITURE_GALLERY_WORLD || closed) return
        tasks.runLater(1L) {
            event.world.loadedChunks.forEach { enqueueChunk(it.x, it.z) }
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
        chunk.entities.forEach { entity ->
            if (markers.isOwned(entity)) return@forEach
            if (entity !is ItemDisplay && entity !is ArmorStand) return@forEach
            val id = runCatching { rootResolver.furnitureId(entity) }.getOrNull() ?: return@forEach
            val profile = profilesById[id] ?: return@forEach
            if (!runCatching { hasPurchaseOffer(id) }.getOrDefault(false)) return@forEach
            found += entity.uniqueId
            trackRoot(entity, profile)
        }
        roots.values.asSequence()
            .filter { it.plan.rootChunkX == chunkX && it.plan.rootChunkZ == chunkZ && it.plan.rootId !in found }
            .map { it.plan.rootId }
            .toList()
            .forEach(::removeRoot)
    }

    private fun trackRoot(entity: Entity, profile: FurnitureGalleryProfile) {
        if (entity.world.name != FURNITURE_GALLERY_WORLD || !entity.isValid) return
        val id = profile.furnitureId
        if (rootResolver.furnitureId(entity) != id || !runCatching { hasPurchaseOffer(id) }.getOrDefault(false)) return
        val anchor = entity.location.toGalleryAnchor()
        val current = roots[entity.uniqueId]
        if (current != null && current.profile === profile && current.plan.anchor == anchor) {
            current.root = entity
            reconcileMarkers(current)
            return
        }
        val plan = runCatching { FurnitureGalleryTargetPlanner.plan(profile, entity.uniqueId, entity.location.toGalleryAnchor()) }
            .getOrElse {
                logIssue(id, "profile geometry is invalid")
                removeRoot(entity.uniqueId)
                return
            }
        if (current != null) removeRoot(entity.uniqueId)
        if (roots.size >= FURNITURE_GALLERY_MAX_ROOTS) {
            logIssue("<capacity>", "active profiled furniture roots exceed $FURNITURE_GALLERY_MAX_ROOTS")
            return
        }
        val state = RootState(entity, profile, plan)
        roots[entity.uniqueId] = state
        reconcileMarkers(state)
    }

    private fun reconcileTrackedRoots() {
        roots.values.toList().forEach { state ->
            if (!rootStillMatches(state)) {
                val entity = state.root
                val currentId = if (entity.isValid && entity.world.name == FURNITURE_GALLERY_WORLD) {
                    runCatching { rootResolver.furnitureId(entity) }.getOrNull()
                } else null
                removeRoot(state.plan.rootId)
                if (currentId != null) profilesById[currentId]?.let { trackRoot(entity, it) }
            } else {
                trackRoot(state.root, profilesById.getValue(state.plan.furnitureId))
            }
        }
    }

    private fun rootStillMatches(state: RootState): Boolean {
        val root = state.root
        return root.isValid && root.world.name == FURNITURE_GALLERY_WORLD &&
            root.uniqueId == state.plan.rootId &&
            root.location.toGalleryAnchor() == state.plan.anchor &&
            runCatching { rootResolver.furnitureId(root) == state.plan.furnitureId }.getOrDefault(false) &&
            runCatching { hasPurchaseOffer(state.plan.furnitureId) }.getOrDefault(false)
    }

    private fun reconcileMarkers(state: RootState) {
        val world = galleryWorld() ?: return
        val plan = state.plan
        if (!world.isChunkLoaded(plan.rootChunkX, plan.rootChunkZ) || !rootStillMatches(state)) {
            removeRoot(state.plan.rootId)
            return
        }
        val loadedChunks = plan.dependencyChunks.filterTo(HashSet()) { (x, z) -> world.isChunkLoaded(x, z) }
        val desired = FurnitureGalleryTargetPlanner.desiredSegments(
            plan,
            rootChunkLoaded = true,
            loadedChunks = loadedChunks,
        )
        val desiredByIndex = desired.associateBy(FurnitureGallerySegment::index)
        val desiredIndexes = desiredByIndex.keys

        state.markers.toMap().forEach { (index, uuid) ->
            val entity = Bukkit.getEntity(uuid)
            val segment = desiredByIndex[index]
            if (entity == null || segment == null || !entityMatchesSegment(entity, plan, segment)) {
                removeOwnedMarker(uuid, plan)
                state.markers.remove(index)
            }
        }

        val loadedExisting = desired.flatMap { segment ->
            segment.touchedChunks.asSequence()
                .filter { (x, z) -> world.isChunkLoaded(x, z) }
                .flatMap { (x, z) -> world.getChunkAt(x, z).entities.asSequence() }
        }.distinctBy(Entity::getUniqueId)
            .mapNotNull { entity ->
                val marker = markers.read(entity) ?: return@mapNotNull null
                if (marker.targetKey != plan.targetKey || marker.furnitureId != plan.furnitureId) return@mapNotNull null
                val segment = desiredByIndex[marker.segment] ?: return@mapNotNull null
                if (!entityMatchesSegment(entity, plan, segment)) {
                    entity.remove()
                    return@mapNotNull null
                }
                marker.segment to entity
            }
        val duplicates = loadedExisting.groupBy({ it.first }, { it.second })
        duplicates.forEach { (index, entities) ->
            val keep = state.markers[index]?.let(Bukkit::getEntity)?.takeIf { candidate -> entities.any { it.uniqueId == candidate.uniqueId } }
                ?: entities.first()
            state.markers[index] = keep.uniqueId
            entities.filter { it.uniqueId != keep.uniqueId }.forEach(Entity::remove)
        }
        val existingIndexes = duplicates.keys
        desired.filterNot { it.index in existingIndexes || it.index in state.markers }.forEach { segment ->
            spawnMarker(world, state, segment)
        }
        state.markers.keys.filterNot(desiredIndexes::contains).toList().forEach { state.markers.remove(it) }
    }

    private fun spawnMarker(world: World, state: RootState, segment: FurnitureGallerySegment) {
        val plan = state.plan
        val interaction = runCatching {
            world.spawn(Location(world, segment.x, segment.y, segment.z), Interaction::class.java).apply {
                interactionWidth = segment.width.toFloat()
                interactionHeight = segment.height.toFloat()
                isResponsive = true
                isPersistent = false
                isInvulnerable = true
                setGravity(false)
                markers.write(this, plan, segment)
            }
        }.getOrElse {
            logIssue(plan.furnitureId, "could not create a profiled click target")
            return
        }
        state.markers[segment.index] = interaction.uniqueId
    }

    private fun removeRoot(rootId: UUID) {
        val state = roots.remove(rootId) ?: return
        state.markers.values.toList().forEach { markerId -> removeOwnedMarker(markerId, state.plan) }
    }

    private fun removeOwnedMarker(markerId: UUID, plan: FurnitureGalleryHitboxPlan) {
        val entity = Bukkit.getEntity(markerId) ?: return
        val marker = markers.read(entity) ?: return
        if (marker.targetKey == plan.targetKey && marker.furnitureId == plan.furnitureId) entity.remove()
    }

    private fun removeAllOwnedMarkers(world: World) {
        world.loadedChunks.forEach { chunk ->
            chunk.entities.filter(markers::isOwned).forEach(Entity::remove)
        }
    }

    private fun entityMatchesSegment(
        entity: Entity,
        plan: FurnitureGalleryHitboxPlan,
        segment: FurnitureGallerySegment,
    ): Boolean {
        val interaction = entity as? Interaction ?: return false
        return entity.world.name == FURNITURE_GALLERY_WORLD &&
            markers.matchesTarget(entity, plan.targetKey, plan.furnitureId, segment.index) &&
            kotlin.math.abs(entity.location.x - segment.x) <= MARKER_LOCATION_TOLERANCE &&
            kotlin.math.abs(entity.location.y - segment.y) <= MARKER_LOCATION_TOLERANCE &&
            kotlin.math.abs(entity.location.z - segment.z) <= MARKER_LOCATION_TOLERANCE &&
            kotlin.math.abs(interaction.interactionWidth - segment.width.toFloat()) <= MARKER_SIZE_TOLERANCE &&
            kotlin.math.abs(interaction.interactionHeight - segment.height.toFloat()) <= MARKER_SIZE_TOLERANCE &&
            interaction.isResponsive
    }

    private fun suppressRootMarkersForNativeSwing(plan: FurnitureGalleryHitboxPlan) {
        val state = roots[plan.rootId]?.takeIf { it.plan == plan } ?: return
        val suppressed = state.markers.mapNotNull { (segmentIndex, markerId) ->
            val segment = plan.segments.getOrNull(segmentIndex) ?: return@mapNotNull null
            val entity = Bukkit.getEntity(markerId) as? Interaction ?: return@mapNotNull null
            val marker = markers.read(entity) ?: return@mapNotNull null
            if (marker.targetKey != plan.targetKey || marker.furnitureId != plan.furnitureId) return@mapNotNull null
            if (!entityMatchesSegment(entity, plan, segment)) return@mapNotNull null

            SuppressedMarker(markerId, entity.interactionWidth, entity.interactionHeight).also {
                entity.interactionWidth = 0.0f
                entity.interactionHeight = 0.0f
            }
        }
        if (suppressed.isEmpty()) return

        tasks.runLater(1L) {
            if (closed || roots[plan.rootId]?.plan != plan) return@runLater
            val rootState = roots[plan.rootId] ?: return@runLater
            if (!rootStillMatches(rootState)) return@runLater
            suppressed.forEach { original ->
                val entity = Bukkit.getEntity(original.markerId) as? Interaction ?: return@forEach
                val marker = markers.read(entity) ?: return@forEach
                if (marker.targetKey != plan.targetKey || marker.furnitureId != plan.furnitureId) return@forEach
                entity.interactionWidth = original.width
                entity.interactionHeight = original.height
            }
        }
    }

    private fun logIssue(id: String, detail: String) {
        val key = id.take(256)
        if (!issueIds.add(key)) return
        if (issueIds.size > MAX_LOGGED_ISSUES) issueIds.remove(issueIds.first())
        plugin.logger.warning("Furniture gallery $detail for $key; synthetic targets fail closed")
    }

    private fun galleryWorld(): World? = Bukkit.getWorld(FURNITURE_GALLERY_WORLD)

    private fun Location.toGalleryAnchor() = FurnitureGalleryAnchor(x, y, z, yaw.toDouble())

    private class RootState(
        var root: Entity,
        val profile: FurnitureGalleryProfile,
        val plan: FurnitureGalleryHitboxPlan,
        val markers: MutableMap<Int, UUID> = LinkedHashMap(),
    )

    private data class SuppressedMarker(
        val markerId: UUID,
        val width: Float,
        val height: Float,
    )

    private companion object {
        const val CHUNKS_PER_SCAN_TICK = 8
        const val RECONCILE_PERIOD_TICKS = 100L
        const val MARKER_LOCATION_TOLERANCE = 0.01
        const val MARKER_SIZE_TOLERANCE = 0.01f
        const val NATIVE_BREAK_RAY_DISTANCE = 5
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
    warn("Furniture gallery profile configuration is invalid; synthetic click targets remain disabled", failure)
    null
}

private const val MAX_INVALID_PROFILE_EXAMPLES = 5

private fun FurnitureGalleryMarker.rootIdOrNull(): UUID? =
    targetKey.takeIf { it.startsWith("native:") }
        ?.removePrefix("native:")
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
