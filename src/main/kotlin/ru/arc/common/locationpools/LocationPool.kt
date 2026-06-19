package ru.arc.common.locationpools

import org.bukkit.Location
import ru.arc.common.ServerLocation
import ru.arc.common.WeightedRandom

/**
 * Пул локаций для размещения сундуков или других объектов.
 *
 * Хранит взвешенную коллекцию ServerLocation для поддержки
 * мультисерверной архитектуры.
 *
 * @property id уникальный идентификатор пула
 */
class LocationPool(
    val id: String,
) {
    private val _locations = WeightedRandom<ServerLocation>()
    private var _dirty = false

    /**
     * Флаг изменений - true если пул был изменён и требует сохранения.
     */
    val isDirty: Boolean get() = _dirty

    /**
     * Количество локаций в пуле.
     */
    val size: Int get() = _locations.size()

    /**
     * Проверяет, пуст ли пул.
     */
    val isEmpty: Boolean get() = _locations.size() == 0

    /**
     * Добавляет Bukkit Location в пул.
     *
     * @param location Bukkit локация
     * @param weight вес для взвешенного случайного выбора
     */
    fun addLocation(
        location: Location,
        weight: Double = 1.0,
    ) {
        addLocation(ServerLocation.of(location), weight)
    }

    /**
     * Добавляет ServerLocation в пул.
     *
     * @param location серверная локация
     * @param weight вес для взвешенного случайного выбора
     */
    fun addLocation(
        location: ServerLocation,
        weight: Double = 1.0,
    ) {
        _locations.add(location, weight)
        _dirty = true
    }

    /**
     * Удаляет локацию из пула.
     *
     * @param location Bukkit локация для удаления
     * @return true если локация была найдена и удалена
     */
    fun removeLocation(location: Location): Boolean = removeLocation(ServerLocation.of(location))

    /**
     * Удаляет серверную локацию из пула.
     *
     * @param location серверная локация для удаления
     * @return true если локация была найдена и удалена
     */
    fun removeLocation(location: ServerLocation): Boolean {
        val removed = _locations.remove(location)
        if (removed) _dirty = true
        return removed
    }

    /**
     * Получает N случайных локаций из пула.
     *
     * @param n количество локаций
     * @return множество случайных локаций (может быть меньше n если пул маленький)
     */
    fun getRandomLocations(n: Int): Set<ServerLocation> = _locations.getNRandom(n).toSet()

    /**
     * Находит все локации в радиусе от указанной точки.
     *
     * @param center центр поиска
     * @param radius радиус поиска
     * @return множество локаций в радиусе
     */
    fun nearbyLocations(
        center: Location,
        radius: Double,
    ): Set<ServerLocation> =
        _locations
            .values()
            .filter { it.distance(center).orElse(Double.MAX_VALUE) <= radius }
            .toSet()

    /**
     * Получает все локации текущего сервера как Bukkit Location.
     *
     * @return список Bukkit локаций только для текущего сервера
     */
    fun getLocalLocations(): List<Location> =
        _locations
            .values()
            .filter { it.isSameServer() }
            .mapNotNull { it.toLocation() }

    /**
     * Получает все серверные локации.
     */
    fun getAllLocations(): Collection<ServerLocation> = _locations.values()

    /**
     * Сбрасывает флаг dirty после сохранения.
     */
    fun markClean() {
        _dirty = false
    }

    override fun toString(): String = "LocationPool(id='$id', size=$size)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocationPool) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
