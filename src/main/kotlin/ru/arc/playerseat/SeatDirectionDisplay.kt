package ru.arc.playerseat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

internal class SeatDirectionDisplay(plugin: Plugin) : SeatDirections {
    private val displays = PaperPacketDisplays(plugin, "player-seat-directions")
    private val signals = mutableMapOf<UUID, Signal>()

    private class Signal(val carrierId: UUID, val arrow: List<PacketBlockDisplay>, val caption: PacketTextDisplay) {
        fun remove() {
            arrow.forEach(PacketDisplay::remove)
            caption.remove()
        }
    }

    override fun show(carrier: Player, rider: Player, yaw: Float, caption: String) {
        val pose = SeatDirectionPose(yaw, carrier.height.toFloat())
        val text = Component.text(caption, TextColor.color(0xFFF2CC))
        val riderId = rider.uniqueId
        if (signals[riderId]?.carrierId != carrier.uniqueId) hide(riderId)
        val signal = signals.getOrPut(riderId) { createSignal(carrier, rider, text) }
        signal.arrow.forEachIndexed { index, block -> block.transformation = pose.arrow[index] }
        signal.caption.transformation = pose.caption
        signal.caption.text(text)
    }

    private fun createSignal(carrier: Player, rider: Player, text: Component): Signal {
        // A fixed display applies its entity yaw/pitch before its transform. Keep those axes world-aligned.
        val at = carrier.location.apply {
            yaw = 0f
            pitch = 0f
        }
        val parts = mutableListOf<PacketDisplay>()
        try {
            val arrow = listOf(Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE, Material.YELLOW_CONCRETE).map {
                displays.spawnBlock(at, it.createBlockData()).also(parts::add)
            }
            val caption = displays.spawnText(at, text).also(parts::add).apply {
                backgroundColor = Color.fromARGB(180, 36, 28, 20)
                isShadowed = true
            }
            for (part in parts) {
                part.isVisibleByDefault = false
                part.showTo(carrier)
                part.showTo(rider)
                part.brightness = Display.Brightness(15, 15)
                part.pauseWhenNotObserved = false
                part.attachTo(carrier)
            }
            return Signal(carrier.uniqueId, arrow, caption)
        } catch (failure: Exception) {
            parts.forEach(PacketDisplay::remove)
            throw failure
        }
    }

    override fun hide(riderId: UUID) {
        signals.remove(riderId)?.remove()
    }

    override fun close() {
        signals.clear()
        displays.close()
    }
}

/** Offsets from the native passenger origin above the carrier, with +Z forward at Bukkit yaw zero. */
internal class SeatDirectionPose(yaw: Float, carrierHeight: Float) {
    private val heading = Quaternionf().rotationY(-Math.toRadians(yaw.toDouble() % 360.0).toFloat())
    private val tipY = -.335f * carrierHeight

    // A low arrow with a gently raised tip reads as forward from both eye heights.
    // The different wing thicknesses avoid coplanar faces at the tip.
    val arrow = listOf(
        stroke(0f, -.52f * carrierHeight, 4.30f, .18f, .10f),
        stroke(-.59f, -.47f * carrierHeight, 5.17f, .20f, .14f),
        stroke(.59f, -.47f * carrierHeight, 5.17f, .20f, .15f),
    )

    // Both players share the carrier's horizontal position. Fixed text faces them from above the arrow.
    val caption = Transformation(
        heading.transform(Vector3f(0f, .035f * carrierHeight, 5.84f)), Quaternionf(heading).rotateY(PI.toFloat()),
        Vector3f(1.04f), Quaternionf(),
    )

    private fun stroke(tailX: Float, tailY: Float, tailZ: Float, width: Float, height: Float): Transformation {
        val tipZ = 6f
        val depth = tipZ - tailZ
        val run = hypot(tailX, depth)
        val rise = tipY - tailY
        val scale = Vector3f(width, height, hypot(run, rise))
        val rotation = Quaternionf(heading).rotateY(atan2(-tailX, depth)).rotateX(-atan2(rise, run))
        val center = heading.transform(Vector3f(tailX / 2f, (tailY + tipY) / 2f, (tailZ + tipZ) / 2f))
        // A vanilla block mesh starts at its corner, not its center.
        val corner = rotation.transform(Vector3f(scale).mul(-.5f)).add(center)
        return Transformation(corner, rotation, scale, Quaternionf())
    }
}
