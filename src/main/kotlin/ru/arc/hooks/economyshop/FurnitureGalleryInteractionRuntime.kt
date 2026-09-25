package ru.arc.hooks.economyshop

import dev.lone.itemsadder.api.CustomFurniture
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.util.Logging.warn
import java.util.LinkedHashMap

internal data class FurnitureGalleryMarker(
    val targetKey: String,
    val furnitureId: String,
    val segment: Int,
)

/** Owns marker keys so purchase and ItemInfo routes resolve one exact target identity. */
internal class FurnitureGalleryTargetMarkers(plugin: Plugin) {
    private val ownerKey = NamespacedKey(plugin, "furniture-gallery-hitbox")
    private val targetKey = NamespacedKey(plugin, "furniture-gallery-target")
    private val furnitureKey = NamespacedKey(plugin, "furniture-gallery-furniture")
    private val segmentKey = NamespacedKey(plugin, "furniture-gallery-segment")

    fun read(entity: Entity): FurnitureGalleryMarker? = runCatching {
        val data = entity.persistentDataContainer
        if (data.get(ownerKey, PersistentDataType.BYTE) != MARKER_VERSION) return null
        val target = data.get(targetKey, PersistentDataType.STRING)?.takeIf(String::isNotBlank) ?: return null
        val furniture = data.get(furnitureKey, PersistentDataType.STRING)
            ?.takeIf { it.matches(FURNITURE_ID_PATTERN) }
            ?: return null
        val segment = data.get(segmentKey, PersistentDataType.INTEGER)?.takeIf { it >= 0 } ?: return null
        FurnitureGalleryMarker(target, furniture, segment)
    }.getOrNull()

    fun isOwned(entity: Entity): Boolean =
        entity.persistentDataContainer.get(ownerKey, PersistentDataType.BYTE) == MARKER_VERSION

    fun write(entity: Interaction, target: FurnitureGalleryTarget, segment: FurnitureGallerySegment) {
        entity.persistentDataContainer.apply {
            set(ownerKey, PersistentDataType.BYTE, MARKER_VERSION)
            set(targetKey, PersistentDataType.STRING, target.key)
            set(furnitureKey, PersistentDataType.STRING, target.furnitureId)
            set(segmentKey, PersistentDataType.INTEGER, segment.index)
        }
    }

    private companion object {
        val MARKER_VERSION: Byte = 1
        val FURNITURE_ID_PATTERN = Regex("[a-z0-9._-]+:[a-z0-9._/-]+")
    }
}

internal fun interface FurnitureGalleryNativeRootLookup {
    fun matches(world: World, target: FurnitureGalleryTarget): Boolean
}

/**
 * Reconciles ephemeral click targets only in the authored gallery world. It
 * never loads chunks and never persists synthetic entities across restarts.
 */
internal class FurnitureGalleryInteractionRuntime(
    private val plugin: Plugin,
    targets: List<FurnitureGalleryTarget>,
    private val nativeRootLookup: FurnitureGalleryNativeRootLookup = ItemsAdderFurnitureGalleryRootLookup,
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    val markers = FurnitureGalleryTargetMarkers(plugin)
    private val targetsByKey = targets.associateBy(FurnitureGalleryTarget::key)
    private val targetList = targetsByKey.values.toList()
    private val targetsByFurnitureId = targetList.groupBy(FurnitureGalleryTarget::furnitureId)
    private val targetsByChunk = targetList
        .flatMap { target -> target.dependencyChunks.map { chunk -> chunk to target } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.distinctBy(FurnitureGalleryTarget::key) }
    private val targetsByAnchorChunk = targetList.groupBy { it.anchorChunkX to it.anchorChunkZ }
    private val retryRemaining = LinkedHashMap<String, Int>()
    private val immediateReconcile = LinkedHashSet<String>()
    private var retryScheduled = false
    private var reconcileScheduled = false
    private var closed = false

    init {
        require(targets.size <= FURNITURE_GALLERY_MAX_TARGETS) {
            "Furniture gallery target count exceeds $FURNITURE_GALLERY_MAX_TARGETS"
        }
        require(targetsByKey.size == targets.size) { "Furniture gallery target keys must be unique" }
    }

    fun start() {
        check(!closed) { "Furniture gallery interaction runtime is closed" }
        val token = tasks.restart()
        Bukkit.getWorld(FURNITURE_GALLERY_WORLD)?.let(::removeAllOwnedMarkers)
        galleryWorld()?.let { world ->
            targetList.forEach { target ->
                if (target.touchesLoadedChunk(world)) enqueue(target)
            }
        }
        scheduleImmediate(token)
        if (targetList.isNotEmpty()) {
            tasks.runTimer(token, RECONCILE_PERIOD_TICKS, RECONCILE_PERIOD_TICKS) {
                val activeWorld = galleryWorld() ?: return@runTimer
                targetList.asSequence()
                    .filter { it.touchesLoadedChunk(activeWorld) }
                    .forEach(::reconcileTarget)
            }
        }
    }

    fun targetForMarker(entity: Entity): FurnitureGalleryTarget? {
        if (entity.world.name != FURNITURE_GALLERY_WORLD) return null
        val marker = markers.read(entity) ?: return null
        val target = targetsByKey[marker.targetKey] ?: return null
        if (target.furnitureId != marker.furnitureId || marker.segment !in target.segments.indices) return null
        if (!entityMatchesSegment(entity, target, target.segments[marker.segment])) return null
        val world = galleryWorld() ?: return null
        if (!world.isChunkLoaded(target.anchorChunkX, target.anchorChunkZ)) return null
        if (!nativeRootLookup.matches(world, target)) return null
        return target
    }

    fun targetForFurniture(furnitureId: String?, root: Entity?): FurnitureGalleryTarget? {
        if (root == null || root.world.name != FURNITURE_GALLERY_WORLD) return null
        val id = furnitureId?.takeIf(String::isNotBlank) ?: return null
        val world = galleryWorld() ?: return null
        return targetsByFurnitureId[id].orEmpty().firstOrNull { target ->
            FurnitureGalleryTargetPlanner.matchesFurnitureRoot(target, id, root.location.toGalleryAnchor()) &&
                world.isChunkLoaded(target.anchorChunkX, target.anchorChunkZ) &&
                nativeRootLookup.matches(world, target)
        }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (event.world.name != FURNITURE_GALLERY_WORLD || closed) return
        val affected = targetsByChunk[event.chunk.x to event.chunk.z].orEmpty()
        affected.forEach(::enqueue)
        scheduleImmediate(tasks.token())
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        if (event.world.name != FURNITURE_GALLERY_WORLD || closed) return
        targetsByAnchorChunk[event.chunk.x to event.chunk.z].orEmpty().asSequence()
            .forEach { target ->
                retryRemaining.remove(target.key)
                immediateReconcile.remove(target.key)
                removeTargetMarkers(target)
            }
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        if (event.world.name != FURNITURE_GALLERY_WORLD || closed) return
        targetList.forEach(::enqueue)
        scheduleImmediate(tasks.token())
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        retryRemaining.clear()
        immediateReconcile.clear()
        retryScheduled = false
        reconcileScheduled = false
        Bukkit.getWorld(FURNITURE_GALLERY_WORLD)?.let(::removeAllOwnedMarkers)
    }

    private fun enqueue(target: FurnitureGalleryTarget) {
        if (closed) return
        retryRemaining[target.key] = RETRY_ATTEMPTS
        immediateReconcile += target.key
    }

    private fun scheduleImmediate(token: LifecycleTaskScope.Token) {
        if (reconcileScheduled || immediateReconcile.isEmpty()) return
        reconcileScheduled = true
        tasks.runLater(token, 1L) {
            reconcileScheduled = false
            val keys = immediateReconcile.toList()
            immediateReconcile.clear()
            keys.forEach { key ->
                val target = targetsByKey[key] ?: return@forEach
                when (reconcileTarget(target)) {
                    TargetState.READY, TargetState.WAITING_FOR_CHUNK -> retryRemaining.remove(key)
                    TargetState.WAITING_FOR_ROOT -> Unit
                }
            }
            scheduleRetry(tasks.token())
        }
    }

    private fun scheduleRetry(token: LifecycleTaskScope.Token) {
        if (retryScheduled || retryRemaining.isEmpty()) return
        retryScheduled = true
        tasks.runLater(token, RETRY_PERIOD_TICKS) {
            retryScheduled = false
            val keys = retryRemaining.keys.toList()
            keys.forEach { key ->
                val target = targetsByKey[key] ?: run {
                    retryRemaining.remove(key)
                    return@forEach
                }
                when (reconcileTarget(target)) {
                    TargetState.READY, TargetState.WAITING_FOR_CHUNK -> retryRemaining.remove(key)
                    TargetState.WAITING_FOR_ROOT -> {
                        val left = (retryRemaining[key] ?: 0) - 1
                        if (left <= 0) retryRemaining.remove(key) else retryRemaining[key] = left
                    }
                }
            }
            scheduleRetry(tasks.token())
        }
    }

    private fun reconcileTarget(target: FurnitureGalleryTarget): TargetState {
        val world = galleryWorld() ?: return TargetState.WAITING_FOR_CHUNK
        if (!world.isChunkLoaded(target.anchorChunkX, target.anchorChunkZ)) {
            removeTargetMarkers(target)
            return TargetState.WAITING_FOR_CHUNK
        }
        if (!nativeRootLookup.matches(world, target)) {
            removeTargetMarkers(target)
            return TargetState.WAITING_FOR_ROOT
        }

        val loadedChunkKeys = target.segments.asSequence()
            .map { it.chunkX to it.chunkZ }
            .distinct()
            .filter { (x, z) -> world.isChunkLoaded(x, z) }
            .toSet()
        val desired = FurnitureGalleryTargetPlanner.desiredSegments(
            target,
            anchorChunkLoaded = true,
            exactRootLoaded = true,
            loadedChunks = loadedChunkKeys,
        )
        val existing = loadedSegments(world, target)
        val desiredIndexes = desired.mapTo(HashSet(), FurnitureGallerySegment::index)
        existing.filter { it.index !in desiredIndexes }.forEach { it.entity.remove() }
        existing.groupBy(ExistingSegment::index).values.forEach { duplicates ->
            duplicates.drop(1).forEach { it.entity.remove() }
        }
        val existingIndexes = existing.asSequence()
            .filter { it.index in desiredIndexes }
            .map { it.index }
            .toSet()
        desired.filterNot { it.index in existingIndexes }.forEach { segment -> spawnMarker(world, target, segment) }
        return TargetState.READY
    }

    private fun loadedSegments(world: World, target: FurnitureGalleryTarget): List<ExistingSegment> =
        target.segments.asSequence()
            .map { it.chunkX to it.chunkZ }
            .distinct()
            .filter { (x, z) -> world.isChunkLoaded(x, z) }
            .flatMap { (x, z) -> world.getChunkAt(x, z).entities.asSequence() }
            .mapNotNull { entity ->
                val marker = markers.read(entity) ?: return@mapNotNull null
                if (marker.targetKey != target.key) return@mapNotNull null
                val segment = target.segments.getOrNull(marker.segment)
                    ?: return@mapNotNull ExistingSegment(marker.segment, entity)
                if (!entityMatchesSegment(entity, target, segment)) {
                    entity.remove()
                    return@mapNotNull null
                }
                ExistingSegment(marker.segment, entity)
            }
            .toList()

    private fun spawnMarker(world: World, target: FurnitureGalleryTarget, segment: FurnitureGallerySegment) {
        val location = Location(world, segment.x, segment.y, segment.z)
        val interaction = world.spawn(location, Interaction::class.java)
        interaction.interactionWidth = segment.width.toFloat()
        interaction.interactionHeight = segment.height.toFloat()
        interaction.isResponsive = true
        interaction.isPersistent = false
        interaction.isInvulnerable = true
        interaction.setGravity(false)
        markers.write(interaction, target, segment)
    }

    private fun removeTargetMarkers(target: FurnitureGalleryTarget) {
        val world = galleryWorld() ?: return
        target.segments.asSequence()
            .map { it.chunkX to it.chunkZ }
            .distinct()
            .filter { (x, z) -> world.isChunkLoaded(x, z) }
            .flatMap { (x, z) -> world.getChunkAt(x, z).entities.asSequence() }
            .filter { markers.read(it)?.targetKey == target.key }
            .forEach(Entity::remove)
    }

    private fun removeAllOwnedMarkers(world: World) {
        world.loadedChunks.forEach { chunk ->
            chunk.entities.filter(markers::isOwned).forEach(Entity::remove)
        }
    }

    private fun entityMatchesSegment(entity: Entity, target: FurnitureGalleryTarget, segment: FurnitureGallerySegment): Boolean {
        val interaction = entity as? Interaction ?: return false
        return entity.world.name == FURNITURE_GALLERY_WORLD &&
            markers.read(entity)?.let {
                it.targetKey == target.key && it.furnitureId == target.furnitureId && it.segment == segment.index
            } == true &&
            kotlin.math.abs(entity.location.x - segment.x) <= MARKER_LOCATION_TOLERANCE &&
            kotlin.math.abs(entity.location.y - segment.y) <= MARKER_LOCATION_TOLERANCE &&
            kotlin.math.abs(entity.location.z - segment.z) <= MARKER_LOCATION_TOLERANCE &&
            kotlin.math.abs(interaction.interactionWidth - segment.width.toFloat()) <= MARKER_SIZE_TOLERANCE &&
            kotlin.math.abs(interaction.interactionHeight - segment.height.toFloat()) <= MARKER_SIZE_TOLERANCE &&
            interaction.isResponsive
    }

    private fun FurnitureGalleryTarget.touchesLoadedChunk(world: World): Boolean =
        dependencyChunks.any { (x, z) -> world.isChunkLoaded(x, z) }

    private fun galleryWorld(): World? = Bukkit.getWorld(FURNITURE_GALLERY_WORLD)

    private fun Location.toGalleryAnchor() = FurnitureGalleryAnchor(x, y, z, yaw.toDouble())

    private enum class TargetState { READY, WAITING_FOR_CHUNK, WAITING_FOR_ROOT }

    private data class ExistingSegment(val index: Int, val entity: Entity)

    private companion object {
        const val RETRY_ATTEMPTS = 5
        const val RETRY_PERIOD_TICKS = 20L
        const val RECONCILE_PERIOD_TICKS = 100L
        const val MARKER_LOCATION_TOLERANCE = 0.01
        const val MARKER_SIZE_TOLERANCE = 0.01f
    }
}

internal fun loadFurnitureGalleryTargets(dataPath: java.nio.file.Path): List<FurnitureGalleryTarget>? = try {
    val config = ConfigManager.ofModule(dataPath, FURNITURE_GALLERY_TARGET_RESOURCE)
    config.mergeMissingFromBundled("modules/$FURNITURE_GALLERY_TARGET_RESOURCE")
    var invalidCount = 0
    val invalidKeys = mutableListOf<String>()
    val parsed = FurnitureGalleryTargetConfig(config).snapshot { key ->
        invalidCount++
        if (invalidKeys.size < MAX_INVALID_TARGET_EXAMPLES) {
            invalidKeys += key.filterNot(Char::isISOControl).take(80)
        }
    }
    if (invalidCount > 0) {
        ARC.instance.logger.warning(
            "Furniture gallery skipped $invalidCount invalid target(s); examples=${invalidKeys.joinToString(",")}",
        )
    }
    parsed
} catch (failure: Exception) {
    warn("Furniture gallery configuration is invalid; click targets remain disabled", failure)
    null
}

private const val MAX_INVALID_TARGET_EXAMPLES = 5

private object ItemsAdderFurnitureGalleryRootLookup : FurnitureGalleryNativeRootLookup {
    override fun matches(world: World, target: FurnitureGalleryTarget): Boolean {
        if (!world.isChunkLoaded(target.anchorChunkX, target.anchorChunkZ)) return false
        return world.getChunkAt(target.anchorChunkX, target.anchorChunkZ).entities.asSequence()
            .filterIsInstance<ItemDisplay>()
            .filter { entity ->
                FurnitureGalleryTargetPlanner.matchesAnchor(target.anchor, entity.location.toGalleryAnchor())
            }
            .any { entity ->
                val furniture = runCatching { CustomFurniture.byAlreadySpawned(entity) }.getOrNull() ?: return@any false
                FurnitureGalleryTargetPlanner.matchesFurnitureRoot(
                    target,
                    furniture.namespacedID,
                    entity.location.toGalleryAnchor(),
                ) && furniture.entity?.uniqueId == entity.uniqueId
            }
    }
}

private fun Location.toGalleryAnchor() = FurnitureGalleryAnchor(x, y, z, yaw.toDouble())
