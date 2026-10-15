package ru.arc.staffspells

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal enum class StaffSpellHudPhase { READY, CHARGING, CHANNEL, COOLDOWN }

internal data class StaffSpellHudState(
    val spell: StaffSpell,
    val secondary: Boolean,
    val phase: StaffSpellHudPhase,
    val progress: Double = 0.0,
    val remainingTicks: Long = 0,
    val totalTicks: Long = 0,
)

/** Private, camera-follow packet text for the currently selected staff ability. */
internal class StaffSpellHud(
    private val displays: PaperPacketDisplays,
    private val config: StaffSpellConfig,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
) : AutoCloseable {
    private data class Card(
        val display: PacketTextDisplay,
        var state: StaffSpellHudState,
        var location: Location,
        var scale: Float,
    )

    private val cards = mutableMapOf<UUID, Card>()
    private val visual = staffSpellHudVisualSpec()
    private var started = false
    private var closed = false

    fun start() {
        check(!closed) { "Staff spell HUD is closed" }
        if (started) return
        started = true
        tasks.runTimer(visual.followPeriodTicks, visual.followPeriodTicks, ::follow)
    }

    fun ready(player: Player, spell: StaffSpell, secondary: Boolean) =
        update(player, StaffSpellHudState(spell, secondary, StaffSpellHudPhase.READY))

    fun charging(player: Player, spell: StaffSpell, secondary: Boolean, progress: Double) =
        update(player, StaffSpellHudState(spell, secondary, StaffSpellHudPhase.CHARGING,
            progress = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0))

    fun channel(player: Player, spell: StaffSpell, secondary: Boolean) =
        update(player, StaffSpellHudState(spell, secondary, StaffSpellHudPhase.CHANNEL))

    fun cooldown(player: Player, spell: StaffSpell, secondary: Boolean, remainingTicks: Long, totalTicks: Long) {
        if (remainingTicks <= 0) ready(player, spell, secondary)
        else update(player, StaffSpellHudState(spell, secondary, StaffSpellHudPhase.COOLDOWN,
            progress = staffCooldownProgress(remainingTicks, totalTicks).toDouble(),
            remainingTicks = remainingTicks, totalTicks = totalTicks))
    }

    fun clear(playerId: UUID) {
        cards.remove(playerId)?.display?.remove()
    }

    private fun update(player: Player, state: StaffSpellHudState) {
        if (closed || !eligible(player) || StaffSpell.from(player.inventory.itemInMainHand) != state.spell) {
            clear(player.uniqueId)
            return
        }
        val text = render(state)
        val card = cards[player.uniqueId]
        if (card != null && card.display.isValid) {
            if (card.state != state) card.display.text(text)
            card.state = state
            return
        }
        card?.display?.remove()
        val placement = placement(player)
        val at = placement.location
        if (at.world?.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4) == false) {
            clear(player.uniqueId)
            return
        }
        val display = displays.spawnText(at, text).apply {
            isVisibleByDefault = false
            billboard = Display.Billboard.CENTER
            lineWidth = visual.lineWidth
            alignment = TextDisplay.TextAlignment.CENTER
            textOpacity = visual.textOpacity
            isShadowed = visual.shadowed
            isSeeThrough = visual.seeThrough
            isDefaultBackground = false
            backgroundColor = visual.backgroundColor
            displayWidth = visual.displayWidth
            displayHeight = visual.displayHeight
            viewRange = visual.viewRange
            interpolationDuration = visual.interpolationTicks
            teleportDuration = visual.interpolationTicks
            transformation = transformation(placement.scale)
            showTo(player)
        }
        cards[player.uniqueId] = Card(display, state, at, placement.scale)
    }

    private fun render(state: StaffSpellHudState): Component = when (state.phase) {
        StaffSpellHudPhase.READY -> config.staffHudReady(state.spell, state.secondary)
        StaffSpellHudPhase.CHARGING -> config.staffHudCharging(state.spell, state.secondary,
            state.progress, staffSpellHudBar(state.progress))
        StaffSpellHudPhase.CHANNEL -> config.staffHudChannel(state.spell, state.secondary)
        StaffSpellHudPhase.COOLDOWN -> config.staffHudCooldown(state.spell, state.secondary,
            state.remainingTicks, staffSpellHudBar(state.progress))
    }

    private fun follow() {
        if (closed) return
        cards.toMap().forEach { (playerId, card) ->
            val player = Bukkit.getPlayer(playerId)
            val heldSpell = player?.let { StaffSpell.from(it.inventory.itemInMainHand) }
            if (player == null || !eligible(player) || heldSpell == null ||
                card.location.world?.uid != player.world.uid || !card.display.isValid) {
                clear(playerId)
                return@forEach
            }

            if (heldSpell != card.state.spell) {
                when (card.state.phase) {
                    StaffSpellHudPhase.READY -> ready(player, heldSpell, player.isSneaking)
                    StaffSpellHudPhase.COOLDOWN -> cooldown(player, heldSpell, player.isSneaking,
                        card.state.remainingTicks, card.state.totalTicks)
                    StaffSpellHudPhase.CHARGING, StaffSpellHudPhase.CHANNEL -> {
                        clear(playerId)
                        return@forEach
                    }
                }
            }

            // Sneak changes the selected attack. Charge input itself cancels on this transition.
            if (card.state.secondary != player.isSneaking) {
                when (card.state.phase) {
                    StaffSpellHudPhase.READY -> ready(player, card.state.spell, player.isSneaking)
                    StaffSpellHudPhase.COOLDOWN -> cooldown(player, card.state.spell, player.isSneaking,
                        card.state.remainingTicks, card.state.totalTicks)
                    else -> Unit
                }
            }
            val placement = placement(player)
            val at = placement.location
            if (at.world?.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4) == false) return@forEach
            if (card.location.world != at.world || card.location.distanceSquared(at) >= POSITION_EPSILON_SQUARED) {
                card.display.teleport(at)
                card.location = at
            }
            if (abs(card.scale - placement.scale) >= SCALE_EPSILON) {
                card.display.transformation = transformation(placement.scale)
                card.scale = placement.scale
            }
        }
    }

    private fun eligible(player: Player) = player.isOnline && player.isValid && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR

    /** Below the reticle in the camera plane; pull toward the eye if a solid block occludes the card. */
    private fun placement(player: Player): StaffHudPlacement {
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val cameraRight = Vector(cos(yaw), 0.0, sin(yaw))
        val cameraDown = cameraRight.crossProduct(direction).normalize()
        val baseOffset = direction.multiply(STAFF_HUD_FORWARD_DISTANCE)
            .add(cameraDown.multiply(STAFF_HUD_LOWER_OFFSET))
        val baseDistance = baseOffset.length()
        val ray = baseOffset.clone().normalize()
        val blockRay = staffBlockHit(eye, ray, baseDistance)
        val hitDistance = blockRay.hit?.hitPosition?.distance(eye.toVector())
        val safeDistance = staffHudSafeDistance(baseDistance, hitDistance.takeIf { blockRay.pathLoaded })
        val pose = staffSpellHudPose(safeDistance)
        return StaffHudPlacement(eye.add(ray.multiply(safeDistance)), pose.textScale)
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        cards.keys.toList().forEach(::clear)
        displays.close()
    }

    private companion object {
        const val POSITION_EPSILON_SQUARED = 0.0004
        const val SCALE_EPSILON = 0.0005f
    }
}

private data class StaffHudPlacement(val location: Location, val scale: Float)

internal data class StaffSpellHudPose(
    val forwardOffset: Double,
    val cameraDownOffset: Double,
    val distance: Double,
    val textScale: Float,
)

internal data class StaffSpellHudVisualSpec(
    val lineWidth: Int,
    val displayWidth: Float,
    val displayHeight: Float,
    val viewRange: Float,
    val followPeriodTicks: Long,
    val interpolationTicks: Int,
    val textOpacity: Byte,
    val shadowed: Boolean,
    val seeThrough: Boolean,
    val backgroundColor: Color,
)

internal fun staffSpellHudVisualSpec() = StaffSpellHudVisualSpec(
    lineWidth = 220,
    displayWidth = 3.0f,
    displayHeight = 1.0f,
    viewRange = 2.0f,
    followPeriodTicks = 2L,
    interpolationTicks = 2,
    textOpacity = 0xFF.toByte(),
    shadowed = true,
    seeThrough = false,
    backgroundColor = Color.fromARGB(148, 11, 17, 26),
)

internal const val STAFF_HUD_FORWARD_DISTANCE = 2.8
internal const val STAFF_HUD_LOWER_OFFSET = 0.62
internal const val STAFF_HUD_TEXT_SCALE = 0.72
internal const val STAFF_HUD_BLOCK_CLEARANCE = 0.18
internal const val STAFF_HUD_MIN_VISIBLE_DISTANCE = 0.12

internal fun staffHudBaseDistance() = kotlin.math.hypot(STAFF_HUD_FORWARD_DISTANCE, STAFF_HUD_LOWER_OFFSET)

internal fun staffHudSafeDistance(desiredDistance: Double, obstructionDistance: Double?): Double {
    val desired = desiredDistance.takeIf { it.isFinite() && it > 0.0 } ?: staffHudBaseDistance()
    val obstruction = obstructionDistance?.takeIf { it.isFinite() && it >= 0.0 } ?: return desired
    return (obstruction - STAFF_HUD_BLOCK_CLEARANCE).coerceAtLeast(STAFF_HUD_MIN_VISIBLE_DISTANCE).coerceAtMost(desired)
}

/** Source-owned camera-plane offsets and scale for the unclipped or nearer occlusion pose. */
internal fun staffSpellHudPose(distance: Double = staffHudBaseDistance()): StaffSpellHudPose {
    val baseDistance = staffHudBaseDistance()
    val safeDistance = distance.takeIf { it.isFinite() && it > 0.0 }
        ?.coerceIn(STAFF_HUD_MIN_VISIBLE_DISTANCE, baseDistance) ?: baseDistance
    val ratio = safeDistance / baseDistance
    return StaffSpellHudPose(
        forwardOffset = STAFF_HUD_FORWARD_DISTANCE * ratio,
        cameraDownOffset = STAFF_HUD_LOWER_OFFSET * ratio,
        distance = safeDistance,
        textScale = (STAFF_HUD_TEXT_SCALE * ratio).toFloat(),
    )
}

private fun transformation(scale: Float) =
    Transformation(Vector3f(), Quaternionf(), Vector3f(scale), Quaternionf())

internal fun staffSpellHudBar(progress: Double, segments: Int = 10): String {
    val bounded = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val filled = (bounded * segments).toInt().coerceIn(0, segments)
    return "■".repeat(filled) + "□".repeat(segments - filled)
}
