package ru.arc.aurora

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.particle.PaperViewerParticles
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Main captures a bounded audience and validates loaded terrain. Detached shape
 * planning runs asynchronously; the Core emitter encodes/sends on Netty under
 * the shared ArcCore byte budget. One replaceable batch, no cosmetic backlog.
 * No chunk loads, real entities, world mutation or async Bukkit world access.
 */
internal class AuroraEngine(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    val settings: AuroraSettings,
    private val eventProfiles: (UUID) -> List<AuroraProfile>?,
    private val emitterFactory: () -> PaperViewerParticles = { PaperViewerParticles(plugin, "ambient") },
    private val previewProfiles: (UUID) -> List<AuroraProfile>? = { null },
) : AutoCloseable {
    private class Viewer(val id: UUID, var world: UUID, val particles: PaperViewerParticles, window: Int) {
        val ping = AuroraPingWindow(window)
        var sampleAt = 0L
        var due = 0L
        var revision = 0L
        var pass = 0L
        // Worker owns scene creation; Main retains immutable strokes and acknowledges attempted points.
        var sky: SkyInput? = null
        var skyTurn = 0L
        val skyScenes = mutableMapOf<String, SkyScene>()
    }
    private class SkyScene(
        val profile: AuroraProfile, val world: UUID, val revision: Long, val born: Long,
        val originX: Double, val originY: Double, val originZ: Double,
        val x: Double, val y: Double, val z: Double, val normalX: Double, val normalZ: Double,
        val path: List<AuroraOffset>,
    ) {
        val cursor = AuroraSkyCursor(profile.points)
        var nextAt = born // Main alone owns emission cadence; a late tick never catches up in a burst.
    }
    private data class Input(
        val viewer: Viewer, val world: UUID, val x: Double, val y: Double, val z: Double,
        val yaw: Float, val profiles: List<AuroraProfile>, val event: Boolean, val age: Long,
        val revision: Long, val pass: Long,
    )
    private data class Emission(
        val input: Input, val profile: AuroraProfile, val x: Double, val y: Double, val z: Double,
    )
    private data class SkyInput(val input: Input, val scenes: List<SkyScene>, val budget: Int)
    private data class Planned(val emissions: List<Emission>, val sky: SkyInput? = null)
    private data class Batch(val age: Long, val emissions: List<Emission>, val skies: List<SkyInput>)
    private val viewers = mutableMapOf<UUID, Viewer>()
    private val rotation = ArrayDeque<Viewer>()
    private val closed = AtomicBoolean()
    private val planning = AtomicBoolean()
    private val completed = AtomicReference<Batch?>()
    private var age = 0L
    private var firstViewer = 0
    private var firstSkyViewer = 0
    @Volatile var viewerCount = 0; private set
    @Volatile var pingSkipped = 0L; private set
    @Volatile var submittedPackets = 0L; private set
    private val disabledKey = NamespacedKey(plugin, "disabled")

    fun join(player: Player) {
        if (closed.get() || viewers.containsKey(player.uniqueId)) return
        Viewer(player.uniqueId, player.world.uid, emitterFactory(), settings.ping.windowSamples).also {
            viewers[it.id] = it; rotation.addLast(it)
        }
        viewerCount = viewers.size
    }

    fun quit(id: UUID) {
        viewers.remove(id)?.particles?.close()
        // Stale queue entries retire during the bounded audience scan.
        viewerCount = viewers.size
    }

    fun invalidate(id: UUID) { viewers[id]?.let { it.revision++; it.sky = null; it.particles.invalidatePending() } }
    fun invalidateWorld(worldId: UUID) {
        viewers.values.filter { it.world == worldId }.forEach { it.revision++; it.sky = null; it.particles.invalidatePending() }
    }

    fun personallyEnabled(player: Player) = player.persistentDataContainer.get(disabledKey, PersistentDataType.BYTE) != 1.toByte()
    fun setPersonallyEnabled(player: Player, enabled: Boolean) {
        if (enabled) player.persistentDataContainer.remove(disabledKey)
        else player.persistentDataContainer.set(disabledKey, PersistentDataType.BYTE, 1.toByte())
        invalidate(player.uniqueId)
    }

    /** Called exclusively by the owned one-tick Main timer. */
    fun tick() {
        if (closed.get()) return
        age++
        val deadline = System.nanoTime() + settings.nativeBudgetMicros * 1000L
        completed.getAndSet(null)?.takeIf { age - it.age <= 4L }?.let { batch ->
            batch.skies.forEach { sky ->
                val viewer = sky.input.viewer
                if (viewers[viewer.id] === viewer && viewer.revision == sky.input.revision) viewer.sky = sky
            }
            for (emission in batch.emissions) {
                if (System.nanoTime() >= deadline) break
                deliver(emission)
            }
        }
        val audience = ArrayList<Viewer>()
        repeat(minOf(rotation.size, settings.viewersPerTick)) {
            val viewer = rotation.removeFirst()
            if (viewers[viewer.id] === viewer) { rotation.addLast(viewer); audience += viewer }
        }
        emitSky(audience, deadline)
        if (planning.get() || System.nanoTime() >= deadline) return
        val inputs = ArrayList<Input>()
        audience.forEach { viewer ->
            if (System.nanoTime() >= deadline) return@forEach
            if (age < viewer.due) return@forEach
            viewer.due = age + settings.intervalTicks
            val player = Bukkit.getPlayer(viewer.id) ?: return@forEach
            val event = previewProfiles(viewer.id) ?: eventProfiles(player.world.uid)
            if (!eligible(player, viewer, event != null)) return@forEach
            val at = player.location
            if (!player.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) return@forEach
            val profiles = event ?: matching(player, at, settings.profiles)
            if (profiles.isNotEmpty()) {
                inputs += Input(viewer, player.world.uid, at.x, at.y, at.z, at.yaw, profiles, event != null, age, viewer.revision, viewer.pass++)
            }
        }
        if (inputs.isEmpty() || !planning.compareAndSet(false, true)) return
        tasks.runAsync {
            try {
                val order = firstViewer++ % inputs.size
                val rows = (inputs.drop(order) + inputs.take(order)).map(::plan)
                val emissions = ArrayList<Emission>()
                // Interleave viewers so one dense preset cannot consume the whole native slice.
                repeat(rows.maxOfOrNull { it.emissions.size } ?: 0) { index ->
                    rows.forEach { row -> row.emissions.getOrNull(index)?.let(emissions::add) }
                }
                val plan = Batch(inputs.first().age, emissions, rows.mapNotNull { it.sky })
                if (!closed.get()) completed.set(plan)
            } finally { planning.set(false) }
        }
    }

    private fun eligible(player: Player, viewer: Viewer, event: Boolean): Boolean {
        if (!player.isOnline || player.isDead) return false
        viewer.world = player.world.uid
        val current = player.ping
        if (age >= viewer.sampleAt) {
            viewer.ping.sample(current)
            viewer.sampleAt = age + settings.ping.sampleIntervalTicks
        }
        if (!viewer.ping.admits(current, settings.ping)) {
            viewer.particles.invalidatePending()
            pingSkipped++
            return false
        }
        if (!event && (!player.hasPermission(settings.permission) || !personallyEnabled(player))) {
            viewer.particles.invalidatePending()
            return false
        }
        return true
    }

    private fun matching(player: Player, at: Location, candidates: List<AuroraProfile>): List<AuroraProfile> {
        val world = player.world
        val block = world.getBlockAt(at.blockX, at.blockY.coerceIn(world.minHeight, world.maxHeight - 1), at.blockZ)
        val biome = settings.canonicalBiomeKey(block.biome.key.toString())
        val matches = candidates.filter { it.matches(biome, world.name, world.time, world.hasStorm(), world.isThundering,
            at.y, block.lightFromSky.toInt(), block.type == Material.WATER) }
        val priority = matches.maxOfOrNull { it.priority } ?: return emptyList()
        return matches.filter { it.priority == priority }
    }

    /** Pure asynchronous geometry. A sky path is prepared once, not regenerated per emitted point. */
    private fun plan(input: Input): Planned {
        val sky = input.profiles.filter { it.skyScene }
        val ordinary = input.profiles.filterNot { it.skyScene }
        input.viewer.skyScenes.entries.removeIf { (_, scene) ->
            scene.world != input.world || scene.revision != input.revision || sky.none { it === scene.profile }
        }
        if (sky.isEmpty()) return Planned(planOrdinary(input, ordinary, settings.packetsPerViewer), SkyInput(input, emptyList(), 0))
        val budget = if (ordinary.isEmpty()) settings.skyPacketsPerViewer else maxOf(settings.packetsPerViewer, settings.skyPacketsPerViewer)
        val ordinaryBudget = when {
            ordinary.isEmpty() -> 0
            budget == 1 -> if (input.pass % 2L == 0L) 0 else 1
            else -> (budget * ordinary.size / input.profiles.size).coerceIn(1, minOf(settings.packetsPerViewer, budget - 1))
        }
        return Planned(planOrdinary(input, ordinary, ordinaryBudget),
            SkyInput(input, planSky(input, sky), budget - ordinaryBudget))
    }

    /** Bounded Main drip: one new point per ready tracer, even if its worker refresh is late. */
    private fun emitSky(audience: List<Viewer>, deadline: Long) {
        if (audience.isEmpty()) return
        val first = Math.floorMod(firstSkyViewer++, audience.size)
        for (viewerIndex in audience.indices) {
            val viewer = audience[(first + viewerIndex) % audience.size]
            if (viewers[viewer.id] !== viewer) continue
            val sky = viewer.sky ?: continue
            if (System.nanoTime() >= deadline) return
            if (!sky.input.event) {
                val player = Bukkit.getPlayer(viewer.id) ?: continue
                val at = player.location
                if (!player.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4) ||
                    matching(player, at, settings.profiles) != sky.input.profiles) {
                    viewer.sky = null
                    continue
                }
            }
            var remaining = sky.budget
            if (sky.scenes.isEmpty() || remaining == 0) continue
            val start = Math.floorMod(viewer.skyTurn++, sky.scenes.size.toLong()).toInt()
            repeat(sky.scenes.size) { index ->
                if (System.nanoTime() >= deadline) return
                if (remaining == 0) return@repeat
                val scene = sky.scenes[(start + index) % sky.scenes.size]
                if (age < scene.nextAt || age - scene.born >= scene.profile.sceneDurationTicks) return@repeat
                val plan = scene.cursor.plan()
                repeat(minOf(scene.profile.skyTracers, remaining)) point@{
                    if (System.nanoTime() >= deadline) return
                    val point = plan.next() ?: return@point
                    val offset = scene.path[point.index]
                    point.attempted()
                    scene.nextAt = age + scene.profile.updateTicks
                    remaining--
                    deliver(Emission(sky.input, scene.profile,
                        scene.x + offset.x * scene.normalZ + offset.z * scene.normalX,
                        scene.y + offset.y,
                        scene.z - offset.x * scene.normalX + offset.z * scene.normalZ))
                }
            }
        }
    }

    private fun planOrdinary(input: Input, profiles: List<AuroraProfile>, budget: Int): List<Emission> {
        if (budget == 0) return emptyList()
        val random = ThreadLocalRandom.current()
        val output = ArrayList<Emission>()
        repeat(settings.attemptsPerViewer) { attempt ->
            if (output.size >= budget) return@repeat
            val profile = profiles[(Math.floorMod(input.pass, profiles.size.toLong()).toInt() + attempt) % profiles.size]
            val angle = random.nextDouble(Math.PI * 2)
            val distance = sqrt(random.nextDouble(profile.minDistance * profile.minDistance, profile.radius * profile.radius + 1e-9))
            val x = input.x + cos(angle) * distance
            val z = input.z + sin(angle) * distance
            val y = input.y + if (profile.verticalMin == profile.verticalMax) profile.verticalMin
                else random.nextDouble(profile.verticalMin, profile.verticalMax)
            for (offset in AuroraGeometry.offsets(profile.shape, profile.shapeSize, profile.points, input.age)) {
                if (output.size >= budget) break
                output += Emission(input, profile, x + offset.x, y + offset.y, z + offset.z)
            }
        }
        return output
    }

    private fun planSky(input: Input, profiles: List<AuroraProfile>): List<SkyScene> = profiles.map { profile ->
        val previous = input.viewer.skyScenes[profile.id]
        val range = profile.radius + profile.shapeSize + 8.0
        if (previous == null || previous.profile !== profile ||
            input.age - previous.born >= profile.sceneDurationTicks ||
            (previous.originX - input.x).let { it * it } + (previous.originY - input.y).let { it * it } +
            (previous.originZ - input.z).let { it * it } > range * range
        ) createSkyScene(input, profile).also { input.viewer.skyScenes[profile.id] = it } else previous
    }

    private fun createSkyScene(input: Input, profile: AuroraProfile): SkyScene {
        val random = ThreadLocalRandom.current()
        // Include the previous same-profile anchor: its native tail can still be fading.
        val neighbours = input.viewer.skyScenes.values
        // Take the first random safe candidate. Always maximizing clearance would pin roles to side lanes.
        val candidates = List(12) {
            val angle = Math.toRadians(input.yaw.toDouble() + random.nextDouble(-70.0, 70.0))
            val distance = sqrt(random.nextDouble(profile.minDistance * profile.minDistance, profile.radius * profile.radius + 1e-9))
            val height = if (profile.verticalMin == profile.verticalMax) profile.verticalMin
                else random.nextDouble(profile.verticalMin, profile.verticalMax)
            Triple(angle, distance, height)
        }
        fun clearance(candidate: Triple<Double, Double, Double>): Double {
            val (angle, distance, height) = candidate
            return neighbours.minOfOrNull { other ->
                val dx = other.x - input.x
                val dz = other.z - input.z
                val bearing = atan2(-dx, dz)
                val separation = atan2(sin(angle - bearing), cos(angle - bearing))
                val elevation = atan2(height, distance) - atan2(other.y - input.y, sqrt(dx * dx + dz * dz))
                sqrt(separation * separation + elevation * elevation) -
                    atan2(profile.shapeSize, distance) - atan2(other.profile.shapeSize, sqrt(dx * dx + dz * dz))
            } ?: Double.POSITIVE_INFINITY
        }
        val anchor = candidates.firstOrNull { clearance(it) >= Math.toRadians(8.0) }
            ?: candidates.maxBy(::clearance)
        val (angle, distance, height) = anchor
        val delay = if (profile.spawnDelayTicks == 0) 0 else List(8) { random.nextInt(profile.spawnDelayTicks + 1) }
            .maxBy { candidate -> neighbours.minOfOrNull { kotlin.math.abs(input.age + candidate - it.born) } ?: 0L }
        val path = AuroraSkyShapes.stroke(profile.shape, (profile.shapeSize * random.nextDouble(0.8, 1.0)).coerceAtLeast(0.1),
            profile.points, profile.updateTicks, profile.motionPeriodTicks,
            random.nextInt(profile.motionPeriodTicks), random.nextDouble(-0.45, 0.45), random.nextBoolean(), profile.skyTracers)
        // Local +Z faces the creation viewer; turning never retargets a live scene.
        return SkyScene(profile, input.world, input.revision, input.age + delay,
            input.x, input.y, input.z, input.x - sin(angle) * distance, input.y + height, input.z + cos(angle) * distance,
            sin(angle), -cos(angle), path)
    }

    private fun deliver(emission: Emission) {
        val input = emission.input
        val viewer = input.viewer
        if (viewers[viewer.id] !== viewer || viewer.revision != input.revision) return
        val player = Bukkit.getPlayer(viewer.id) ?: return
        if (player.world.uid != input.world) { viewer.particles.invalidatePending(); return }
        val event = previewProfiles(viewer.id) ?: eventProfiles(input.world)
        if (input.event && event !== input.profiles) return // lease/config generation changed
        if (!input.event && event != null) return
        if (!eligible(player, viewer, event != null)) return
        val at = player.location
        if (at.distanceSquared(Location(player.world, input.x, input.y, input.z)) > 16.0) return
        val world = player.world
        if (emission.y < world.minHeight || emission.y >= world.maxHeight) return
        val location = Location(world, emission.x, emission.y, emission.z)
        // Sky min-distance selects its anchor, not a sphere that would cut off the near half of a large figure.
        val eyeClearance = if (emission.profile.skyScene) 2.0 else emission.profile.minDistance
        if (location.distanceSquared(player.eyeLocation) < eyeClearance * eyeClearance) return
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return
        val block = location.block
        if (!block.isPassable || block.isLiquid && !(emission.profile.underwater && block.type == Material.WATER)) return
        if (!input.event && emission.profile !in matching(player, location, settings.profiles)) return
        // Final permission/ping gate above is shared by ambient and temporary events.
        val p = emission.profile
        viewer.particles.emit(player, location, p.particle, p.count, p.offsetX, p.offsetY, p.offsetZ, p.speed, p.particleData, p.skyScene)
        submittedPackets++
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        completed.set(null)
        viewers.values.forEach { it.particles.close() }
        viewers.clear(); rotation.clear(); viewerCount = 0
    }
}

/** Worker plans are detached; only points actually attempted by Main advance the next plan. */
internal class AuroraSkyCursor(private val points: Int) {
    private val acknowledged = AtomicInteger()

    fun plan() = Plan(acknowledged.get())

    inner class Plan internal constructor(private var cursor: Int) {
        fun next(): Point? {
            if (cursor >= points) return null
            val index = cursor++
            return Point(index, cursor)
        }
    }

    inner class Point internal constructor(val index: Int, private val next: Int) {
        fun attempted() { acknowledged.compareAndSet(index, next) }
    }
}
