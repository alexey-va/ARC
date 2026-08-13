package ru.arc.mounts

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

open class MountModuleConfig(private val config: Config) {
    open val enabled: Boolean get() = config.bool("enabled", false)
    open val allowedWorlds: Set<String> get() = config.stringList("allowed-worlds").map { it.lowercase(Locale.ROOT) }.toSet()
    open val sessionDuration: Duration get() = config.duration("session-duration", Duration.ofMinutes(10))
    open val adminSessionDuration: Duration get() = config.duration("admin-session-duration", Duration.ofSeconds(5))
    open val doubleSneakWindow: Duration get() = config.duration("controls.double-sneak-window", Duration.ofMillis(450))
    open val descendingHintCooldown: Duration get() = config.duration("controls.hint-cooldown", Duration.ofSeconds(5))
    open val walkingSpeedScale: Double get() = config.double("movement.walking-speed-scale", 0.16)
    open val flyingSpeedScale: Double get() = config.double("movement.flying-speed-scale", 0.55)
    open val swimmingSpeedScale: Double get() = config.double("movement.swimming-speed-scale", 0.45)
    open val acceleration: Double get() = config.double("movement.acceleration", 0.28)
    open val deceleration: Double get() = config.double("movement.deceleration", 0.38)
    open val turnSmoothing: Double get() = config.double("movement.turn-smoothing", 0.32)
    open val sprintMultiplier: Double get() = config.double("movement.sprint-multiplier", 1.15)
    open val jumpVelocity: Double get() = config.double("movement.jump-velocity", 0.5)
    open val verticalSpeedRatio: Double get() = config.double("movement.vertical-speed-ratio", 0.75)
    open val maximumVerticalSpeed: Double get() = config.double("movement.maximum-vertical-speed", 0.5)
    open val flightPitchInfluence: Double get() = config.double("movement.flight-pitch-influence", 0.65)
    open val maximumHeightAboveWorld: Int get() = config.integer("movement.maximum-height-above-world", 32)
    open val postFlightSlowFalling: Duration get() = config.duration("safety.post-flight-slow-falling", Duration.ofSeconds(8))
    open val backCommand: String get() = config.string("gui.back-command", "m").trim().removePrefix("/")
    open val listTitle: String get() = config.string("gui.list-title", "<dark_gray><bold>Маунты")
    open val detailTitle: String get() = config.string("gui.detail-title", "<dark_gray><bold>Маунт: <mount>")

    open fun legacyGlowOwners(): Map<String, Set<String>> =
        config.keys("legacy-glow-owners").associate { playerName ->
            playerName.lowercase(Locale.ROOT) to
                config.stringList("legacy-glow-owners.$playerName").map { it.lowercase(Locale.ROOT) }.toSet()
        }

    open fun catalog(): MountCatalog {
        val definitions =
            config.keys("mounts").map { rawId ->
                val id = rawId.lowercase(Locale.ROOT)
                require(id == rawId) { "Mount id '$rawId' must be normalized lowercase" }
                val root = "mounts.$id"
                MountDefinition(
                    id = id,
                    movement = strictMovement(config.string("$root.type", "walking"), id),
                    entityType = config.string("$root.entity", id).trim().uppercase(Locale.ROOT),
                    iconMaterial = config.string("$root.item", "PAPER").trim().uppercase(Locale.ROOT),
                    displayName = config.string("$root.name", id).trim(),
                    speeds = numberList("$root.speeds", id, nullable = false).filterNotNull(),
                    prices = numberList("$root.prices", id, nullable = true),
                    glowPrice = config.doubleOrNull("$root.buy-glow"),
                )
            }
        return MountCatalog(definitions)
    }

    open fun validated(): MountModuleConfig {
        if (!enabled) return this
        require(!sessionDuration.isZero && !sessionDuration.isNegative) { "Mount session-duration must be positive" }
        require(!adminSessionDuration.isZero && !adminSessionDuration.isNegative) { "Mount admin-session-duration must be positive" }
        require(!doubleSneakWindow.isZero && !doubleSneakWindow.isNegative) { "Mount double-sneak-window must be positive" }
        require(walkingSpeedScale > 0.0 && walkingSpeedScale.isFinite()) { "walking-speed-scale must be positive" }
        require(flyingSpeedScale > 0.0 && flyingSpeedScale.isFinite()) { "flying-speed-scale must be positive" }
        require(swimmingSpeedScale > 0.0 && swimmingSpeedScale.isFinite()) { "swimming-speed-scale must be positive" }
        require(acceleration in 0.0..1.0) { "Mount acceleration must be between 0 and 1" }
        require(deceleration in 0.0..1.0) { "Mount deceleration must be between 0 and 1" }
        require(turnSmoothing in 0.0..1.0) { "Mount turn-smoothing must be between 0 and 1" }
        require(sprintMultiplier >= 1.0 && sprintMultiplier.isFinite()) { "Mount sprint-multiplier must be at least 1" }
        require(jumpVelocity > 0.0 && jumpVelocity.isFinite()) { "Mount jump-velocity must be positive" }
        require(verticalSpeedRatio > 0.0 && verticalSpeedRatio.isFinite()) { "Mount vertical-speed-ratio must be positive" }
        require(maximumVerticalSpeed > 0.0 && maximumVerticalSpeed.isFinite()) {
            "Mount maximum-vertical-speed must be positive"
        }
        require(flightPitchInfluence in 0.0..1.0) { "Mount flight-pitch-influence must be between 0 and 1" }
        require(maximumHeightAboveWorld in 0..256) { "Mount maximum-height-above-world must be between 0 and 256" }
        val loadedCatalog = catalog()
        val knownIds = loadedCatalog.all.map(MountDefinition::id).toSet()
        legacyGlowOwners().forEach { (player, mounts) ->
            require(player.isNotBlank()) { "Legacy glow owner name cannot be blank" }
            require(mounts.all(knownIds::contains)) { "Legacy glow owner '$player' references an unknown mount" }
        }
        return this
    }

    open fun message(path: String, fallback: String): String = config.string("messages.$path", fallback)

    private fun numberList(path: String, mountId: String, nullable: Boolean): List<Double?> {
        if (!config.exists(path)) return emptyList()
        return config.list<Any?>(path).mapIndexed { index, value ->
            if (value == null || value.toString().equals("null", ignoreCase = true)) {
                require(nullable) { "Mount '$mountId' $path level ${index + 1} cannot be null" }
                null
            } else {
                value.toString().toDoubleOrNull()
                    ?: throw IllegalArgumentException("Mount '$mountId' $path level ${index + 1} is not a number")
            }
        }
    }

    private fun strictMovement(raw: String, mountId: String): MountMovement =
        runCatching { MountMovement.valueOf(raw.trim().uppercase(Locale.ROOT)) }
            .getOrElse { throw IllegalArgumentException("Mount '$mountId' has invalid type '$raw'") }

    companion object {
        fun load(dataPath: Path): MountModuleConfig =
            MountModuleConfig(ConfigManager.ofModule(dataPath, "mounts.yml")).validated()
    }
}
