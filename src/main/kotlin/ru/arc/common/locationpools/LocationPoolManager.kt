package ru.arc.common.locationpools

import org.bukkit.Location
import org.bukkit.event.block.BlockPlaceEvent
import ru.arc.ARC
import java.util.UUID

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
    fun addPool(pool: LocationPool) = LocationPoolRepository.add(pool)

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
    }

    @JvmStatic
    fun createEphemeralPool(): LocationPool = LocationPoolRepository.createEphemeral()

    @JvmStatic
    fun isEphemeralPool(id: String?): Boolean = id != null && LocationPoolRepository.isEphemeral(id)

    @JvmStatic
    fun removeEphemeralPool(id: String): Boolean = LocationPoolRepository.removeEphemeral(id)

    @JvmStatic
    fun delete(id: String): Boolean {
        if (isEphemeralPool(id)) {
            LocationPoolEditor.cancelEditingForPool(id)
            return removeEphemeralPool(id)
        }
        if (LocationPoolRepository.get(id) == null) return false
        // Delete durable state first so a failed filesystem operation cannot
        // make a pool disappear only until the next restart.
        ARC.plugin?.locationPoolConfig?.deleteFile(id)
        LocationPoolEditor.cancelEditingForPool(id)
        return LocationPoolRepository.remove(id)
    }

    @JvmStatic
    fun replacePersistent(pool: LocationPool) {
        require(!isEphemeralPool(pool.id)) { "Cannot replace an ephemeral location pool" }
        val config =
            ARC.plugin?.locationPoolConfig
                ?: throw IllegalStateException("Location pool config is not initialized")
        // Persist the fully built replacement before publishing it to readers.
        config.saveLocationPool(pool)
        LocationPoolRepository.add(pool)
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
