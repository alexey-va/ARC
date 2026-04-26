package ru.arc.common.locationpools

import org.bukkit.Location
import java.util.concurrent.ConcurrentHashMap

/**
 * Репозиторий для хранения и управления LocationPool.
 *
 * Отвечает только за хранение - не содержит логики редактирования или отображения.
 */
object LocationPoolRepository {
    private val pools = ConcurrentHashMap<String, LocationPool>()

    /**
     * Получает пул по ID.
     *
     * @param id идентификатор пула
     * @return пул или null если не найден
     */
    fun get(id: String): LocationPool? = pools[id]

    /**
     * Получает пул или создаёт новый если не существует.
     *
     * @param id идентификатор пула
     * @return существующий или новый пул
     */
    fun getOrCreate(id: String): LocationPool = pools.getOrPut(id) { LocationPool(id) }

    /**
     * Добавляет пул в репозиторий.
     *
     * @param pool пул для добавления
     */
    fun add(pool: LocationPool) {
        pools[pool.id] = pool
    }

    /**
     * Удаляет пул из репозитория.
     *
     * @param id идентификатор пула
     * @return true если пул был найден и удалён
     */
    fun remove(id: String): Boolean = pools.remove(id) != null

    /**
     * Получает все пулы.
     */
    fun getAll(): List<LocationPool> = pools.values.toList()

    /**
     * Получает все ID пулов.
     */
    fun getAllIds(): Set<String> = pools.keys.toSet()

    /**
     * Проверяет существование пула.
     */
    fun exists(id: String): Boolean = pools.containsKey(id)

    /**
     * Очищает все пулы.
     */
    fun clear() {
        pools.clear()
    }

    /**
     * Получает все пулы, требующие сохранения.
     */
    fun getDirtyPools(): List<LocationPool> = pools.values.filter { it.isDirty }

    // === Convenience методы для работы с локациями ===

    /**
     * Добавляет локацию в пул (создаёт пул если не существует).
     */
    fun addLocation(
        poolId: String,
        location: Location,
        weight: Double = 1.0,
    ) {
        getOrCreate(poolId).addLocation(location, weight)
    }

    /**
     * Удаляет локацию из пула.
     *
     * @return true если локация удалена, false если пул не найден или локация не в пуле
     */
    fun removeLocation(
        poolId: String,
        location: Location,
    ): Boolean {
        val pool = get(poolId) ?: return false
        return pool.removeLocation(location)
    }

    /**
     * Получает ближайшие локации из пула.
     */
    fun getNearbyLocations(
        poolId: String,
        center: Location,
        radius: Double = 50.0,
    ): List<Location> {
        val pool = get(poolId) ?: return emptyList()
        return pool
            .nearbyLocations(center, radius)
            .filter { it.isSameServer() }
            .mapNotNull { it.toLocation() }
    }
}
