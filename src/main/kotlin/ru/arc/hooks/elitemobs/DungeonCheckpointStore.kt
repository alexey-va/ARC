package ru.arc.hooks.elitemobs

import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

internal enum class DungeonSaveKind { MANUAL, AUTO }

internal data class DungeonSavePoint(
    val id: String,
    val name: String,
    val kind: DungeonSaveKind,
    val location: Location,
    val savedAt: Long,
)

/** Player-owned native PDC: at most 16 dungeon checkpoints, never an inventory/state backup. */
internal class DungeonCheckpointStore {
    private val root = NamespacedKey("arc", "dungeon_checkpoints")
    private val worldKey = NamespacedKey("arc", "world")
    private val runKey = NamespacedKey("arc", "run")
    private val positionKey = NamespacedKey("arc", "position")
    private val timeKey = NamespacedKey("arc", "saved_at")
    private val savesRoot = NamespacedKey("arc", "dungeon_save_points")
    private val idKey = NamespacedKey("arc", "id")
    private val nameKey = NamespacedKey("arc", "name")
    private val kindKey = NamespacedKey("arc", "kind")

    fun list(data: PersistentDataContainer, world: UUID, run: String, now: Long, ttl: Long): List<DungeonSavePoint> {
        val bukkitWorld = org.bukkit.Bukkit.getWorld(world) ?: return emptyList()
        return readSaves(data).filter { it.world == world.toString() && it.run == run && valid(it, now, ttl) }
            .map { it.detached(bukkitWorld) }
    }

    fun save(
        data: PersistentDataContainer,
        location: Location,
        run: String,
        name: String,
        kind: DungeonSaveKind,
        now: Long,
        ttl: Long,
    ): DungeonSavePoint? {
        val world = location.world ?: return null
        val cleanName = name.trim()
        if (now < 0 || ttl < 0 || !validLocation(location) || !validRun(run) || !validName(cleanName)) return null
        val worldId = world.uid.toString()
        val current = readSaves(data).filter { valid(it, now, ttl) && (it.world != worldId || it.run == run) }.toMutableList()
        val sameName = current.firstOrNull { kind == DungeonSaveKind.MANUAL && it.kind == DungeonSaveKind.MANUAL && it.world == worldId && it.run == run && it.name.equals(cleanName, ignoreCase = true) }
        val point = Save(
            sameName?.id ?: UUID.randomUUID().toString(), cleanName, kind, worldId, run,
            doubleArrayOf(location.x, location.y, location.z, location.yaw.toDouble(), location.pitch.toDouble()), now,
        )
        current.removeAll { it.id == point.id }
        if (sameName == null && kind == DungeonSaveKind.MANUAL && current.count { it.world == worldId && it.run == run && it.kind == kind } >= 5) return null
        current += point
        trimWorld(current, worldId, run)
        trimWorlds(current)
        writeSaves(data, current)
        return point.detached(world)
    }

    fun remove(data: PersistentDataContainer, world: UUID, run: String, id: String): Boolean {
        if (!validUuid(id)) return false
        val points = readSaves(data)
        val kept = points.filterNot { it.world == world.toString() && it.run == run && it.id == id }
        if (kept.size == points.size) return false
        writeSaves(data, kept)
        return true
    }

    fun remember(data: PersistentDataContainer, location: Location, run: String, now: Long, ttl: Long) {
        val entries = read(data).filter { valid(it, now, ttl) && it.world != location.world.uid.toString() }
        write(data, (entries + Point(location.world.uid.toString(), run,
            doubleArrayOf(location.x, location.y, location.z, location.yaw.toDouble(), location.pitch.toDouble()), now))
            .sortedByDescending { it.savedAt }.take(16))
    }

    fun destination(data: PersistentDataContainer, world: World, run: String, now: Long, ttl: Long): Location? =
        read(data).firstOrNull { it.world == world.uid.toString() && it.run == run && valid(it, now, ttl) }
            ?.let { Location(world, it.position[0], it.position[1], it.position[2], it.position[3].toFloat(), it.position[4].toFloat()) }

    fun forget(data: PersistentDataContainer, world: UUID) {
        write(data, read(data).filter { it.world != world.toString() })
        writeSaves(data, readSaves(data).filter { it.world != world.toString() })
    }

    private data class Point(val world: String, val run: String, val position: DoubleArray, val savedAt: Long)
    private data class Save(val id: String, val name: String, val kind: DungeonSaveKind, val world: String, val run: String, val position: DoubleArray, val savedAt: Long)

    private fun Save.detached(world: World): DungeonSavePoint = DungeonSavePoint(id, name, kind, Location(world, position[0], position[1], position[2], position[3].toFloat(), position[4].toFloat()), savedAt)

    private fun valid(point: Point, now: Long, ttl: Long): Boolean =
        validUuid(point.world) && validRun(point.run) && point.position.size == 5 &&
            point.position.all(Double::isFinite) && point.position.drop(3).all { it.toFloat().isFinite() } && point.position.take(3).all { kotlin.math.abs(it) <= 30_000_000 } &&
            point.savedAt >= 0 && now >= point.savedAt && now - point.savedAt <= ttl && ttl >= 0

    private fun valid(point: Save, now: Long, ttl: Long): Boolean =
        validUuid(point.id) && validUuid(point.world) && validRun(point.run) && validName(point.name) &&
            point.position.size == 5 && point.position.all(Double::isFinite) && point.position.drop(3).all { it.toFloat().isFinite() } &&
            point.position.take(3).all { kotlin.math.abs(it) <= 30_000_000 } && point.savedAt >= 0 && now >= point.savedAt && now - point.savedAt <= ttl && ttl >= 0

    private fun validUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
    private fun validRun(value: String): Boolean = value.isNotBlank() && value.length <= 64 && value.none(Char::isISOControl)
    private fun validName(value: String): Boolean = value == value.trim() && value.length in 1..32 && value.none(Char::isISOControl)
    private fun validLocation(location: Location): Boolean = location.world != null && location.x.isFinite() && location.y.isFinite() && location.z.isFinite() &&
        location.yaw.toDouble().isFinite() && location.pitch.toDouble().isFinite() && listOf(location.x, location.y, location.z).all { kotlin.math.abs(it) <= 30_000_000 }

    private fun trimWorld(points: MutableList<Save>, world: String, run: String) {
        while (points.count { it.world == world && it.run == run && it.kind == DungeonSaveKind.MANUAL } > 5) points.remove(points.filter { it.world == world && it.run == run && it.kind == DungeonSaveKind.MANUAL }.minBy { it.savedAt })
        while (points.count { it.world == world && it.run == run && it.kind == DungeonSaveKind.AUTO } > 3) points.remove(points.filter { it.world == world && it.run == run && it.kind == DungeonSaveKind.AUTO }.minBy { it.savedAt })
        while (points.count { it.world == world } > 8) points.remove(points.filter { it.world == world && it.kind == DungeonSaveKind.AUTO }.minByOrNull { it.savedAt } ?: points.filter { it.world == world }.minBy { it.savedAt })
    }

    private fun trimWorlds(points: MutableList<Save>) {
        while (points.map { it.world }.distinct().size > 16) {
            val oldest = points.groupBy { it.world }.minBy { (_, values) -> values.maxOf { it.savedAt } }.key
            points.removeAll { it.world == oldest }
        }
    }

    @Suppress("DEPRECATION")
    private fun read(data: PersistentDataContainer): List<Point> {
        val entries = runCatching { data.get(root, PersistentDataType.TAG_CONTAINER_ARRAY).orEmpty() }
            .getOrDefault(emptyArray())
        return entries.take(16).mapNotNull { entry -> runCatching {
            val world = entry.get(worldKey, PersistentDataType.STRING) ?: return@runCatching null
            val run = entry.get(runKey, PersistentDataType.STRING) ?: return@runCatching null
            val position = entry.get(positionKey, PersistentDataType.LIST.doubles())?.toDoubleArray() ?: return@runCatching null
            val time = entry.get(timeKey, PersistentDataType.LONG) ?: return@runCatching null
            Point(world, run, position, time)
        }.getOrNull() }
    }

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

    @Suppress("DEPRECATION")
    private fun readSaves(data: PersistentDataContainer): List<Save> {
        val entries = runCatching { data.get(savesRoot, PersistentDataType.TAG_CONTAINER_ARRAY).orEmpty() }
            .getOrDefault(emptyArray())
        return entries.take(128).mapNotNull { entry -> runCatching {
            val id = entry.get(idKey, PersistentDataType.STRING) ?: return@runCatching null
            val name = entry.get(nameKey, PersistentDataType.STRING) ?: return@runCatching null
            val kind = entry.get(kindKey, PersistentDataType.STRING)?.let { DungeonSaveKind.valueOf(it) } ?: return@runCatching null
            val world = entry.get(worldKey, PersistentDataType.STRING) ?: return@runCatching null
            val run = entry.get(runKey, PersistentDataType.STRING) ?: return@runCatching null
            val position = entry.get(positionKey, PersistentDataType.LIST.doubles())?.toDoubleArray() ?: return@runCatching null
            Save(id, name, kind, world, run, position, entry.get(timeKey, PersistentDataType.LONG) ?: return@runCatching null)
        }.getOrNull() }
    }

    @Suppress("DEPRECATION")
    private fun writeSaves(data: PersistentDataContainer, points: List<Save>) {
        if (points.isEmpty()) { data.remove(savesRoot); return }
        data.set(savesRoot, PersistentDataType.TAG_CONTAINER_ARRAY, points.map { point -> data.adapterContext.newPersistentDataContainer().apply {
            set(idKey, PersistentDataType.STRING, point.id); set(nameKey, PersistentDataType.STRING, point.name); set(kindKey, PersistentDataType.STRING, point.kind.name)
            set(worldKey, PersistentDataType.STRING, point.world); set(runKey, PersistentDataType.STRING, point.run); set(positionKey, PersistentDataType.LIST.doubles(), point.position.toList()); set(timeKey, PersistentDataType.LONG, point.savedAt)
        } }.toTypedArray())
    }
}
