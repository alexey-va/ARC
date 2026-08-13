package ru.arc.mounts

import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class MountMovement(val displayName: String) {
    WALKING("Пеший"),
    FLYING("Летающий"),
    SWIMMING("Водный"),
}

data class MountDefinition(
    val id: String,
    val movement: MountMovement,
    val entityType: String,
    val iconMaterial: String,
    val displayName: String,
    val speeds: List<Double>,
    val prices: List<Double?>,
    val glowPrice: Double?,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid mount id: $id" }
        require(entityType.isNotBlank()) { "Mount '$id' entity type is blank" }
        require(iconMaterial.isNotBlank()) { "Mount '$id' icon material is blank" }
        require(displayName.isNotBlank()) { "Mount '$id' display name is blank" }
        require(speeds.isNotEmpty()) { "Mount '$id' must have at least one speed level" }
        require(speeds.size <= MAX_LEVELS) { "Mount '$id' has more than $MAX_LEVELS levels" }
        require(speeds.all { it.isFinite() && it > 0.0 }) { "Mount '$id' speeds must be positive and finite" }
        require(prices.size <= speeds.size) { "Mount '$id' has more prices than speed levels" }
        require(prices.filterNotNull().all { it.isFinite() && it > 0.0 }) {
            "Mount '$id' prices must be positive and finite"
        }
        require(glowPrice == null || glowPrice.isFinite() && glowPrice > 0.0) {
            "Mount '$id' glow price must be positive and finite"
        }
    }

    val maxLevel: Int get() = speeds.size

    fun speed(level: Int): Double = speeds[(level.coerceIn(1, maxLevel)) - 1]

    fun price(level: Int): Double? = prices.getOrNull(level - 1)

    fun levelPermission(level: Int): String = "arc.mounts.$id.$level"

    val glowPermission: String get() = "arc.mounts.$id.glow"
    val glowDisabledPermission: String get() = "arc.mounts.$id.glow.disabled"

    companion object {
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{1,31}")
        private const val MAX_LEVELS = 16
    }
}

class MountCatalog(definitions: Collection<MountDefinition>) {
    val all: List<MountDefinition> = definitions.toList()
    private val byId = all.associateBy(MountDefinition::id)

    init {
        require(all.isNotEmpty()) { "Mount catalog cannot be empty" }
        require(byId.size == all.size) { "Mount ids must be unique" }
    }

    operator fun get(id: String): MountDefinition? = byId[id.lowercase(Locale.ROOT)]
}

data class MountPermissionSubject(
    val uniqueId: UUID,
    val name: String,
    val hasPermission: (String) -> Boolean,
)

data class MountProfile(
    val level: Int,
    val glowOwned: Boolean,
    val glowDisabled: Boolean,
) {
    val unlocked: Boolean get() = level > 0
    val glowEnabled: Boolean get() = glowOwned && !glowDisabled
}

interface MountOwnership {
    fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile

    fun grantLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void>

    fun grantGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void>

    fun setGlowEnabled(playerId: UUID, mount: MountDefinition, enabled: Boolean): CompletableFuture<Void>

    fun resolveUniqueId(playerName: String): CompletableFuture<UUID?>
}

interface MountWallet {
    val available: Boolean

    fun balance(playerId: UUID): Double

    fun withdraw(playerId: UUID, amount: Double): Boolean

    fun deposit(playerId: UUID, amount: Double): Boolean
}

data class MountInputState(
    val forward: Boolean = false,
    val backward: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
    val jump: Boolean = false,
    val sneak: Boolean = false,
    val sprint: Boolean = false,
) {
    val forwardAxis: Double get() = (if (forward) 1.0 else 0.0) - (if (backward) 1.0 else 0.0)
    val strafeAxis: Double get() = (if (right) 1.0 else 0.0) - (if (left) 1.0 else 0.0)
}

data class MotionVector(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    val horizontalLength: Double get() = sqrt(x * x + z * z)
    val length: Double get() = sqrt(x * x + y * y + z * z)

    fun normalized(): MotionVector {
        val magnitude = length
        return if (magnitude <= EPSILON) ZERO else MotionVector(x / magnitude, y / magnitude, z / magnitude)
    }

    fun normalizedHorizontal(): MotionVector {
        val magnitude = horizontalLength
        return if (magnitude <= EPSILON) ZERO else MotionVector(x / magnitude, 0.0, z / magnitude)
    }

    operator fun plus(other: MotionVector) = MotionVector(x + other.x, y + other.y, z + other.z)
    operator fun times(scale: Double) = MotionVector(x * scale, y * scale, z * scale)

    companion object {
        val ZERO = MotionVector(0.0, 0.0, 0.0)
        private const val EPSILON = 1.0e-9
    }
}

object MountMotion {
    fun planarDirection(yawDegrees: Float, input: MountInputState): MotionVector {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val forward = MotionVector(-sin(yaw), 0.0, cos(yaw))
        val right = MotionVector(-cos(yaw), 0.0, -sin(yaw))
        return (forward * input.forwardAxis + right * input.strafeAxis).normalizedHorizontal()
    }

    fun smooth(current: MotionVector, target: MotionVector, acceleration: Double, deceleration: Double): MotionVector {
        val factor = if (target.length > current.length) acceleration else deceleration
        val bounded = factor.coerceIn(0.0, 1.0)
        return MotionVector(
            current.x + (target.x - current.x) * bounded,
            current.y + (target.y - current.y) * bounded,
            current.z + (target.z - current.z) * bounded,
        )
    }

    fun smoothYaw(current: Float, target: Float, factor: Double): Float {
        val delta = ((target - current + 540.0f) % 360.0f) - 180.0f
        return current + delta * factor.coerceIn(0.0, 1.0).toFloat()
    }

    fun facingYaw(motion: MotionVector, fallback: Float): Float {
        if (abs(motion.x) < 1.0e-6 && abs(motion.z) < 1.0e-6) return fallback
        return Math.toDegrees(kotlin.math.atan2(-motion.x, motion.z)).toFloat()
    }

    fun airborneTarget(
        pitchDegrees: Float,
        input: MountInputState,
        planar: MotionVector,
        maximumSpeed: Double,
        verticalSpeedRatio: Double,
        maximumVerticalSpeed: Double,
        pitchInfluence: Double,
    ): MotionVector {
        require(maximumSpeed >= 0.0 && maximumSpeed.isFinite()) { "Maximum speed must be finite and non-negative" }
        val verticalLimit = min(maximumSpeed * verticalSpeedRatio, maximumVerticalSpeed)
        val manualVertical = (if (input.jump) 1.0 else 0.0) - (if (input.sneak) 1.0 else 0.0)
        val pitchRadians = Math.toRadians(pitchDegrees.toDouble())
        val pitchVertical = -sin(pitchRadians) * input.forwardAxis * maximumSpeed * pitchInfluence
        val vertical = (manualVertical * verticalLimit + pitchVertical).coerceIn(-verticalLimit, verticalLimit)
        val raw = MotionVector(planar.x * maximumSpeed, vertical, planar.z * maximumSpeed)
        return if (raw.length > maximumSpeed && maximumSpeed > 0.0) raw.normalized() * maximumSpeed else raw
    }
}

enum class SneakGestureResult {
    NONE,
    PRESSED,
    DOUBLE_PRESSED,
}

class DoubleSneakGesture(private val doublePressWindowMillis: Long) {
    private var previousSneak = false
    private var lastPressAt = Long.MIN_VALUE

    init {
        require(doublePressWindowMillis > 0L) { "Double-sneak window must be positive" }
    }

    fun update(sneaking: Boolean, nowMillis: Long): SneakGestureResult {
        val risingEdge = sneaking && !previousSneak
        previousSneak = sneaking
        if (!risingEdge) return SneakGestureResult.NONE

        val isDouble = lastPressAt != Long.MIN_VALUE && nowMillis - lastPressAt in 0..doublePressWindowMillis
        lastPressAt = if (isDouble) Long.MIN_VALUE else nowMillis
        return if (isDouble) SneakGestureResult.DOUBLE_PRESSED else SneakGestureResult.PRESSED
    }
}
