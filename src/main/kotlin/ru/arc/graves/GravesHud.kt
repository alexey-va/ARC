package ru.arc.graves

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.dungeons.api.CompassStrip
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Personal guidance only: no world entities, block writes, or chunk loads. */
internal class GravesHud(
    plugin: Plugin,
    private val server: String,
    private val settings: GravesSettings,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "graves"),
) : AutoCloseable {
    private class Bars(val notice: BossBar, val compass: BossBar, var compassShown: Boolean = false)
    private class Marker(val world: UUID, val x: Int, val y: Int, val z: Int, val parts: List<PacketDisplay>) {
        fun matches(grave: GraveView): Boolean = x == grave.x && y == grave.y && z == grave.z
        fun remove() = parts.forEach(PacketDisplay::remove)
    }
    private val bars = mutableMapOf<UUID, Pair<Player, Bars>>()
    private val markers = mutableMapOf<UUID, MutableMap<UUID, Marker>>()
    private var closed = false

    fun refresh(player: Player, graves: List<GraveView>, selected: UUID?, tracking: Boolean) {
        if (closed) return
        if (graves.isEmpty() || player.isDead || !player.isOnline) { clear(player); return }
        clearOtherWorldMarkers(player)
        val state = bars.getOrPut(player.uniqueId) {
            val created = Bars(
                BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS),
                BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS),
            )
            player.showBossBar(created.notice)
            player to created
        }.second
        state.notice.name(settings.text("hud-notice", "count" to graves.size.toString()))
        val target = graves.firstOrNull { it.id == selected } ?: graves.first()
        val local = target.server == server && target.world == player.world.name
        if (tracking && local) {
            val location = player.location
            val dx = target.x + 0.5 - location.x
            val dz = target.z + 0.5 - location.z
            val dy = target.y - location.y
            val distance = sqrt(dx * dx + dy * dy + dz * dz).roundToInt()
            val bearing = Math.toDegrees(atan2(-dx, dz))
            state.compass.name(CompassStrip.render(location.yaw, bearing, "☠", 0xffcf70))
            state.notice.name(settings.text("hud-target", "distance" to distance.toString(), "vertical" to when {
                dy > 3 -> " · ↑ ${dy.roundToInt()} м"
                dy < -3 -> " · ↓ ${(-dy).roundToInt()} м"
                else -> ""
            }))
            if (!state.compassShown) { player.showBossBar(state.compass); state.compassShown = true }
        } else {
            if (state.compassShown) { player.hideBossBar(state.compass); state.compassShown = false }
            if (tracking) state.notice.name(settings.text(
                if (target.server == server) "hud-world" else "hud-remote",
                "server" to target.server, "world" to target.world,
            ))
        }
    }

    /** Stable private scenes; PaperPacketDisplays owns connection, sent-chunk and world reconciliation. */
    fun refreshGlow(player: Player, graves: List<GraveView>) {
        if (closed) return
        clearOtherWorldMarkers(player)
        val current = markers.getOrPut(player.uniqueId, ::mutableMapOf)
        val desired = if (player.isDead || !player.isOnline) emptyList() else graves.filter { grave ->
            grave.ownerId == player.uniqueId && grave.server == server && grave.world == player.world.name &&
                player.location.distanceSquared(Location(player.world, grave.x + .5, grave.y + .5, grave.z + .5)) <= settings.nearbyGlowRadius.toDouble() * settings.nearbyGlowRadius &&
                player.world.isChunkLoaded(grave.x shr 4, grave.z shr 4)
        }
        val ids = desired.mapTo(hashSetOf()) { it.id }
        (current.keys - ids).forEach { current.remove(it)?.remove() }
        desired.forEach { grave ->
            if (current[grave.id]?.matches(grave) == true) return@forEach
            current.remove(grave.id)?.remove()
            val parts = mutableListOf<PacketDisplay>()
            try {
                GraveMarkerGeometry.parts.forEach { part ->
                    displays.spawnBlock(
                        Location(player.world, grave.x + .5 + part.x, grave.y + part.y, grave.z + .5 + part.z),
                        part.material.createBlockData(),
                    ).also(parts::add).apply {
                        isVisibleByDefault = false
                        showTo(player)
                        isGlowing = true
                        glowColorOverride = Color.fromRGB(0xffcf70)
                        viewRange = settings.nearbyGlowRadius / 64f
                        transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(part.width, part.height, part.depth), Quaternionf())
                    }
                }
                displays.spawnText(
                    Location(player.world, grave.x + .5, grave.y + GraveMarkerGeometry.labelY, grave.z + .5),
                    settings.text("marker-label"),
                ).also(parts::add).apply {
                    isVisibleByDefault = false
                    showTo(player)
                    billboard = Display.Billboard.CENTER
                    viewRange = settings.nearbyGlowRadius / 64f
                    lineWidth = 200
                    isShadowed = true
                    backgroundColor = Color.fromARGB(0xb020182a.toInt())
                    transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(GraveMarkerGeometry.labelScale), Quaternionf())
                }
                current[grave.id] = Marker(player.world.uid, grave.x, grave.y, grave.z, parts)
            } catch (failure: RuntimeException) {
                parts.forEach(PacketDisplay::remove)
                throw failure
            }
        }
        if (current.isEmpty()) markers.remove(player.uniqueId)
    }

    private fun clearOtherWorldMarkers(player: Player) {
        val current = markers[player.uniqueId] ?: return
        current.entries.removeIf { (_, marker) ->
            (marker.world != player.world.uid).also { if (it) marker.remove() }
        }
        if (current.isEmpty()) markers.remove(player.uniqueId)
    }

    fun clear(player: Player) {
        bars.remove(player.uniqueId)?.second?.let {
            player.hideBossBar(it.notice)
            player.hideBossBar(it.compass)
        }
        markers.remove(player.uniqueId)?.values?.forEach(Marker::remove)
    }

    /** The owning module must observe this future before starting a replacement runtime. */
    fun closeAsync(): CompletableFuture<Void> {
        closed = true
        bars.values.map { it.first }.forEach(::clear)
        markers.values.forEach { it.values.forEach(Marker::remove) }
        markers.clear()
        return displays.closeAsync()
    }

    override fun close() { closeAsync() }
}

/** Canonical cuboids are shared by the runtime and its source-driven composition preview. */
internal object GraveMarkerGeometry {
    data class Part(
        val material: Material,
        val x: Double, val y: Double, val z: Double,
        val width: Float, val height: Float, val depth: Float,
    )

    val parts = listOf(
        Part(Material.POLISHED_DEEPSLATE, -.35, .02, -.22, .70f, .12f, .44f),
        Part(Material.CHISELED_DEEPSLATE, -.25, .14, -.10, .50f, .62f, .20f),
    )
    const val labelY = 1.02
    const val labelScale = .65f
}
