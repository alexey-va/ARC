package ru.arc

import com.destroystokyo.paper.ParticleBuilder
import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
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

internal data class PortalOriginGateSettings(
    val astralItemId: String,
    val chaosItemId: String,
    val openingStartTick: Int,
    val openingDurationTicks: Int,
    val closingDurationTicks: Int,
    val width: Float,
    val height: Float,
    val verticalOffset: Double,
    val viewRange: Float,
    val suctionEnabled: Boolean,
    val suctionStreams: Int,
    val reducedSuctionStreams: Int,
    val suctionRadius: Double,
    val suctionParticleSize: Float,
) {
    companion object {
        private val namespacedId = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

        fun load(config: Config): PortalOriginGateSettings? {
            val path = "portal.origin-gate"
            if (!config.bool("$path.enabled", false)) return null

            return validated(
                astralItemId = config.string("$path.astral-item", ""),
                chaosItemId = config.string("$path.chaos-item", ""),
                openingStartTick = config.integer("$path.opening-start-tick", 0),
                openingDurationTicks = config.integer("$path.opening-duration-ticks", 36),
                closingDurationTicks = config.integer("$path.closing-duration-ticks", 12),
                width = config.real("$path.width", 2.0).toFloat(),
                height = config.real("$path.height", 2.8).toFloat(),
                verticalOffset = config.real("$path.vertical-offset", 1.65),
                viewRange = config.real("$path.view-range", 1.0).toFloat(),
                suctionEnabled = config.bool("$path.suction.enabled", true),
                suctionStreams = config.integer("$path.suction.streams", 8),
                reducedSuctionStreams = config.integer("$path.suction.reduced-streams", 3),
                suctionRadius = config.real("$path.suction.radius", 2.25),
                suctionParticleSize = config.real("$path.suction.particle-size", 0.8).toFloat(),
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
            closingDurationTicks: Int,
            width: Float,
            height: Float,
            verticalOffset: Double,
            viewRange: Float,
            suctionEnabled: Boolean,
            suctionStreams: Int,
            reducedSuctionStreams: Int,
            suctionRadius: Double,
            suctionParticleSize: Float,
        ): PortalOriginGateSettings? {
            val normalizedAstral = astralItemId.trim().lowercase()
            val normalizedChaos = chaosItemId.trim().lowercase()
            if (normalizedAstral.length !in 3..128 || !namespacedId.matches(normalizedAstral)) return null
            if (normalizedChaos.length !in 3..128 || !namespacedId.matches(normalizedChaos)) return null
            if (openingStartTick !in 0..58) return null
            if (openingDurationTicks !in 1..60 || closingDurationTicks !in 1..60) return null
            if (!width.isFinite() || width !in 0.1f..4.0f) return null
            if (!height.isFinite() || height !in 0.1f..4.0f) return null
            if (!verticalOffset.isFinite() || verticalOffset !in 0.5..4.0) return null
            if (!viewRange.isFinite() || viewRange !in 0.1f..4.0f) return null
            if (suctionStreams !in 1..16) return null
            if (reducedSuctionStreams !in 1..suctionStreams) return null
            if (!suctionRadius.isFinite() || suctionRadius !in 0.25..5.0) return null
            if (!suctionParticleSize.isFinite() || suctionParticleSize !in 0.1f..2.0f) return null

            return PortalOriginGateSettings(
                astralItemId = normalizedAstral,
                chaosItemId = normalizedChaos,
                openingStartTick = openingStartTick,
                openingDurationTicks = openingDurationTicks,
                closingDurationTicks = closingDurationTicks,
                width = width,
                height = height,
                verticalOffset = verticalOffset,
                viewRange = viewRange,
                suctionEnabled = suctionEnabled,
                suctionStreams = suctionStreams,
                reducedSuctionStreams = reducedSuctionStreams,
                suctionRadius = suctionRadius,
                suctionParticleSize = suctionParticleSize,
            )
        }
    }
}

internal interface PortalOriginGateHandle {
    fun updateScale(multiplier: Float)

    fun prepareClosing()

    fun remove()
}

internal class PortalOriginGateController(
    private val settings: PortalOriginGateSettings,
    private val spawn: () -> PortalOriginGateHandle?,
) {
    private var handle: PortalOriginGateHandle? = null
    private var spawnAttempted = false
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
        activeHandle.updateScale(originGateOpeningScale(elapsedTicks, settings.openingDurationTicks))
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
): Float {
    if (elapsedTicks <= durationTicks) {
        val progress = (elapsedTicks.toFloat() / durationTicks).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        return TINY_SCALE_MULTIPLIER + ((1f - TINY_SCALE_MULTIPLIER) * eased)
    }
    val idleTicks = elapsedTicks - durationTicks
    return 1f + (sin(idleTicks * 0.18f) * 0.035f)
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

internal data class OriginGateParticleOffset(
    val x: Double,
    val y: Double,
    val z: Double,
)

internal fun originGateParticleOffsets(
    tick: Int,
    streams: Int,
    radius: Double,
): List<OriginGateParticleOffset> {
    val advance = Math.floorMod(tick, SUCTION_CYCLE_TICKS) / SUCTION_CYCLE_TICKS.toDouble()
    return List(streams) { stream ->
        val travel = (advance + (stream / streams.toDouble())) % 1.0
        val remaining = 1.0 - travel
        val currentRadius = radius * remaining.pow(0.85)
        val angle = (tick * 0.16) + ((2.0 * PI * stream) / streams) + (travel * 2.0 * PI)
        OriginGateParticleOffset(
            x = cos(angle) * currentRadius,
            y = sin((angle * 1.6) + stream) * currentRadius * 0.55,
            z = sin(angle) * currentRadius,
        )
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
        location.yaw = originGateFacingYaw(location.x, location.z, viewerLocation.x, viewerLocation.z)
        location.pitch = 0f

        var display: ItemDisplay? = null
        return try {
            val created = base.world.spawn(location, ItemDisplay::class.java)
            display = created
            configure(created, astral, settings)
            BukkitPortalOriginGateHandle(created, chaos, settings)
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
        renderSuction(center, tick, settings.suctionStreams, settings, fullReceivers)
        if (tick % 2 == 0) {
            renderSuction(center, tick, settings.reducedSuctionStreams, settings, reducedReceivers)
        }
    }

    private fun renderSuction(
        center: Location,
        tick: Int,
        streams: Int,
        settings: PortalOriginGateSettings,
        receivers: Collection<Player>,
    ) {
        if (receivers.isEmpty()) return
        val dust = Particle.DustTransition(SUCTION_START_COLOR, SUCTION_END_COLOR, settings.suctionParticleSize)
        for (offset in originGateParticleOffsets(tick, streams, settings.suctionRadius)) {
            ParticleBuilder(Particle.DUST_COLOR_TRANSITION)
                .count(1)
                .location(center.clone().add(offset.x, offset.y, offset.z))
                .receivers(receivers)
                .data(dust)
                .spawn()
        }
        if (tick % 3 == 0) {
            ParticleBuilder(Particle.REVERSE_PORTAL)
                .count(if (streams >= settings.suctionStreams) 4 else 2)
                .location(center)
                .receivers(receivers)
                .offset(0.35, 0.55, 0.35)
                .extra(0.02)
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
}

private const val TINY_SCALE_MULTIPLIER = 0.02f
private const val SUCTION_CYCLE_TICKS = 24
