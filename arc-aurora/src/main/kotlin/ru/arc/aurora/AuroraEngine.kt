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
) : AutoCloseable {
    private class Viewer(val id: UUID, var world: UUID, val particles: PaperViewerParticles, window: Int) {
        val ping = AuroraPingWindow(window)
        var sampleAt = 0L
        var due = 0L
        var revision = 0L
        var pass = 0L
    }
    private data class Input(
        val viewer: Viewer, val world: UUID, val x: Double, val y: Double, val z: Double,
        val profiles: List<AuroraProfile>, val event: Boolean, val age: Long, val revision: Long, val pass: Long,
    )
    private data class Emission(val input: Input, val profile: AuroraProfile, val x: Double, val y: Double, val z: Double)
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
            val event = eventProfiles(player.world.uid)
            if (!eligible(player, viewer, event != null)) return@repeat
            val at = player.location
            if (!player.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) return@repeat
            val profiles = event ?: matching(player, at, settings.profiles)
            if (profiles.isNotEmpty()) inputs += Input(viewer, player.world.uid, at.x, at.y, at.z, profiles, event != null, age, viewer.revision, viewer.pass++)
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
        val random = ThreadLocalRandom.current()
        val output = ArrayList<Emission>()
        repeat(settings.attemptsPerViewer) { attempt ->
            if (output.size >= settings.packetsPerViewer) return@repeat
            val profile = input.profiles[(Math.floorMod(input.pass, input.profiles.size.toLong()).toInt() + attempt) % input.profiles.size]
            val angle = random.nextDouble(Math.PI * 2)
            val distance = sqrt(random.nextDouble(profile.minDistance * profile.minDistance, profile.radius * profile.radius + 1e-9))
            val x = input.x + cos(angle) * distance
            val z = input.z + sin(angle) * distance
            val y = input.y + if (profile.verticalMin == profile.verticalMax) profile.verticalMin
                else random.nextDouble(profile.verticalMin, profile.verticalMax)
            for (offset in AuroraGeometry.offsets(profile.shape, profile.shapeSize, profile.points, input.age)) {
                if (output.size >= settings.packetsPerViewer) break
                output += Emission(input, profile, x + offset.x, y + offset.y, z + offset.z)
            }
        }
        return output
    }

    private fun deliver(emission: Emission) {
        val input = emission.input
        val viewer = input.viewer
        if (viewers[viewer.id] !== viewer || viewer.revision != input.revision) return
        val player = Bukkit.getPlayer(viewer.id) ?: return
        if (player.world.uid != input.world) { viewer.particles.invalidatePending(); return }
        val event = eventProfiles(input.world)
        if (input.event && event !== input.profiles) return // lease/config generation changed
        if (!input.event && event != null) return
        if (!eligible(player, viewer, event != null)) return
        val at = player.location
        if (at.distanceSquared(Location(player.world, input.x, input.y, input.z)) > 16.0) return
        val world = player.world
        if (emission.y < world.minHeight || emission.y >= world.maxHeight) return
        val location = Location(world, emission.x, emission.y, emission.z)
        if (location.distanceSquared(player.eyeLocation) < emission.profile.minDistance * emission.profile.minDistance) return
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return
        val block = location.block
        if (!block.isPassable || block.isLiquid && !(emission.profile.underwater && block.type == Material.WATER)) return
        if (!input.event && emission.profile !in matching(player, location, settings.profiles)) return
        // Final permission/ping gate above is shared by ambient and temporary events.
        val p = emission.profile
        viewer.particles.emit(player, location, p.particle, p.count, p.offsetX, p.offsetY, p.offsetZ, p.speed, p.particleData, false)
        submittedPackets++
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        completed.set(null)
        viewers.values.forEach { it.particles.close() }
        viewers.clear(); rotation.clear(); viewerCount = 0
    }
}
