package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

/** Short-lived shapes; core owns packet transport, received chunks and connection cleanup. */
internal class StaffSpellDisplayEffects(private val displays: PaperPacketDisplays) : AutoCloseable {
    private data class Scene(
        val id: UUID,
        val casterId: UUID,
        val spell: StaffSpell,
        var origin: Location,
        var rotation: Quaternionf,
        var length: Double,
        var radius: Double,
        var duration: Int,
        var impact: Boolean,
        val secondary: Boolean,
        val input: StaffCastInput,
        val charging: Boolean = false,
        var controllerDriven: Boolean = false,
        var controllerDirty: Boolean = false,
        var chargeProgress: Double = 0.0,
        var age: Int = 0,
        var priorStageTicks: Int = 0,
        var transitionFrom: List<StaffDisplayPart>? = null,
        var renderedParts: List<StaffDisplayPart> = emptyList(),
        var trail: List<Vector3f>? = null,
        var bounds: List<Pair<org.bukkit.util.Vector, Double>> = emptyList(),
        var previousBounds: List<Pair<org.bukkit.util.Vector, Double>> = emptyList(),
        val handles: MutableList<PacketBlockDisplay> = mutableListOf(),
        val viewers: MutableMap<UUID, Player> = mutableMapOf(),
    )

    private val tasks = LifecycleTaskScope()
    private val scenes = linkedMapOf<UUID, Scene>()
    private val blocks = mutableMapOf<Material, BlockData>()
    private var closed = false

    private var visibilityTick = 0
    init { tasks.runTimer(1, 1) {
        if (++visibilityTick % FRAME_TICKS == 0) tick() else if (scenes.isNotEmpty()) refreshAudience()
    } }

    fun play(casterId: UUID, spell: StaffSpell, from: Location, to: Location = from,
        radius: Double = 1.0, durationTicks: Int = 16, impact: Boolean = false, secondary: Boolean = false,
        input: StaffCastInput = StaffCastInput.of(secondary, false), charging: Boolean = false,
        controllerDriven: Boolean = false, charge: Double = 0.0): UUID? {
        check(Bukkit.isPrimaryThread())
        if (closed || !valid(from) || !valid(to) || from.world != to.world) return null
        val caster = Bukkit.getPlayer(casterId) ?: return null
        if (!eligible(caster, from)) return null
        // ponytail: bounded oldest-first visual eviction; combat admission never depends on this pool.
        while (scenes.values.count { it.casterId == casterId } >= MAX_PER_CASTER)
            remove(scenes.values.first { it.casterId == casterId }.id)
        while (scenes.size >= MAX_SCENES) remove(scenes.keys.first())
        val offset = to.toVector().subtract(from.toVector())
        val length = offset.length()
        val rotation = if (charging) Quaternionf().rotationY(Math.toRadians(from.yaw.toDouble()).toFloat() * -1f)
        else if (spell == StaffSpell.MARK)
            Quaternionf().rotationY(kotlin.math.atan2(caster.location.x - from.x, caster.location.z - from.z).toFloat())
        else if (spell == StaffSpell.GALE)
            staffYawOnlyOrientation(Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()))
        else if (spell == StaffSpell.LANCE)
            staffLanceOrientation(Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()))
        else if (length > 0.001)
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).normalize())
        else Quaternionf()
        val scene = Scene(UUID.randomUUID(), casterId, spell, from.clone(), rotation,
            length.coerceAtLeast(0.1),
            radius.takeIf(Double::isFinite)?.coerceIn(0.1, if (spell == StaffSpell.FROST) 96.0 else 16.0) ?: 1.0,
            durationTicks.coerceIn(1, 240), impact, secondary, input, charging = charging,
            controllerDriven = controllerDriven,
            chargeProgress = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0)
        scenes[scene.id] = scene
        if (spell == StaffSpell.CHAIN) scene.trail = listOf(Vector3f())
        render(scene)
        refreshAudience()
        return scene.id
    }

    fun charge(sceneId: UUID?, eye: Location, progress: Double) {
        val scene = scenes[sceneId] ?: return
        scene.origin = eye.clone()
        scene.rotation = Quaternionf().rotationY(-Math.toRadians(eye.yaw.toDouble()).toFloat())
        scene.chargeProgress = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        if (scene.controllerDriven) scene.controllerDirty = true
    }

    /** Applies the controller's animation clock without letting the display timer advance it again. */
    fun synchronize(sceneId: UUID?, ageTicks: Int) {
        val scene = scenes[sceneId] ?: return
        if (!scene.controllerDriven) return
        val age = ageTicks.coerceAtLeast(0)
        if (scene.age == age && !scene.controllerDirty) return
        scene.age = age
        scene.controllerDirty = false
        render(scene)
    }

    fun move(sceneId: UUID?, at: Location) {
        val scene = scenes[sceneId] ?: return
        if (!valid(at) || at.world != scene.origin.world) { remove(sceneId); return }
        scene.origin = at.clone()
        if (scene.controllerDriven) scene.controllerDirty = true
        if (scene.spell == StaffSpell.EMBER && !scene.impact && !scene.controllerDriven) render(scene)
    }

    fun moveProjectile(sceneId: UUID?, at: Location, direction: org.bukkit.util.Vector) {
        val scene = scenes[sceneId] ?: return
        move(sceneId, at)
        val vector = Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
        scene.rotation = if (scene.spell == StaffSpell.GALE) staffYawOnlyOrientation(vector)
            else staffLanceOrientation(vector)
        if (scene.controllerDriven) scene.controllerDirty = true
    }

    fun moveTrail(sceneId: UUID?, points: List<Location>) {
        val scene = scenes[sceneId] ?: return
        if (points.isEmpty() || points.any { !valid(it) || it.world != scene.origin.world }) return
        scene.origin = points.first().clone()
        scene.rotation = Quaternionf()
        scene.trail = points.take(41).map { at ->
            val delta = at.toVector().subtract(scene.origin.toVector())
            Vector3f(delta.x.toFloat(), delta.y.toFloat(), delta.z.toFloat())
        }
        if (scene.controllerDriven) scene.controllerDirty = true
    }

    fun finishTrail(sceneId: UUID?) {
        val scene = scenes[sceneId] ?: return
        scene.controllerDriven = false
        scene.controllerDirty = false
        scene.impact = true
        scene.priorStageTicks += scene.age
        scene.age = 0
        scene.duration = 8
    }

    /** Charge/flight becomes its own release; retain the physical pieces and packet IDs. */
    fun impact(sceneId: UUID?, at: Location, radius: Double, durationTicks: Int) {
        val scene = scenes[sceneId] ?: return
        if (!valid(at) || at.world != scene.origin.world) { remove(sceneId); return }
        scene.transitionFrom = if (scene.spell == StaffSpell.EMBER) {
            // Flight frames are keyed at the projectile position; keep the retained core
            // centered on the collision point. Rebase only a repeated impact-stage transition.
            val delta = if (scene.impact) scene.origin.toVector().subtract(at.toVector()) else org.bukkit.util.Vector()
            val priorRotation = Quaternionf(scene.rotation)
            scene.rotation = Quaternionf() // Meteor and horizontal comet impacts both expand over the world ground plane.
            scene.renderedParts.map { part -> part.copy(
                center = priorRotation.transform(Vector3f(part.center)).add(delta.x.toFloat(), delta.y.toFloat(), delta.z.toFloat()),
                rotation = Quaternionf(priorRotation).mul(part.rotation),
            ) }
        } else scene.renderedParts
        scene.origin = at.clone()
        scene.radius = radius.takeIf(Double::isFinite)?.coerceIn(0.1, 16.0) ?: 1.0
        scene.duration = durationTicks.coerceIn(8, 160)
        scene.impact = true
        scene.controllerDriven = false
        scene.controllerDirty = false
        scene.priorStageTicks += scene.age
        scene.age = 0
        render(scene)
    }

    fun remove(sceneId: UUID?) {
        scenes.remove(sceneId)?.handles?.forEach { it.remove() }
    }

    fun cancel(casterId: UUID) {
        scenes.values.filter { it.casterId == casterId }.map { it.id }.forEach(::remove)
    }

    private fun tick() {
        scenes.values.toList().forEach { scene ->
            if (scenes[scene.id] !== scene) return@forEach
            val caster = Bukkit.getPlayer(scene.casterId)
            if (caster == null || !eligible(caster, scene.origin)) {
                remove(scene.id)
            } else if (!scene.controllerDriven) {
                scene.age += FRAME_TICKS
                val tracked = !scene.charging && !scene.impact && scene.spell in setOf(StaffSpell.MARK, StaffSpell.EMBER)
                val expired = if (tracked) scene.age >= 160 else scene.age >= scene.duration
                if (expired) remove(scene.id)
                // Flying comets have one movement owner sending two-tick forward keyframes.
                else if (scene.charging || scene.spell != StaffSpell.EMBER || scene.impact) render(scene)
            }
        }
        if (scenes.isNotEmpty()) refreshAudience()
    }

    private fun render(scene: Scene) {
        val previousParts = scene.renderedParts
        val previousBounds = scene.bounds
        var parts = (if (scene.charging) staffChargeParts(scene.spell, scene.chargeProgress, scene.age) else scene.trail?.let { path ->
            staffChargeResponsiveOldParts(scene.spell, staffLightningTrailParts(path,
                if (scene.impact) (1.0 - scene.age / 8.0).coerceAtLeast(0.0) else 1.0), scene.chargeProgress)
        }
            ?: staffDisplayParts(scene.spell, scene.age, scene.duration, scene.length, scene.radius, scene.impact,
                scene.secondary, animationAgeTicks = scene.priorStageTicks + scene.age, input = scene.input,
                charge = scene.chargeProgress)).take(MAX_PARTS)
        scene.transitionFrom?.let { previous ->
            parts = blendStaffParts(parts, previous, scene.age)
            if (scene.age >= (if (scene.spell == StaffSpell.EMBER) 4 else 2)) scene.transitionFrom = null
        }
        while (scenes.values.filter { it.id != scene.id && it.casterId == scene.casterId }.sumOf { it.handles.size } + parts.size > MAX_PARTS_PER_CASTER)
            remove(scenes.values.first { it.id != scene.id && it.casterId == scene.casterId }.id)
        while (scenes.values.filter { it.id != scene.id }.sumOf { it.handles.size } + parts.size > MAX_TOTAL_PARTS)
            remove(scenes.values.first { it.id != scene.id }.id)
        val previousIndexByPart = mutableListOf<Int?>()
        val matchedPrevious = mutableSetOf<Int>()
        parts.forEachIndexed { index, part ->
            val previousIndex = if (part.identity == null) {
                index.takeIf { it !in matchedPrevious && previousParts.getOrNull(it)?.identity == null }
            } else {
                previousParts.indices.firstOrNull {
                    it !in matchedPrevious && previousParts[it].identity == part.identity
                }
            }
            if (previousIndex != null) matchedPrevious += previousIndex
            previousIndexByPart += previousIndex
        }
        val casterEye = Bukkit.getPlayer(scene.casterId)?.eyeLocation
        // The broad ice fan needs per-piece clipping; projectile owners already sweep their paths.
        if (scene.spell == StaffSpell.FROST && !scene.charging && casterEye != null) parts = parts.map { part ->
            val point = scene.rotation.transform(Vector3f(part.center))
            val at = scene.origin.clone().add(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())
            part.copy(visible = part.visible && visible(casterEye, at))
        }
        val currentBounds = parts.map { part ->
            val point = scene.rotation.transform(Vector3f(part.center))
            scene.origin.toVector().add(org.bukkit.util.Vector(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) to
                part.scale.length() * 0.5
        }
        scene.previousBounds = previousIndexByPart.mapIndexed { index, previousIndex ->
            previousIndex?.let { previousBounds.getOrNull(it) } ?: currentBounds[index]
        }
        scene.bounds = currentBounds
        scene.renderedParts = parts
        val previousHandles = scene.handles.toList()
        val reusedHandles = previousIndexByPart.map { previousIndex ->
            previousIndex?.let { previousHandles.getOrNull(it) }?.takeIf(PacketBlockDisplay::isValid)
        }
        val retainedHandles = reusedHandles.filterNotNull().toSet()
        previousHandles.filterNot(retainedHandles::contains).forEach(PacketBlockDisplay::remove)
        scene.handles.clear()
        parts.forEachIndexed { index, part ->
            val center = scene.rotation.transform(Vector3f(part.center))
            val at = scene.origin.clone().add(center.x.toDouble(), center.y.toDouble(), center.z.toDouble()).apply {
                yaw = 0f; pitch = 0f
            }
            val handle = reusedHandles[index] ?: displays.spawnBlock(at,
                blocks.getOrPut(part.material) { part.material.createBlockData() }).also {
                it.isVisibleByDefault = false
                it.brightness = Display.Brightness(15, 15)
                it.viewRange = VIEW_RANGE.toFloat() / 64f
                it.interpolationDuration = if (scene.spell == StaffSpell.CHAIN) 0 else FRAME_TICKS
                it.shadowStrength = 0f
            }
            scene.handles += handle
            if (!handle.isValid) return@forEachIndexed
            if (handle.blockData.material != part.material)
                handle.blockData = blocks.getOrPut(part.material) { part.material.createBlockData() }
            handle.teleportDuration = when {
                scene.spell == StaffSpell.CHAIN -> 0
                else -> FRAME_TICKS
            }
            handle.teleport(at)
            val rotation = Quaternionf(scene.rotation).mul(part.rotation)
            // Block models occupy [0,1]^3. Center them at their real packet anchor for chunk visibility.
            handle.transformation = Transformation(rotation.transform(Vector3f(part.scale).mul(-0.5f)),
                rotation, Vector3f(part.scale), Quaternionf())
        }
    }

    private fun refreshAudience() {
        val online = Bukkit.getOnlinePlayers().associateBy { it.uniqueId }
        val selected = mutableMapOf<UUID, MutableSet<UUID>>()
        online.values.forEach { player ->
            val eye = player.eyeLocation
            var remainingParts = MAX_PARTS_PER_VIEWER
            scenes.values.asSequence().filter { it.origin.world == eye.world }
                .map { scene -> scene to (scene.bounds.minOfOrNull { (at, radius) ->
                    (at.distance(eye.toVector()) - radius).coerceAtLeast(0.0)
                } ?: scene.origin.distance(eye)) }.filter { it.second <= VIEW_RANGE }
                .sortedWith(compareBy<Pair<Scene, Double>> { it.first.casterId != player.uniqueId }.thenBy { it.second })
                .take(MAX_PER_VIEWER).forEach { (scene, _) ->
                    if (scene.handles.size <= remainingParts) {
                        remainingParts -= scene.handles.size
                        selected.getOrPut(scene.id, ::mutableSetOf).add(player.uniqueId)
                    }
                }
        }
        scenes.values.forEach { scene ->
            val audience = selected[scene.id].orEmpty()
            // Clear explicit visibility even for an offline viewer; core handles its connection teardown.
            (scene.viewers.keys - audience).forEach { id -> scene.viewers[id]?.let { player -> scene.handles.forEach { it.hideFrom(player) } } }
            audience.forEach { id -> online[id]?.let { player ->
                scene.handles.forEachIndexed { index, handle ->
                    val bound = scene.bounds.getOrNull(index)
                    val previous = if (scene.spell == StaffSpell.CHAIN) bound
                        else scene.previousBounds.getOrNull(index) ?: bound
                    val eye = player.eyeLocation.toVector()
                    val padding = staffEyeClearance(scene.spell, scene.impact) + player.velocity.length() * FRAME_TICKS + 0.15
                    if (scene.renderedParts.getOrNull(index)?.visible == true && bound != null && previous != null &&
                        staffSweptDistance(eye, previous.first, bound.first) >= padding + maxOf(bound.second, previous.second))
                        handle.showTo(player)
                    else handle.hideFrom(player)
                }
            } }
            scene.viewers.clear()
            audience.forEach { id -> online[id]?.let { scene.viewers[id] = it } }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        scenes.keys.toList().forEach(::remove)
        blocks.clear()
        displays.close()
    }

    private fun eligible(player: Player, origin: Location) = player.isOnline && player.isValid && !player.isDead && player.world == origin.world
    private fun valid(at: Location) = at.world != null && at.x.isFinite() && at.y.isFinite() && at.z.isFinite()

    internal companion object {
        const val FRAME_TICKS = 2
        const val MAX_SCENES = 32
        const val MAX_PER_CASTER = 12
        const val MAX_PER_VIEWER = 16
        const val MAX_PARTS = 48
        const val MAX_PARTS_PER_CASTER = 192
        const val MAX_PARTS_PER_VIEWER = 192
        const val MAX_TOTAL_PARTS = 576
        const val VIEW_RANGE = 64.0
    }
}

/** Compact flight remains close to the reticle; large explosions retain a wider exclusion. */
internal fun staffEyeClearance(spell: StaffSpell, impact: Boolean): Double =
    when {
        impact && spell in setOf(StaffSpell.MARK, StaffSpell.EMBER) -> 3.2
        spell == StaffSpell.NOVA -> 0.55
        else -> 1.15
    }

/** Conservative sphere includes the entire transformed cuboid, not only its anchor. */
internal fun staffPartClearOfEye(part: StaffDisplayPart, origin: Location, rotation: Quaternionf, eye: Location,
    clearance: Double = 3.2): Boolean {
    if (origin.world != eye.world) return false
    val center = rotation.transform(Vector3f(part.center))
    val distance = eye.toVector().distance(origin.toVector().add(org.bukkit.util.Vector(
        center.x.toDouble(), center.y.toDouble(), center.z.toDouble())))
    return distance >= clearance + part.scale.length() * 0.5
}

internal fun staffSweptDistance(eye: org.bukkit.util.Vector, from: org.bukkit.util.Vector, to: org.bukkit.util.Vector): Double {
    val delta = to.clone().subtract(from)
    val t = if (delta.lengthSquared() < 0.000001) 0.0 else
        eye.clone().subtract(from).dot(delta).div(delta.lengthSquared()).coerceIn(0.0, 1.0)
    return eye.distance(from.clone().add(delta.multiply(t)))
}

/** Gale visuals pitch only around the world vertical axis, even for an elevated target. */
internal fun staffYawOnlyOrientation(direction: Vector3f): Quaternionf {
    val x = direction.x.toDouble()
    val z = direction.z.toDouble()
    if (!x.isFinite() || !z.isFinite()) return Quaternionf()
    return Quaternionf().rotationY(kotlin.math.atan2(x, z).toFloat())
}
