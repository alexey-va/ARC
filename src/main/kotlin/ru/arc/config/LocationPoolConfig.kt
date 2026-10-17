package ru.arc.config

import ru.arc.common.ServerLocation
import ru.arc.common.WeightedRandom
import ru.arc.common.locationpools.LocationPool
import ru.arc.common.locationpools.LocationPoolMutation
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.util.Common
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/** Single file/mirror owner. No worker serializes a mutable gameplay TreeMap. */
class LocationPoolConfig private constructor(
    private val folder: Path,
    val initialPools: List<LocationPool>,
    private val worker: BoundedAsyncExecutor,
    private val write: (Path, LocationPool) -> Unit,
) {
    private data class State(val live: LocationPool, val mirror: LocationPool, var revision: Long, var dirty: Boolean)
    private val mirrors = linkedMapOf<String, State>() // Worker-only after preparation.
    private var saveTask: ScheduledTask? = null
    @Volatile private var failure: Throwable? = null
    @Volatile private var paused = false
    @Volatile private var bindingEpoch = 0L
    private var closeResult: CompletableFuture<Unit>? = null

    init {
        initialPools.forEach { pool ->
            pool.markClean()
            mirrors[pool.id] = State(pool, copy(pool.id, pool.getWeightedLocations()), pool.currentRevision(), false)
        }
    }

    /** Main attach: already prepared pools need no file access or second full snapshot. */
    fun bindInitialPools() { initialPools.forEach(::bind) }

    /** New empty pools and existing API-created pools join the same FIFO before their first edit. */
    fun register(pool: LocationPool) {
        if (pool.isPersistenceBound()) return
        require(LocationPool.normalizePersistentId(pool.id) == pool.id) { "Location pool id is not normalized" }
        val entries = pool.getWeightedLocations()
        val revision = pool.currentRevision()
        admit {
            mirrors[pool.id] = State(pool, copy(pool.id, entries), revision, true)
        }
        bind(pool)
    }

    private fun bind(pool: LocationPool) {
        val epoch = bindingEpoch
        pool.bindPersistence { mutation ->
            check(epoch == bindingEpoch) { "Location pool ${pool.id} belongs to a replaced generation" }
            admit {
                val state = checkNotNull(mirrors[pool.id]) { "Location pool ${pool.id} is unavailable" }
                check(state.live === pool) { "Location pool ${pool.id} was replaced" }
                when (mutation) {
                    is LocationPoolMutation.Add -> state.mirror.addLocation(mutation.location, mutation.weight)
                    is LocationPoolMutation.Remove -> state.mirror.removeLocation(mutation.location)
                }
                state.revision = mutation.revision
                state.dirty = true
            }
        }
    }

    /** Throw on rejected admission before native code changes its in-memory pool. */
    private fun admit(action: () -> Unit) {
        requireWritable()
        failure?.let { throw RejectedExecutionException("Location pool persistence failed; edits are paused", it) }
        worker.execute {
            try { action() }
            catch (problem: Throwable) {
                failure = problem
                error("Location pool persistence stopped accepting edits", problem)
            }
        }
    }

    fun requireWritable() { check(!paused) { "Location pools are reloading; retry after preparation" } }

    /** Seal native editing before a worker flush/read; a failed read retains the old mirrors. */
    fun prepareReload(): CompletableFuture<List<LocationPool>> {
        requireWritable()
        paused = true
        return worker.submit {
            saveOnWorker(true)
            val loaded = loadPools(folder)
            val replacements = loaded.associate { pool ->
                pool.markClean()
                pool.id to State(pool, copy(pool.id, pool.getWeightedLocations()), pool.currentRevision(), false)
            }
            mirrors.clear()
            mirrors.putAll(replacements)
            loaded
        }.whenComplete { _, problem -> if (problem != null) paused = false }
    }

    /** Native publication after the complete worker read; old references can no longer admit edits. */
    fun bindReload(pools: List<LocationPool>) {
        bindingEpoch++
        pools.forEach(::bind)
        paused = false
    }

    fun saveLocationPools(onlyDirty: Boolean): CompletableFuture<Unit> = worker.submit { saveOnWorker(onlyDirty) }

    fun snapshots(pools: List<LocationPool>): CompletableFuture<List<LocationPool>> = worker.submit {
        pools.map { pool ->
            val state = checkNotNull(mirrors[pool.id]) { "Location pool ${pool.id} is unavailable" }
            check(state.live === pool) { "Location pool ${pool.id} is being replaced" }
            copy(pool.id, state.mirror.getWeightedLocations())
        }
    }

    fun nearby(pool: LocationPool, center: ServerLocation, radius: Double): CompletableFuture<List<ServerLocation>> = worker.submit {
        val state = checkNotNull(mirrors[pool.id]) { "Location pool ${pool.id} is unavailable" }
        check(state.live === pool) { "Location pool ${pool.id} is being replaced" }
        val squared = radius * radius
        state.mirror.getAllLocations().filter { point ->
            point.server.equals(center.server, true) && point.world == center.world &&
                (point.x - center.x) * (point.x - center.x) + (point.y - center.y) * (point.y - center.y) +
                (point.z - center.z) * (point.z - center.z) <= squared
        }
    }

    private fun saveOnWorker(onlyDirty: Boolean) {
        failure?.let { throw IllegalStateException("Location pool edit could not be recorded", it) }
        var saveFailure: Throwable? = null
        for ((id, state) in mirrors) {
            if (onlyDirty && !state.dirty) continue
            try {
                write(folder.resolve("$id.json"), state.mirror)
                state.live.markPersisted(state.revision)
                state.dirty = false
            } catch (problem: Throwable) {
                if (saveFailure == null) saveFailure = problem else saveFailure.addSuppressed(problem)
            }
        }
        saveFailure?.let { throw it }
    }

    /** Candidate is frozen by the main-thread manager until this durable write is acknowledged. */
    fun replace(pool: LocationPool): CompletableFuture<Unit> = worker.submit {
        failure?.let { throw IllegalStateException("Location pool persistence failed", it) }
        val id = LocationPool.normalizePersistentId(pool.id)
        val mirror = copy(id, pool.getWeightedLocations())
        write(folder.resolve("$id.json"), mirror)
        mirrors[id] = State(pool, mirror, pool.currentRevision(), false)
        pool.markClean()
    }

    /** Called on main only after a successful durable replacement. */
    fun bindReplacement(pool: LocationPool) = bind(pool)

    fun deleteFile(id: String): CompletableFuture<Boolean> = worker.submit {
        failure?.let { throw IllegalStateException("Location pool persistence failed", it) }
        val normalizedId = LocationPool.normalizePersistentId(id)
        Files.deleteIfExists(folder.resolve("$normalizedId.json"))
        mirrors.remove(normalizedId) != null
    }

    fun startSaveTask(scheduler: TaskScheduler) {
        cancelTasks()
        saveTask = scheduler.runTimer(1_200L, 1_200L) {
            saveLocationPools(true).whenComplete { _, problem ->
                if (problem != null) error("Could not save location pools", problem)
            }
        }
    }

    fun cancelTasks() {
        saveTask?.cancel()
        saveTask = null
    }

    /** Main seals admission; the reserved worker terminal persists every accepted edit. */
    @Synchronized
    fun closeAsync(): CompletableFuture<Unit> {
        closeResult?.let { return it.copy() }
        return worker.closeAsync { saveOnWorker(true) }.also { closeResult = it }.copy()
    }

    companion object {
        /** Worker-only cold filesystem read. The returned facade is attached on main. */
        fun open(root: Path): LocationPoolConfig {
            val folder = root.resolve("location_pools")
            val pools = loadPools(folder)
            return LocationPoolConfig(folder, pools, BoundedAsyncExecutor("arc-location-pools"), ::writeAtomic)
        }

        private fun loadPools(folder: Path): List<LocationPool> {
            Files.createDirectories(folder)
            val pools = linkedMapOf<String, LocationPool>()
            Files.newDirectoryStream(folder, "*.json").use { paths ->
                for (path in paths) {
                    if (!Files.isRegularFile(path)) continue
                    try {
                        val pool = Common.prettyGson.fromJson(Files.readString(path), LocationPool::class.java)
                        require(LocationPool.normalizePersistentId(pool.id) == pool.id) { "Pool id is not normalized" }
                        if (pools.put(pool.id, pool) != null) warn("Duplicate location pool id {} in {}", pool.id, path.fileName)
                    } catch (problem: Exception) {
                        error("Failed to load location pool {}", path.fileName, problem)
                        // Preserve the historical policy: invalid files remain untouched for repair.
                    }
                }
            }
            info("Loaded {} location pools", pools.size)
            return pools.values.toList()
        }

        internal fun forTest(
            folder: Path,
            pools: List<LocationPool> = emptyList(),
            capacity: Int = 256,
            write: (Path, LocationPool) -> Unit = ::writeAtomic,
        ) = LocationPoolConfig(folder, pools, BoundedAsyncExecutor("arc-location-pools-test", capacity = capacity), write)

        private fun copy(id: String, entries: List<WeightedRandom.Pair<ServerLocation>>) = LocationPool(id).also { copy ->
            entries.forEach { copy.addLocation(it.value, it.weight) }
            copy.markClean()
        }

        private fun writeAtomic(path: Path, pool: LocationPool) {
            val folder = path.parent
            Files.createDirectories(folder)
            val temp = Files.createTempFile(folder, ".${path.fileName}-", ".tmp")
            try {
                Files.writeString(temp, Common.prettyGson.toJson(pool), StandardOpenOption.TRUNCATE_EXISTING)
                try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temp) }
        }
    }
}
