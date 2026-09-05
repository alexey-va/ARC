package ru.arc.hooks.elitemobs

import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** Player-owned native PDC: at most 16 dungeon checkpoints, never an inventory/state backup. */
internal class DungeonCheckpointStore {
    private val root = NamespacedKey("arc", "dungeon_checkpoints")
    private val worldKey = NamespacedKey("arc", "world")
    private val runKey = NamespacedKey("arc", "run")
    private val positionKey = NamespacedKey("arc", "position")
    private val timeKey = NamespacedKey("arc", "saved_at")

    fun remember(data: PersistentDataContainer, location: Location, run: String, now: Long, ttl: Long) {
        val entries = read(data).filter { valid(it, now, ttl) && it.world != location.world.uid.toString() }
        write(data, (entries + Point(location.world.uid.toString(), run,
            doubleArrayOf(location.x, location.y, location.z, location.yaw.toDouble(), location.pitch.toDouble()), now))
            .sortedByDescending { it.savedAt }.take(16))
    }

    fun destination(data: PersistentDataContainer, world: World, run: String, now: Long, ttl: Long): Location? =
        read(data).firstOrNull { it.world == world.uid.toString() && it.run == run && valid(it, now, ttl) }
            ?.let { Location(world, it.position[0], it.position[1], it.position[2], it.position[3].toFloat(), it.position[4].toFloat()) }

    fun forget(data: PersistentDataContainer, world: UUID) = write(data, read(data).filter { it.world != world.toString() })

    private data class Point(val world: String, val run: String, val position: DoubleArray, val savedAt: Long)

    private fun valid(point: Point, now: Long, ttl: Long): Boolean =
        point.world.length == 36 && point.run.length <= 64 && point.position.size == 5 &&
            point.position.all(Double::isFinite) && point.position.drop(3).all { it.toFloat().isFinite() } && point.position.take(3).all { kotlin.math.abs(it) <= 30_000_000 } &&
            point.savedAt <= now && now - point.savedAt <= ttl

    @Suppress("DEPRECATION")
    private fun read(data: PersistentDataContainer): List<Point> = runCatching {
        data.get(root, PersistentDataType.TAG_CONTAINER_ARRAY).orEmpty().take(16).mapNotNull { entry ->
            val world = entry.get(worldKey, PersistentDataType.STRING) ?: return@mapNotNull null
            val run = entry.get(runKey, PersistentDataType.STRING) ?: return@mapNotNull null
            val position = entry.get(positionKey, PersistentDataType.LIST.doubles())?.toDoubleArray() ?: return@mapNotNull null
            val time = entry.get(timeKey, PersistentDataType.LONG) ?: return@mapNotNull null
            Point(world, run, position, time)
        }
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun write(data: PersistentDataContainer, points: List<Point>) {
        if (points.isEmpty()) {
            data.remove(root)
            return
        }
        data.set(root, PersistentDataType.TAG_CONTAINER_ARRAY, points.map { point ->
            data.adapterContext.newPersistentDataContainer().apply {
                set(worldKey, PersistentDataType.STRING, point.world)
                set(runKey, PersistentDataType.STRING, point.run)
                set(positionKey, PersistentDataType.LIST.doubles(), point.position.toList())
                set(timeKey, PersistentDataType.LONG, point.savedAt)
            }
        }.toTypedArray())
    }
}
