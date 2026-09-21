package ru.arc.hooks.elitemobs

import com.destroystokyo.paper.ParticleBuilder
import com.magmaguy.elitemobs.treasurechest.TreasureChest
import com.magmaguy.elitemobs.wormhole.Wormhole
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.ParticleManager
import ru.arc.xserver.playerlist.PlayerManager
import ru.arc.config.material
import ru.arc.config.materialSet
import ru.arc.config.particle
import ru.arc.config.sound
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

internal data class WormholeBoundaryCircle(val surfaceY: Double, val radius: Double)

internal data class WormholeBoundaryPoint(val x: Double, val surfaceY: Double, val z: Double)

private data class WormholeVisualCandidate(
    val location: Location,
    val sizeMultiplier: Double,
    val color: Color,
    val receivers: List<Player>,
    val nearestDistanceSquared: Double,
)

private const val WORMHOLE_PARTICLE_BUILDERS_PER_PASS = 160

/** Horizontal intersection of EliteMobs' spherical trigger with the local walking surface. */
internal fun wormholeBoundaryCircle(
    centerY: Double,
    surfaceY: Double,
    sizeMultiplier: Double,
): WormholeBoundaryCircle? {
    if (!centerY.isFinite() || !surfaceY.isFinite() || !sizeMultiplier.isFinite() || sizeMultiplier <= 0.0) return null
    val radiusSquared = wormholeTriggerRadiusSquared(sizeMultiplier)
    val verticalDistance = centerY - surfaceY
    val horizontalRadiusSquared = radiusSquared - verticalDistance * verticalDistance
    if (horizontalRadiusSquared <= 0.0) return null
    return WormholeBoundaryCircle(surfaceY, sqrt(horizontalRadiusSquared))
}

internal fun wormholeBoundaryPoints(
    centerY: Double,
    centerSurfaceY: Double,
    sizeMultiplier: Double,
    pointCount: Int,
    surfaceYAt: (xOffset: Double, zOffset: Double) -> Double?,
): List<WormholeBoundaryPoint> {
    require(pointCount >= 3) { "pointCount must be at least 3" }
    return (0 until pointCount).mapNotNull { index ->
        val angle = 2.0 * PI * index / pointCount
        wormholeBoundaryPoint(centerY, centerSurfaceY, sizeMultiplier, cos(angle), sin(angle), surfaceYAt)
    }
}

internal fun wormholeBoundaryPoint(
    centerY: Double,
    centerSurfaceY: Double,
    sizeMultiplier: Double,
    xUnit: Double,
    zUnit: Double,
    surfaceYAt: (xOffset: Double, zOffset: Double) -> Double?,
): WormholeBoundaryPoint? {
    val first = wormholeBoundaryCircle(centerY, centerSurfaceY, sizeMultiplier) ?: return null
    val surfaces = LinkedHashSet<Double>()
    var boundary = first
    repeat(6) {
        surfaces += boundary.surfaceY
        val xOffset = xUnit * boundary.radius
        val zOffset = zUnit * boundary.radius
        val sampledSurface = surfaceYAt(xOffset, zOffset) ?: return null
        val sampledBoundary = wormholeBoundaryCircle(centerY, sampledSurface, sizeMultiplier) ?: return null
        if (abs(sampledBoundary.surfaceY - boundary.surfaceY) < 1.0e-9) {
            return WormholeBoundaryPoint(xUnit * sampledBoundary.radius, sampledBoundary.surfaceY, zUnit * sampledBoundary.radius)
        }
        boundary = sampledBoundary
    }
    // A sharp step can make the radius alternate between two columns. Keep only
    // a point whose final coordinates agree with the surface used to derive it.
    return surfaces.asSequence().mapNotNull { surfaceY ->
        val candidate = wormholeBoundaryCircle(centerY, surfaceY, sizeMultiplier) ?: return@mapNotNull null
        val xOffset = xUnit * candidate.radius
        val zOffset = zUnit * candidate.radius
        val sampledSurface = surfaceYAt(xOffset, zOffset) ?: return@mapNotNull null
        if (abs(sampledSurface - surfaceY) < 1.0e-9) WormholeBoundaryPoint(xOffset, surfaceY, zOffset) else null
    }.firstOrNull()
}

internal fun <T> roundRobinSlice(items: List<T>, start: Int, count: Int): List<T> {
    if (items.isEmpty() || count <= 0) return emptyList()
    val normalizedStart = start.mod(items.size)
    return List(minOf(count, items.size)) { offset -> items[(normalizedStart + offset) % items.size] }
}

internal fun wormholeSurfaceY(centerY: Double, triggerRadius: Double, candidateTops: Sequence<Double>): Double? =
    candidateTops
        .filter { surfaceY ->
            surfaceY.isFinite() && surfaceY <= centerY && centerY - surfaceY <= triggerRadius
        }
        .maxOrNull()

class EMWormholes internal constructor(
    private val config: Config,
    private val scheduleWormholes: (periodTicks: Long, task: () -> Unit) -> ScheduledTask,
) : AutoCloseable {
    constructor() : this(
        config = ConfigManager.of(ARC.instance.dataPath, "modules/elitemobs.yml"),
        scheduleWormholes = { periodTicks, task ->
            repeating(periodTicks.ticks, delay = 20.ticks) {
                task()
            }
        },
    )

    private var wormholeTask: ScheduledTask? = null
    private val lastChestSoundTick = mutableMapOf<UUID, Long>()
    private var wormholeRingCursor = 0
    private var closed = false

    @Synchronized
    fun init() {
        check(!closed) { "EMWormholes is closed" }
        cancelTask()
        info("Starting wormhole task")
        val periodTicks = config.integer("wormholes.period-ticks", 2).toLong()
        require(periodTicks > 0) { "wormholes.period-ticks must be positive, got $periodTicks" }
        wormholeTask = scheduleWormholes(periodTicks) {
            try {
                runWormholes()
                runChests()
            } catch (e: Exception) {
                error("Error running wormholes", e)
            }
        }
    }

    @Deprecated("Use close()", ReplaceWith("close()"))
    fun cancel() {
        close()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cancelTask()
        lastChestSoundTick.clear()
        wormholeRingCursor = 0
    }

    private fun cancelTask() {
        val task = wormholeTask
        wormholeTask = null
        task?.takeUnless { it.isCancelled }?.cancel()
    }

    private fun runChests() {
        val players = PlayerManager.getOnlinePlayersThreadSafe()
        if (players.isEmpty()) return
        val chests = TreasureChest.getTreasureChestHashMap() ?: return
        if (chests.isEmpty()) return

        val distance = config.real("chests.distance", 40.0)
        val distanceSquared = distance * distance
        val particle = config.particle("chests.particle", Particle.END_ROD)
        val particleCount = config.integer("chests.particle-count", 10)
        val particleOffset = config.real("chests.particle-offset", 0.5)
        val particleExtra = config.real("chests.particle-extra", 0.05)
        val soundPeriodTicks = config.integer("chests.sound-period-ticks", 100).toLong().coerceAtLeast(1L)
        val currentTick = Bukkit.getCurrentTick().toLong()
        val sound = runCatching {
            Registry.SOUNDS.get(Key.key(config.string("chests.sound", "block.beacon.ambient")))
        }.onFailure { error("Error resolving chest sound", it) }.getOrNull()
        val entries = snapshotEntries(chests)
        try {
            for ((location, chest) in entries) {
                if (location == null) continue
                val worldName = chest.worldName ?: continue
                val world = Bukkit.getWorld(worldName) ?: continue
                location.world = world

                val receivers = ArrayList<Player>()
                for (p in players) {
                    val playerLocation = p.location
                    if (playerLocation.world != world) continue
                    if (location.distanceSquared(playerLocation) > distanceSquared) continue

                    val restockTimers = chest.customTreasureChestConfigFields.restockTimers ?: continue
                    val playerId = p.uniqueId.toString()
                    val found = restockTimers.any { timer -> belongsToPlayer(timer, playerId) }
                    if (found) continue
                    receivers.add(p)
                }

                if (receivers.isNotEmpty()) {
                    ParticleManager.queue(
                        ParticleBuilder(particle)
                            .count(particleCount)
                            .location(location.toCenterLocation())
                            .offset(particleOffset, particleOffset, particleOffset)
                            .extra(particleExtra)
                            .receivers(receivers)
                    )
                    sound?.let { resolved ->
                        receivers.forEach { player ->
                            val lastTick = lastChestSoundTick[player.uniqueId]
                            if (lastTick == null || currentTick - lastTick >= soundPeriodTicks) {
                                player.playSound(player.location, resolved, 1.0f, 1.0f)
                                lastChestSoundTick[player.uniqueId] = currentTick
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            error("Error running chests", e)
        }
    }

    private fun runWormholes() {
        val wormholes = Wormhole.getWormholes() ?: return
        if (wormholes.isEmpty()) return
        val players = PlayerManager.getOnlinePlayersThreadSafe()
        if (players.isEmpty()) return
        val particle = config.particle("wormholes.particle", Particle.DUST)
        val extra = config.real("wormholes.particle-extra", 0.0)
        val particleSize = config.real("wormholes.particle-size", 0.65).toFloat().coerceIn(0.1f, 4.0f)
        val pointCount = config.integer("wormholes.boundary-points", 24).coerceIn(8, 64)
        val heightOffset = config.real("wormholes.boundary-height-offset", 0.06)
        val renderDistance = config.real("wormholes.render-distance", 16.0).coerceAtLeast(1.0)
        val renderDistanceSquared = renderDistance * renderDistance
        val configuredMaxRings = config.integer("wormholes.max-rings-per-pass", 6).coerceIn(1, 8)
        val maxRingsPerPass = minOf(configuredMaxRings, (WORMHOLE_PARTICLE_BUILDERS_PER_PASS / pointCount).coerceAtLeast(1))
        val candidates = ArrayList<WormholeVisualCandidate>()

        for (wormhole in snapshot(wormholes)) {
            val e1 = wormhole.wormholeEntry1 ?: continue
            val e2 = wormhole.wormholeEntry2 ?: continue
            val l1 = e1.location ?: continue
            val l2 = e2.location ?: continue

            val sizeMultiplier = wormhole.wormholeConfigFields.sizeMultiplier

            fun collect(location: Location) {
                val world = location.world ?: return
                val receivers = players.filter { player ->
                    player.world == world && player.location.distanceSquared(location) <= renderDistanceSquared
                }
                if (receivers.isEmpty()) return
                candidates += WormholeVisualCandidate(
                    location,
                    sizeMultiplier,
                    wormhole.particleColor,
                    receivers,
                    receivers.minOf { player -> player.location.distanceSquared(location) },
                )
            }
            collect(l1)
            collect(l2)
        }

        val sortedCandidates = candidates.sortedBy { it.nearestDistanceSquared }
        val selectedCandidates = roundRobinSlice(sortedCandidates, wormholeRingCursor, maxRingsPerPass)
        if (sortedCandidates.isNotEmpty()) {
            wormholeRingCursor = (wormholeRingCursor + selectedCandidates.size) % sortedCandidates.size
        }
        for (candidate in selectedCandidates) {
            val location = candidate.location
            val world = location.world ?: continue
            val centerSurfaceY = findWormholeSurfaceY(location, candidate.sizeMultiplier, location.x, location.z) ?: continue
            val points = wormholeBoundaryPoints(
                centerY = location.y,
                centerSurfaceY = centerSurfaceY,
                sizeMultiplier = candidate.sizeMultiplier,
                pointCount = pointCount,
            ) { xOffset, zOffset ->
                findWormholeSurfaceY(location, candidate.sizeMultiplier, location.x + xOffset, location.z + zOffset)
            }
            for (point in points) {
                ParticleManager.queue(
                    ParticleBuilder(particle)
                        .count(1)
                        .location(world, location.x + point.x, point.surfaceY + heightOffset, location.z + point.z)
                        .extra(extra)
                        .offset(0.0, 0.0, 0.0)
                        .receivers(candidate.receivers)
                        .color(candidate.color, particleSize)
                )
            }
        }
    }

    private fun findWormholeSurfaceY(location: Location, sizeMultiplier: Double, x: Double, z: Double): Double? {
        val world = location.world ?: return null
        if (!sizeMultiplier.isFinite() || sizeMultiplier <= 0.0) return null
        val blockX = floor(x).toInt()
        val blockZ = floor(z).toInt()
        if (!world.isChunkLoaded(blockX shr 4, blockZ shr 4)) return null
        val localX = x - blockX
        val localZ = z - blockZ
        val triggerRadius = sqrt(wormholeTriggerRadiusSquared(sizeMultiplier))
        val highestBlockY = floor(location.y).toInt()
        val lowestBlockY = floor(location.y - triggerRadius).toInt() - 1
        val candidateTops = (highestBlockY downTo lowestBlockY).asSequence()
            .flatMap { blockY ->
                world.getBlockAt(blockX, blockY, blockZ).collisionShape.boundingBoxes.asSequence()
                    .filter { box -> localX in box.minX..box.maxX && localZ in box.minZ..box.maxZ }
                    .map { box -> blockY + box.maxY }
            }
        return wormholeSurfaceY(location.y, triggerRadius, candidateTops)
    }

    private fun belongsToPlayer(timer: String, playerId: String): Boolean =
        timer.length > playerId.length &&
            timer[playerId.length] == ':' &&
            timer.regionMatches(0, playerId, 0, playerId.length, ignoreCase = true)

    private fun <T> snapshot(source: Iterable<T>): List<T> {
        val copy = ArrayList<T>()
        try {
            for (item in source) copy.add(item)
        } catch (ignored: ConcurrentModificationException) {
        }
        return copy
    }

    private fun <K, V> snapshotEntries(map: Map<K, V>): List<Map.Entry<K, V>> {
        return try {
            ArrayList(map.entries)
        } catch (ignored: ConcurrentModificationException) {
            emptyList()
        }
    }
}
