package ru.arc

import com.destroystokyo.paper.ParticleBuilder
import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.config.Config
import ru.arc.util.Logging.warn
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

internal const val ORIGIN_GATE_PERMISSION = "arc.portal.origin-gate"

internal fun shouldUseOriginGate(
    enabled: Boolean,
    hasPermission: Boolean,
): Boolean = enabled && hasPermission

internal enum class OriginGateOpeningCurve {
    SMOOTH,
    DRAMATIC;

    companion object {
        fun parse(value: String): OriginGateOpeningCurve? =
            entries.firstOrNull { curve -> curve.name.equals(value.trim(), ignoreCase = true) }
    }
}

internal data class PortalOriginGateSettings(
    val astralItemId: String,
    val chaosItemId: String,
    val openingStartTick: Int,
    val openingDurationTicks: Int,
    val openingCurve: OriginGateOpeningCurve,
    val closingDurationTicks: Int,
    val width: Float,
    val height: Float,
    val verticalOffset: Double,
    val yawOffsetDegrees: Float,
    val viewRange: Float,
    val openingSoundEnabled: Boolean,
    val openingSoundDelayTicks: Int,
    val openingSoundId: String,
    val openingSoundVolume: Float,
    val openingSoundPitch: Float,
    val suctionEnabled: Boolean,
    val suctionStreams: Int,
    val reducedSuctionStreams: Int,
    val suctionPointsPerStream: Int,
    val reducedSuctionPointsPerStream: Int,
    val suctionRadius: Double,
    val suctionHeight: Double,
    val suctionTurns: Double,
    val suctionParticleSize: Float,
    val suctionCoreCount: Int,
) {
    val entryTick: Int
        get() = openingStartTick + openingDurationTicks

    companion object {
        private val namespacedId = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

        fun load(config: Config): PortalOriginGateSettings? {
            val path = "portal.origin-gate"
            if (!config.bool("$path.enabled", false)) return null

            return validated(
                astralItemId = config.string("$path.astral-item", ""),
                chaosItemId = config.string("$path.chaos-item", ""),
                openingStartTick = config.integer("$path.opening-start-tick", 0),
                openingDurationTicks = config.integer("$path.opening-duration-ticks", 66),
                openingCurve = config.string("$path.opening-curve", "dramatic"),
                closingDurationTicks = config.integer("$path.closing-duration-ticks", 12),
                width = config.real("$path.width", 6.0).toFloat(),
                height = config.real("$path.height", 8.4).toFloat(),
                verticalOffset = config.real("$path.vertical-offset", 2.75),
                yawOffsetDegrees = config.real("$path.yaw-offset-degrees", 180.0).toFloat(),
                viewRange = config.real("$path.view-range", 1.0).toFloat(),
                openingSoundEnabled = config.bool("$path.sound.enabled", true),
                openingSoundDelayTicks = config.integer("$path.sound.delay-ticks", 40),
                openingSoundId = config.string("$path.sound.id", "minecraft:block.end_portal.spawn"),
                openingSoundVolume = config.real("$path.sound.volume", 1.35).toFloat(),
                openingSoundPitch = config.real("$path.sound.pitch", 0.9).toFloat(),
                suctionEnabled = config.bool("$path.suction.enabled", true),
                suctionStreams = config.integer("$path.suction.streams", 10),
                reducedSuctionStreams = config.integer("$path.suction.reduced-streams", 4),
                suctionPointsPerStream = config.integer("$path.suction.points-per-stream", 3),
                reducedSuctionPointsPerStream = config.integer("$path.suction.reduced-points-per-stream", 1),
                suctionRadius = config.real("$path.suction.radius", 6.0),
                suctionHeight = config.real("$path.suction.height", 7.0),
                suctionTurns = config.real("$path.suction.turns", 2.25),
                suctionParticleSize = config.real("$path.suction.particle-size", 0.8).toFloat(),
                suctionCoreCount = config.integer("$path.suction.core-count", 12),
            ).also { settings ->
                if (settings == null) {
                    warn("Portal origin-gate config is invalid; falling back to the legacy portal visual")
                }
            }
        }

        internal fun validated(
            astralItemId: String,
            chaosItemId: String,
            openingStartTick: Int,
            openingDurationTicks: Int,
            openingCurve: String,
            closingDurationTicks: Int,
            width: Float,
            height: Float,
            verticalOffset: Double,
            yawOffsetDegrees: Float,
            viewRange: Float,
            openingSoundEnabled: Boolean,
            openingSoundDelayTicks: Int,
            openingSoundId: String,
            openingSoundVolume: Float,
            openingSoundPitch: Float,
            suctionEnabled: Boolean,
            suctionStreams: Int,
            reducedSuctionStreams: Int,
            suctionPointsPerStream: Int,
            reducedSuctionPointsPerStream: Int,
            suctionRadius: Double,
            suctionHeight: Double,
            suctionTurns: Double,
            suctionParticleSize: Float,
            suctionCoreCount: Int,
        ): PortalOriginGateSettings? {
            val normalizedAstral = astralItemId.trim().lowercase()
            val normalizedChaos = chaosItemId.trim().lowercase()
            val normalizedSound = openingSoundId.trim().lowercase()
            val normalizedOpeningCurve = OriginGateOpeningCurve.parse(openingCurve) ?: return null
            if (normalizedAstral.length !in 3..128 || !namespacedId.matches(normalizedAstral)) return null
            if (normalizedChaos.length !in 3..128 || !namespacedId.matches(normalizedChaos)) return null
            if (openingStartTick !in 0..58) return null
            if (openingDurationTicks !in 1..200 || closingDurationTicks !in 1..60) return null
            if (openingSoundDelayTicks !in 0..openingDurationTicks) return null
            if (!width.isFinite() || width !in 0.1f..12.0f) return null
            if (!height.isFinite() || height !in 0.1f..12.0f) return null
            if (!verticalOffset.isFinite() || verticalOffset !in 0.5..12.0) return null
            if (!yawOffsetDegrees.isFinite() || yawOffsetDegrees !in -360f..360f) return null
            if (!viewRange.isFinite() || viewRange !in 0.1f..4.0f) return null
            if (
                openingSoundEnabled &&
                (normalizedSound.length !in 3..128 || !namespacedId.matches(normalizedSound))
            ) {
                return null
            }
            if (!openingSoundVolume.isFinite() || openingSoundVolume !in 0.01f..4.0f) return null
            if (!openingSoundPitch.isFinite() || openingSoundPitch !in 0.5f..2.0f) return null
            if (suctionStreams !in 1..16) return null
            if (reducedSuctionStreams !in 1..suctionStreams) return null
            if (suctionPointsPerStream !in 1..6) return null
            if (reducedSuctionPointsPerStream !in 1..suctionPointsPerStream) return null
            if (suctionStreams * suctionPointsPerStream > MAX_SUCTION_PARTICLES_PER_TICK) return null
            if (reducedSuctionStreams * reducedSuctionPointsPerStream > MAX_SUCTION_PARTICLES_PER_TICK) return null
            if (!suctionRadius.isFinite() || suctionRadius !in 0.25..12.0) return null
            if (!suctionHeight.isFinite() || suctionHeight !in 0.25..12.0) return null
            if (!suctionTurns.isFinite() || suctionTurns !in 0.25..4.0) return null
            if (!suctionParticleSize.isFinite() || suctionParticleSize !in 0.1f..2.0f) return null
            if (suctionCoreCount !in 0..24) return null

            return PortalOriginGateSettings(
                astralItemId = normalizedAstral,
                chaosItemId = normalizedChaos,
                openingStartTick = openingStartTick,
                openingDurationTicks = openingDurationTicks,
                openingCurve = normalizedOpeningCurve,
                closingDurationTicks = closingDurationTicks,
                width = width,
                height = height,
                verticalOffset = verticalOffset,
                yawOffsetDegrees = yawOffsetDegrees,
                viewRange = viewRange,
                openingSoundEnabled = openingSoundEnabled,
                openingSoundDelayTicks = openingSoundDelayTicks,
                openingSoundId = normalizedSound,
                openingSoundVolume = openingSoundVolume,
                openingSoundPitch = openingSoundPitch,
                suctionEnabled = suctionEnabled,
                suctionStreams = suctionStreams,
                reducedSuctionStreams = reducedSuctionStreams,
                suctionPointsPerStream = suctionPointsPerStream,
                reducedSuctionPointsPerStream = reducedSuctionPointsPerStream,
                suctionRadius = suctionRadius,
                suctionHeight = suctionHeight,
                suctionTurns = suctionTurns,
                suctionParticleSize = suctionParticleSize,
                suctionCoreCount = suctionCoreCount,
            )
        }
    }
}

internal interface PortalOriginGateHandle {
    fun updateScale(multiplier: Float)

    fun playOpeningSound()

    fun prepareClosing()

    fun remove()
}

internal class PortalOriginGateController(
    private val settings: PortalOriginGateSettings,
    private val spawn: () -> PortalOriginGateHandle?,
) {
    private var handle: PortalOriginGateHandle? = null
    private var spawnAttempted = false
    private var openingSoundPlayed = false
    private var closing = false
    private var closingTicks = 0
    private var removed = false

    val isActive: Boolean
        get() = !removed && handle != null

    fun tickOpening(phase: Int): Boolean {
        if (removed || closing || phase < settings.openingStartTick) return isActive

        if (!spawnAttempted) {
            spawnAttempted = true
            handle = spawn()
        }

        val activeHandle = handle ?: return false
        val elapsedTicks = (phase - settings.openingStartTick).coerceAtLeast(0)
        activeHandle.updateScale(
            originGateOpeningScale(elapsedTicks, settings.openingDurationTicks, settings.openingCurve),
        )
        if (!openingSoundPlayed && elapsedTicks >= settings.openingSoundDelayTicks) {
            openingSoundPlayed = true
            activeHandle.playOpeningSound()
        }
        return true
    }

    fun beginClosing() {
        val activeHandle = handle ?: return
        if (removed || closing) return
        closing = true
        closingTicks = 0
        activeHandle.prepareClosing()
        activeHandle.updateScale(1f)
    }

    fun tickClosing(): Boolean {
        if (removed || !closing || handle == null) return false
        if (closingTicks >= settings.closingDurationTicks) return false
        closingTicks++
        handle?.updateScale(originGateClosingScale(closingTicks, settings.closingDurationTicks))
        return true
    }

    fun remove() {
        if (removed) return
        removed = true
        handle?.remove()
        handle = null
    }
}

internal fun originGateOpeningScale(
    elapsedTicks: Int,
    durationTicks: Int,
    curve: OriginGateOpeningCurve,
): Float {
    if (elapsedTicks <= durationTicks) {
        val progress = (elapsedTicks.toFloat() / durationTicks).coerceIn(0f, 1f)
        val eased =
            when (curve) {
                OriginGateOpeningCurve.SMOOTH -> progress * progress * (3f - 2f * progress)
                OriginGateOpeningCurve.DRAMATIC -> dramaticOriginGateOpening(progress)
            }
        return TINY_SCALE_MULTIPLIER + ((1f - TINY_SCALE_MULTIPLIER) * eased)
    }
    val idleTicks = elapsedTicks - durationTicks
    return 1f + (sin(idleTicks * 0.18f) * 0.035f)
}

private fun dramaticOriginGateOpening(progress: Float): Float {
    if (progress <= DRAMATIC_SNAP_PROGRESS) {
        val charge = (progress / DRAMATIC_SNAP_PROGRESS).coerceIn(0f, 1f)
        val easedCharge = charge * charge * (3f - 2f * charge)
        return DRAMATIC_CHARGE_SCALE * easedCharge
    }

    val snap =
        ((progress - DRAMATIC_SNAP_PROGRESS) / (1f - DRAMATIC_SNAP_PROGRESS))
            .coerceIn(0f, 1f)
    val easedSnap = 1f - (1f - snap).pow(3)
    return DRAMATIC_CHARGE_SCALE + ((1f - DRAMATIC_CHARGE_SCALE) * easedSnap)
}

internal fun originGateClosingScale(
    elapsedTicks: Int,
    durationTicks: Int,
): Float {
    val progress = (elapsedTicks.toFloat() / durationTicks).coerceIn(0f, 1f)
    val eased = progress * progress * (3f - 2f * progress)
    return TINY_SCALE_MULTIPLIER + ((1f - TINY_SCALE_MULTIPLIER) * (1f - eased))
}

internal fun originGateFacingYaw(
    fromX: Double,
    fromZ: Double,
    targetX: Double,
    targetZ: Double,
): Float {
    val deltaX = targetX - fromX
    val deltaZ = targetZ - fromZ
    if ((deltaX * deltaX) + (deltaZ * deltaZ) < 1.0e-6) return 0f
    return Math.toDegrees(atan2(-deltaX, deltaZ)).toFloat()
}

internal fun originGateDisplayYaw(
    fromX: Double,
    fromZ: Double,
    targetX: Double,
    targetZ: Double,
    yawOffsetDegrees: Float,
): Float {
    var yaw = (originGateFacingYaw(fromX, fromZ, targetX, targetZ) + yawOffsetDegrees) % 360f
    if (yaw < -180f) yaw += 360f
    if (yaw > 180f) yaw -= 360f
    return if (yaw == -180f) 180f else yaw
}

internal data class OriginGateParticleOffset(
    val stream: Int,
    val trailPoint: Int,
    val x: Double,
    val y: Double,
    val z: Double,
)

internal fun originGateParticleOffsets(
    tick: Int,
    streams: Int,
    pointsPerStream: Int,
    radius: Double,
    height: Double,
    turns: Double,
): List<OriginGateParticleOffset> {
    val advance = Math.floorMod(tick, SUCTION_CYCLE_TICKS) / SUCTION_CYCLE_TICKS.toDouble()
    return buildList(streams * pointsPerStream) {
        repeat(streams) { stream ->
            repeat(pointsPerStream) { trailPoint ->
                val travel =
                    (advance + (stream / streams.toDouble()) + (trailPoint * SUCTION_TRAIL_SPACING)) % 1.0
                val remaining = 1.0 - travel
                val currentRadius = radius * remaining.pow(0.88)
                val angle =
                    (tick * 0.18) +
                        ((2.0 * PI * stream) / streams) +
                        (travel * turns * 2.0 * PI)
                add(
                    OriginGateParticleOffset(
                        stream = stream,
                        trailPoint = trailPoint,
                        x = cos(angle) * currentRadius,
                        y = sin((angle * 0.72) + (stream * 0.91)) * (height * 0.5) * remaining.pow(0.72),
                        z = sin(angle) * currentRadius,
                    ),
                )
            }
        }
    }
}

internal object BukkitPortalOriginGate {
    fun spawn(
        base: Block,
        settings: PortalOriginGateSettings,
        viewerLocation: Location,
    ): PortalOriginGateHandle? {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null

        val astral = item(settings.astralItemId) ?: return missing(settings.astralItemId)
        val chaos = item(settings.chaosItemId) ?: return missing(settings.chaosItemId)
        val location =
            base.location.clone().add(
                0.5,
                settings.verticalOffset,
                0.5,
            )
        location.yaw =
            originGateDisplayYaw(
                location.x,
                location.z,
                viewerLocation.x,
                viewerLocation.z,
                settings.yawOffsetDegrees,
            )
        location.pitch = 0f

        var display: ItemDisplay? = null
        return try {
            val created = base.world.spawn(location, ItemDisplay::class.java)
            display = created
            configure(created, astral, settings)
            BukkitPortalOriginGateHandle(created, chaos, location, settings)
        } catch (failure: Exception) {
            display?.remove()
            warn(
                "Could not spawn portal origin-gate display; falling back to the legacy visual: {}",
                failure.message ?: failure.javaClass.simpleName,
            )
            null
        }
    }

    private fun item(namespacedId: String): ItemStack? =
        runCatching { CustomStack.getInstance(namespacedId)?.itemStack?.clone() }.getOrNull()

    private fun missing(namespacedId: String): PortalOriginGateHandle? {
        warn("Portal origin-gate item '{}' is unavailable; falling back to the legacy visual", namespacedId)
        return null
    }

    private fun playOpeningSound(
        location: Location,
        settings: PortalOriginGateSettings,
    ) {
        if (!settings.openingSoundEnabled) return
        location.world.playSound(
            location,
            settings.openingSoundId,
            SoundCategory.BLOCKS,
            settings.openingSoundVolume,
            settings.openingSoundPitch,
        )
    }

    private fun configure(
        display: ItemDisplay,
        astral: ItemStack,
        settings: PortalOriginGateSettings,
    ) {
        display.setItemStack(astral)
        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
        display.billboard = Display.Billboard.FIXED
        display.brightness = Display.Brightness(15, 15)
        display.shadowRadius = 0f
        display.shadowStrength = 0f
        display.viewRange = settings.viewRange
        display.displayWidth = settings.width * 1.25f
        display.displayHeight = settings.height * 1.25f
        display.isPersistent = false
        display.setGravity(false)
        display.isInvulnerable = true
        display.interpolationDelay = 0
        display.interpolationDuration = 0
        display.transformation = transformation(
            settings.width * TINY_SCALE_MULTIPLIER,
            settings.height * TINY_SCALE_MULTIPLIER,
            1f,
        )
    }

    fun renderSuction(
        center: Location,
        tick: Int,
        settings: PortalOriginGateSettings,
        fullReceivers: Collection<Player>,
        reducedReceivers: Collection<Player>,
    ) {
        if (!settings.suctionEnabled) return
        renderSuction(
            center,
            tick,
            settings.suctionStreams,
            settings.suctionPointsPerStream,
            settings,
            fullReceivers,
        )
        if (tick % 2 == 0) {
            renderSuction(
                center,
                tick,
                settings.reducedSuctionStreams,
                settings.reducedSuctionPointsPerStream,
                settings,
                reducedReceivers,
            )
        }
    }

    private fun renderSuction(
        center: Location,
        tick: Int,
        streams: Int,
        pointsPerStream: Int,
        settings: PortalOriginGateSettings,
        receivers: Collection<Player>,
    ) {
        if (receivers.isEmpty()) return
        val primaryDust =
            Particle.DustTransition(SUCTION_START_COLOR, SUCTION_END_COLOR, settings.suctionParticleSize)
        val secondaryDust =
            Particle.DustTransition(
                SUCTION_ACCENT_START_COLOR,
                SUCTION_ACCENT_END_COLOR,
                settings.suctionParticleSize * 0.8f,
            )
        val offsets =
            originGateParticleOffsets(
                tick,
                streams,
                pointsPerStream,
                settings.suctionRadius,
                settings.suctionHeight,
                settings.suctionTurns,
            )
        for (offset in offsets) {
            ParticleBuilder(Particle.DUST_COLOR_TRANSITION)
                .count(1)
                .location(center.clone().add(offset.x, offset.y, offset.z))
                .receivers(receivers)
                .data(if ((offset.stream + offset.trailPoint) % 3 == 0) secondaryDust else primaryDust)
                .spawn()
        }
        val fullQuality =
            streams == settings.suctionStreams &&
                pointsPerStream == settings.suctionPointsPerStream
        if (settings.suctionCoreCount > 0 && tick % 3 == 0) {
            ParticleBuilder(Particle.REVERSE_PORTAL)
                .count(
                    if (fullQuality) {
                        settings.suctionCoreCount
                    } else {
                        (settings.suctionCoreCount / 3).coerceAtLeast(1)
                    },
                )
                .location(center)
                .receivers(receivers)
                .offset(settings.width * 0.22, settings.height * 0.32, settings.width * 0.22)
                .extra(0.08)
                .spawn()
        }
    }

    private fun transformation(
        x: Float,
        y: Float,
        z: Float,
    ): Transformation =
        Transformation(
            Vector3f(),
            AxisAngle4f(),
            Vector3f(x, y, z),
            AxisAngle4f(),
        )

    private class BukkitPortalOriginGateHandle(
        private val display: ItemDisplay,
        private val chaos: ItemStack,
        private val soundLocation: Location,
        private val settings: PortalOriginGateSettings,
    ) : PortalOriginGateHandle {
        override fun updateScale(multiplier: Float) {
            if (!display.isValid) return
            display.transformation = transformation(
                settings.width * multiplier,
                settings.height * multiplier,
                1f,
            )
        }

        override fun playOpeningSound() {
            if (display.isValid) playOpeningSound(soundLocation, settings)
        }

        override fun prepareClosing() {
            if (!display.isValid) return
            display.setItemStack(chaos)
        }

        override fun remove() {
            if (display.isValid) display.remove()
        }
    }

    private val SUCTION_START_COLOR = Color.fromRGB(154, 55, 255)
    private val SUCTION_END_COLOR = Color.fromRGB(35, 205, 255)
    private val SUCTION_ACCENT_START_COLOR = Color.fromRGB(46, 94, 255)
    private val SUCTION_ACCENT_END_COLOR = Color.fromRGB(255, 75, 214)
}

private const val TINY_SCALE_MULTIPLIER = 0.02f
private const val DRAMATIC_SNAP_PROGRESS = 0.60f
private const val DRAMATIC_CHARGE_SCALE = 0.12f
private const val SUCTION_CYCLE_TICKS = 24
private const val SUCTION_TRAIL_SPACING = 0.055
private const val MAX_SUCTION_PARTICLES_PER_TICK = 64
