package ru.arc

import ru.arc.config.Config
import ru.arc.util.Logging.warn

/** Operator-owned motion; the renderer's geometry and particle budget remain invariants. */
internal data class PortalOriginGateAnimation(
    val minimumScale: Float = 0.02f,
    val chargeProgress: Float = 0.25f,
    val chargeScale: Float = 0.08f,
    val snapExponent: Float = 6f,
    val idleAmplitude: Float = 0.035f,
    val idleSpeed: Float = 0.18f,
) {
    companion object {
        fun load(config: Config, path: String) = PortalOriginGateAnimation(
            minimumScale = config.portalReal("$path.minimum-scale", 0.02, 0.001..0.5).toFloat(),
            chargeProgress = config.portalReal("$path.dramatic.charge-progress", 0.25, 0.01..0.99).toFloat(),
            chargeScale = config.portalReal("$path.dramatic.charge-scale", 0.08, 0.0..1.0).toFloat(),
            snapExponent = config.portalReal("$path.dramatic.snap-exponent", 6.0, 1.0..16.0).toFloat(),
            idleAmplitude = config.portalReal("$path.idle.amplitude", 0.035, 0.0..0.25).toFloat(),
            idleSpeed = config.portalReal("$path.idle.speed", 0.18, 0.0..2.0).toFloat(),
        )
    }
}

internal data class PortalSuctionMotion(
    val cycleTicks: Int = 24,
    val trailSpacing: Double = 0.055,
    val rotationSpeed: Double = 0.18,
    val radiusExponent: Double = 0.88,
    val heightExponent: Double = 0.72,
    val verticalFrequency: Double = 0.72,
    val streamPhase: Double = 0.91,
    val intervalTicks: Int = 1,
    val reducedIntervalTicks: Int = 2,
    val accentSizeMultiplier: Float = 0.8f,
    val accentEvery: Int = 3,
    val coreIntervalTicks: Int = 3,
    val reducedCoreDivisor: Int = 3,
    val coreWidthMultiplier: Double = 0.22,
    val coreHeightMultiplier: Double = 0.32,
    val coreSpeed: Double = 0.08,
) {
    companion object {
        fun load(config: Config, path: String) = PortalSuctionMotion(
            cycleTicks = config.portalInt("$path.cycle-ticks", 24, 1..200),
            trailSpacing = config.portalReal("$path.trail-spacing", 0.055, 0.0..1.0),
            rotationSpeed = config.portalReal("$path.rotation-speed", 0.18, -2.0..2.0),
            radiusExponent = config.portalReal("$path.radius-exponent", 0.88, 0.1..8.0),
            heightExponent = config.portalReal("$path.height-exponent", 0.72, 0.1..8.0),
            verticalFrequency = config.portalReal("$path.vertical-frequency", 0.72, 0.0..8.0),
            streamPhase = config.portalReal("$path.stream-phase", 0.91, -8.0..8.0),
            intervalTicks = config.portalInt("$path.interval-ticks", 1, 1..40),
            reducedIntervalTicks = config.portalInt("$path.reduced-interval-ticks", 2, 1..40),
            accentSizeMultiplier = config.portalReal("$path.accent-size-multiplier", 0.8, 0.1..1.0).toFloat(),
            accentEvery = config.portalInt("$path.accent-every", 3, 1..64),
            coreIntervalTicks = config.portalInt("$path.core-interval-ticks", 3, 1..40),
            reducedCoreDivisor = config.portalInt("$path.reduced-core-divisor", 3, 1..24),
            coreWidthMultiplier = config.portalReal("$path.core-width-multiplier", 0.22, 0.0..1.0),
            coreHeightMultiplier = config.portalReal("$path.core-height-multiplier", 0.32, 0.0..1.0),
            coreSpeed = config.portalReal("$path.core-speed", 0.08, 0.0..1.0),
        )
    }
}

internal data class PortalTimingSettings(
    val lifetimeTicks: Int,
    val blindnessStartTick: Int,
    val borderDurationTicks: Int,
    val materializeTick: Int,
    val entryTick: Int,
    val blockRefreshTicks: Int,
    val particleStartTick: Int,
    val particleIntervalTicks: Int,
    val chimeIntervalTicks: Int,
) {
    fun shouldMaterializeLegacy(tick: Int, materialized: Boolean): Boolean =
        tick >= materializeTick && (!materialized || (tick - materializeTick) % blockRefreshTicks == 0)

    companion object {
        fun load(config: Config) = PortalTimingSettings(
            lifetimeTicks = config.portalInt("portal.animation.lifetime-ticks", 400, 1..1200),
            blindnessStartTick = config.portalInt("portal.animation.blindness-start-tick", 12, 0..1200),
            borderDurationTicks = config.portalInt("portal.animation.legacy.border-duration-ticks", 8, 1..200),
            materializeTick = config.portalInt("portal.animation.legacy.materialize-tick", 12, 0..200),
            entryTick = config.portalInt("portal.animation.legacy.entry-tick", 12, 0..200),
            blockRefreshTicks = config.portalInt("portal.animation.legacy.block-refresh-ticks", 10, 1..40),
            particleStartTick = config.portalInt("portal.animation.legacy.particle-start-tick", 8, 0..200),
            particleIntervalTicks = config.portalInt("portal.animation.legacy.particle-interval-ticks", 10, 1..40),
            chimeIntervalTicks = config.portalInt("portal.animation.legacy.chime-interval-ticks", 4, 1..200),
        )
    }
}

private fun Config.portalInt(path: String, fallback: Int, range: IntRange): Int {
    val value = integer(path, fallback)
    if (value in range) return value
    warn("PORTAL phase=CONFIG reason=invalid-value path={} value={} fallback={}", path, value, fallback)
    return fallback
}

private fun Config.portalReal(path: String, fallback: Double, range: ClosedFloatingPointRange<Double>): Double {
    val value = real(path, fallback)
    if (value.isFinite() && value in range) return value
    warn("PORTAL phase=CONFIG reason=invalid-value path={} value={} fallback={}", path, value, fallback)
    return fallback
}
