package ru.arc.aurora

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.HeightMap
import org.bukkit.Particle
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.particle.PaperViewerParticles
import java.util.UUID
import java.util.Random
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
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
    private enum class Source { ORDINARY, NIGHTLY, EVENT }
    private data class Selection(val source: Source, val profiles: List<AuroraProfile>) {
        fun sameAs(other: Selection): Boolean = source == other.source &&
            if (source == Source.ORDINARY) profiles.size == other.profiles.size &&
                profiles.indices.all { profiles[it] === other.profiles[it] }
            else profiles === other.profiles
    }
    private class Viewer(val id: UUID, var world: UUID, val particles: PaperViewerParticles, window: Int) {
        val ping = AuroraPingWindow(window)
        var sampleAt = 0L
        var due = 0L
        var revision = 0L
        var pass = 0L
        var selection: Selection? = null
        // Worker owns scene creation; Main retains immutable strokes and acknowledges attempted points.
        var sky: SkyInput? = null
        var skyTurn = 0L
        val skyScenes = mutableMapOf<AuroraSkyCell, SkyScene>()
        val rejectedCells = mutableMapOf<AuroraSkyCell, Long>()
        val retiredCells = linkedMapOf<AuroraSkyCell, Long>()
        var fieldWorld: UUID? = null
        var fieldRevision = Long.MIN_VALUE
        var fieldProfiles: List<AuroraProfile>? = null
        var fieldAdmissionAfter = 0L
    }
    private class SkyScene(
        val profile: AuroraProfile, val world: UUID, val revision: Long, val born: Long,
        val cell: AuroraSkyCell, val epoch: Long,
        val x: Double, val y: Double, val z: Double, val normalX: Double, val normalZ: Double,
        val path: List<AuroraOffset>, val motion: List<AuroraOffset>,
    ) {
        val cursor = AuroraSkyCursor(profile.points)
        val rejected = AtomicBoolean()
        val culled = AtomicBoolean()
        var surfaceY: Int? = null // Resolved once on Main; x/y-offset/z never follow a viewer.
        val columns = skyColumns(x, y, z, normalX, normalZ, path, motion, profile.particle)
        @Volatile var startsAt = Long.MAX_VALUE // Main publishes birth after the full envelope passes.
        var checkedColumns = 0
        var nextAt = born
        var refreshCursor = 0
        var refreshCredit = 0
        var repairIndex = -1
        var repairAt = Long.MIN_VALUE
    }
    private enum class Delivery { SUBMITTED, SKIPPED, OBSTRUCTED }
    private data class Input(
        val viewer: Viewer, val world: UUID, val x: Double, val y: Double, val z: Double,
        val yaw: Float, val selection: Selection, val age: Long,
        val revision: Long, val pass: Long,
    ) {
        val profiles get() = selection.profiles
    }
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
            val selection = select(player, viewer)
            if (!eligible(player, viewer, selection.source)) return@forEach
            val at = player.location
            if (!player.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) return@forEach
            if (selection.profiles.isNotEmpty()) {
                inputs += Input(viewer, player.world.uid, at.x, at.y, at.z, at.yaw, selection, age, viewer.revision, viewer.pass++)
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

    /** Reconcile Main-owned source changes before consuming a point or dispatching captured work. */
    private fun select(player: Player, viewer: Viewer): Selection {
        val world = player.world
        val event = previewProfiles(viewer.id) ?: eventProfiles(world.uid)
        val selected = when {
            event != null -> Selection(Source.EVENT, event)
            settings.nightSky.enabled && settings.nightSky.matches(world.name, world.environment, world.time) ->
                Selection(Source.NIGHTLY, settings.eventPresets.getValue(settings.nightSky.preset))
            else -> {
                val at = player.location
                val profiles = if (player.hasPermission(settings.permission) && personallyEnabled(player) &&
                    world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) matching(player, at, settings.profiles)
                    else emptyList()
                Selection(Source.ORDINARY, profiles)
            }
        }
        if (viewer.selection?.sameAs(selected) == false) {
            invalidate(viewer.id)
            viewer.due = age
        }
        viewer.selection = selected
        return selected
    }

    private fun eligible(player: Player, viewer: Viewer, source: Source): Boolean {
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
        if (source != Source.EVENT && (!personallyEnabled(player) ||
            source == Source.ORDINARY && !player.hasPermission(settings.permission))) {
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
        if (input.viewer.fieldWorld != input.world || input.viewer.fieldRevision != input.revision ||
            input.viewer.fieldProfiles?.let { old -> old.size == input.profiles.size &&
                old.indices.all { old[it] === input.profiles[it] } } != true) {
            input.viewer.skyScenes.clear(); input.viewer.rejectedCells.clear(); input.viewer.retiredCells.clear()
            input.viewer.fieldAdmissionAfter = 0L
            input.viewer.fieldWorld = input.world; input.viewer.fieldRevision = input.revision
            input.viewer.fieldProfiles = input.profiles
        }
        input.viewer.skyScenes.entries.removeIf { (_, scene) ->
            scene.world != input.world || scene.revision != input.revision || sky.none { it === scene.profile }
        }
        if (sky.isEmpty()) input.viewer.rejectedCells.clear()
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

    /** One advancing point per tracer, with a bounded rolling refresh behind the revealed frontier. */
    private fun emitSky(audience: List<Viewer>, deadline: Long) {
        if (audience.isEmpty()) return
        val first = Math.floorMod(firstSkyViewer++, audience.size)
        for (viewerIndex in audience.indices) {
            val viewer = audience[(first + viewerIndex) % audience.size]
            if (viewers[viewer.id] !== viewer) continue
            if (System.nanoTime() >= deadline) return
            val player = Bukkit.getPlayer(viewer.id) ?: continue
            val selection = select(player, viewer)
            val sky = viewer.sky ?: continue
            if (player.world.uid != sky.input.world || viewer.revision != sky.input.revision) continue
            if (!selection.sameAs(sky.input.selection) || !eligible(player, viewer, selection.source)) continue
            var remaining = sky.budget
            if (sky.scenes.isEmpty() || remaining == 0) continue
            val start = Math.floorMod(viewer.skyTurn++, sky.scenes.size.toLong()).toInt()
            for (index in sky.scenes.indices) {
                if (System.nanoTime() >= deadline) return
                if (remaining == 0) break
                val scene = sky.scenes[(start + index) % sky.scenes.size]
                val current = player.location
                val limit = settings.skyField.viewDistance + settings.skyField.cullHysteresis
                if ((scene.x - current.x).let { it * it } + (scene.z - current.z).let { it * it } > limit * limit) {
                    scene.culled.set(true) // Stop the whole figure; its existing native tail expires naturally.
                }
                if (scene.culled.get() || scene.rejected.get() || !admitSky(scene, player, deadline) || age < scene.startsAt) continue
                val sceneAge = age - scene.startsAt
                if (sceneAge >= scene.profile.sceneDurationTicks) continue
                val refresh = scene.profile.skyRefreshTicks
                val exitTicks = minOf(scene.profile.skyOptions.exitTicks, scene.profile.sceneDurationTicks / 4)
                val exitAt = scene.profile.sceneDurationTicks - exitTicks
                val motion = scene.motion[minOf(sceneAge.toInt(), scene.motion.lastIndex)]
                fun emit(index: Int) {
                    val point = scene.path[index]
                    val offset = AuroraOffset(point.x + motion.x, point.y + motion.y, point.z + motion.z)
                    remaining--
                    if (deliver(Emission(sky.input, scene.profile,
                        scene.x + offset.x * scene.normalZ + offset.z * scene.normalX,
                        scene.y + checkNotNull(scene.surfaceY) + offset.y,
                        scene.z - offset.x * scene.normalX + offset.z * scene.normalZ)) == Delivery.OBSTRUCTED) {
                        scene.rejected.set(true) // Relocate a damaged figure instead of carving a permanent hole.
                    }
                }
                val repair = scene.repairIndex.takeIf { it >= 0 && age > scene.repairAt && age - scene.repairAt <= scene.profile.skyOptions.freshHeadMaxAgeTicks }
                scene.repairIndex = -1
                if (age >= scene.nextAt && (refresh == 0 || sceneAge < exitAt)) {
                    val plan = scene.cursor.plan()
                    repeat(minOf(scene.profile.skyTracers, remaining)) head@{
                        if (System.nanoTime() >= deadline) return
                        if (scene.rejected.get()) return@head
                        val point = plan.next() ?: return@head
                        point.attempted()
                        scene.nextAt = age + scene.profile.updateTicks
                        emit(point.index)
                        if (refresh == 0 && scene.profile.shape == AuroraShape.WAVE && scene.profile.skyOptions.freshHeadRepair) {
                            scene.repairIndex = point.index
                            scene.repairAt = age
                        }
                    }
                }
                // Only the previous fresh head may be repaired once; old wave tails are never refreshed.
                if (repair != null && remaining > 0 && !scene.rejected.get()) {
                    if (System.nanoTime() >= deadline) return
                    emit(repair)
                }
                if (refresh == 0 || scene.rejected.get()) continue
                val revealed = scene.cursor.position
                val from = if (sceneAge < exitAt) 0 else
                    ((sceneAge - exitAt) * scene.profile.points / exitTicks).toInt().coerceAtMost(revealed)
                val available = revealed - from
                if (available == 0) continue
                val credit = scene.refreshCredit + available
                scene.refreshCredit = credit % refresh // Keep fractional credit only; no catch-up backlog.
                repeat(minOf(credit / refresh, remaining)) refill@{
                    if (System.nanoTime() >= deadline) return
                    if (scene.rejected.get()) return@refill
                    if (scene.refreshCursor !in from until revealed) scene.refreshCursor = from
                    emit(scene.refreshCursor++)
                }
            }
        }
    }

    /** Loaded heightmap checks cover the whole swept footprint before any point is sent. */
    private fun admitSky(scene: SkyScene, player: Player, deadline: Long): Boolean {
        if (scene.startsAt != Long.MAX_VALUE) return true
        val world = player.world
        if (scene.surfaceY == null) {
            val x = floor(scene.x).toInt(); val z = floor(scene.z).toInt()
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
            scene.surfaceY = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE)
        }
        val surfaceY = checkNotNull(scene.surfaceY)
        repeat(24) {
            if (scene.checkedColumns >= scene.columns.size) {
                scene.startsAt = maxOf(scene.born, age)
                scene.nextAt = scene.startsAt
                return true
            }
            if (System.nanoTime() >= deadline) return false
            val column = scene.columns[scene.checkedColumns]
            if (!world.isChunkLoaded(column.x shr 4, column.z shr 4)) return false
            if (column.low + surfaceY < world.minHeight || column.high + surfaceY >= world.maxHeight ||
                column.low + surfaceY <= world.getHighestBlockYAt(column.x, column.z, HeightMap.WORLD_SURFACE) + scene.profile.skyOptions.terrainClearance) {
                scene.rejected.set(true)
                return false
            }
            scene.checkedColumns++
        }
        return false
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

    private fun planSky(input: Input, profiles: List<AuroraProfile>): List<SkyScene> {
        val field = settings.skyField
        val candidates = AuroraWorldField.candidates(input.world, profiles, input.x, input.z, input.age, field)
        val byCell = candidates.associateBy { it.cell }
        val active = ArrayList<SkyScene>()
        val scenes = input.viewer.skyScenes
        val rejected = input.viewer.rejectedCells
        val retired = input.viewer.retiredCells
        retired.entries.removeIf { it.value <= input.age }
        rejected.entries.removeIf { (cell, epoch) -> byCell[cell]?.epoch != epoch }
        for ((cell, scene) in scenes.toMap()) {
            val keep = (scene.x - input.x).let { it * it } + (scene.z - input.z).let { it * it } <=
                (field.viewDistance + field.cullHysteresis).let { it * it } && !scene.culled.get() && !scene.rejected.get() &&
                (scene.startsAt == Long.MAX_VALUE || input.age - scene.startsAt < scene.profile.sceneDurationTicks)
            if (keep && active.size < field.maxScenes) active += scene
            else {
                rejected[cell] = scene.epoch // Never restart an expired/cancelled path in the same epoch.
                if (scene.culled.get()) {
                    retired[cell] = maxOf(input.age + field.cullCooldownTicks,
                        if (scene.startsAt == Long.MAX_VALUE) 0 else scene.startsAt + scene.profile.sceneDurationTicks)
                }
                scenes.remove(cell)
            }
        }
        if (retired.size > 256) {
            input.viewer.fieldAdmissionAfter = maxOf(input.viewer.fieldAdmissionAfter, retired.values.max())
            retired.clear() // The viewer-wide gate now preserves every unexpired tail protection.
        }
        // Preserve admitted scenes across cell/epoch boundaries; fill vacancies without nearest-slot churn.
        for (candidate in candidates) {
            if (input.age < input.viewer.fieldAdmissionAfter || active.size >= field.maxScenes) break
            if (scenes.containsKey(candidate.cell) || retired.containsKey(candidate.cell) || rejected[candidate.cell] == candidate.epoch) continue
            if (active.count { it.profile === candidate.profile } >= field.maxPerProfile) continue
            val distanceSquared = (candidate.x - input.x).let { it * it } + (candidate.z - input.z).let { it * it }
            val outer = minOf(field.viewDistance, candidate.profile.radius)
            if (distanceSquared > outer * outer || distanceSquared < candidate.profile.minDistance * candidate.profile.minDistance) continue
            createSkyScene(input, candidate).also { scenes[candidate.cell] = it; active += it }
        }
        return active
    }

    private fun createSkyScene(input: Input, candidate: AuroraWorldCandidate): SkyScene {
        val profile = candidate.profile
        val options = profile.skyOptions
        val random = Random(candidate.seed)
        val height = profile.verticalMin + random.nextDouble() * (profile.verticalMax - profile.verticalMin)
        val angle = Math.toRadians(options.yawMinDegrees + random.nextDouble() * (options.yawMaxDegrees - options.yawMinDegrees))
        val delay = if (profile.spawnDelayTicks == 0) 0 else random.nextInt(profile.spawnDelayTicks + 1)
        val scale = options.scaleMin + random.nextDouble() * (options.scaleMax - options.scaleMin)
        val size = (profile.shapeSize * scale).coerceAtLeast(0.1)
        val path = AuroraSkyShapes.stroke(profile.shape, size, profile.points, profile.updateTicks,
            profile.motionPeriodTicks, random.nextInt(profile.motionPeriodTicks),
            (random.nextDouble() * 2.0 - 1.0) * options.rollRadians, random.nextBoolean(), profile.skyTracers, options)
        return SkyScene(profile, input.world, input.revision, input.age + delay,
            candidate.cell, candidate.epoch, candidate.x, height, candidate.z, sin(angle), -cos(angle), path,
            if (profile.skyRefreshTicks > 0) AuroraSkyShapes.heldMotion(size, profile.sceneDurationTicks, profile.motionPeriodTicks, options)
            else listOf(AuroraOffset(0.0, 0.0, 0.0)))
    }

    private fun deliver(emission: Emission): Delivery {
        val input = emission.input
        val viewer = input.viewer
        if (viewers[viewer.id] !== viewer || viewer.revision != input.revision) return Delivery.SKIPPED
        val player = Bukkit.getPlayer(viewer.id) ?: return Delivery.SKIPPED
        if (player.world.uid != input.world) { viewer.particles.invalidatePending(); return Delivery.SKIPPED }
        val selection = select(player, viewer)
        if (!selection.sameAs(input.selection) || viewer.revision != input.revision) return Delivery.SKIPPED
        if (!eligible(player, viewer, selection.source)) return Delivery.SKIPPED
        val at = player.location
        if (!emission.profile.skyScene && at.distanceSquared(Location(player.world, input.x, input.y, input.z)) > 16.0)
            return Delivery.SKIPPED
        val world = player.world
        if (emission.y < world.minHeight || emission.y >= world.maxHeight) return Delivery.OBSTRUCTED
        val location = Location(world, emission.x, emission.y, emission.z)
        // Sky min-distance selects its anchor, not a sphere that would cut off the near half of a large figure.
        val eyeClearance = if (emission.profile.skyScene) 2.0 else emission.profile.minDistance
        if (location.distanceSquared(player.eyeLocation) < eyeClearance * eyeClearance) return Delivery.OBSTRUCTED
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return Delivery.OBSTRUCTED
        val block = location.block
        if (!block.isPassable || block.isLiquid && !(emission.profile.underwater && block.type == Material.WATER)) return Delivery.OBSTRUCTED
        if (input.selection.source == Source.ORDINARY && emission.profile !in matching(player, location, settings.profiles))
            return Delivery.OBSTRUCTED
        // Final permission/ping gate above is shared by ambient and temporary events.
        val p = emission.profile
        viewer.particles.emit(player, location, p.particle, p.count, p.offsetX, p.offsetY, p.offsetZ, p.speed, p.particleData, p.skyScene)
        submittedPackets++
        return Delivery.SUBMITTED
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

    val position: Int get() = acknowledged.get()

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


private data class SkyColumn(val x: Int, val z: Int, val low: Double, val high: Double)

/** Detached conservative envelope, including full native fall and sprite half-size for bundled types. */
private fun skyColumns(
    x: Double, y: Double, z: Double, normalX: Double, normalZ: Double,
    path: List<AuroraOffset>, motion: List<AuroraOffset>, particle: Particle,
): List<SkyColumn> {
    val flame = particle == Particle.FLAME || particle == Particle.SOUL_FIRE_FLAME
    val padding = if (flame) 0.5 else 0.22 // Flame also has native spawn jitter and a small random drift.
    val fall = when (particle) {
        Particle.FLAME, Particle.SOUL_FIRE_FLAME -> 0.0
        Particle.END_ROD -> 0.35
        Particle.FIREWORK -> 2.2
        Particle.TOTEM_OF_UNDYING -> 8.7
        else -> 1.0
    }
    val minX = motion.minOf { it.x }; val maxX = motion.maxOf { it.x }
    val minY = motion.minOf { it.y }; val maxY = motion.maxOf { it.y }
    val columns = linkedMapOf<Pair<Int, Int>, SkyColumn>()
    for (point in path) {
        val x0 = x + (point.x + minX) * normalZ + point.z * normalX
        val x1 = x + (point.x + maxX) * normalZ + point.z * normalX
        val z0 = z - (point.x + minX) * normalX + point.z * normalZ
        val z1 = z - (point.x + maxX) * normalX + point.z * normalZ
        val low = y + point.y + minY - fall - padding
        val high = y + point.y + maxY + padding
        for (columnX in floor(minOf(x0, x1) - padding).toInt()..floor(maxOf(x0, x1) + padding).toInt()) {
            for (columnZ in floor(minOf(z0, z1) - padding).toInt()..floor(maxOf(z0, z1) + padding).toInt()) {
                val key = columnX to columnZ
                val previous = columns[key]
                columns[key] = SkyColumn(columnX, columnZ, minOf(low, previous?.low ?: low), maxOf(high, previous?.high ?: high))
            }
        }
    }
    return columns.values.toList()
}
