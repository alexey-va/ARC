package ru.arc.onboarding

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PacketDisplayScene
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.SceneFrame
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.scene.ScenePose
import ru.arc.paper.scene.SceneTextStyle
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One private instruction and 24 particles per second; never spawns entities or loads chunks. */
internal class StoryQuestWaypointHud(
    private val plugin: Plugin,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "story-quest-waypoint"),
) : Listener, AutoCloseable {
    private class Marker(val scene: PacketDisplayScene<String>, var lastParticlesAt: Long = Long.MIN_VALUE)
    private val markers = mutableMapOf<UUID, Marker>()
    private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    /** Target is the entrance's feet and portal yaw, or an NPC's feet with smaller bounds. */
    fun refresh(player: Player, target: Location?, title: Component, width: Double = 6.0, height: Double = 8.4) {
        if (closed) return
        require(width.isFinite() && width in 0.5..12.0)
        require(height.isFinite() && height in 1.0..20.0)
        if (target?.world == null || target.world != player.world || !player.isOnline || player.isDead) {
            clear(player.uniqueId)
            return
        }
        val from = player.location
        val distance = from.distance(target)
        val distant = distance > 48.0
        val position = storyQuestWaypointLocation(from, target)
        if (!player.world.isChunkLoaded(position.blockX shr 4, position.blockZ shr 4)) {
            clear(player.uniqueId)
            return
        }
        val label = if (distant) title.append(Component.newline()).append(
            Component.text("${distance.roundToInt()} м", TextColor.color(0xE8DFD2)),
        ) else title
        val marker = markers.getOrPut(player.uniqueId) { Marker(PacketDisplayScene(displays)) }
        marker.scene.apply(storyQuestWaypointFrame(player.uniqueId, position, label))

        val now = System.nanoTime()
        if (marker.lastParticlesAt != Long.MIN_VALUE && now - marker.lastParticlesAt < 1_000_000_000L) return
        marker.lastParticlesAt = now
        storyQuestWaypointParticles(from, target, width, height, includeRing = !distant).forEach { point ->
            if (player.world.isChunkLoaded(point.blockX shr 4, point.blockZ shr 4)) {
                player.spawnParticle(Particle.END_ROD, point, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    fun clear(player: Player) = clear(player.uniqueId)

    fun clear(playerId: UUID) { markers.remove(playerId)?.scene?.close() }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { clear(event.player) }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) { clear(event.player) }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        try { markers.values.forEach { it.scene.close() } } finally {
            markers.clear()
            displays.close()
        }
    }
}

/** Far destinations keep a direction cue inside the viewer's loaded surroundings. */
internal fun storyQuestWaypointLocation(from: Location, target: Location): Location {
    val distance = from.distance(target)
    if (distance <= 48.0) return target.clone()
    return from.clone().add(target.toVector().subtract(from.toVector()).multiply(24.0 / distance))
        .apply { yaw = target.yaw; pitch = 0f }
}

internal fun storyQuestWaypointFrame(viewerId: UUID, target: Location, title: Component): SceneFrame<String> {
    val angle = Math.toRadians(target.yaw.toDouble())
    val position = target.clone().add(sin(angle) * 0.8, 3.2, -cos(angle) * 0.8).apply { yaw = 0f; pitch = 0f }
    return SceneFrame(
        listOf(ScenePart.Text(
            key = "instruction",
            pose = ScenePose(position, Transformation(Vector3f(), Quaternionf(), Vector3f(1.15f), Quaternionf())),
            style = SceneDisplayStyle(
                billboard = Display.Billboard.CENTER,
                brightness = Display.Brightness(15, 15),
                viewRange = 1.5f,
                displayWidth = 7f,
                displayHeight = 1f,
            ),
            text = title,
            textStyle = SceneTextStyle(lineWidth = 240, background = Color.fromARGB(190, 24, 28, 31), shadowed = true),
        )),
        SceneAudience.Private(viewerId),
    )
}

/** An upright portal outline and a short direction trail at the viewer's feet, with a fixed budget. */
internal fun storyQuestWaypointParticles(
    from: Location, target: Location, width: Double, height: Double, includeRing: Boolean = true,
): List<Location> {
    val yaw = Math.toRadians(target.yaw.toDouble())
    val sideX = cos(yaw)
    val sideZ = sin(yaw)
    val ringHeight = height * 0.82
    return buildList(24) {
        if (includeRing) {
            repeat(18) { index ->
                val angle = index * 2.0 * PI / 18.0
                val side = sin(angle) * width * 0.44
                add(target.clone().add(
                    sideX * side + sin(yaw) * 0.2,
                    0.15 + ringHeight * (0.5 - cos(angle) * 0.5),
                    sideZ * side - cos(yaw) * 0.2,
                ))
            }
        }
        val dx = target.x - from.x
        val dz = target.z - from.z
        val distance = kotlin.math.hypot(dx, dz)
        if (distance > 1.0) {
            val length = (distance - 0.5).coerceAtMost(3.0)
            repeat(6) { index ->
                val progress = length * (index + 1) / 6.0 / distance
                add(from.clone().add(dx * progress, 0.2, dz * progress))
            }
        }
    }
}
