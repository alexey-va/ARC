package ru.arc.backwings

import kotlin.math.abs
import kotlin.math.atan2

/** LivingEntity.tick/tickHeadTurn in 1.21.11 and 26.3, using observed movement, not server xo/zo. */
internal fun nextBackWingBodyYaw(
    bodyYaw: Float,
    headYaw: Float,
    deltaX: Double,
    deltaZ: Double,
    blocking: Boolean,
): Float {
    var target = bodyYaw
    if ((deltaX * deltaX + deltaZ * deltaZ).toFloat() > 0.0025000002f) {
        val movementYaw = atan2(deltaZ, deltaX).toFloat() * 57.295776f - 90f
        val difference = abs(wrapWingYaw(headYaw) - movementYaw)
        target = if (difference > 95f && difference < 265f) movementYaw - 180f else movementYaw
    }
    var next = bodyYaw + wrapWingYaw(target - bodyYaw) * 0.3f
    val headDifference = wrapWingYaw(headYaw - next)
    val limit = if (blocking) 15f else 50f
    if (abs(headDifference) > limit) next += headDifference - headDifference.coerceIn(-limit, limit)
    return wrapWingYaw(next)
}

private fun wrapWingYaw(value: Float): Float {
    var wrapped = value % 360f
    if (wrapped >= 180f) wrapped -= 360f
    if (wrapped < -180f) wrapped += 360f
    return wrapped
}

/**
 * One client-style body step per observed server tick. Multiple move packets, F and reload in
 * the same tick revise that step from its original input instead of smoothing repeatedly.
 * Idle gaps need no replay: with unchanged head/position the native body angle stays unchanged.
 */
internal class BackWingBodyYaw(initialYaw: Float, x: Double, z: Double, tick: Long) {
    var yaw: Float = initialYaw
        private set
    private var sampleTick = tick
    private var startYaw = initialYaw
    private var startX = x
    private var startZ = z
    private var latestX = x
    private var latestZ = z

    fun observe(tick: Long, x: Double, z: Double, headYaw: Float, blocking: Boolean): Float {
        if (tick != sampleTick) {
            startYaw = yaw
            startX = latestX
            startZ = latestZ
            sampleTick = tick
        }
        yaw = nextBackWingBodyYaw(startYaw, headYaw, x - startX, z - startZ, blocking)
        latestX = x
        latestZ = z
        return yaw
    }
}
