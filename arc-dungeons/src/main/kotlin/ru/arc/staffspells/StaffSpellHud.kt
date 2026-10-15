package ru.arc.staffspells

import net.kyori.adventure.text.Component
import net.kyori.adventure.bossbar.BossBar
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
import net.luckperms.api.LuckPermsProvider
import ru.arc.staffspells.api.StaffHudMode
import ru.arc.staffspells.api.StaffHudPlacement
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
) : AutoCloseable {
    private data class Card(
        val display: PacketTextDisplay,
        var state: StaffSpellHudState,
        var location: Location,
        var scale: Float,
    )
    private data class TrackedBossBar(val bar: BossBar, var name: Component, var progress: Float)

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
        states[player.uniqueId] = state
        worldIds[player.uniqueId] = player.world.uid
        renderSurface(player, state, preferences.read(player))
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
        val text = render(state)
        val card = cards[player.uniqueId]
        if (card != null && card.display.isValid) {
            if (card.state != state) card.display.text(text)
            card.state = state
            syncCard(player, card, placementChoice)
            return
        }
        card?.display?.remove()
        val placement = placement(player, placementChoice)
        val at = placement.location
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
            teleportDuration = visual.interpolationTicks
            transformation = transformation(placement.scale)
            showTo(player)
        }
        cards[player.uniqueId] = Card(display, state, at, placement.scale)
    }

    private fun showBossBar(player: Player, state: StaffSpellHudState) {
        val name = config.staffHudBossbar(state)
        val progress = when (state.phase) {
            StaffSpellHudPhase.CHARGING, StaffSpellHudPhase.COOLDOWN ->
                state.progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f
            StaffSpellHudPhase.READY, StaffSpellHudPhase.CHANNEL -> 1f
        }
        val tracked = bossBars.getOrPut(player.uniqueId) {
            val created = BossBar.bossBar(name, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)
            player.showBossBar(created)
            TrackedBossBar(created, name, progress)
        }
        if (tracked.name != name) {
            tracked.bar.name(name)
            tracked.name = name
        }
        if (abs(tracked.progress - progress) >= BAR_PROGRESS_EPSILON) {
            tracked.bar.progress(progress)
            tracked.progress = progress
        }
    }

    private fun syncCard(player: Player, card: Card, placementChoice: StaffHudPlacement) {
        val placement = placement(player, placementChoice)
        val at = placement.location
        if (at.world?.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4) == false) return
        if (card.location.world != at.world || card.location.distanceSquared(at) >= POSITION_EPSILON_SQUARED) {
            card.display.teleport(at)
            card.location = at
        }
        if (abs(card.scale - placement.scale) >= SCALE_EPSILON) {
            card.display.transformation = transformation(placement.scale)
            card.scale = placement.scale
        }
    }

    private fun removeCard(playerId: UUID) { cards.remove(playerId)?.display?.remove() }

    private fun removeBossBar(playerId: UUID) {
        bossBars.remove(playerId)?.let { tracked -> Bukkit.getPlayer(playerId)?.hideBossBar(tracked.bar) }
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
            states[playerId] = state
            renderSurface(player, state, preferences.read(player))
        }
    }

    private fun eligible(player: Player) = player.isOnline && player.isValid && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR

    /** Below the reticle in the camera plane; pull toward the eye if a solid block occludes the card. */
    private fun placement(player: Player, preference: StaffHudPlacement): StaffSpellHudPlacement {
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val cameraRight = Vector(cos(yaw), 0.0, sin(yaw))
        val cameraDown = cameraRight.clone().crossProduct(direction).normalize()
        val configured = config.hudPosition
        val position = configured.copy(verticalBlocks = if (preference == StaffHudPlacement.BOTTOM)
            configured.verticalBlocks else -configured.verticalBlocks)
        val basePose = staffSpellHudPose(position)
        val baseOffset = direction.clone().multiply(basePose.forwardOffset)
            .add(cameraDown.clone().multiply(basePose.cameraDownOffset))
            .add(cameraRight.clone().multiply(basePose.horizontalOffset))
        val ray = baseOffset.clone().normalize()
        val blockRay = staffBlockHit(eye, ray, basePose.distance)
        val hitDistance = blockRay.hit?.hitPosition?.distance(eye.toVector())
        val safeDistance = staffHudSafeDistance(basePose.distance, hitDistance.takeIf { blockRay.pathLoaded })
        val pose = staffSpellHudPose(safeDistance, position)
        val at = eye.clone()
            .add(direction.clone().multiply(pose.forwardOffset))
            .add(cameraDown.clone().multiply(pose.cameraDownOffset))
            .add(cameraRight.clone().multiply(pose.horizontalOffset))
        return StaffSpellHudPlacement(at, pose.textScale)
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        (states.keys + cards.keys + bossBars.keys).toSet().forEach(::clear)
        displays.close()
    }

    private companion object {
        const val POSITION_EPSILON_SQUARED = 0.0004
        const val SCALE_EPSILON = 0.0005f
        const val BAR_PROGRESS_EPSILON = 0.0005f
    }
}

private data class StaffSpellHudPlacement(val location: Location, val scale: Float)

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

internal const val STAFF_HUD_BLOCK_CLEARANCE = 0.18
internal const val STAFF_HUD_MIN_VISIBLE_DISTANCE = 0.12

internal fun staffHudBaseDistance(position: StaffSpellHudPositionSettings = StaffSpellHudPositionSettings()): Double =
    kotlin.math.sqrt(position.forwardBlocks * position.forwardBlocks +
        position.verticalBlocks * position.verticalBlocks + position.horizontalBlocks * position.horizontalBlocks)

internal fun staffHudSafeDistance(desiredDistance: Double, obstructionDistance: Double?): Double {
    val desired = desiredDistance.takeIf { it.isFinite() && it > 0.0 } ?: staffHudBaseDistance()
    val obstruction = obstructionDistance?.takeIf { it.isFinite() && it >= 0.0 } ?: return desired
    return (obstruction - STAFF_HUD_BLOCK_CLEARANCE).coerceAtLeast(STAFF_HUD_MIN_VISIBLE_DISTANCE).coerceAtMost(desired)
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
        textScale = (position.scale * ratio).toFloat(),
    )
}

private fun transformation(scale: Float) =
    Transformation(Vector3f(), Quaternionf(), Vector3f(scale), Quaternionf())

internal fun staffSpellHudBar(progress: Double, segments: Int = 10): String {
    val bounded = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val filled = (bounded * segments).toInt().coerceIn(0, segments)
    return "■".repeat(filled) + "□".repeat(segments - filled)
}
