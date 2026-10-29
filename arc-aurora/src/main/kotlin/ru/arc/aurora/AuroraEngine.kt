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
        // Only the serial worker accesses this map and scene anchors; Main acknowledges attempted points.
        val skyScenes = mutableMapOf<String, SkyScene>()
    }
    private class SkyScene(
        val profile: AuroraProfile, val world: UUID, val revision: Long, val born: Long,
        val x: Double, val y: Double, val z: Double, val normalX: Double, val normalZ: Double,
    ) {
        val cursor = AuroraSkyCursor(profile.points)
    }
    private data class Input(
        val viewer: Viewer, val world: UUID, val x: Double, val y: Double, val z: Double,
        val yaw: Float, val profiles: List<AuroraProfile>, val event: Boolean, val age: Long,
        val revision: Long, val pass: Long,
    )
    private data class Emission(
        val input: Input, val profile: AuroraProfile, val x: Double, val y: Double, val z: Double,
        val skyPoint: AuroraSkyCursor.Point? = null,
    )
    private data class Batch(val age: Long, val emissions: List<Emission>)
    private val viewers = mutableMapOf<UUID, Viewer>()
    private val rotation = ArrayDeque<Viewer>()
    private val closed = AtomicBoolean()
    private val planning = AtomicBoolean()
    private val completed = AtomicReference<Batch?>()
    private var age = 0L
    private var firstViewer = 0
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

    fun invalidate(id: UUID) { viewers[id]?.let { it.revision++; it.particles.invalidatePending() } }
    fun invalidateWorld(worldId: UUID) {
        viewers.values.filter { it.world == worldId }.forEach { it.revision++; it.particles.invalidatePending() }
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
            for (emission in batch.emissions) {
                if (System.nanoTime() >= deadline) break
                emission.skyPoint?.attempted()
                deliver(emission)
            }
        }
        if (planning.get() || System.nanoTime() >= deadline) return
        val inputs = ArrayList<Input>()
        repeat(minOf(rotation.size, settings.viewersPerTick)) {
            if (System.nanoTime() >= deadline) return@repeat
            val viewer = rotation.removeFirst()
            if (viewers[viewer.id] !== viewer) return@repeat
            rotation.addLast(viewer)
            if (age < viewer.due) return@repeat
            viewer.due = age + settings.intervalTicks
            val player = Bukkit.getPlayer(viewer.id) ?: return@repeat
            val event = previewProfiles(viewer.id) ?: eventProfiles(player.world.uid)
            if (!eligible(player, viewer, event != null)) return@repeat
            val at = player.location
            if (!player.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) return@repeat
            val profiles = event ?: matching(player, at, settings.profiles)
            if (profiles.isNotEmpty()) {
                viewer.due = age + minOf(settings.intervalTicks, profiles.filter { it.skyScene }.minOfOrNull { it.updateTicks } ?: settings.intervalTicks)
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
                repeat(rows.maxOfOrNull { it.size } ?: 0) { index ->
                    rows.forEach { row -> row.getOrNull(index)?.let(emissions::add) }
                }
                val plan = Batch(inputs.first().age, emissions)
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

    /** Pure asynchronous geometry, bounded by attempts and output packet caps. */
    private fun plan(input: Input): List<Emission> {
        val sky = input.profiles.filter { it.skyScene }
        val ordinary = input.profiles.filterNot { it.skyScene }
        input.viewer.skyScenes.entries.removeIf { (_, scene) ->
            scene.world != input.world || scene.revision != input.revision || sky.none { it === scene.profile }
        }
        if (sky.isEmpty()) return planOrdinary(input, ordinary, settings.packetsPerViewer)
        val budget = if (ordinary.isEmpty()) settings.skyPacketsPerViewer else maxOf(settings.packetsPerViewer, settings.skyPacketsPerViewer)
        val ordinaryBudget = when {
            ordinary.isEmpty() -> 0
            budget == 1 -> if (input.pass % 2L == 0L) 0 else 1
            else -> (budget * ordinary.size / input.profiles.size).coerceIn(1, minOf(settings.packetsPerViewer, budget - 1))
        }
        val skyEmissions = planSky(input, sky, budget - ordinaryBudget)
        if (ordinary.isEmpty()) return skyEmissions
        val ordinaryEmissions = planOrdinary(input, ordinary, ordinaryBudget)
        val rows = if (input.pass % 2L == 0L) listOf(skyEmissions, ordinaryEmissions) else listOf(ordinaryEmissions, skyEmissions)
        return buildList {
            repeat(maxOf(skyEmissions.size, ordinaryEmissions.size)) { index ->
                rows.forEach { it.getOrNull(index)?.let(::add) }
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

    private fun planSky(input: Input, profiles: List<AuroraProfile>, budget: Int): List<Emission> {
        if (budget == 0) return emptyList()
        val frames = profiles.mapIndexed { slot, profile ->
            val previous = input.viewer.skyScenes[profile.id]
            val range = profile.radius + profile.shapeSize + 8.0
            val scene = if (previous == null || previous.profile !== profile ||
                input.age - previous.born >= profile.sceneDurationTicks ||
                (previous.x - input.x).let { it * it } + (previous.y - input.y).let { it * it } +
                (previous.z - input.z).let { it * it } > range * range
            ) createSkyScene(input, profile, slot, profiles.size).also { input.viewer.skyScenes[profile.id] = it } else previous
            Triple(scene, AuroraSkyShapes.offsets(profile.shape, profile.shapeSize, profile.points,
                input.age - scene.born, profile.motionPeriodTicks), scene.cursor.plan())
        }
        val start = Math.floorMod(input.pass, frames.size.toLong()).toInt()
        return List(budget) { index ->
            val (scene, offsets, cursor) = frames[(start + index) % frames.size]
            val point = cursor.next()
            val offset = offsets[point.index]
            Emission(input, scene.profile,
                scene.x + offset.x * scene.normalZ + offset.z * scene.normalX,
                scene.y + offset.y,
                scene.z - offset.x * scene.normalX + offset.z * scene.normalZ, point)
        }
    }

    private fun createSkyScene(input: Input, profile: AuroraProfile, slot: Int, slots: Int): SkyScene {
        val random = ThreadLocalRandom.current()
        val spacing = minOf(35.0, 100.0 / maxOf(1, slots - 1))
        val sector = (slot - (slots - 1) / 2.0) * spacing
        val angle = Math.toRadians(input.yaw.toDouble() + sector + random.nextDouble(-8.0, 8.0))
        val distance = sqrt(random.nextDouble(profile.minDistance * profile.minDistance, profile.radius * profile.radius + 1e-9))
        val x = input.x - sin(angle) * distance
        val z = input.z + cos(angle) * distance
        val y = input.y + if (profile.verticalMin == profile.verticalMax) profile.verticalMin
            else random.nextDouble(profile.verticalMin, profile.verticalMax)
        // Local +Z faces the viewer captured at creation; turning never retargets a live scene.
        return SkyScene(profile, input.world, input.revision, input.age, x, y, z, sin(angle), -cos(angle))
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
        fun next(): Point {
            val index = cursor
            cursor = (cursor + 1) % points
            return Point(index, cursor)
        }
    }

    inner class Point internal constructor(val index: Int, private val next: Int) {
        fun attempted() { acknowledged.set(next) }
    }
}
