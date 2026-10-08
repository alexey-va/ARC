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
        var age: Int = 0,
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
        radius: Double = 1.0, durationTicks: Int = 16, impact: Boolean = false, secondary: Boolean = false): UUID? {
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
        val rotation = if (spell == StaffSpell.MARK)
            Quaternionf().rotationY(kotlin.math.atan2(caster.location.x - from.x, caster.location.z - from.z).toFloat())
        else if (length > 0.001 && (spell != StaffSpell.NOVA || secondary))
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).normalize())
        else Quaternionf()
        val scene = Scene(UUID.randomUUID(), casterId, spell, from.clone(), rotation,
            length.coerceAtLeast(0.1),
            radius.takeIf(Double::isFinite)?.coerceIn(0.1, if (spell == StaffSpell.FROST) 96.0 else 16.0) ?: 1.0,
            durationTicks.coerceIn(1, 160), impact, secondary)
        scenes[scene.id] = scene
        render(scene)
        refreshAudience()
        return scene.id
    }

    fun move(sceneId: UUID?, at: Location) {
        val scene = scenes[sceneId] ?: return
        if (!valid(at) || at.world != scene.origin.world) { remove(sceneId); return }
        scene.origin = at.clone()
    }

    fun moveTrail(sceneId: UUID?, points: List<Location>) {
        val scene = scenes[sceneId] ?: return
        if (points.size < 2 || points.any { !valid(it) || it.world != scene.origin.world }) return
        scene.origin = points.first().clone()
        scene.rotation = Quaternionf()
        scene.trail = points.takeLast(17).map { at ->
            val delta = at.toVector().subtract(scene.origin.toVector())
            Vector3f(delta.x.toFloat(), delta.y.toFloat(), delta.z.toFloat())
        }
    }

    fun finishTrail(sceneId: UUID?) {
        val scene = scenes[sceneId] ?: return
        scene.impact = true
        scene.age = 0
        scene.duration = 8
    }

    /** Charge/flight becomes its own release; retain the physical pieces and packet IDs. */
    fun impact(sceneId: UUID?, at: Location, radius: Double, durationTicks: Int) {
        val scene = scenes[sceneId] ?: return
        if (!valid(at) || at.world != scene.origin.world) { remove(sceneId); return }
        scene.transitionFrom = if (scene.spell == StaffSpell.EMBER) {
            val delta = scene.origin.toVector().subtract(at.toVector())
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
            scene.age += FRAME_TICKS
            val caster = Bukkit.getPlayer(scene.casterId)
            val tracked = !scene.impact && scene.spell in setOf(StaffSpell.MARK, StaffSpell.EMBER)
            val expired = if (tracked) scene.age >= 160 else scene.age >= scene.duration
            if (expired || caster == null || !eligible(caster, scene.origin)) remove(scene.id)
            else render(scene)
        }
        if (scenes.isNotEmpty()) refreshAudience()
    }

    private fun render(scene: Scene) {
        var parts = (scene.trail?.let { path -> staffLightningTrailParts(path,
            if (scene.impact) (1.0 - scene.age / 8.0).coerceAtLeast(0.0) else 1.0) }
            ?: staffDisplayParts(scene.spell, scene.age, scene.duration, scene.length, scene.radius, scene.impact, scene.secondary)).take(MAX_PARTS)
        scene.transitionFrom?.let { previous ->
            parts = blendStaffParts(parts, previous, scene.age)
            if (scene.age >= 4) scene.transitionFrom = null
        }
        scene.renderedParts = parts
        scene.previousBounds = scene.bounds
        scene.bounds = parts.map { part ->
            val point = scene.rotation.transform(Vector3f(part.center))
            scene.origin.toVector().add(org.bukkit.util.Vector(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) to
                part.scale.length() * 0.5
        }
        while (scene.handles.size > parts.size) scene.handles.removeLast().remove()
        parts.forEachIndexed { index, part ->
            val center = scene.rotation.transform(Vector3f(part.center))
            val at = scene.origin.clone().add(center.x.toDouble(), center.y.toDouble(), center.z.toDouble()).apply {
                yaw = 0f; pitch = 0f
            }
            val handle = scene.handles.getOrNull(index) ?: displays.spawnBlock(at,
                blocks.getOrPut(part.material) { part.material.createBlockData() }).also {
                it.isVisibleByDefault = false
                it.brightness = Display.Brightness(15, 15)
                it.viewRange = VIEW_RANGE.toFloat() / 64f
                it.interpolationDuration = FRAME_TICKS
                it.teleportDuration = FRAME_TICKS
                it.shadowStrength = 0f
                scene.handles += it
            }
            if (handle.blockData.material != part.material)
                handle.blockData = blocks.getOrPut(part.material) { part.material.createBlockData() }
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
            scenes.values.asSequence().filter { it.origin.world == eye.world }
                .map { scene -> scene to (scene.bounds.minOfOrNull { (at, radius) ->
                    (at.distance(eye.toVector()) - radius).coerceAtLeast(0.0)
                } ?: scene.origin.distance(eye)) }.filter { it.second <= VIEW_RANGE }
                .sortedWith(compareBy<Pair<Scene, Double>> { it.first.casterId != player.uniqueId }.thenBy { it.second })
                .take(MAX_PER_VIEWER).forEach { (scene, _) -> selected.getOrPut(scene.id, ::mutableSetOf).add(player.uniqueId) }
        }
        scenes.values.forEach { scene ->
            val audience = selected[scene.id].orEmpty()
            // Clear explicit visibility even for an offline viewer; core handles its connection teardown.
            (scene.viewers.keys - audience).forEach { id -> scene.viewers[id]?.let { player -> scene.handles.forEach { it.hideFrom(player) } } }
            audience.forEach { id -> online[id]?.let { player ->
                scene.handles.forEachIndexed { index, handle ->
                    val bound = scene.bounds.getOrNull(index)
                    val previous = scene.previousBounds.getOrNull(index) ?: bound
                    val eye = player.eyeLocation.toVector()
                    val padding = 3.2 + player.velocity.length() * FRAME_TICKS + 0.35
                    if (bound != null && previous != null &&
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
        const val MAX_SCENES = 12
        const val MAX_PER_CASTER = 4
        const val MAX_PER_VIEWER = 4
        const val MAX_PARTS = 48
        const val VIEW_RANGE = 64.0
    }
}

/** Conservative sphere includes the entire transformed cuboid, not only its anchor. */
internal fun staffPartClearOfEye(part: StaffDisplayPart, origin: Location, rotation: Quaternionf, eye: Location): Boolean {
    if (origin.world != eye.world) return false
    val center = rotation.transform(Vector3f(part.center))
    val distance = eye.toVector().distance(origin.toVector().add(org.bukkit.util.Vector(
        center.x.toDouble(), center.y.toDouble(), center.z.toDouble())))
    return distance >= 3.2 + part.scale.length() * 0.5
}

internal fun staffSweptDistance(eye: org.bukkit.util.Vector, from: org.bukkit.util.Vector, to: org.bukkit.util.Vector): Double {
    val delta = to.clone().subtract(from)
    val t = if (delta.lengthSquared() < 0.000001) 0.0 else
        eye.clone().subtract(from).dot(delta).div(delta.lengthSquared()).coerceIn(0.0, 1.0)
    return eye.distance(from.clone().add(delta.multiply(t)))
}
