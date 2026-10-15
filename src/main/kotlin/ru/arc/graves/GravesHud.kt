package ru.arc.graves

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.dungeons.api.CompassStrip
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Personal guidance only: no world entities, block writes, or chunk loads. */
internal class GravesHud(plugin: Plugin, private val server: String, private val settings: GravesSettings) : AutoCloseable {
    private class Bars(val notice: BossBar, val compass: BossBar, var compassShown: Boolean = false)
    private val bars = mutableMapOf<UUID, Pair<Player, Bars>>()
    private val displays = PaperPacketDisplays(plugin, "graves")
    private val glows = mutableMapOf<UUID, MutableMap<UUID, PacketBlockDisplay>>()

    fun refresh(player: Player, graves: List<GraveView>, selected: UUID?, tracking: Boolean) {
        if (graves.isEmpty() || player.isDead || !player.isOnline) { clear(player); return }
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

    /** Once per snapshot interval; PaperPacketDisplays handles actual viewer/chunk packet lifecycle. */
    fun refreshGlow(player: Player, graves: List<GraveView>) {
        val current = glows.getOrPut(player.uniqueId, ::mutableMapOf)
        val desired = if (player.isDead) emptyList() else graves.filter { grave ->
            grave.server == server && grave.world == player.world.name &&
                player.location.distanceSquared(Location(player.world, grave.x + .5, grave.y + .5, grave.z + .5)) <= settings.nearbyGlowRadius.toDouble() * settings.nearbyGlowRadius &&
                player.world.isChunkLoaded(grave.x shr 4, grave.z shr 4)
        }
        val ids = desired.mapTo(hashSetOf()) { it.id }
        (current.keys - ids).forEach { current.remove(it)?.remove() }
        desired.forEach { grave ->
            if (grave.id in current) return@forEach
            val block = player.world.getBlockAt(grave.x, grave.y, grave.z)
            if (block.type.isAir) return@forEach
            val box = block.boundingBox
            if (box.widthX <= .04 || box.height <= .04 || box.widthZ <= .04) return@forEach
            // The solid marker stays inside the actual grave block, avoiding coincident visible faces.
            // Its glow outline is visible through terrain without changing the real block or hitbox.
            current[grave.id] = displays.spawnBlock(
                Location(player.world, box.minX + .01, box.minY + .01, box.minZ + .01),
                Material.WHITE_CONCRETE.createBlockData(),
            ).apply {
                isVisibleByDefault = false
                showTo(player)
                isGlowing = true
                glowColorOverride = Color.fromRGB(0xffcf70)
                viewRange = settings.nearbyGlowRadius / 64f
                transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(
                    (box.widthX - .02).toFloat(), (box.height - .02).toFloat(), (box.widthZ - .02).toFloat(),
                ), Quaternionf())
            }
        }
        if (current.isEmpty()) glows.remove(player.uniqueId)
    }

    fun clear(player: Player) {
        bars.remove(player.uniqueId)?.second?.let {
            player.hideBossBar(it.notice)
            player.hideBossBar(it.compass)
        }
        glows.remove(player.uniqueId)?.values?.forEach(PacketBlockDisplay::remove)
    }

    override fun close() {
        bars.values.map { it.first }.forEach(::clear)
        displays.close()
    }
}
