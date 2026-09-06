package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfig
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.utils.ConfigurationLocation
import com.magmaguy.elitemobs.wormhole.WormholeEntry
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.floor

internal data class DungeonVisit(
    val run: String,
    val waiting: Boolean = false,
    val canResume: Boolean = true,
    val entry: Location? = null,
    val members: Set<UUID>? = null,
    val instanced: Boolean = false,
)

internal data class NativeWormholeVolume(val location: Location, val radiusSquared: Double)

private val ENTRY_OFFSETS = buildList {
    for (x in -8..8) for (z in -8..8) for (y in -4..4) add(Triple(x, y, z))
}.sortedWith(compareBy({ it.first * it.first * 0.25 + it.second * it.second + it.third * it.third * 0.25 }, { it.first }, { it.second }, { it.third }))

internal class NativeDungeonVisits(
    private val volumes: () -> List<NativeWormholeVolume> = ::nativeWormholeVolumes,
    private val safe: (Location) -> Boolean = ::safeDungeonTerrain,
) {
    private val runs = WeakHashMap<DungeonInstance, String>()
    private val normalized = object : LinkedHashMap<String, Location>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Location>): Boolean = size > 64
    }
    fun resolve(world: World): DungeonVisit? {
        val instance = DungeonInstance.getDungeonInstances().firstOrNull { it.world == world }
        if (instance != null) {
            val state = instance.state.name
            // Same conversion as DynamicDungeonInstance.generate: blueprint coordinates,
            // rebound to this exact clone. Never use the blueprint world as the target.
            val entry = runCatching {
                ConfigurationLocation.serialize(instance.contentPackagesConfigFields.startLocationString)?.clone()?.also { it.world = world }
            }.getOrNull()
            return DungeonVisit(runs.getOrPut(instance) { UUID.randomUUID().toString() },
                waiting = state == "WAITING" && !instance.isCancelled,
                canResume = state == "ONGOING" && !instance.isCancelled,
                entry = cachedEntry(entry, world),
                members = instance.players.map { it.uniqueId }.toSet(), instanced = true)
        }
        val fields = ContentPackagesConfig.getDungeonPackages().values.firstOrNull { it.worldName == world.name } ?: return null
        return if (fields.contentType.name == "OPEN_DUNGEON") {
            DungeonVisit("open", entry = cachedEntry(fields.teleportLocation, world))
        } else null
    }

    private fun cachedEntry(authored: Location?, world: World): Location? {
        val entry = authored?.clone()?.takeIf { it.world == world } ?: return null
        val volumes = volumes()
        val key = "${world.uid}:${entry.x}:${entry.y}:${entry.z}:${entry.yaw}:${entry.pitch}"
        normalized[key]?.takeIf { safe(it) && !isNativeWormholeTrigger(it, volumes) }?.let { return it.clone() }
        val resolved = normalizeDungeonEntry(entry, world, volumes, safe)
        if (resolved == null) normalized.remove(key) else normalized[key] = resolved.clone()
        return resolved?.clone()
    }

}

internal fun normalizeDungeonEntry(
    entry: Location?,
    world: World,
    volumes: List<NativeWormholeVolume>,
    safe: (Location) -> Boolean,
): Location? {
    val authored = entry?.clone()?.takeIf { it.world == world } ?: return null
    if (safe(authored) && !isNativeWormholeTrigger(authored, volumes)) return authored

    // Native wormholes trigger at their center, so move only when the
    // authored entry is inside one of those volumes or is not a valid floor.
    val yBase = floor(authored.y)
    return ENTRY_OFFSETS.asSequence()
        .map { (x, y, z) -> authored.clone().add(x * 0.5, yBase + y - authored.y, z * 0.5) }
        .firstOrNull { !isNativeWormholeTrigger(it, volumes) && safe(it) }
}

/** Matches EliteMobs 10.8.1's WormholeManager trigger volume. */
internal fun nativeWormholeVolumes(): List<NativeWormholeVolume> =
    WormholeEntry.getWormholeEntries().mapNotNull { entry ->
        val portal = entry.location ?: return@mapNotNull null
        val sizeMultiplier = entry.wormhole.wormholeConfigFields.sizeMultiplier
        if (!sizeMultiplier.isFinite() || sizeMultiplier < 0.0) null
        else NativeWormholeVolume(portal, wormholeTriggerRadiusSquared(sizeMultiplier))
    }

internal fun isNativeWormholeTrigger(location: Location): Boolean =
    isNativeWormholeTrigger(location, nativeWormholeVolumes())

internal fun isNativeWormholeTrigger(location: Location, volumes: List<NativeWormholeVolume>): Boolean =
    volumes.any { it.location.world == location.world && location.distanceSquared(it.location) <= it.radiusSquared }

internal fun wormholeTriggerRadiusSquared(sizeMultiplier: Double): Double =
    (1.5 * sizeMultiplier) * (1.5 * sizeMultiplier)
