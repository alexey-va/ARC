package ru.arc.worldcontent

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Sign
import org.bukkit.block.data.type.WallSign
import ru.arc.common.chests.FurnitureBarrierTracker
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class ScenePreview(
    val sceneId: String,
    val reviewDigest: String,
    val createIds: List<String>,
    val updateIds: List<String>,
    val deleteIds: List<String>,
    val unchangedIds: List<String>,
    val conflicts: List<String>,
) {
    val createCount: Int get() = createIds.size
    val updateCount: Int get() = updateIds.size
    val deleteCount: Int get() = deleteIds.size
    val unchangedCount: Int get() = unchangedIds.size
}

data class SceneMutationResult(
    val sceneId: String,
    val revision: Long,
    val objectCount: Int,
    val created: Int,
    val updated: Int,
    val deleted: Int,
)

data class ManagedObjectReadback(
    val state: ManagedSceneObjectState,
    val healthy: Boolean,
    val live: String,
)

class SceneReviewConflictException(message: String) : IllegalStateException(message)

data class ManagedSceneObjectState(
    val spec: SceneObjectSpec,
    val entityUuid: String? = null,
    val barriers: List<BlockPosition> = emptyList(),
    val priorBlockData: String? = null,
    val appliedBlockData: String? = null,
)

data class ManagedSceneState(
    val id: String,
    val currentSpec: WorldSceneSpec,
    val previousSpec: WorldSceneSpec? = null,
    val revision: Long = 0,
    val updatedAt: String = Instant.EPOCH.toString(),
    val objects: List<ManagedSceneObjectState> = emptyList(),
)

data class WorldSceneStoreFile(
    val version: Int = 1,
    val scenes: MutableMap<String, ManagedSceneState> = linkedMapOf(),
)

class WorldSceneRepository(
    private val path: Path,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create(),
) {
    fun load(): WorldSceneStoreFile {
        if (!Files.exists(path)) return WorldSceneStoreFile()
        return requireNotNull(gson.fromJson(Files.readString(path), WorldSceneStoreFile::class.java)) {
            "world scene store is empty: $path"
        }.also { require(it.version == 1) { "unsupported world scene store version: ${it.version}" } }
    }

    fun save(store: WorldSceneStoreFile) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, ".${path.fileName}-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(store), StandardCharsets.UTF_8)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

class WorldSceneManager(
    private val repository: WorldSceneRepository,
    private val furnitureRuntime: FurnitureRuntime = ItemsAdderFurnitureRuntime,
) {
    @Synchronized
    fun list(): List<WorldSceneSpec> =
        repository.load().scenes.values.sortedBy { it.id }.map { it.currentSpec }

    @Synchronized
    fun get(id: String): WorldSceneSpec? = repository.load().scenes[id.lowercase()]?.currentSpec

    @Synchronized
    fun state(id: String): ManagedSceneState? = repository.load().scenes[id.lowercase()]

    @Synchronized
    fun states(): List<ManagedSceneState> = repository.load().scenes.values.sortedBy { it.id }

    @Synchronized
    fun readback(id: String): List<ManagedObjectReadback> {
        val scene = repository.load().scenes[id.lowercase()]
            ?: throw NoSuchElementException("World scene not found: $id")
        return scene.objects.map { ManagedObjectReadback(it, liveMatches(it), liveDescription(it)) }
    }

    @Synchronized
    fun preview(spec: WorldSceneSpec): ScenePreview {
        val store = repository.load()
        val current = store.scenes[spec.id]
        validate(spec, current, store)
        return previewAgainst(spec, current)
    }

    @Synchronized
    fun previewDelete(id: String): ScenePreview {
        val current = repository.load().scenes[id.lowercase()]
            ?: throw NoSuchElementException("World scene not found: $id")
        return previewAgainst(WorldSceneSpec(current.id, emptyList()), current)
    }

    @Synchronized
    fun previewRollback(id: String): ScenePreview {
        val store = repository.load()
        val current = store.scenes[id.lowercase()]
            ?: throw NoSuchElementException("World scene not found: $id")
        val previous = current.previousSpec
            ?: throw NoSuchElementException("World scene has no previous revision: $id")
        validate(previous, current, store)
        return previewAgainst(previous, current)
    }

    @Synchronized
    fun apply(
        spec: WorldSceneSpec,
        reviewDigest: String,
    ): SceneMutationResult {
        val store = repository.load()
        val current = store.scenes[spec.id]
        validate(spec, current, store)
        val preview = previewAgainst(spec, current)
        if (preview.reviewDigest != reviewDigest) {
            throw SceneReviewConflictException("stale world scene review; run preview again")
        }
        if (preview.conflicts.isNotEmpty()) {
            throw SceneReviewConflictException("managed world content changed: ${preview.conflicts.joinToString()}")
        }
        if (preview.createCount == 0 && preview.updateCount == 0 && preview.deleteCount == 0) {
            return SceneMutationResult(spec.id, current?.revision ?: 0, spec.objects.size, 0, 0, 0)
        }

        val states = current?.objects.orEmpty().associateByTo(linkedMapOf()) { it.spec.id }
        val removeIds = (preview.deleteIds + preview.updateIds).distinct()
        removeIds.forEach { objectId ->
            states.remove(objectId)?.let(::removeManagedObject)
            saveInterim(store, current, spec.id, states)
        }
        val desired = spec.objects.associateBy { it.id }
        (preview.createIds + preview.updateIds).distinct().forEach { objectId ->
            states[objectId] = placeManagedObject(desired.getValue(objectId))
            saveInterim(store, current, spec.id, states)
        }

        val next =
            ManagedSceneState(
                id = spec.id,
                currentSpec = spec,
                previousSpec = current?.currentSpec,
                revision = (current?.revision ?: 0) + 1,
                updatedAt = Instant.now().toString(),
                objects = spec.objects.mapNotNull { states[it.id] },
            )
        store.scenes[spec.id] = next
        repository.save(store)
        return SceneMutationResult(
            sceneId = spec.id,
            revision = next.revision,
            objectCount = next.objects.size,
            created = preview.createCount,
            updated = preview.updateCount,
            deleted = preview.deleteCount,
        )
    }

    @Synchronized
    fun delete(
        id: String,
        reviewDigest: String,
    ): SceneMutationResult {
        val current = repository.load().scenes[id.lowercase()]
            ?: throw NoSuchElementException("World scene not found: $id")
        return apply(WorldSceneSpec(current.id, emptyList()), reviewDigest)
    }

    @Synchronized
    fun rollback(
        id: String,
        reviewDigest: String,
    ): SceneMutationResult {
        val current = repository.load().scenes[id.lowercase()]
            ?: throw NoSuchElementException("World scene not found: $id")
        val previous = current.previousSpec
            ?: throw NoSuchElementException("World scene has no previous revision: $id")
        return apply(previous, reviewDigest)
    }

    private fun previewAgainst(
        spec: WorldSceneSpec,
        current: ManagedSceneState?,
    ): ScenePreview {
        val existing = current?.objects.orEmpty().associateBy { it.spec.id }
        val desired = spec.objects.associateBy { it.id }
        val create = desired.keys.filter { it !in existing }.sorted()
        val delete = existing.keys.filter { it !in desired }.sorted()
        val update = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        desired.forEach { (id, objectSpec) ->
            val old = existing[id]
            if (old == null) return@forEach
            val matches = liveMatches(old)
            if (!matches && old.spec.kind == SceneObjectKind.MINECRAFT_BLOCK) {
                conflicts += id
            } else if (old.spec == objectSpec && matches) {
                unchanged += id
            } else {
                update += id
            }
        }
        delete.forEach { id ->
            val old = existing.getValue(id)
            if (!liveMatches(old) && old.spec.kind == SceneObjectKind.MINECRAFT_BLOCK) conflicts += id
        }
        val fingerprint = liveFingerprint(spec, current)
        return ScenePreview(
            sceneId = spec.id,
            reviewDigest = WorldSceneReview.digest(spec, current?.revision ?: 0, fingerprint),
            createIds = create,
            updateIds = update.sorted(),
            deleteIds = delete,
            unchangedIds = unchanged.sorted(),
            conflicts = conflicts.distinct().sorted(),
        )
    }

    private fun liveFingerprint(
        spec: WorldSceneSpec,
        current: ManagedSceneState?,
    ): String =
        buildString {
            spec.objects.sortedBy { it.id }.forEach { objectSpec ->
                val world = Bukkit.getWorld(objectSpec.world)
                append("target:").append(objectSpec.id).append(':')
                if (world == null || !world.isChunkLoaded(objectSpec.x.toInt() shr 4, objectSpec.z.toInt() shr 4)) {
                    append("unloaded")
                } else {
                    append(world.getBlockAt(objectSpec.x.toInt(), objectSpec.y.toInt(), objectSpec.z.toInt()).blockData.asString)
                }
                append('\n')
            }
            current?.objects.orEmpty().sortedBy { it.spec.id }.forEach { state ->
                append("managed:").append(state.spec.id).append(':').append(liveDescription(state)).append('\n')
            }
        }

    private fun liveDescription(state: ManagedSceneObjectState): String {
        val world = Bukkit.getWorld(state.spec.world) ?: return "world-unloaded"
        return when (state.spec.kind) {
            SceneObjectKind.MINECRAFT_BLOCK ->
                world.getBlockAt(state.spec.x.toInt(), state.spec.y.toInt(), state.spec.z.toInt()).blockData.asString

            SceneObjectKind.ITEMSADDER_FURNITURE -> {
                val entity = state.entityUuid?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }?.let(world::getEntity)
                val furniture = entity?.takeIf { it.isValid }?.let(furnitureRuntime::inspect)
                val barriers = state.barriers.count { pos -> world.getBlockAt(pos.x, pos.y, pos.z).type == Material.BARRIER }
                "${furniture?.namespacedId ?: "missing"}:$barriers"
            }
        }
    }

    private fun liveMatches(state: ManagedSceneObjectState): Boolean =
        when (state.spec.kind) {
            SceneObjectKind.MINECRAFT_BLOCK -> liveDescription(state) == state.appliedBlockData
            SceneObjectKind.ITEMSADDER_FURNITURE -> {
                val world = Bukkit.getWorld(state.spec.world) ?: return false
                val entity = state.entityUuid?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }?.let(world::getEntity)
                val handle = entity?.takeIf { it.isValid }?.let(furnitureRuntime::inspect)
                handle?.namespacedId == state.spec.namespacedId
            }
        }

    private fun placeManagedObject(spec: SceneObjectSpec): ManagedSceneObjectState {
        val world = Bukkit.getWorld(spec.world) ?: throw IllegalStateException("World is not loaded: ${spec.world}")
        return when (spec.kind) {
            SceneObjectKind.MINECRAFT_BLOCK -> {
                val block = world.getBlockAt(spec.x.toInt(), spec.y.toInt(), spec.z.toInt())
                val desired = Bukkit.createBlockData(requireNotNull(spec.blockData))
                val prior = block.blockData.asString
                block.setBlockData(desired, false)
                ManagedSceneObjectState(
                    spec = spec,
                    priorBlockData = prior,
                    appliedBlockData = block.blockData.asString,
                )
            }

            SceneObjectKind.ITEMSADDER_FURNITURE -> {
                val anchor = world.getBlockAt(spec.x.toInt(), spec.y.toInt(), spec.z.toInt())
                if (spec.placement == FurniturePlacement.BLOCK && !anchor.type.isAir) {
                    throw SceneReviewConflictException("block furniture anchor is not air: ${spec.id}")
                }
                val before = FurnitureBarrierTracker.snapshotBarrierBlocks(anchor)
                val entity =
                    when (spec.placement) {
                        FurniturePlacement.BLOCK -> furnitureRuntime.spawnBlock(requireNotNull(spec.namespacedId), anchor)
                        FurniturePlacement.PRECISE_NON_SOLID ->
                            furnitureRuntime.spawnPreciseNonSolid(
                                requireNotNull(spec.namespacedId),
                                org.bukkit.Location(world, spec.x, spec.y, spec.z, spec.yaw, spec.pitch),
                            )
                        null -> throw IllegalArgumentException("Furniture placement is missing: ${spec.id}")
                    }
                val after = FurnitureBarrierTracker.snapshotBarrierBlocks(anchor)
                val barriers =
                    FurnitureBarrierTracker.detectSpawned(before, after).map {
                        BlockPosition(world.name, it.x, it.y, it.z)
                    }
                ManagedSceneObjectState(spec = spec, entityUuid = entity.uniqueId.toString(), barriers = barriers)
            }
        }
    }

    private fun removeManagedObject(state: ManagedSceneObjectState) {
        val world = Bukkit.getWorld(state.spec.world)
            ?: throw SceneReviewConflictException("World is not loaded: ${state.spec.world}")
        when (state.spec.kind) {
            SceneObjectKind.MINECRAFT_BLOCK -> {
                val block = world.getBlockAt(state.spec.x.toInt(), state.spec.y.toInt(), state.spec.z.toInt())
                if (block.blockData.asString != state.appliedBlockData) {
                    throw SceneReviewConflictException("managed block changed: ${state.spec.id}")
                }
                block.setBlockData(Bukkit.createBlockData(requireNotNull(state.priorBlockData)), false)
            }

            SceneObjectKind.ITEMSADDER_FURNITURE -> {
                val entity = state.entityUuid?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }?.let(world::getEntity)
                if (entity != null && entity.isValid) {
                    val handle = furnitureRuntime.inspect(entity)
                        ?: throw SceneReviewConflictException("managed furniture identity changed: ${state.spec.id}")
                    if (!furnitureRuntime.remove(handle.root, handle.family)) {
                        throw IllegalStateException("ItemsAdder failed to remove furniture: ${state.spec.id}")
                    }
                }
                state.barriers.forEach { pos ->
                    val block = world.getBlockAt(pos.x, pos.y, pos.z)
                    if (block.type == Material.BARRIER) block.setType(Material.AIR, false)
                }
            }
        }
    }

    private fun validate(
        spec: WorldSceneSpec,
        current: ManagedSceneState?,
        store: WorldSceneStoreFile,
    ) {
        require(spec.objects.size <= WorldSceneSpecParser.MAX_OBJECTS) { "too many scene objects" }
        require(spec.objects.map { it.id }.distinct().size == spec.objects.size) { "duplicate object ids" }
        val occupied = mutableSetOf<String>()
        val foreignPositions =
            store.scenes.values
                .asSequence()
                .filter { it.id != spec.id }
                .flatMap { scene -> scene.objects.asSequence().map { it.spec } }
                .associateBy(
                    { "${it.world}:${it.x}:${it.y}:${it.z}" },
                    { it.id },
                )
        spec.objects.forEach { objectSpec ->
            val world = Bukkit.getWorld(objectSpec.world)
                ?: throw IllegalArgumentException("World is not loaded: ${objectSpec.world}")
            require(world.isChunkLoaded(objectSpec.x.toInt() shr 4, objectSpec.z.toInt() shr 4)) {
                "Chunk is not loaded for ${objectSpec.id}"
            }
            val position = "${objectSpec.world}:${objectSpec.x}:${objectSpec.y}:${objectSpec.z}"
            require(occupied.add(position)) { "scene objects share one coordinate: $position" }
            require(position !in foreignPositions) {
                "coordinate is already managed by another scene object ${foreignPositions[position]}: $position"
            }
            when (objectSpec.kind) {
                SceneObjectKind.MINECRAFT_BLOCK -> validateSafeBlock(objectSpec)
                SceneObjectKind.ITEMSADDER_FURNITURE -> {
                    check(furnitureRuntime.available) { "ItemsAdder is not enabled" }
                    if (objectSpec.placement == FurniturePlacement.BLOCK) {
                        val anchor = world.getBlockAt(objectSpec.x.toInt(), objectSpec.y.toInt(), objectSpec.z.toInt())
                        val old = current?.objects?.firstOrNull { it.spec.id == objectSpec.id }
                        val sameManagedAnchor =
                            old?.spec?.kind == SceneObjectKind.ITEMSADDER_FURNITURE &&
                                old.spec.world == objectSpec.world &&
                                old.spec.x == objectSpec.x &&
                                old.spec.y == objectSpec.y &&
                                old.spec.z == objectSpec.z
                        require(anchor.type.isAir || sameManagedAnchor) {
                            "block furniture anchor must be air: ${objectSpec.id}"
                        }
                    }
                }
            }
        }
    }

    private fun validateSafeBlock(spec: SceneObjectSpec) {
        val data =
            try {
                Bukkit.createBlockData(requireNotNull(spec.blockData))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid blockData for ${spec.id}: ${e.message}", e)
            }
        val material = data.material
        require(material.isBlock && !material.isAir && !material.hasGravity()) { "Unsafe managed block material: $material" }
        require(data !is Bisected && data !is Bed && data !is Door && data !is Sign && data !is WallSign) {
            "Multi-block or tile-state material is not supported: $material"
        }
        val name = material.name
        val forbidden =
            listOf(
                "BARRIER", "CHEST", "BARREL", "SHULKER", "FURNACE", "HOPPER", "DISPENSER", "DROPPER",
                "COMMAND_BLOCK", "STRUCTURE_BLOCK", "JIGSAW", "SPAWNER", "VAULT", "CRAFTER", "LECTERN",
                "JUKEBOX", "TNT", "FIRE", "PORTAL", "REDSTONE", "REPEATER", "COMPARATOR", "PISTON",
                "_SIGN", "_HEAD", "_SKULL",
            )
        require(forbidden.none(name::contains)) { "Unsafe managed block material: $material" }
    }

    private fun saveInterim(
        store: WorldSceneStoreFile,
        previous: ManagedSceneState?,
        sceneId: String,
        states: Map<String, ManagedSceneObjectState>,
    ) {
        store.scenes[sceneId] =
            ManagedSceneState(
                id = sceneId,
                currentSpec = WorldSceneSpec(sceneId, states.values.map { it.spec }),
                // If a later object fails, the persisted hybrid can still be rolled
                // back to the exact scene that existed before this batch started.
                previousSpec = previous?.currentSpec,
                revision = previous?.revision ?: 0,
                updatedAt = Instant.now().toString(),
                objects = states.values.toList(),
            )
        repository.save(store)
    }
}
