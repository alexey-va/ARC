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
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.particle.PaperViewerParticles
import java.util.UUID
import java.util.Random
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
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
    private val lanternFactory: () -> AuroraLanternDisplays = { AuroraLanternDisplays(plugin) },
    private val showcaseProfiles: List<AuroraProfile>? = AuroraShowcase.profiles(settings),
    private val onShowcaseBlocked: (Player) -> Unit = {},
    private val showcaseEffects: Map<AuroraShape, AuroraProfile> = showcaseEffects(settings),
) : AutoCloseable {
    val showcaseShapes: Set<AuroraShape> get() = showcaseEffects.keys

    private enum class Source { ORDINARY, NIGHTLY, EVENT, SHOWCASE }
    private data class Selection(val source: Source, val profiles: List<AuroraProfile>) {
        fun sameAs(other: Selection): Boolean = source == other.source &&
            if (source == Source.ORDINARY) profiles.size == other.profiles.size &&
                profiles.indices.all { profiles[it] === other.profiles[it] }
            else profiles === other.profiles
    }
    private data class PreviewDrain(val profiles: List<AuroraProfile>, val scenes: Set<SkyScene>)
    private class Viewer(val id: UUID, var world: UUID, val particles: PaperViewerParticles, window: Int) {
        val ping = AuroraPingWindow(window)
        var sampleAt = 0L
        var due = 0L
        var revision = 0L
        var pass = 0L
        var selection: Selection? = null
        var previewDrain: PreviewDrain? = null // Main-owned; immutable snapshot copied into worker inputs.
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
    private class TerrainColumn(val support: AuroraSurface, val clearance: Double) {
        var verifiedAt = Long.MIN_VALUE
    }
    private class SkyScene(
        val profile: AuroraProfile, val world: UUID, val revision: Long, val born: Long,
        val cell: AuroraSkyCell, val epoch: Long,
        val x: Double, val y: Double, val z: Double, val normalX: Double, val normalZ: Double,
        val path: List<AuroraOffset>, val motion: List<AuroraOffset>,
        val animation: SkyAnimation? = null,
        val phaseOffsetTicks: Int = 0,
        val storyBounds: StoryBounds? = null,
        val fixedFacing: Quaternionf? = if (profile.shape == AuroraShape.DEATH_STAR)
            Quaternionf().rotationY(atan2(normalX, normalZ).toFloat()) else null,
    ) {
        // A showcase's first stroke can join an action in progress. Later cycles start at its first point.
        var cursor = AuroraSkyCursor(profile.points,
            if (animation == null) profile.points * phaseOffsetTicks / profile.sceneDurationTicks else 0)
        val exitTicks = minOf(profile.skyOptions.exitTicks, profile.sceneDurationTicks / 4)
        val rejected = AtomicBoolean()
        val culled = AtomicBoolean()
        var surfaceY: Int? = null // Resolved once on Main; x/y-offset/z never follow a viewer.
        var terrainAnchor: AuroraSurface? = null
        val terrainColumns = if (cell.terrain) mutableMapOf<Pair<Int, Int>, TerrainColumn>() else null
        val riverWaterline get() = if (profile.shape == AuroraShape.PAPER_FLOTILLA) terrainAnchor?.blockY else null
        val columns = when {
            fixedFacing != null -> fixedStoryColumns(profile, x, y, z, checkNotNull(animation).size, fixedFacing)
            storyBounds != null -> storyColumns(x, y, z, storyBounds, checkNotNull(animation).size)
            else -> skyColumns(x, y, z, normalX, normalZ, path, motion,
                profile.skyPalette.map { it.particle }.ifEmpty { profile.skyHeadParticles.ifEmpty { listOf(profile.particle) } }, animation,
                if (profile.shape.isMiniature) AuroraSkyMiniatures.partRadius(profile.shape) * checkNotNull(animation).size
                else profile.skyModel?.let { 2.0 * it.scale }, terrain = cell.terrain)
        }
        @Volatile var startsAt = Long.MAX_VALUE // Main publishes birth after the full envelope passes.
        var checkedColumns = 0
        var nextAt = born
        var refreshCursor = 0
        var refreshCredit = 0
        var repairIndex = -1
        var repairAt = Long.MIN_VALUE
        var animationCursor = 0
        var storyPose: List<AuroraLanternDisplays.Part>? = null // One complete phase while a small field budget sends its slices.
        var lastStoryPoseAt = Long.MIN_VALUE
        var modelCycle = -1L
        var renderedModels = false // The latest Main submission has a nonzero-size member and has not been retired.
    }
    private enum class Delivery { SUBMITTED, SKIPPED, OBSTRUCTED }
    private data class Input(
        val viewer: Viewer, val world: UUID, val x: Double, val y: Double, val z: Double,
        val yaw: Float, val selection: Selection, val age: Long,
        val revision: Long, val pass: Long, val previewDrain: PreviewDrain?, val showcase: AuroraShowcase?,
    ) {
        val profiles get() = selection.profiles
    }
    private data class Emission(
        val input: Input, val profile: AuroraProfile, val x: Double, val y: Double, val z: Double,
        val particle: Particle = profile.particle, val particleData: Any? = profile.particleData,
        val support: AuroraSurface? = null,
    )
    private data class SkyInput(
        val input: Input, val scenes: List<SkyScene>, val budget: Int, val showcase: ShowcaseAdmission? = null,
    )
    private class ShowcaseAdmission(scenes: List<SkyScene>) {
        class Clearance(group: List<SkyScene>) {
            val bottom = group.minOf { scene -> scene.columns.minOf { it.low } }
            val top = group.maxOf { scene -> scene.columns.maxOf { it.high } }
            var requiredLift = Int.MIN_VALUE
        }
        // Rain sweeps independent columns around the player. The other showcases form one fixed panel.
        private val clearances = if (scenes.first().profile.shape == AuroraShape.FROG_RAIN)
            scenes.map { Clearance(listOf(it)) } else listOf(Clearance(scenes))
        fun clearance(slot: Int) = clearances[if (clearances.size == 1) 0 else slot]
        var nextSlot = 0
    }
    private data class Planned(val emissions: List<Emission>, val sky: SkyInput? = null)
    private data class Batch(val age: Long, val emissions: List<Emission>, val skies: List<SkyInput>)
    private class ShowcaseViewer(val player: Player, val eye: Location)
    private class SharedShowcase(val panel: AuroraShowcase) {
        var audience: Map<UUID, ShowcaseViewer> = emptyMap()
        var recipientOrder: List<UUID> = emptyList()
        var nextRecipient = 0
        // Each subscription can cancel its own pending Netty writes without interrupting another show.
        val particles = mutableMapOf<UUID, PaperViewerParticles>()
        fun clearParticles() {
            particles.values.forEach { it.close() }
            particles.clear()
        }
    }
    private val showcases = mutableMapOf<UUID, SharedShowcase>()
    private val viewers = mutableMapOf<UUID, Viewer>()
    private val rotation = ArrayDeque<Viewer>()
    private val closed = AtomicBoolean()
    private var lanterns: AuroraLanternDisplays? = null
    private var retirement = CompletableFuture.completedFuture<Void>(null)
    private val planning = AtomicBoolean()
    private val completed = AtomicReference<Batch?>()
    private var age = 0L
    private var firstViewer = 0
    private var firstSkyViewer = 0
    // One immutable unit-size animation per configured shape/count, shared by all viewer scenes.
    // Only the lifecycle-owned single planning worker reads/writes this bounded cache.
    private val animationFrames = mutableMapOf<Pair<AuroraShape, Int>, List<List<AuroraCelestialPoint>>>()
    private val storyEnvelopes = mutableMapOf<Triple<AuroraShape, Int, Boolean>, StoryBounds>()
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
        showcases.remove(id)?.clearParticles()
        leaveShowcases(id)
        viewers.remove(id)?.particles?.close()
        lanterns?.removeViewer(id)
        // Stale queue entries retire during the bounded audience scan.
        viewerCount = viewers.size
    }

    fun invalidate(id: UUID) {
        showcases[id]?.clearParticles()
        viewers[id]?.let {
            it.sky?.scenes?.forEach { scene -> scene.renderedModels = false }
            it.revision++; it.sky = null; it.particles.invalidatePending()
            it.previewDrain = it.previewDrain?.copy(scenes = emptySet())
        }
        lanterns?.removeViewer(id)
    }

    fun startShowcase(player: Player, shape: AuroraShape? = null): Boolean {
        val viewer = viewers[player.uniqueId] ?: return false
        if (!player.hasPermission(AURORA_ADMIN_PERMISSION)) return false
        val profiles = if (shape == null) showcaseProfiles ?: return false else {
            val selected = showcaseEffects[shape] ?: return false
            // Each display group is submitted together. Keep it eligible even under a one-request cap.
            val profile = if (shape == AuroraShape.SKY_LANTERNS && selected.points > settings.skyPacketsPerViewer)
                selected.copy(points = settings.skyPacketsPerViewer) else selected
            List(AuroraShowcase.copyCount(shape)) { profile }
        }
        val duration = if (shape == null) AuroraShowcase.DURATION_TICKS else AuroraShowcase.SELECTED_DURATION_TICKS
        val eye = player.eyeLocation
        val panel = AuroraShowcase(player.world.uid, AuroraOffset(eye.x, eye.y, eye.z), eye.yaw,
            profiles, age + duration)
        showcases.put(viewer.id, SharedShowcase(panel))?.clearParticles()
        viewer.previewDrain = null
        viewer.selection = null
        viewer.due = age
        invalidate(viewer.id)
        return true
    }

    fun stopShowcase(id: UUID) {
        val viewer = viewers[id] ?: return
        val showcase = showcases.remove(id) ?: return
        showcase.clearParticles()
        viewer.selection = null
        viewer.due = age
        invalidate(id)
    }

    fun changeWorld(id: UUID) {
        if (id in showcases) stopShowcase(id) else invalidate(id)
        leaveShowcases(id)
    }

    private fun leaveShowcases(id: UUID) {
        for ((owner, show) in showcases) {
            if (id in show.audience) setAudience(owner, show, show.audience - id)
        }
    }

    private fun setAudience(owner: UUID, show: SharedShowcase, members: Map<UUID, ShowcaseViewer>) {
        if (members.keys != show.audience.keys) {
            for (id in show.audience.keys) if (id !in members) show.particles.remove(id)?.close()
            show.recipientOrder = members.keys.toList()
            if (show.nextRecipient >= members.size) show.nextRecipient = 0
            if (members.isEmpty()) viewers[owner]?.let(::retireModels)
            else lanterns?.setShowcaseAudience(owner, members.keys)
        }
        show.audience = members
    }

    /** The main thread snapshots membership once for all the points in this tick. */
    private fun refreshShowcaseAudiences() {
        if (showcases.isEmpty()) return
        for ((id, show) in showcases.toMap()) {
            val owner = Bukkit.getPlayer(id)
            if (owner == null || !owner.isOnline || owner.world.uid != show.panel.world ||
                age >= show.panel.expiresAt || !owner.hasPermission(AURORA_ADMIN_PERMISSION)) stopShowcase(id)
        }
        if (showcases.isEmpty()) return
        val audiences = showcases.mapValues { mutableMapOf<UUID, ShowcaseViewer>() }
        for (viewer in viewers.values) {
            val player = Bukkit.getPlayer(viewer.id) ?: continue
            if (!player.isOnline || player.isDead) continue
            val eye = player.eyeLocation
            val world = player.world.uid
            var recipient: ShowcaseViewer? = null
            for ((id, show) in showcases) {
                if (world != show.panel.world || !show.panel.contains(eye.x, eye.y, eye.z)) continue
                if (recipient == null) {
                    if (!pingAdmits(player, viewer)) break
                    recipient = ShowcaseViewer(player, eye)
                }
                audiences.getValue(id)[viewer.id] = recipient
            }
        }
        for ((owner, show) in showcases) setAudience(owner, show, audiences.getValue(owner))
    }

    /** Stop preview admission now; only these already-visible groups may finish their original lifetime. */
    fun beginPreviewDrain(id: UUID, profiles: List<AuroraProfile>): Long {
        if (closed.get() || previewProfiles(id) !== profiles) return 0L
        val viewer = viewers[id] ?: return 0L
        val scenes = viewer.sky?.takeIf { it.input.revision == viewer.revision && it.input.selection.profiles === profiles }
            ?.scenes.orEmpty().filter { scene ->
                scene.renderedModels && !scene.culled.get() && !scene.rejected.get() &&
                    scene.startsAt != Long.MAX_VALUE && age - scene.startsAt < scene.profile.sceneDurationTicks
            }.toSet()
        viewer.previewDrain = PreviewDrain(profiles, scenes)
        viewer.particles.invalidatePending()
        lanterns?.retain(id, scenes)
        return scenes.maxOfOrNull { it.startsAt + it.profile.sceneDurationTicks - age } ?: 0L
    }
    fun invalidateWorld(worldId: UUID) {
        viewers.values.filter { it.world == worldId && it.id !in showcases }.map { it.id }.forEach(::invalidate)
    }

    fun personallyEnabled(player: Player) = player.persistentDataContainer.get(disabledKey, PersistentDataType.BYTE) != 1.toByte()
    fun setPersonallyEnabled(player: Player, enabled: Boolean) {
        if (enabled) player.persistentDataContainer.remove(disabledKey)
        else player.persistentDataContainer.set(disabledKey, PersistentDataType.BYTE, 1.toByte())
        if (player.uniqueId !in showcases) invalidate(player.uniqueId)
    }

    /** Called exclusively by the owned one-tick Main timer. */
    fun tick() {
        if (closed.get()) return
        age++
        val deadline = System.nanoTime() + settings.nativeBudgetMicros * 1000L
        refreshShowcaseAudiences()
        completed.getAndSet(null)?.let { batch ->
            val fresh = age - batch.age <= 4L
            batch.skies.forEach { sky ->
                val viewer = sky.input.viewer
                if ((fresh || sky.showcase != null) && viewers[viewer.id] === viewer && viewer.revision == sky.input.revision) {
                    viewer.sky = sky
                }
            }
            for (emission in batch.emissions) {
                if (!fresh || System.nanoTime() >= deadline) break
                deliver(emission, deadline)
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
            val showcase = showcases[viewer.id]?.panel
            if (showcase != null && viewer.sky != null) return@forEach
            val at = player.location
            if (showcase == null && !player.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) return@forEach
            if (selection.profiles.isNotEmpty()) {
                inputs += Input(viewer, player.world.uid, at.x, at.y, at.z, at.yaw, selection, age, viewer.revision,
                    viewer.pass++, viewer.previewDrain, showcase)
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
        showcases[viewer.id]?.let { return Selection(Source.SHOWCASE, it.panel.profiles) }
        val preview = previewProfiles(viewer.id)
        if (viewer.previewDrain?.profiles !== preview) viewer.previewDrain = null
        val event = preview ?: eventProfiles(world.uid)
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
        // The owner drives the timeline even while out of range or unable to receive cosmetic packets.
        if (source == Source.SHOWCASE) return showcases[viewer.id]?.audience?.isNotEmpty() == true
        if (!player.isOnline || player.isDead) { retireModels(viewer); return false }
        viewer.world = player.world.uid
        if (!pingAdmits(player, viewer)) {
            viewer.particles.invalidatePending()
            retireModels(viewer)
            return false
        }
        if (source != Source.EVENT && (!personallyEnabled(player) ||
            source == Source.ORDINARY && !player.hasPermission(settings.permission))) {
            viewer.particles.invalidatePending()
            retireModels(viewer)
            return false
        }
        return true
    }

    private fun pingAdmits(player: Player, viewer: Viewer): Boolean {
        val current = player.ping
        if (age >= viewer.sampleAt) {
            viewer.ping.sample(current)
            viewer.sampleAt = age + settings.ping.sampleIntervalTicks
        }
        if (viewer.ping.admits(current, settings.ping)) return true
        pingSkipped++
        return false
    }

    private fun retireModels(viewer: Viewer) {
        lanterns?.removeViewer(viewer.id)
        viewer.sky?.scenes?.forEach { it.renderedModels = false }
        viewer.previewDrain = viewer.previewDrain?.copy(scenes = emptySet())
    }

    private fun matching(player: Player, at: Location, candidates: List<AuroraProfile>, aboveSurface: Boolean = false): List<AuroraProfile> {
        val world = player.world
        val block = world.getBlockAt(at.blockX, at.blockY.coerceIn(world.minHeight, world.maxHeight - 1), at.blockZ)
        val biome = settings.canonicalBiomeKey(block.biome.key.toString())
        val matches = candidates.filter { it.matches(biome, world.name, world.time, world.hasStorm(), world.isThundering,
            at.y, block.lightFromSky.toInt(), !aboveSurface && block.type == Material.WATER) }
        // An eligible terrain profile still needs physical support. Its selection leaves biome ambience available.
        val priorities = matches.groupingBy { it.shape.followsTerrain }.fold(Int.MIN_VALUE) { priority, profile ->
            maxOf(priority, profile.priority)
        }
        return matches.filter { it.priority == priorities[it.shape.followsTerrain] }
    }

    /** Pure asynchronous geometry. A sky path is prepared once, not regenerated per emitted point. */
    private fun plan(input: Input): Planned {
        input.showcase?.let { return Planned(emptyList(), planShowcase(input, it)) }
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
        if (input.previewDrain != null) return Planned(emptyList(), SkyInput(input, planSky(input, sky), settings.skyPacketsPerViewer))
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
            val player = Bukkit.getPlayer(viewer.id)
            if (player == null) { retireModels(viewer); continue }
            val selection = select(player, viewer)
            if (selection.source == Source.SHOWCASE && showcases.getValue(viewer.id).audience.isEmpty()) continue
            val sky = viewer.sky
            if (sky == null || player.world.uid != sky.input.world || viewer.revision != sky.input.revision ||
                !selection.sameAs(sky.input.selection) || !eligible(player, viewer, selection.source)) {
                retireModels(viewer)
                continue
            }
            val showcase = sky.showcase
            if (showcase != null && !admitShowcase(sky, showcase, player, deadline)) continue
            // A pre-expiry worker batch may arrive late; it cannot introduce another scene during drain.
            val scenes = viewer.previewDrain?.let { drain -> sky.scenes.filter { it in drain.scenes } } ?: sky.scenes
            lanterns?.retain(viewer.id, scenes.filter {
                !it.culled.get() && !it.rejected.get() && if (showcase == null)
                    it.startsAt == Long.MAX_VALUE || age - it.startsAt < it.profile.sceneDurationTicks
                else it.profile.shape.isStory || it.modelCycle == (age - it.startsAt) / it.profile.sceneDurationTicks
            }.toSet())
            var remaining = sky.budget
            if (scenes.isEmpty() || remaining == 0) continue
            val start = Math.floorMod(viewer.skyTurn++, scenes.size.toLong()).toInt()
            for (index in scenes.indices) {
                if (System.nanoTime() >= deadline) return
                if (remaining == 0) break
                val scene = scenes[(start + index) % scenes.size]
                val current = player.location
                val limit = settings.skyField.viewDistance + settings.skyField.cullHysteresis
                if (showcase == null && (scene.x - current.x).let { it * it } + (scene.z - current.z).let { it * it } > limit * limit) {
                    scene.culled.set(true) // Stop the whole figure; its existing native tail expires naturally.
                    lanterns?.retain(viewer.id, scenes.filter { !it.culled.get() && !it.rejected.get() }.toSet())
                }
                if (scene.culled.get() || scene.rejected.get() || !admitSky(scene, player, deadline) || age < scene.startsAt) continue
                val elapsed = age - scene.startsAt
                val sceneAge = if (showcase == null) elapsed else showcaseSceneAge(scene, elapsed)
                if (sceneAge >= scene.profile.sceneDurationTicks) continue
                val animation = scene.animation
                if (animation != null) {
                    if (age < scene.nextAt) continue
                    if (scene.profile.shape.isStory) {
                        remaining -= emitStory(sky, scene, player, sceneAge, remaining)
                        continue
                    }
                    if (scene.profile.shape.isModel) {
                        // No miniature part exists at birth. Leave the first budget slice for its visible pose.
                        if (scene.profile.shape.isMiniature && sceneAge == 0L) continue
                        val points = scene.profile.points
                        val firstPart = scene.animationCursor
                        val endPart = minOf(points, firstPart + remaining)
                        // Send a whole pose whenever the visit cap allows. Under smaller caps, keep all
                        // authored slots and advance through them across visits, sampling only the current age.
                        if (endPart < points && (!scene.profile.shape.isMiniature || sky.budget >= points)) continue
                        val progress = sceneAge.toDouble() / (scene.profile.sceneDurationTicks - 1)
                        val centers = List(points) { index ->
                            val point = animation.pose(AuroraCelestialShapes.pointAt(animation.frames, progress, index).offset)
                            Location(player.world, scene.x + point.x * scene.normalZ + point.z * scene.normalX,
                                scene.y + checkNotNull(scene.surfaceY) + point.y,
                                scene.z - point.x * scene.normalX + point.z * scene.normalZ)
                        }
                        if (centers.any { location ->
                            location.y < player.world.minHeight || location.y >= player.world.maxHeight ||
                                !player.world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4) ||
                                !location.block.isPassable || location.block.isLiquid ||
                                sky.input.selection.source == Source.ORDINARY && scene.profile !in matching(player, location, settings.profiles)
                        }) {
                            scene.rejected.set(true)
                            lanterns?.retain(viewer.id, scenes.filter { it !== scene && !it.rejected.get() && !it.culled.get() }.toSet())
                            continue
                        }
                        val displays = displaysFor(sky.input)
                        val yaw = atan2(scene.normalX, scene.normalZ)
                        if (scene.profile.shape.isMiniature) {
                            displays.updateMiniature(scene, player, scene.profile, firstPart, centers.subList(firstPart, endPart),
                                progress, yaw, animation.pitch, animation.size)
                            scene.animationCursor = endPart % points
                        } else {
                            displays.update(scene, player, checkNotNull(scene.profile.skyModel), centers, progress, yaw, scene.profile.updateTicks)
                        }
                        scene.renderedModels = progress > 0.0 && progress < 1.0
                        scene.modelCycle = elapsed / scene.profile.sceneDurationTicks
                        scene.nextAt = age + if (endPart == points) scene.profile.updateTicks else 1
                        remaining -= endPart - firstPart
                        continue
                    }
                    scene.nextAt = age + scene.profile.updateTicks
                    val progress = sceneAge.toDouble() / (scene.profile.sceneDurationTicks - 1)
                    // Sample the current pose only. Slow viewers drop obsolete poses, never replay them.
                    val terrain = scene.terrainColumns
                    var scanned = 0
                    var sampled = 0
                    while (scanned < scene.profile.points && sampled < scene.profile.skyTracers && remaining > 0) {
                        if (System.nanoTime() >= deadline) return
                        if (scene.rejected.get()) break
                        val index = scene.animationCursor++ % scene.profile.points
                        scanned++
                        val sample = AuroraCelestialShapes.pointAt(animation.frames, progress, index)
                        // Density fade; native particles retain their own alpha, motion and lifetime.
                        if (sample.opacity <= 0.0) continue
                        sampled++ // Faded samples consume their slot; refilling it would undo the density fade.
                        if ((index * 7L + sceneAge) % 16 >= sample.opacity * 16) continue
                        val point = animation.pose(sample.offset)
                        val palette = scene.profile.skyPalette
                        val style = palette.getOrNull(Math.floorMod(sample.paletteSlot, palette.size.coerceAtLeast(1)))
                        val x = scene.x + point.x * scene.normalZ + point.z * scene.normalX
                        val z = scene.z - point.x * scene.normalX + point.z * scene.normalZ
                        val support = if (terrain == null) null else {
                            val column = floor(x).toInt() to floor(z).toInt()
                            val saved = terrain[column]
                            if (saved == null || saved.verifiedAt != age && terrainSurfaceAt(player.world, scene.profile.shape,
                                    column.first, column.second, scene.riverWaterline, saved.clearance) != saved.support) {
                                scene.rejected.set(true)
                                break
                            }
                            saved.verifiedAt = age
                            saved.support
                        }
                        val y = scene.y + (support?.height ?: checkNotNull(scene.surfaceY).toDouble()) + point.y
                        remaining--
                        if (deliver(Emission(sky.input, scene.profile, x, y, z,
                            style?.particle ?: scene.profile.particle,
                            if (style == null) scene.profile.particleData else style.data, support), deadline) == Delivery.OBSTRUCTED) scene.rejected.set(true)
                    }
                    continue
                }
                val refresh = scene.profile.skyRefreshTicks
                val exitTicks = scene.exitTicks
                val exitAt = scene.profile.sceneDurationTicks - exitTicks
                val motion = scene.motion[minOf(sceneAge.toInt(), scene.motion.lastIndex)]
                fun emit(index: Int) {
                    val point = scene.path[index]
                    val offset = AuroraOffset(point.x + motion.x, point.y + motion.y, point.z + motion.z)
                    val palette = scene.profile.skyHeadParticles
                    remaining--
                    if (deliver(Emission(sky.input, scene.profile,
                        scene.x + offset.x * scene.normalZ + offset.z * scene.normalX,
                        scene.y + checkNotNull(scene.surfaceY) + offset.y,
                        scene.z - offset.x * scene.normalX + offset.z * scene.normalZ,
                        particle = if (palette.isEmpty()) scene.profile.particle else palette[index % 2],
                        particleData = if (palette.isEmpty()) scene.profile.particleData else null), deadline) == Delivery.OBSTRUCTED) {
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

    private fun emitStory(sky: SkyInput, scene: SkyScene, player: Player, sceneAge: Long, remaining: Int): Int {
        val profile = scene.profile
        val first = scene.animationCursor
        val end = minOf(first + remaining, profile.points)
        if (end < profile.points && sky.budget >= profile.points) return 0
        val anchor = Location(player.world, scene.x, scene.y + checkNotNull(scene.surfaceY), scene.z)
        val billboard = profile.shape.usesBillboard
        val packFace = profile.hasPackFace
        val modelIndex = when {
            profile.shape.isIllustration -> 0
            packFace -> AuroraJuFufu.packFaceIndex
            else -> -1
        }
        val worldFacing = scene.fixedFacing ?: when {
            profile.shape == AuroraShape.FROG_RAIN -> player.eyeLocation.let {
                Quaternionf().rotationY(atan2(it.x - anchor.x, it.z - anchor.z).toFloat())
            }
            sky.showcase != null || profile.shape == AuroraShape.INFERNAL_PENTAGRAM -> player.eyeLocation.let {
                AuroraShowcase.storyFacing(it.x - anchor.x, it.y - anchor.y, it.z - anchor.z)
            }
            else -> Quaternionf().rotationY(atan2(scene.normalX, scene.normalZ).toFloat())
        }
        val parts = scene.storyPose ?: run {
            // CENTER applies the client's camera orientation before translation: +X is screen-right,
            // +Y is up, and +Z faces the viewer. ItemDisplay's native half-turn applies to the mesh only.
            val rotation = if (billboard) Quaternionf() else worldFacing
            val size = checkNotNull(scene.animation).size.toFloat()
            val progress = sceneAge.toDouble() / (profile.sceneDurationTicks - 1)
            List(profile.points) { index ->
                val part = AuroraSkyMiniatures.part(profile.shape, index, progress, packFace)
                val center = part.center
                val translation = rotation.transform(Vector3f(center.x.toFloat(), center.y.toFloat(), center.z.toFloat())).mul(size)
                val model = profile.skyModel.takeIf { index == modelIndex }
                AuroraLanternDisplays.Part(model?.material ?: part.material,
                    Transformation(translation, Quaternionf(rotation).mul(part.rotation), Vector3f(part.scale).mul(size), Quaternionf()),
                    model?.customModelData)
            }
        }
        val spot = anchor.clone()
        val checkedOffset = if (billboard) Vector3f() else null
        for (index in first until end) {
            val transform = parts[index].transform
            if (transform.scale.lengthSquared() == 0f) continue
            // Billboards remain local on the wire. Use the owner's facing for world obstruction checks.
            val t = checkedOffset?.let { worldFacing.transform(transform.translation, it) } ?: transform.translation
            spot.x = anchor.x + t.x
            spot.y = anchor.y + t.y
            spot.z = anchor.z + t.z
            if (spot.y < player.world.minHeight || spot.y >= player.world.maxHeight ||
                !player.world.isChunkLoaded(spot.blockX shr 4, spot.blockZ shr 4) ||
                !spot.block.isPassable || spot.block.isLiquid ||
                sky.input.selection.source == Source.ORDINARY && profile !in matching(player, spot, settings.profiles)) {
                scene.rejected.set(true)
                scene.storyPose = null
                lanterns?.retain(player.uniqueId, sky.scenes.filter { !it.rejected.get() && !it.culled.get() }.toSet())
                return 0
            }
        }
        val cadence = if (sky.showcase == null) profile.updateTicks else maxOf(profile.updateTicks,
            Math.ceilDiv(sky.scenes.size, AuroraShowcase.STORY_COPIES_PER_VISIT))
        val interpolation = if (sky.showcase == null || scene.lastStoryPoseAt == Long.MIN_VALUE) cadence
            else maxOf(cadence, (age - scene.lastStoryPoseAt).coerceAtMost(59).toInt())
        val displays = displaysFor(sky.input)
        scene.renderedModels = displays.updateStory(scene, player, profile, anchor, parts, first, end, interpolation)
        scene.lastStoryPoseAt = age
        scene.animationCursor = end % profile.points
        scene.storyPose = if (end < profile.points) parts else null
        scene.nextAt = age + if (end < profile.points) 1 else cadence
        return end - first
    }

    private fun displaysFor(input: Input): AuroraLanternDisplays {
        val displays = lanterns ?: lanternFactory().also { lanterns = it }
        if (input.showcase != null) displays.setShowcaseAudience(input.viewer.id, showcases.getValue(input.viewer.id).audience.keys)
        return displays
    }

    /** Loaded heightmap checks cover the whole swept footprint before any point is sent. */
    private fun admitSky(scene: SkyScene, player: Player, deadline: Long): Boolean {
        if (scene.startsAt != Long.MAX_VALUE) return true
        val world = player.world
        val terrain = scene.terrainColumns
        if (scene.surfaceY == null && scene.terrainAnchor == null) {
            val x = floor(scene.x).toInt()
            val z = floor(scene.z).toInt()
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
            if (terrain == null) {
                scene.surfaceY = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE)
            } else {
                scene.terrainAnchor = terrainSurfaceAt(world, scene.profile.shape, x, z)
                if (scene.terrainAnchor == null) {
                    scene.rejected.set(true)
                    return false
                }
            }
        }
        repeat(24) {
            if (scene.checkedColumns >= scene.columns.size) {
                scene.startsAt = maxOf(scene.born, age)
                scene.nextAt = scene.startsAt
                return true
            }
            if (System.nanoTime() >= deadline) return false
            val column = scene.columns[scene.checkedColumns]
            if (!world.isChunkLoaded(column.x shr 4, column.z shr 4)) return false
            if (terrain != null) {
                val support = terrainSurfaceAt(world, scene.profile.shape, column.x, column.z, scene.riverWaterline, column.high)
                val maxStep = if (scene.profile.shape == AuroraShape.INVISIBLE_HARE) 0.25 else 0.0
                if (support == null || abs(support.height - checkNotNull(scene.terrainAnchor).height) > maxStep ||
                    column.low < 0.0 || column.high + support.height >= world.maxHeight ||
                    column.high + support.height > scene.profile.maxY ||
                    scene.profile !in matching(player, Location(world, column.x + 0.5, column.low + support.height, column.z + 0.5),
                        settings.profiles, aboveSurface = true)) {
                    scene.rejected.set(true)
                    return false
                }
                terrain[column.x to column.z] = TerrainColumn(support, column.high)
            } else {
                val surfaceY = checkNotNull(scene.surfaceY)
                if (column.low + surfaceY < world.minHeight || column.high + surfaceY >= world.maxHeight ||
                    column.low + surfaceY <= world.getHighestBlockYAt(column.x, column.z, HeightMap.WORLD_SURFACE) + scene.profile.skyOptions.terrainClearance) {
                    scene.rejected.set(true)
                    return false
                }
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

    private fun planShowcase(input: Input, showcase: AuroraShowcase): SkyInput {
        val scenes = showcase.profiles.mapIndexed { index, profile ->
            val pose = showcase.pose(index)
            val animation = if (profile.shape.isCelestial || profile.shape.isModel)
                SkyAnimation(celestialFrames(profile), pose.size, 0.0, false, pose.pitch) else null
            val path = animation?.frames?.first()?.map { animation.pose(it.offset) }
                ?: showcaseContour(profile.shape, pose.size, profile.points, pose.viewerLocalSlot)
                ?: AuroraSkyShapes.stroke(profile.shape, pose.size, profile.points, profile.updateTicks,
                    profile.motionPeriodTicks, pose.phaseOffsetTicks % profile.motionPeriodTicks,
                    0.0, false, profile.skyTracers, profile.skyOptions)
            val motion = if (animation == null && profile.skyRefreshTicks > 0)
                AuroraSkyShapes.heldMotion(pose.size, profile.sceneDurationTicks, profile.motionPeriodTicks, profile.skyOptions)
                else listOf(AuroraOffset(0.0, 0.0, 0.0))
            val fixedFacing = when (profile.shape) {
                AuroraShape.DEATH_STAR, AuroraShape.BLINKING_EYE -> showcase.facingAtStart(pose)
                AuroraShape.ENDLESS_TENTACLES -> showcase.facingAtStart(pose, upright = true).rotateY((index % 3 - 1) * 0.20f)
                else -> null
            }
            SkyScene(profile, input.world, input.revision, input.age,
                AuroraSkyCell(index, 0), 0L, pose.anchor.x, pose.anchor.y, pose.anchor.z,
                showcase.normalX, showcase.normalZ, path, motion, animation, pose.phaseOffsetTicks,
                storyBounds = if (fixedFacing == null) storyBounds(profile) else null, fixedFacing = fixedFacing)
        }
        val first = scenes.first().profile
        val budget = if (first.shape.isStory) first.points * AuroraShowcase.STORY_COPIES_PER_VISIT else settings.skyPacketsPerViewer
        return SkyInput(input, scenes, budget, ShowcaseAdmission(scenes))
    }

    /** All integer ages can be submitted. Rain rotates only about Y, so its fall height need not widen the checked footprint. */
    private fun storyBounds(profile: AuroraProfile): StoryBounds? {
        if (!profile.shape.isStory || profile.shape == AuroraShape.DEATH_STAR) return null
        val packFace = profile.hasPackFace
        return storyEnvelopes.getOrPut(Triple(profile.shape, profile.sceneDurationTicks, packFace)) {
            // Camera-facing scenes use the same spherical envelope as other freely rotating stories.
            val upright = profile.shape == AuroraShape.FROG_RAIN
            var radius = 0.0
            var low = Double.POSITIVE_INFINITY
            var high = Double.NEGATIVE_INFINITY
            for (tick in 0 until profile.sceneDurationTicks) {
                val progress = tick.toDouble() / (profile.sceneDurationTicks - 1)
                for (index in 0 until profile.points) {
                    val part = AuroraSkyMiniatures.part(profile.shape, index, progress, packFace)
                    if (part.scale.lengthSquared() == 0f) continue
                    val p = part.center
                    val mesh = part.meshRadius()
                    val centerRadius = if (upright) hypot(p.x, p.z) else sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
                    radius = maxOf(radius, centerRadius + mesh)
                    if (upright) {
                        low = minOf(low, p.y - mesh)
                        high = maxOf(high, p.y + mesh)
                    }
                }
            }
            StoryBounds(radius + 0.01, if (upright) (low - 0.01)..(high + 0.01) else null)
        }
    }

    private fun showcaseSceneAge(scene: SkyScene, elapsed: Long): Long {
        val profile = scene.profile
        val duration = profile.sceneDurationTicks
        if (scene.animation != null) return elapsed % duration
        val exitTicks = if (profile.skyRefreshTicks == 0) 0 else scene.exitTicks
        val lastRevealTick = (duration - exitTicks - 1).toLong()
        // A low shared cap can take more than one nominal lifetime to reveal the whole stroke.
        if (elapsed > lastRevealTick && scene.cursor.position < profile.points) {
            scene.startsAt = age - lastRevealTick
            return lastRevealTick
        }
        if (elapsed < duration) return elapsed
        scene.cursor = AuroraSkyCursor(profile.points)
        scene.refreshCursor = 0
        scene.refreshCredit = 0
        scene.repairIndex = -1
        scene.nextAt = age
        scene.startsAt = age
        return 0L
    }

    /** Panels rise together. Rain stays near the ground in clear sectors when another sector crosses a treetop. */
    private fun admitShowcase(sky: SkyInput, panel: ShowcaseAdmission, player: Player, deadline: Long): Boolean {
        if (panel.nextSlot == sky.scenes.size) return true
        val world = player.world
        // Wider scenes can use more of the same bounded main-thread deadline.
        val columnBudget = if (sky.scenes.first().profile.shape == AuroraShape.ENDLESS_TENTACLES) 256 else 24
        repeat(columnBudget) {
            val scene = sky.scenes.getOrNull(panel.nextSlot) ?: return@repeat
            val clearance = panel.clearance(panel.nextSlot)
            if (System.nanoTime() >= deadline) return false
            val column = scene.columns[scene.checkedColumns]
            if (!world.isChunkLoaded(column.x shr 4, column.z shr 4)) return false
            val terrain = world.getHighestBlockYAt(column.x, column.z, HeightMap.WORLD_SURFACE)
            val neededLift = floor(terrain + scene.profile.skyOptions.terrainClearance - column.low).toInt() + 1
            clearance.requiredLift = maxOf(clearance.requiredLift, neededLift, ceil(world.minHeight - clearance.bottom).toInt())
            if (clearance.requiredLift > ceil(world.maxHeight - clearance.top).toInt() - 1) {
                stopShowcase(player.uniqueId)
                onShowcaseBlocked(player)
                return false
            }
            scene.checkedColumns++
            if (scene.checkedColumns == scene.columns.size) panel.nextSlot++
        }
        if (panel.nextSlot != sky.scenes.size) return false
        for ((index, scene) in sky.scenes.withIndex()) {
            val clearance = panel.clearance(index)
            scene.surfaceY = 0.coerceIn(clearance.requiredLift, ceil(world.maxHeight - clearance.top).toInt() - 1)
            scene.startsAt = age - Math.floorMod(scene.phaseOffsetTicks, scene.profile.sceneDurationTicks)
            scene.nextAt = age
        }
        return true
    }

    private fun planSky(input: Input, profiles: List<AuroraProfile>): List<SkyScene> {
        val field = settings.skyField
        val candidates = AuroraWorldField.candidates(input.world, profiles, input.x, input.z, input.age, field,
            terrainScenes = input.selection.source == Source.ORDINARY)
        val byCell = candidates.associateBy { it.cell }
        val active = ArrayList<SkyScene>()
        val scenes = input.viewer.skyScenes
        val rejected = input.viewer.rejectedCells
        val retired = input.viewer.retiredCells
        retired.entries.removeIf { it.value <= input.age }
        rejected.entries.removeIf { (cell, epoch) -> byCell[cell]?.epoch != epoch }
        for ((cell, scene) in scenes.toMap()) {
            val keep = (input.previewDrain == null || scene in input.previewDrain.scenes) &&
                (scene.x - input.x).let { it * it } + (scene.z - input.z).let { it * it } <=
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
        if (input.previewDrain != null) return active
        // Preserve admitted scenes across cell/epoch boundaries; fill vacancies without nearest-slot churn.
        for (candidate in candidates) {
            if (input.age < input.viewer.fieldAdmissionAfter || active.size >= field.maxScenes) break
            if (scenes.containsKey(candidate.cell) || retired.containsKey(candidate.cell) || rejected[candidate.cell] == candidate.epoch) continue
            if (candidate.profile.shape.isModel && active.count { it.profile.shape.isModel } >= field.maxLanternScenes) continue
            if (candidate.profile.shape != AuroraShape.SKY_LANTERNS &&
                active.count { it.profile === candidate.profile } >= field.maxPerProfile) continue
            if (candidate.profile.shape.isCelestial &&
                active.count { it.profile.shape.isCelestial } >= field.maxCelestialScenes) continue
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
        if (profile.shape.isCelestial || profile.shape.isModel) {
            val frames = celestialFrames(profile)
            val animation = if (profile.shape.isModel) SkyAnimation(frames, size, 0.0, false)
                else SkyAnimation(frames, size, if (candidate.cell.terrain) 0.0 else (random.nextDouble() * 2.0 - 1.0) * options.rollRadians,
                    random.nextBoolean())
            return SkyScene(profile, input.world, input.revision, input.age + delay,
                candidate.cell, candidate.epoch, candidate.x, height, candidate.z, sin(angle), -cos(angle),
                frames.first().map { animation.pose(it.offset) }, listOf(AuroraOffset(0.0, 0.0, 0.0)), animation,
                storyBounds = storyBounds(profile))
        }
        val path = AuroraSkyShapes.stroke(profile.shape, size, profile.points, profile.updateTicks,
            profile.motionPeriodTicks, random.nextInt(profile.motionPeriodTicks),
            (random.nextDouble() * 2.0 - 1.0) * options.rollRadians, random.nextBoolean(), profile.skyTracers, options)
        return SkyScene(profile, input.world, input.revision, input.age + delay,
            candidate.cell, candidate.epoch, candidate.x, height, candidate.z, sin(angle), -cos(angle), path,
            if (profile.skyRefreshTicks > 0) AuroraSkyShapes.heldMotion(size, profile.sceneDurationTicks, profile.motionPeriodTicks, options)
            else listOf(AuroraOffset(0.0, 0.0, 0.0)))
    }

    private fun celestialFrames(profile: AuroraProfile): List<List<AuroraCelestialPoint>> =
        animationFrames.getOrPut(profile.shape to profile.points) {
            when {
                profile.shape == AuroraShape.SKY_LANTERNS -> AuroraLanternMotion.frames(profile.points)
                profile.shape.isMiniature -> AuroraSkyMiniatures.frames(profile.shape)
                else -> AuroraCelestialShapes.frames(profile.shape, 1.0, profile.points)
            }
        }

    private fun deliver(emission: Emission, deadline: Long): Delivery {
        val input = emission.input
        val viewer = input.viewer
        if (viewers[viewer.id] !== viewer || viewer.revision != input.revision) return Delivery.SKIPPED
        val player = Bukkit.getPlayer(viewer.id) ?: return Delivery.SKIPPED
        if (player.world.uid != input.world) { viewer.particles.invalidatePending(); return Delivery.SKIPPED }
        val selection = select(player, viewer)
        if (!selection.sameAs(input.selection) || viewer.revision != input.revision) return Delivery.SKIPPED
        if (viewer.previewDrain != null) return Delivery.SKIPPED
        if (!eligible(player, viewer, selection.source)) return Delivery.SKIPPED
        if (!emission.profile.skyScene && player.location.distanceSquared(Location(player.world, input.x, input.y, input.z)) > 16.0)
            return Delivery.SKIPPED
        val world = player.world
        if (emission.y < world.minHeight || emission.y >= world.maxHeight) return Delivery.OBSTRUCTED
        val location = Location(world, emission.x, emission.y, emission.z)
        // Sky min-distance selects its anchor, not a sphere that would cut off the near half of a large figure.
        val eyeClearance = if (emission.profile.skyScene) 2.0 else emission.profile.minDistance
        if (input.showcase == null && location.distanceSquared(player.eyeLocation) < eyeClearance * eyeClearance) return Delivery.OBSTRUCTED
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return Delivery.OBSTRUCTED
        val block = location.block
        if (emission.support != null && location.y < emission.support.height) return Delivery.OBSTRUCTED
        val aboveSupport = emission.support?.blockY == location.blockY
        if (!aboveSupport && (!block.isPassable || block.isLiquid && !(emission.profile.underwater && block.type == Material.WATER)))
            return Delivery.OBSTRUCTED
        if (input.selection.source == Source.ORDINARY && emission.profile !in matching(player, location, settings.profiles, aboveSupport))
            return Delivery.OBSTRUCTED
        val p = emission.profile
        fun submit(emitter: PaperViewerParticles, recipient: Player) {
            emitter.emit(recipient, location, emission.particle, p.count, p.offsetX, p.offsetY, p.offsetZ, p.speed, emission.particleData, p.skyScene)
            submittedPackets++
        }
        if (input.showcase == null) {
            submit(viewer.particles, player)
        } else {
            val show = showcases.getValue(viewer.id)
            // A crowded show may only reach part of its audience in this native slice. The
            // next fresh point starts with the next viewer; obsolete particles are never queued.
            repeat(show.recipientOrder.size) {
                if (System.nanoTime() >= deadline) return Delivery.SUBMITTED
                val id = show.recipientOrder[show.nextRecipient]
                show.nextRecipient = (show.nextRecipient + 1) % show.recipientOrder.size
                val recipient = show.audience.getValue(id)
                if (location.distanceSquared(recipient.eye) >= eyeClearance * eyeClearance) {
                    submit(show.particles.getOrPut(id, emitterFactory), recipient.player)
                }
            }
        }
        return Delivery.SUBMITTED
    }

    fun closeAsync(): CompletableFuture<Void> {
        if (!closed.compareAndSet(false, true)) return retirement
        completed.set(null)
        showcases.values.forEach { it.clearParticles() }
        showcases.clear()
        viewers.values.forEach { it.particles.close() }
        viewers.clear(); rotation.clear(); viewerCount = 0
        retirement = lanterns?.closeAsync() ?: CompletableFuture.completedFuture(null)
        return retirement
    }
    override fun close() { closeAsync() }
}

/** Worker plans are detached; only points actually attempted by Main advance the next plan. */
internal class AuroraSkyCursor(private val points: Int, initialPosition: Int = 0) {
    private val acknowledged = AtomicInteger(initialPosition)

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

private val AuroraProfile.hasPackFace: Boolean get() = shape == AuroraShape.JU_FUFU && skyModel != null

private data class StoryBounds(val radius: Double, val vertical: ClosedFloatingPointRange<Double>?)

/** Fixed showcase facing lets the full discharge sweep a narrow corridor through the world. */
private fun fixedStoryColumns(
    profile: AuroraProfile, x: Double, y: Double, z: Double, size: Double, facing: Quaternionf,
): List<SkyColumn> {
    val columns = linkedMapOf<Pair<Int, Int>, SkyColumn>()
    val scale = size.toFloat()
    for (index in 0 until profile.points) {
        var basis: Quaternionf? = null
        val inverse = Quaternionf()
        val low = Vector3f(Float.POSITIVE_INFINITY)
        val high = Vector3f(Float.NEGATIVE_INFINITY)
        val sphereLow = Vector3f(Float.POSITIVE_INFINITY)
        val sphereHigh = Vector3f(Float.NEGATIVE_INFINITY)
        var sameRotation = true
        for (tick in 0 until profile.sceneDurationTicks) {
            val part = AuroraSkyMiniatures.part(profile.shape, index, tick.toDouble() / (profile.sceneDurationTicks - 1))
            if (part.scale.lengthSquared() == 0f) continue
            val reference = basis ?: Quaternionf(part.rotation).also {
                basis = it
                inverse.set(it).conjugate()
            }
            val p = part.center
            val position = Vector3f(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
            val half = Vector3f(part.scale).mul(0.5f)
            if (part.generatedSprite) half.z /= 16f
            val radius = half.length()
            val worldCenter = facing.transform(Vector3f(position))
            sphereLow.min(Vector3f(worldCenter).sub(radius, radius, radius))
            sphereHigh.max(Vector3f(worldCenter).add(radius, radius, radius))
            sameRotation = sameRotation && reference == part.rotation
            if (sameRotation) {
                val center = inverse.transform(position)
                low.min(Vector3f(center).sub(half))
                high.max(Vector3f(center).add(half))
            }
        }
        val reference = basis ?: continue
        // A rotating item's sphere covers every client interpolation. A fixed item's faces
        // move linearly with its center and dimensions, so the pooled intervals suffice.
        val worldRotation = if (sameRotation) Quaternionf(facing).mul(reference) else {
            low.set(sphereLow)
            high.set(sphereHigh)
            Quaternionf()
        }
        low.sub(0.01f, 0.01f, 0.01f)
        high.add(0.01f, 0.01f, 0.01f)
        // The beam stretches along local X. Subdivide the pooled interval only after sweeping
        // every age, so extending it diagonally does not fill the unused corners of a large box.
        val slices = if (sameRotation) maxOf(1, ceil((high.x - low.x) * scale / 2.0).toInt()) else 1
        val step = (high.x - low.x) / slices
        val halfX = worldRotation.transform(Vector3f(step * scale * 0.5f, 0f, 0f)).absolute()
        val halfY = worldRotation.transform(Vector3f(0f, (high.y - low.y) * scale * 0.5f, 0f)).absolute()
        val halfZ = worldRotation.transform(Vector3f(0f, 0f, (high.z - low.z) * scale * 0.5f)).absolute()
        val extent = halfX.add(halfY).add(halfZ)
        for (slice in 0 until slices) {
            val center = worldRotation.transform(Vector3f(low.x + (slice + 0.5f) * step,
                (low.y + high.y) * 0.5f, (low.z + high.z) * 0.5f)).mul(scale)
            val bottom = y + center.y - extent.y
            val top = y + center.y + extent.y
            for (cx in floor(x + center.x - extent.x).toInt()..floor(x + center.x + extent.x).toInt()) {
                for (cz in floor(z + center.z - extent.z).toInt()..floor(z + center.z + extent.z).toInt()) {
                    val key = cx to cz
                    val previous = columns[key]
                    columns[key] = SkyColumn(cx, cz, minOf(bottom, previous?.low ?: bottom),
                        maxOf(top, previous?.high ?: top))
                }
            }
        }
    }
    return columns.values.toList()
}

private fun storyColumns(x: Double, y: Double, z: Double, bounds: StoryBounds, size: Double): List<SkyColumn> = buildList {
    val radius = bounds.radius * size
    for (cx in floor(x - radius).toInt()..floor(x + radius).toInt()) {
        val dx = maxOf(0.0, cx - x, x - (cx + 1))
        for (cz in floor(z - radius).toInt()..floor(z + radius).toInt()) {
            val dz = maxOf(0.0, cz - z, z - (cz + 1))
            val square = radius * radius - dx * dx - dz * dz
            if (square >= 0.0) {
                val vertical = bounds.vertical
                if (vertical == null) {
                    val dy = sqrt(square)
                    add(SkyColumn(cx, cz, y - dy, y + dy))
                } else add(SkyColumn(cx, cz, y + vertical.start * size, y + vertical.endInclusive * size))
            }
        }
    }
}

private class SkyAnimation(
    val frames: List<List<AuroraCelestialPoint>>, val size: Double, roll: Double, val reverse: Boolean, val pitch: Double = 0.0,
) {
    private val rollCos = cos(roll)
    private val rollSin = sin(roll)
    private val pitchCos = cos(pitch)
    private val pitchSin = sin(pitch)

    fun pose(offset: AuroraOffset): AuroraOffset {
        val x = if (reverse) -offset.x else offset.x
        val y = x * rollSin + offset.y * rollCos
        return AuroraOffset((x * rollCos - offset.y * rollSin) * size,
            (y * pitchCos - offset.z * pitchSin) * size, (y * pitchSin + offset.z * pitchCos) * size)
    }
}

/** Detached conservative envelope, including full native fall and sprite half-size for bundled types. */
private fun skyColumns(
    x: Double, y: Double, z: Double, normalX: Double, normalZ: Double,
    path: List<AuroraOffset>, motion: List<AuroraOffset>, particles: List<Particle>,
    animation: SkyAnimation? = null, modelRadius: Double? = null, terrain: Boolean = false,
): List<SkyColumn> {
    val flame = particles.any { it == Particle.FLAME || it == Particle.SOUL_FIRE_FLAME || it == Particle.COPPER_FIRE_FLAME }
    val padding = modelRadius ?: if (flame) 0.5 else 0.22 // Flame also has native spawn jitter and a small random drift.
    val fall = if (modelRadius != null) 0.0 else particles.maxOf { particle -> when (particle) {
        Particle.FLAME, Particle.SOUL_FIRE_FLAME, Particle.COPPER_FIRE_FLAME -> 0.0
        Particle.END_ROD -> 0.35
        Particle.FIREWORK -> 2.2
        Particle.TOTEM_OF_UNDYING -> 8.7
        else -> 1.0
    } }
    if (animation != null) {
        val columns = linkedMapOf<Pair<Int, Int>, SkyColumn>()
        fun cover(minX: Double, maxX: Double, minZ: Double, maxZ: Double, low: Double, high: Double) {
            for (cx in floor(minX - padding).toInt()..floor(maxX + padding).toInt()) {
                for (cz in floor(minZ - padding).toInt()..floor(maxZ + padding).toInt()) {
                    val previous = columns[cx to cz]
                    columns[cx to cz] = SkyColumn(cx, cz, minOf(low, previous?.low ?: low), maxOf(high, previous?.high ?: high))
                }
            }
        }
        for (index in path.indices) {
            val poses = animation.frames.map { animation.pose(it[index].offset) }
            val worldX = poses.map { x + it.x * normalZ + it.z * normalX }
            val worldZ = poses.map { z - it.x * normalX + it.z * normalZ }
            if (terrain) {
                // Ground contact is intentional. Sweep visible pose intervals without filling the corners of diagonal routes.
                for (after in 1..poses.lastIndex) {
                    val before = after - 1
                    if (animation.frames[before][index].opacity <= 0.0 && animation.frames[after][index].opacity <= 0.0) continue
                    cover(minOf(worldX[before], worldX[after]), maxOf(worldX[before], worldX[after]),
                        minOf(worldZ[before], worldZ[after]), maxOf(worldZ[before], worldZ[after]),
                        y + minOf(poses[before].y, poses[after].y), y + maxOf(poses[before].y, poses[after].y) + padding)
                }
            } else {
                // The airborne envelope includes all interpolated poses and native particle fall.
                cover(worldX.min(), worldX.max(), worldZ.min(), worldZ.max(),
                    y + poses.minOf { it.y } - fall - padding, y + poses.maxOf { it.y } + padding)
            }
        }
        return columns.values.toList()
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
