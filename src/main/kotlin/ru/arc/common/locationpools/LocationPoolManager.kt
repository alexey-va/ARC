package ru.arc.common.locationpools

import org.bukkit.Location
import org.bukkit.event.block.BlockPlaceEvent
import ru.arc.ARC
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Фасад для управления пулами локаций.
 *
 * Делегирует операции в специализированные компоненты:
 * - [LocationPoolRepository] - хранение пулов
 * - [LocationPoolEditor] - редактирование локаций игроками
 *
 * Этот объект существует для обратной совместимости с существующим кодом.
 */
object LocationPoolManager {
    private val pending = mutableSetOf<String>() // Native owner only.

    internal fun requireWritable(id: String) {
        check(id !in pending) { "Location pool $id is being saved; retry after it finishes" }
    }
    // === Инициализация ===

    /**
     * Инициализирует менеджер локаций.
     */
    @JvmStatic
    fun init() {
        LocationPoolEditor.init()
        LocationPoolEditor.startShowTask()
        LocationPoolEditor.startTimeoutTask()
    }

    // === Работа с пулами (делегирование в Repository) ===

    @JvmStatic
    fun addPool(pool: LocationPool) {
        requireWritable(pool.id)
        LocationPoolRepository.add(pool)
    }

    @JvmStatic
    fun getPool(id: String?): LocationPool? = id?.let { LocationPoolRepository.get(it) }

    @JvmStatic
    fun createPool(id: String): LocationPool = LocationPoolRepository.getOrCreate(id)

    @JvmStatic
    fun getAll(): List<LocationPool> = LocationPoolRepository.getAll()

    @JvmStatic
    fun clear() {
        LocationPoolRepository.clear()
        LocationPoolEditor.clear()
        pending.clear()
    }

    @JvmStatic
    fun createEphemeralPool(): LocationPool = LocationPoolRepository.createEphemeral()

    @JvmStatic
    fun isEphemeralPool(id: String?): Boolean = id != null && LocationPoolRepository.isEphemeral(id)

    @JvmStatic
    fun removeEphemeralPool(id: String): Boolean = LocationPoolRepository.removeEphemeral(id)

    @JvmStatic
    fun delete(id: String): CompletableFuture<Boolean> {
        if (isEphemeralPool(id)) {
            LocationPoolEditor.cancelEditingForPool(id)
            return CompletableFuture.completedFuture(removeEphemeralPool(id))
        }
        val current = LocationPoolRepository.get(id) ?: return CompletableFuture.completedFuture(false)
        val plugin = ARC.instance
        val config = checkNotNull(plugin.locationPoolConfig) { "Location pool persistence is unavailable" }
        config.requireWritable()
        requireWritable(id)
        pending.add(id)
        current.setWritable(false)
        val result = CompletableFuture<Boolean>()
        config.deleteFile(id).whenComplete { _, failure ->
            plugin.nativeWork.call({ plugin.locationPoolConfig === config }) {
                if (failure != null) {
                    pending.remove(id)
                    current.setWritable(true)
                    throw java.util.concurrent.CompletionException(failure)
                }
                check(LocationPoolRepository.get(id) === current) { "Location pool $id changed during deletion" }
                LocationPoolEditor.cancelEditingForPool(id)
                val removed = LocationPoolRepository.remove(id)
                pending.remove(id)
                removed
            }.whenComplete { removed, problem ->
                if (problem == null) result.complete(removed) else result.completeExceptionally(problem)
            }
        }
        return result
    }

    @JvmStatic
    fun replacePersistent(pool: LocationPool): CompletableFuture<Unit> {
        require(!isEphemeralPool(pool.id)) { "Cannot replace an ephemeral location pool" }
        val plugin = ARC.instance
        val config =
            plugin.locationPoolConfig
                ?: throw IllegalStateException("Location pool config is not initialized")
        config.requireWritable()
        requireWritable(pool.id)
        val previous = LocationPoolRepository.get(pool.id)
        pending.add(pool.id)
        previous?.setWritable(false)
        pool.setWritable(false)
        val result = CompletableFuture<Unit>()
        config.replace(pool).whenComplete { _, failure ->
            plugin.nativeWork.call({ plugin.locationPoolConfig === config }) {
                if (failure != null) {
                    pending.remove(pool.id)
                    previous?.setWritable(true)
                    pool.setWritable(true)
                    throw java.util.concurrent.CompletionException(failure)
                }
                check(LocationPoolRepository.get(pool.id) === previous) { "Location pool ${pool.id} changed during replacement" }
                config.bindReplacement(pool)
                LocationPoolRepository.add(pool)
                pool.setWritable(true)
                pending.remove(pool.id)
            }.whenComplete { _, problem ->
                if (problem == null) result.complete(Unit) else result.completeExceptionally(problem)
            }
        }
        return result
    }

    // === Работа с локациями ===

    @JvmStatic
    fun addLocation(
        id: String,
        location: Location,
    ) {
        LocationPoolRepository.addLocation(id, location)
    }

    @JvmStatic
    fun removeLocation(
        id: String,
        location: Location,
    ): Boolean = LocationPoolRepository.removeLocation(id, location)

    @JvmStatic
    fun getNearbyLocations(
        id: String,
        location: Location,
    ): List<Location> = LocationPoolRepository.getNearbyLocations(id, location)

    // === Редактирование (делегирование в Editor) ===

    @JvmStatic
    fun setEditing(
        uuid: UUID,
        id: String,
    ) {
        LocationPoolEditor.startEditing(uuid, id)
    }

    @JvmStatic
    fun getEditing(uuid: UUID): String? = LocationPoolEditor.getEditingPool(uuid)

    @JvmStatic
    fun cancelEditing(
        uuid: UUID,
        timeout: Boolean,
    ) {
        LocationPoolEditor.cancelEditing(uuid, timeout)
    }

    @JvmStatic
    fun processLocationPool(event: BlockPlaceEvent) {
        LocationPoolEditor.processBlockPlace(event)
    }
}
