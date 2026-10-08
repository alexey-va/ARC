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
        val rotation: Quaternionf,
        val length: Double,
        var radius: Double,
        var duration: Int,
        var impact: Boolean,
        var age: Int = 0,
        var transitionFrom: List<StaffDisplayPart>? = null,
        var renderedParts: List<StaffDisplayPart> = emptyList(),
        val handles: MutableList<PacketBlockDisplay> = mutableListOf(),
        val viewers: MutableMap<UUID, Player> = mutableMapOf(),
    )

    private val tasks = LifecycleTaskScope()
    private val scenes = linkedMapOf<UUID, Scene>()
    private val blocks = mutableMapOf<Material, BlockData>()
    private var closed = false

    init { tasks.runTimer(FRAME_TICKS.toLong(), FRAME_TICKS.toLong(), ::tick) }

    fun play(casterId: UUID, spell: StaffSpell, from: Location, to: Location = from,
        radius: Double = 1.0, durationTicks: Int = 16, impact: Boolean = false): UUID? {
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
        else if (length > 0.001 && spell != StaffSpell.NOVA)
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).normalize())
        else Quaternionf()
        val scene = Scene(UUID.randomUUID(), casterId, spell, from.clone(), rotation,
            if (spell == StaffSpell.NOVA) 4.0 else length.coerceAtLeast(0.1),
            radius.takeIf(Double::isFinite)?.coerceIn(0.1, if (spell == StaffSpell.FROST) 96.0 else 16.0) ?: 1.0,
            durationTicks.coerceIn(1, 160), impact)
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

    /** Charge/flight becomes its own release; retain the physical pieces and packet IDs. */
    fun impact(sceneId: UUID?, at: Location, radius: Double, durationTicks: Int) {
        val scene = scenes[sceneId] ?: return
        if (!valid(at) || at.world != scene.origin.world) { remove(sceneId); return }
        scene.transitionFrom = scene.renderedParts
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
        var parts = staffDisplayParts(scene.spell, scene.age, scene.duration, scene.length, scene.radius, scene.impact).take(MAX_PARTS)
        scene.transitionFrom?.let { previous ->
            parts = blendStaffParts(parts, previous, scene.age)
            if (scene.age >= 4) scene.transitionFrom = null
        }
        scene.renderedParts = parts
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
                scene.viewers.values.forEach(it::showTo)
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
                .map { it to it.origin.distanceSquared(eye) }.filter { it.second <= VIEW_RANGE * VIEW_RANGE }
                .sortedWith(compareBy<Pair<Scene, Double>> { it.first.casterId != player.uniqueId }.thenBy { it.second })
                .take(MAX_PER_VIEWER).forEach { (scene, _) -> selected.getOrPut(scene.id, ::mutableSetOf).add(player.uniqueId) }
        }
        scenes.values.forEach { scene ->
            val audience = selected[scene.id].orEmpty()
            // Clear explicit visibility even for an offline viewer; core handles its connection teardown.
            (scene.viewers.keys - audience).forEach { id -> scene.viewers[id]?.let { player -> scene.handles.forEach { it.hideFrom(player) } } }
            (audience - scene.viewers.keys).forEach { id -> online[id]?.let { player -> scene.handles.forEach { it.showTo(player) } } }
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
        const val VIEW_RANGE = 32.0
    }
}
