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

enum class MountRarity(val displayName: String, val color: String) {
    COMMON("Обычный", "<white>"),
    UNCOMMON("Необычный", "<green>"),
    RARE("Редкий", "<aqua>"),
    EPIC("Эпический", "<light_purple>"),
    LEGENDARY("Легендарный", "<gold>"),
}

data class MountLevelDefinition(
    val speed: Double,
    val price: Double?,
    val handlingMultiplier: Double = 1.0,
    val sprintMultiplier: Double = 1.0,
) {
    init {
        require(speed.isFinite() && speed > 0.0) { "Mount level speed must be positive and finite" }
        require(price == null || price.isFinite() && price > 0.0) { "Mount level price must be positive and finite" }
        require(handlingMultiplier.isFinite() && handlingMultiplier in 0.5..2.0) {
            "Mount level handling multiplier must be between 0.5 and 2.0"
        }
        require(sprintMultiplier.isFinite() && sprintMultiplier in 1.0..2.0) {
            "Mount level sprint multiplier must be between 1.0 and 2.0"
        }
    }
}

data class MountTuningDefinition(
    val speedPercentages: List<Int>,
    val walkingStepHeightsHundredths: List<Int>,
    val walkingMaxStepHeightByLevelHundredths: List<Int>,
) {
    init {
        require(speedPercentages.isNotEmpty()) { "Mount tuning speed percentages cannot be empty" }
        require(speedPercentages == speedPercentages.distinct().sorted()) {
            "Mount tuning speed percentages must be unique and sorted"
        }
        require(speedPercentages.all { it in 25..100 } && 100 in speedPercentages) {
            "Mount tuning speed percentages must be between 25 and 100 and include 100"
        }
        require(speedPercentages.size <= 5) { "Mount tuning supports at most five speed options" }
        require(walkingStepHeightsHundredths.isNotEmpty()) { "Mount tuning step heights cannot be empty" }
        require(walkingStepHeightsHundredths == walkingStepHeightsHundredths.distinct().sorted()) {
            "Mount tuning step heights must be unique and sorted"
        }
        require(walkingStepHeightsHundredths.all { it in 60..150 }) {
            "Mount tuning step heights must be between 0.60 and 1.50 blocks"
        }
        require(walkingStepHeightsHundredths.size <= 5) { "Mount tuning supports at most five step-height options" }
        require(walkingMaxStepHeightByLevelHundredths.isNotEmpty()) {
            "Mount tuning step-height level ceilings cannot be empty"
        }
        require(
            walkingMaxStepHeightByLevelHundredths ==
                walkingMaxStepHeightByLevelHundredths.sorted(),
        ) { "Mount tuning step-height level ceilings must be non-decreasing" }
        require(walkingMaxStepHeightByLevelHundredths.all { it in walkingStepHeightsHundredths }) {
            "Every mount tuning step-height level ceiling must be a configured step height"
        }
        require(walkingMaxStepHeightByLevelHundredths.last() == walkingStepHeightsHundredths.last()) {
            "The final mount tuning level must unlock the highest configured step height"
        }
    }

    fun speedPercentage(selected: Int?): Int = resolveSelection(selected, speedPercentages)

    fun speed(baseSpeed: Double, selected: Int?): Double = baseSpeed * speedPercentage(selected) / 100.0

    fun maximumStepHeightHundredths(level: Int): Int =
        walkingMaxStepHeightByLevelHundredths[(level.coerceAtLeast(1) - 1).coerceAtMost(walkingMaxStepHeightByLevelHundredths.lastIndex)]

    fun availableStepHeightsHundredths(level: Int): List<Int> =
        walkingStepHeightsHundredths.filter { it <= maximumStepHeightHundredths(level) }

    fun stepHeightHundredths(level: Int, selected: Int?): Int =
        resolveSelection(selected, availableStepHeightsHundredths(level))

    fun stepHeight(level: Int, selected: Int?): Double = stepHeightHundredths(level, selected) / 100.0

    private fun resolveSelection(selected: Int?, available: List<Int>): Int {
        if (selected == null) return available.last()
        return available.lastOrNull { it <= selected } ?: available.first()
    }
}

enum class MountEquipmentSlot(val configKey: String) {
    HEAD("head"),
    CHEST("chest"),
    LEGS("legs"),
    FEET("feet"),
    MAIN_HAND("main-hand"),
    OFF_HAND("off-hand"),
    BODY("body"),
    SADDLE("saddle"),
}

data class MountAppearance(
    val baby: Boolean = false,
    val scale: Double = 1.0,
    val variant: String? = null,
    val secondaryVariant: String? = null,
    val equipment: Map<MountEquipmentSlot, String> = emptyMap(),
) {
    init {
        require(scale.isFinite() && scale in 0.35..4.0) { "Mount appearance scale must be between 0.35 and 4.0" }
        require(variant == null || APPEARANCE_VALUE_PATTERN.matches(variant)) { "Invalid mount appearance variant: $variant" }
        require(secondaryVariant == null || APPEARANCE_VALUE_PATTERN.matches(secondaryVariant)) {
            "Invalid mount appearance secondary variant: $secondaryVariant"
        }
        require(equipment.values.all { MATERIAL_PATTERN.matches(it) }) { "Invalid mount appearance equipment material" }
    }

    companion object {
        private val APPEARANCE_VALUE_PATTERN = Regex("[A-Z0-9_]{1,48}")
        private val MATERIAL_PATTERN = Regex("[A-Z0-9_]{2,64}")
    }
}

data class MountTrailDefinition(
    val particle: String,
    val intervalTicks: Int = 4,
    val count: Int = 1,
) {
    init {
        require(PARTICLE_PATTERN.matches(particle)) { "Invalid mount trail particle: $particle" }
        require(intervalTicks in 2..40) { "Mount trail interval must be between 2 and 40 ticks" }
        require(count in 1..8) { "Mount trail count must be between 1 and 8" }
    }

    companion object {
        private val PARTICLE_PATTERN = Regex("[A-Z0-9_]{2,64}")
    }
}

data class MountHighJumpAbility(
    val displayName: String,
    val multiplier: Double,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 64) { "Mount high-jump display name is invalid" }
        require(multiplier.isFinite() && multiplier in 1.05..3.0) {
            "Mount high-jump multiplier must be between 1.05 and 3.0"
        }
    }
}

enum class MountAbilityEffect {
    WATER_BREATHING,
    NIGHT_VISION,
    FIRE_RESISTANCE,
    DOLPHINS_GRACE,
}

data class MountAbilityUpgradeDefinition(
    val id: String,
    val displayName: String,
    val description: List<String>,
    val iconMaterial: String,
    val price: Double,
    val effect: MountAbilityEffect,
    val speedMultiplier: Double = 1.0,
) {
    init {
        require(MountDefinition.validId(id)) { "Invalid mount ability id: $id" }
        require(displayName.isNotBlank() && displayName.length <= 64) { "Mount ability '$id' name is invalid" }
        require(description.size <= 4 && description.all { it.isNotBlank() && it.length <= 120 }) {
            "Mount ability '$id' description is invalid"
        }
        require(iconMaterial.isNotBlank()) { "Mount ability '$id' icon material is blank" }
        require(price.isFinite() && price > 0.0) { "Mount ability '$id' price must be positive and finite" }
        require(speedMultiplier.isFinite() && speedMultiplier in 1.0..1.5) {
            "Mount ability '$id' speed multiplier must be between 1.0 and 1.5"
        }
    }
}

data class MountAbilities(
    val highJump: MountHighJumpAbility? = null,
    val upgrades: List<MountAbilityUpgradeDefinition> = emptyList(),
) {
    val displayNames: List<String>
        get() = listOfNotNull(highJump?.displayName) + upgrades.map(MountAbilityUpgradeDefinition::displayName)
}

data class MountSkinDefinition(
    val id: String,
    val displayName: String,
    val iconMaterial: String,
    val price: Double?,
    val appearance: MountAppearance,
    val trail: MountTrailDefinition? = null,
) {
    init {
        require(MountDefinition.validId(id)) { "Invalid mount skin id: $id" }
        require(displayName.isNotBlank()) { "Mount skin '$id' display name is blank" }
        require(iconMaterial.isNotBlank()) { "Mount skin '$id' icon material is blank" }
        require(price == null || price.isFinite() && price > 0.0) { "Mount skin '$id' price must be positive and finite" }
    }
}

data class MountDefinition(
    val id: String,
    val movement: MountMovement,
    val entityType: String,
    val iconMaterial: String,
    val displayName: String,
    val description: List<String>,
    val acquisition: String,
    val rarity: MountRarity,
    val levels: List<MountLevelDefinition>,
    val glowPrice: Double?,
    val abilities: MountAbilities = MountAbilities(),
    val appearance: MountAppearance = MountAppearance(),
    val skins: List<MountSkinDefinition> = emptyList(),
) {
    init {
        require(validId(id)) { "Invalid mount id: $id" }
        require(entityType.isNotBlank()) { "Mount '$id' entity type is blank" }
        require(iconMaterial.isNotBlank()) { "Mount '$id' icon material is blank" }
        require(displayName.isNotBlank()) { "Mount '$id' display name is blank" }
        require(displayName.length <= 64) { "Mount '$id' display name is too long" }
        require(description.size <= 6 && description.all { it.length <= 120 }) { "Mount '$id' description is invalid" }
        require(acquisition.isNotBlank() && acquisition.length <= 120) { "Mount '$id' acquisition text is invalid" }
        require(levels.isNotEmpty()) { "Mount '$id' must have at least one level" }
        require(levels.size <= MAX_LEVELS) { "Mount '$id' has more than $MAX_LEVELS levels" }
        require(glowPrice == null || glowPrice.isFinite() && glowPrice > 0.0) {
            "Mount '$id' glow price must be positive and finite"
        }
        require(abilities.highJump == null || movement == MountMovement.WALKING) {
            "Mount '$id' high-jump ability requires walking movement"
        }
        require(abilities.upgrades.map(MountAbilityUpgradeDefinition::id).toSet().size == abilities.upgrades.size) {
            "Mount '$id' ability upgrade ids must be unique"
        }
        require(activeAbilitySpeedMultiplier(abilities.upgrades) <= 1.5) {
            "Mount '$id' combined ability speed multiplier exceeds 1.5"
        }
        require(
            movement == MountMovement.SWIMMING ||
                abilities.upgrades.none { it.effect == MountAbilityEffect.WATER_BREATHING || it.effect == MountAbilityEffect.DOLPHINS_GRACE },
        ) { "Mount '$id' aquatic abilities require swimming movement" }
        require(skins.size <= MAX_SKINS) { "Mount '$id' has more than $MAX_SKINS skins" }
        require(skins.map(MountSkinDefinition::id).toSet().size == skins.size) { "Mount '$id' skin ids must be unique" }
        require(skins.none { it.id == DEFAULT_SKIN_ID }) { "Mount '$id' cannot redefine the default skin" }
    }

    val maxLevel: Int get() = levels.size

    fun level(level: Int): MountLevelDefinition = levels[(level.coerceIn(1, maxLevel)) - 1]

    fun speed(level: Int): Double = level(level).speed

    fun price(level: Int): Double? = levels.getOrNull(level - 1)?.price

    fun levelPermission(level: Int): String = "arc.mounts.$id.$level"

    val speedTuningPermissionPrefix: String get() = "arc.mounts.$id.tuning.speed."
    val stepHeightTuningPermissionPrefix: String get() = "arc.mounts.$id.tuning.step-height."

    fun speedTuningPermission(percentage: Int): String = "$speedTuningPermissionPrefix$percentage"

    fun stepHeightTuningPermission(hundredths: Int): String = "$stepHeightTuningPermissionPrefix$hundredths"

    val glowPermission: String get() = "arc.mounts.$id.glow"
    val glowDisabledPermission: String get() = "arc.mounts.$id.glow.disabled"

    fun skin(id: String?): MountSkinDefinition? = skins.firstOrNull { it.id == id }

    fun skinPermission(skinId: String): String = "arc.mounts.$id.skin.$skinId"

    fun activeSkinPermission(skinId: String): String = "arc.mounts.$id.skin.active.$skinId"

    fun ability(id: String): MountAbilityUpgradeDefinition? = abilities.upgrades.firstOrNull { it.id == id }

    fun abilityPermission(abilityId: String): String = "arc.mounts.$id.ability.$abilityId"

    companion object {
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{1,31}")
        private const val MAX_LEVELS = 16
        private const val MAX_SKINS = 16
        const val DEFAULT_SKIN_ID = "default"

        fun validId(value: String): Boolean = ID_PATTERN.matches(value)
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
    val ownedSkinIds: Set<String> = emptySet(),
    val activeSkinId: String = MountDefinition.DEFAULT_SKIN_ID,
    val ownedAbilityIds: Set<String> = emptySet(),
    val selectedSpeedPercentage: Int? = null,
    val selectedStepHeightHundredths: Int? = null,
) {
    val unlocked: Boolean get() = level > 0
    val glowEnabled: Boolean get() = glowOwned && !glowDisabled

    fun ownsSkin(skinId: String): Boolean = skinId == MountDefinition.DEFAULT_SKIN_ID || skinId in ownedSkinIds

    fun ownsAbility(abilityId: String): Boolean = abilityId in ownedAbilityIds
}

interface MountOwnership {
    fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile

    fun grantLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void>

    fun revokeLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void>

    fun grantGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void>

    fun revokeGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void>

    fun setGlowEnabled(playerId: UUID, mount: MountDefinition, enabled: Boolean): CompletableFuture<Void>

    fun grantSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition): CompletableFuture<Void>

    fun revokeSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition): CompletableFuture<Void>

    fun setActiveSkin(playerId: UUID, mount: MountDefinition, skinId: String): CompletableFuture<Void>

    fun grantAbility(playerId: UUID, mount: MountDefinition, ability: MountAbilityUpgradeDefinition): CompletableFuture<Void>

    fun revokeAbility(playerId: UUID, mount: MountDefinition, ability: MountAbilityUpgradeDefinition): CompletableFuture<Void>

    fun setSpeedTuning(playerId: UUID, mount: MountDefinition, percentage: Int): CompletableFuture<Void>

    fun setStepHeightTuning(playerId: UUID, mount: MountDefinition, hundredths: Int): CompletableFuture<Void>

    fun hasDirectPermission(playerId: UUID, permission: String): CompletableFuture<Boolean>

    fun resolveUniqueId(playerName: String): CompletableFuture<UUID?>
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

internal fun walkingJumpVelocity(baseVelocity: Double, abilities: MountAbilities): Double {
    require(baseVelocity.isFinite() && baseVelocity > 0.0) { "Walking jump velocity must be positive and finite" }
    return baseVelocity * (abilities.highJump?.multiplier ?: 1.0)
}

internal fun activeAbilitySpeedMultiplier(abilities: Collection<MountAbilityUpgradeDefinition>): Double =
    abilities.fold(1.0) { total, ability -> total * ability.speedMultiplier }
