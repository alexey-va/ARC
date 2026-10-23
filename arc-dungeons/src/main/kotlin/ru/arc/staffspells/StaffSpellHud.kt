package ru.arc.staffspells

import net.kyori.adventure.text.Component
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
import ru.arc.paper.display.DisplayGeometry
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import net.luckperms.api.LuckPermsProvider
import ru.arc.staffspells.api.StaffHudMode
import ru.arc.staffspells.api.StaffHudPlacement
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

internal enum class StaffSpellHudPhase { READY, CHARGING, CHANNEL, COOLDOWN }

internal data class StaffSpellHudState(
    val spell: StaffSpell,
    val secondary: Boolean,
    val phase: StaffSpellHudPhase,
    val progress: Double = 0.0,
    val remainingTicks: Long = 0,
    val totalTicks: Long = 0,
    val hotkeys: StaffHotkeyHudState? = null,
    val armyCount: Int = 0,
)

internal data class StaffSpellHudPreferences(
    val mode: StaffHudMode = StaffHudMode.DEFAULT,
    val placement: StaffHudPlacement = StaffHudPlacement.DEFAULT,
)

internal fun staffSpellHudPreferencesFromStored(mode: String?, placement: String?) =
    StaffSpellHudPreferences(StaffHudMode.fromStored(mode), StaffHudPlacement.fromStored(placement))

internal fun interface StaffSpellHudPreferencesReader {
    fun read(player: Player): StaffSpellHudPreferences
}

/** Reads only LP's loaded user/cache; this never loads a user or performs storage IO on the tick. */
private object CachedStaffSpellHudPreferences : StaffSpellHudPreferencesReader {
    override fun read(player: Player): StaffSpellHudPreferences = runCatching {
        val user = LuckPermsProvider.get().userManager.getUser(player.uniqueId)
        val meta = user?.cachedData?.metaData
        staffSpellHudPreferencesFromStored(
            meta?.getMetaValue(StaffHudMode.META_KEY),
            meta?.getMetaValue(StaffHudPlacement.META_KEY),
        )
    }.getOrDefault(StaffSpellHudPreferences())
}

/** Private, camera-follow packet text for the currently selected staff ability. */
internal class StaffSpellHud(
    private val displays: PaperPacketDisplays,
    private val config: StaffSpellConfig,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val preferences: StaffSpellHudPreferencesReader = CachedStaffSpellHudPreferences,
    private val ownDisplays: Boolean = true,
) : AutoCloseable {
    private data class Card(
        val display: PacketTextDisplay,
        var state: StaffSpellHudState,
        var placement: StaffSpellHudPlacement,
        var preference: StaffHudPlacement,
        var bounds: StaffSpellHudBounds,
    )
    private data class TrackedBossBar(
        val bar: BossBar,
        var name: Component,
        var progress: Float,
        var state: StaffSpellHudState,
    )

    var hotkeyState: ((Player, StaffSpell) -> StaffHotkeyHudState)? = null
    var armyCount: (Player) -> Int = { 0 }

    private val cards = mutableMapOf<UUID, Card>()
    private val bossBars = mutableMapOf<UUID, TrackedBossBar>()
    private val states = mutableMapOf<UUID, StaffSpellHudState>()
    private val worldIds = mutableMapOf<UUID, UUID>()
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
        states.remove(playerId)
        worldIds.remove(playerId)
        removeCard(playerId)
        removeBossBar(playerId)
    }

    private fun update(player: Player, state: StaffSpellHudState) {
        if (closed || !eligible(player) || StaffSpell.from(player.inventory.itemInMainHand) != state.spell) {
            clear(player.uniqueId)
            return
        }
        val current = liveState(player, state)
        states[player.uniqueId] = current
        worldIds[player.uniqueId] = player.world.uid
        renderSurface(player, current, preferences.read(player))
    }

    private fun renderSurface(player: Player, state: StaffSpellHudState, choice: StaffSpellHudPreferences) {
        when (choice.mode) {
            StaffHudMode.HOLOGRAM -> {
                removeBossBar(player.uniqueId)
                showHologram(player, state, choice.placement)
            }
            StaffHudMode.BOSSBAR -> {
                removeCard(player.uniqueId)
                showBossBar(player, state)
            }
            StaffHudMode.OFF -> {
                removeCard(player.uniqueId)
                removeBossBar(player.uniqueId)
            }
        }
    }

    private fun showHologram(player: Player, state: StaffSpellHudState, placementChoice: StaffHudPlacement) {
        val card = cards[player.uniqueId]
        if (card != null && card.display.isValid) {
            if (card.state != state) {
                val text = render(state)
                card.display.text(text)
                card.state = state
                card.bounds = staffSpellHudBounds(PlainTextComponentSerializer.plainText().serialize(text), visual.lineWidth)
            }
            syncCard(player, card, placementChoice)
            return
        }
        val text = render(state)
        val bounds = staffSpellHudBounds(PlainTextComponentSerializer.plainText().serialize(text), visual.lineWidth)
        card?.display?.remove()
        val placement = placement(player, placementChoice, bounds)
        val at = player.location
        if (at.world?.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4) == false) {
            removeCard(player.uniqueId)
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
            teleportDuration = 0
            transformation = placement.transformation
            attachTo(player)
            showTo(player)
        }
        cards[player.uniqueId] = Card(display, state, placement, placementChoice, bounds)
    }

    private fun showBossBar(player: Player, state: StaffSpellHudState) {
        if (bossBars[player.uniqueId]?.state == state) return
        val name = config.staffHudBossbar(state).append(armyText(state)?.let {
            Component.text("  ·  ", net.kyori.adventure.text.format.TextColor.color(0x687D8F)).append(it)
        } ?: Component.empty()).append(state.hotkeys?.let {
            Component.text("  ·  ", net.kyori.adventure.text.format.TextColor.color(0x687D8F))
                .append(config.hotkeyHud(it))
        } ?: Component.empty())
        val progress = when (state.phase) {
            StaffSpellHudPhase.CHARGING, StaffSpellHudPhase.COOLDOWN ->
                state.progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f
            StaffSpellHudPhase.READY, StaffSpellHudPhase.CHANNEL -> 1f
        }
        val tracked = bossBars.getOrPut(player.uniqueId) {
            val created = BossBar.bossBar(name, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)
            player.showBossBar(created)
            TrackedBossBar(created, name, progress, state)
        }
        if (tracked.name != name) {
            tracked.bar.name(name)
            tracked.name = name
        }
        if (abs(tracked.progress - progress) >= BAR_PROGRESS_EPSILON) {
            tracked.bar.progress(progress)
            tracked.progress = progress
        }
        tracked.state = state
    }

    private fun syncCard(player: Player, card: Card, placementChoice: StaffHudPlacement) {
        val placement = placement(player, placementChoice, card.bounds)
        // Mounted movement and CENTER billboard orientation run on the local client. Only this
        // eye-height/wall correction is interpolated; changing sides or a wall pull-in must snap.
        // Compare with the last applied pose so small changes accumulate instead of being lost.
        if (card.placement.transformation.translation.distanceSquared(placement.transformation.translation) >=
                POSITION_EPSILON_SQUARED ||
            abs(card.placement.pose.textScale - placement.pose.textScale) >= SCALE_EPSILON) {
            val smooth = card.preference == placementChoice &&
                placement.clipFraction >= card.placement.clipFraction - 1.0e-6
            card.display.interpolationDuration = if (smooth) visual.interpolationTicks else 0
            card.display.transformation = placement.transformation
            card.placement = placement
        }
        card.preference = placementChoice
    }

    private fun removeCard(playerId: UUID) { cards.remove(playerId)?.display?.remove() }

    private fun removeBossBar(playerId: UUID) {
        bossBars.remove(playerId)?.let { tracked -> Bukkit.getPlayer(playerId)?.hideBossBar(tracked.bar) }
    }

    private fun render(state: StaffSpellHudState): Component = (when (state.phase) {
        StaffSpellHudPhase.READY -> config.staffHudReady(state.spell, state.secondary)
        StaffSpellHudPhase.CHARGING -> config.staffHudCharging(state.spell, state.secondary,
            state.progress, staffSpellHudBar(state.progress))
        StaffSpellHudPhase.CHANNEL -> config.staffHudChannel(state.spell, state.secondary)
        StaffSpellHudPhase.COOLDOWN -> config.staffHudCooldown(state.spell, state.secondary,
            state.remainingTicks, staffSpellHudBar(state.progress))
    }).append(armyText(state)?.let { Component.newline().append(it) } ?: Component.empty())
        .append(state.hotkeys?.let { Component.newline().append(config.hotkeyHud(it)) } ?: Component.empty())

    private fun armyText(state: StaffSpellHudState): Component? =
        if (state.spell == StaffSpell.NECROMANCER) config.staffHudArmy(state.armyCount, STAFF_SUMMON_ARMY_LIMIT) else null

    private fun liveState(player: Player, state: StaffSpellHudState) = state.copy(
        hotkeys = hotkeyState?.invoke(player, state.spell),
        armyCount = if (state.spell == StaffSpell.NECROMANCER) armyCount(player).coerceIn(0, STAFF_SUMMON_ARMY_LIMIT) else 0,
    )

    private fun follow() {
        if (closed) return
        states.toMap().forEach { (playerId, previousState) ->
            val player = Bukkit.getPlayer(playerId)
            val heldSpell = player?.let { StaffSpell.from(it.inventory.itemInMainHand) }
            if (player == null || !eligible(player) || heldSpell == null ||
                worldIds[playerId] != player.world.uid) {
                clear(playerId)
                return@forEach
            }

            var state = previousState
            if (heldSpell != state.spell) {
                state = when (state.phase) {
                    StaffSpellHudPhase.READY -> StaffSpellHudState(heldSpell, player.isSneaking, StaffSpellHudPhase.READY)
                    StaffSpellHudPhase.COOLDOWN -> state.copy(spell = heldSpell, secondary = player.isSneaking)
                    StaffSpellHudPhase.CHARGING, StaffSpellHudPhase.CHANNEL -> {
                        clear(playerId)
                        return@forEach
                    }
                }
            }

            // Sneak changes the selected attack. Charge input itself cancels on this transition.
            if (state.secondary != player.isSneaking) {
                when (state.phase) {
                    StaffSpellHudPhase.READY -> state = state.copy(secondary = player.isSneaking)
                    StaffSpellHudPhase.COOLDOWN -> state = state.copy(secondary = player.isSneaking)
                    else -> Unit
                }
            }
            state = liveState(player, state)
            states[playerId] = state
            renderSurface(player, state, preferences.read(player))
        }
    }

    private fun eligible(player: Player) = player.isOnline && player.isValid && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR

    /** Client-owned movement, a camera-local card, and full-card block clipping from the eye. */
    private fun placement(
        player: Player,
        preference: StaffHudPlacement,
        bounds: StaffSpellHudBounds,
    ): StaffSpellHudPlacement {
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val cameraRight = Vector(-cos(yaw), 0.0, -sin(yaw))
        val cameraDown = direction.clone().crossProduct(cameraRight).normalize()
        val passengerGap = player.height - player.eyeHeight
        val position = staffHudPosition(config.hudPosition, preference, bounds, passengerGap)
        val basePose = staffSpellHudPose(position)
        val baseOffset = direction.clone().multiply(basePose.forwardOffset)
            .add(cameraDown.clone().multiply(basePose.cameraDownOffset))
            .add(cameraRight.clone().multiply(basePose.horizontalOffset))
        val fraction = staffHudCardOffsets(baseOffset, cameraRight, cameraDown, bounds, basePose.textScale)
            .minOf { offset ->
                val distance = offset.length()
                val blockRay = staffBlockHit(eye, offset.clone().normalize(), distance)
                val hit = blockRay.hit?.hitPosition?.distance(eye.toVector()).takeIf { blockRay.pathLoaded }
                staffHudSafeDistance(distance, hit) / distance
            }
        val safeDistance = (basePose.distance * fraction).coerceAtLeast(STAFF_HUD_MIN_VISIBLE_DISTANCE)
        val pose = staffSpellHudPose(safeDistance, position)
        return StaffSpellHudPlacement(pose,
            staffHudBillboardTransformation(pose, bounds, eye, passengerGap), fraction)
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        (states.keys + cards.keys + bossBars.keys).toSet().forEach(::clear)
        if (ownDisplays) displays.close()
    }

    private companion object {
        const val POSITION_EPSILON_SQUARED = 0.000001
        const val SCALE_EPSILON = 0.0005f
        const val BAR_PROGRESS_EPSILON = 0.0005f
    }
}

private data class StaffSpellHudPlacement(
    val pose: StaffSpellHudPose,
    val transformation: Transformation,
    val clipFraction: Double,
)

internal data class StaffSpellHudPose(
    val forwardOffset: Double,
    val cameraDownOffset: Double,
    val horizontalOffset: Double,
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
    // The visible card is translated away from its passenger anchor. A box at that anchor would
    // incorrectly frustum-cull it when looking up/down; zero disables display frustum culling.
    displayWidth = 0.0f,
    displayHeight = 0.0f,
    viewRange = 2.0f,
    followPeriodTicks = 1L,
    interpolationTicks = 1,
    textOpacity = 0xFF.toByte(),
    shadowed = true,
    seeThrough = false,
    backgroundColor = Color.fromARGB(148, 11, 17, 26),
)

internal const val STAFF_HUD_BLOCK_CLEARANCE = 0.18
internal const val STAFF_HUD_MIN_VISIBLE_DISTANCE = 0.12
private const val STAFF_HUD_AIM_SLOPE = 0.14 // Approximately eight degrees of clear view around the reticle.

/** Conservative native font bounds, including background and wrapping; not the entity culling box. */
internal data class StaffSpellHudBounds(
    val halfWidth: Double,
    val verticalRadius: Double,
    val originUp: Double,
)

internal fun staffSpellHudBounds(text: String, lineWidth: Int): StaffSpellHudBounds {
    // Vanilla staff labels fit a nine-pixel glyph advance. Native TextDisplay text grows upward
    // from its origin (10 pixels/line); centering this upper bound also covers shorter wrapped text.
    val lines = text.lines().sumOf { line ->
        max(1, ceil(line.codePointCount(0, line.length) * 9.0 / lineWidth).toInt())
    }
    return StaffSpellHudBounds((lineWidth + 2) * 0.025 / 2,
        (lines * 10 + 2) * 0.025 / 2, (lines * 10 - 1) * 0.025 / 2)
}

internal fun staffHudPosition(
    position: StaffSpellHudPositionSettings,
    preference: StaffHudPlacement,
    bounds: StaffSpellHudBounds,
    passengerGap: Double = 0.18,
): StaffSpellHudPositionSettings {
    val side = if (preference == StaffHudPlacement.BOTTOM) 1.0 else -1.0
    // The native passenger already follows the client's predicted movement. A speed/turn-based
    // retreat here would turn server movement-packet jitter into visible HUD movement and scaling.
    val distance = position.forwardBlocks
    val radius = bounds.verticalRadius * position.scale
    // The only delayed world correction is the small head-to-eye gap. Reserve its full possible
    // reversal (plus ordinary pose changes), not a yaw-dependent vertical offset for the whole card.
    val anchorError = max(0.5, 2 * abs(passengerGap))
    val vertical = max(abs(position.verticalBlocks),
        radius + anchorError + distance * STAFF_HUD_AIM_SLOPE)
    return position.copy(forwardBlocks = distance, verticalBlocks = side * vertical)
}

/** CENTER billboard rotates this local translation with the client camera before rendering. */
internal fun staffHudBillboardTransformation(
    pose: StaffSpellHudPose,
    bounds: StaffSpellHudBounds,
    eye: Location,
    passengerGap: Double,
): Transformation {
    val forward = eye.direction.normalize()
    val yaw = Math.toRadians(eye.yaw.toDouble())
    val right = Vector(-cos(yaw), 0.0, -sin(yaw))
    val down = forward.clone().crossProduct(right).normalize()
    val correction = Vector(0.0, -passengerGap, 0.0)
    return Transformation(Vector3f(
        (pose.horizontalOffset + correction.dot(right)).toFloat(),
        (-pose.cameraDownOffset - correction.dot(down) - bounds.originUp * pose.textScale).toFloat(),
        (-pose.forwardOffset - correction.dot(forward)).toFloat(),
    ), Quaternionf(), Vector3f(pose.textScale), Quaternionf())
}

internal fun staffHudCardOffsets(
    center: Vector,
    right: Vector,
    down: Vector,
    bounds: StaffSpellHudBounds,
    scale: Float,
): List<Vector> = DisplayGeometry.rectangleSamples(
    center, right, down, bounds.halfWidth * scale, bounds.verticalRadius * scale,
)

internal fun staffHudBaseDistance(position: StaffSpellHudPositionSettings = StaffSpellHudPositionSettings()): Double =
    kotlin.math.sqrt(position.forwardBlocks * position.forwardBlocks +
        position.verticalBlocks * position.verticalBlocks + position.horizontalBlocks * position.horizontalBlocks)

internal fun staffHudSafeDistance(desiredDistance: Double, obstructionDistance: Double?): Double {
    val desired = desiredDistance.takeIf { it.isFinite() && it > 0.0 } ?: staffHudBaseDistance()
    val obstruction = obstructionDistance?.takeIf { it.isFinite() && it >= 0.0 } ?: return desired
    return DisplayGeometry.clipDistance(desired, obstruction, STAFF_HUD_BLOCK_CLEARANCE, STAFF_HUD_MIN_VISIBLE_DISTANCE)
}

/** Source-owned camera-plane offsets and scale for the unclipped or nearer occlusion pose. */
internal fun staffSpellHudPose(position: StaffSpellHudPositionSettings): StaffSpellHudPose =
    staffSpellHudPose(staffHudBaseDistance(position), position)

internal fun staffSpellHudPose(distance: Double = staffHudBaseDistance()): StaffSpellHudPose =
    staffSpellHudPose(distance, StaffSpellHudPositionSettings())

internal fun staffSpellHudPose(
    distance: Double,
    position: StaffSpellHudPositionSettings,
): StaffSpellHudPose {
    val baseDistance = staffHudBaseDistance(position)
    val safeDistance = distance.takeIf { it.isFinite() && it > 0.0 }
        ?.coerceIn(STAFF_HUD_MIN_VISIBLE_DISTANCE, baseDistance) ?: baseDistance
    val ratio = safeDistance / baseDistance
    return StaffSpellHudPose(
        forwardOffset = position.forwardBlocks * ratio,
        cameraDownOffset = position.verticalBlocks * ratio,
        horizontalOffset = position.horizontalBlocks * ratio,
        distance = safeDistance,
        textScale = DisplayGeometry.distanceCompensatedScale(position.scale, baseDistance, safeDistance),
    )
}

internal fun staffSpellHudBar(progress: Double, segments: Int = 10): String {
    val bounded = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val filled = (bounded * segments).toInt().coerceIn(0, segments)
    return "■".repeat(filled) + "□".repeat(segments - filled)
}
