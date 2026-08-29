package ru.arc.mounts

import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

enum class MountGuiItemRole(val configKey: String) {
    BACKGROUND("background"),
    BACK("back"),
    PREVIOUS("previous"),
    NEXT("next"),
    CATEGORY_ALL("category-all"),
    CATEGORY_FLYING("category-flying"),
    CATEGORY_WALKING("category-walking"),
    CATEGORY_SWIMMING("category-swimming"),
    INFO("info"),
    BALANCE("balance"),
    FAVORITE("favorite"),
    WHISTLE("whistle"),
    CONFIRM("confirm"),
    CANCEL("cancel"),
}

data class MountGuiItemStyle(
    val material: Material? = null,
    val customModelData: Int? = null,
)

open class MountModuleConfig(private val config: Config) {
    open val enabled: Boolean get() = config.bool("enabled", false)
    open val ownershipMigrationComplete: Boolean get() = config.bool("ownership-migration-complete", false)
    open val allowedWorlds: Set<String> get() = config.stringList("allowed-worlds").map { it.lowercase(Locale.ROOT) }.toSet()
    open val sessionDuration: Duration get() = config.duration("session-duration", Duration.ofMinutes(10))
    open val adminSessionDuration: Duration get() = config.duration("admin-session-duration", Duration.ofSeconds(5))
    open val purchasesEnabled: Boolean get() = config.bool("purchases-enabled", false)
    open val quickSummonSneakSwapHands: Boolean get() = config.bool("quick-summon.sneak-swap-hands", true)
    open val quickSummonWhistle: Boolean get() = config.bool("quick-summon.whistle", true)
    open val idleTimeout: Duration get() = config.duration("safety.idle-timeout", Duration.ofMinutes(5))
    open val summonCooldown: Duration get() = config.duration("safety.summon-cooldown", Duration.ofSeconds(2))
    open val riderKnockoffDamage: Double get() = config.double("safety.rider-knockoff-damage", 6.0)
    open val doubleSneakWindow: Duration get() = config.duration("controls.double-sneak-window", Duration.ofMillis(450))
    open val descendingHintCooldown: Duration get() = config.duration("controls.hint-cooldown", Duration.ofSeconds(5))
    open val hideFlyingMountFromRider: Boolean get() = config.bool("rider-view.hide-flying-mount", true)
    open val hideFlyingMountPitch: Double get() = config.double("rider-view.hide-at-pitch", 35.0)
    open val showFlyingMountPitch: Double get() = config.double("rider-view.show-at-pitch", 20.0)
    open val walkingSpeedScale: Double get() = config.double("movement.walking-speed-scale", 0.16)
    open val flyingSpeedScale: Double get() = config.double("movement.flying-speed-scale", 0.55)
    open val swimmingSpeedScale: Double get() = config.double("movement.swimming-speed-scale", 0.45)
    open val motionTiming: MountMotionTiming
        get() =
            MountMotionTiming(
                accelerationTime = config.duration("movement.acceleration-time", Duration.ofMillis(900)),
                decelerationTime = config.duration("movement.deceleration-time", Duration.ofMillis(350)),
                turnTime = config.duration("movement.turn-time", Duration.ofMillis(200)),
            )
    open val sprintMultiplier: Double get() = config.double("movement.sprint-multiplier", 1.15)
    open val jumpVelocity: Double get() = config.double("movement.jump-velocity", 0.5)
    open val verticalSpeedRatio: Double get() = config.double("movement.vertical-speed-ratio", 0.75)
    open val maximumVerticalSpeed: Double get() = config.double("movement.maximum-vertical-speed", 0.5)
    open val flightPitchInfluence: Double get() = config.double("movement.flight-pitch-influence", 0.65)
    open val maximumSpeedBlocksPerTick: Double get() = config.double("movement.maximum-speed-blocks-per-tick", 1.05)
    open val maximumHeightAboveWorld: Int get() = config.integer("movement.maximum-height-above-world", 32)
    open val compensateAirborneMining: Boolean get() = config.bool("movement.compensate-airborne-mining", true)
    open val postFlightSlowFalling: Duration get() = config.duration("safety.post-flight-slow-falling", Duration.ofSeconds(8))
    open val backCommand: String get() = config.string("gui.back-command", "m").trim().removePrefix("/")
    open val listTitle: String get() = config.string("gui.list-title", "<dark_gray><bold>Маунты")
    open val detailTitle: String get() = config.string("gui.detail-title", "<dark_gray><bold>Маунт: <mount>")
    open val progressionTitle: String get() = config.string("gui.progression-title", "<dark_gray><bold>Развитие: <mount>")
    open val skinsTitle: String get() = config.string("gui.skins-title", "<dark_gray><bold>Облики: <mount>")

    open val tuning: MountTuningDefinition
        get() =
            MountTuningDefinition(
                speedPercentages = intList("tuning.speed-percentages", listOf(50, 65, 80, 90, 100)),
                walkingStepHeightsHundredths =
                    decimalHundredthsList("tuning.walking-step-heights", listOf("1.10", "1.50", "2.00", "3.00", "4.00")),
                walkingMaxStepHeightByLevelHundredths =
                    decimalHundredthsList("tuning.walking-max-step-height-by-level", listOf("1.10", "2.00", "4.00")),
            )

    open fun guiStyle(role: MountGuiItemRole): MountGuiItemStyle {
        val path = "gui.items.${role.configKey}"
        val rawMaterial = config.stringOrNull("$path.material")?.trim()?.takeIf(String::isNotEmpty)
        val material =
            rawMaterial?.let {
                Material.matchMaterial(it)
                    ?: Material.matchMaterial(it.uppercase(Locale.ROOT))
                    ?: throw IllegalArgumentException("Mount GUI item '${role.configKey}' has invalid material '$it'")
            }
        val customModelData =
            if (config.exists("$path.customModelData")) {
                config.integer("$path.customModelData", 0).also {
                    require(it > 0) { "Mount GUI item '${role.configKey}' customModelData must be positive" }
                }
            } else {
                null
            }
        return MountGuiItemStyle(material, customModelData)
    }

    open fun catalog(): MountCatalog {
        val abilityUpgrades = abilityUpgrades()
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
                    description = config.stringList("$root.description").map(String::trim).filter(String::isNotEmpty),
                    acquisition = config.string("$root.acquisition", "Магазин маунтов").trim(),
                    rarity = strictRarity(config.string("$root.rarity", "common"), id),
                    levels = levelList(root, id),
                    glowPrice = config.doubleOrNull("$root.buy-glow"),
                    abilities = abilities("$root.abilities", abilityUpgrades),
                    appearance = appearance("$root.appearance"),
                    skins = skinList(root, id),
                    motion = motion("$root.motion"),
                )
            }
        return MountCatalog(definitions)
    }

    open fun validated(): MountModuleConfig {
        if (!enabled) return this
        require(!sessionDuration.isZero && !sessionDuration.isNegative) { "Mount session-duration must be positive" }
        require(!adminSessionDuration.isZero && !adminSessionDuration.isNegative) { "Mount admin-session-duration must be positive" }
        require(!idleTimeout.isZero && !idleTimeout.isNegative) { "Mount idle-timeout must be positive" }
        require(!summonCooldown.isNegative) { "Mount summon-cooldown cannot be negative" }
        require(riderKnockoffDamage > 0.0 && riderKnockoffDamage.isFinite()) {
            "Mount rider-knockoff-damage must be positive"
        }
        require(!doubleSneakWindow.isZero && !doubleSneakWindow.isNegative) { "Mount double-sneak-window must be positive" }
        require(hideFlyingMountPitch.isFinite() && hideFlyingMountPitch in -90.0..90.0) {
            "Mount hide-at-pitch must be between -90 and 90"
        }
        require(showFlyingMountPitch.isFinite() && showFlyingMountPitch in -90.0..hideFlyingMountPitch) {
            "Mount show-at-pitch must be between -90 and hide-at-pitch"
        }
        require(walkingSpeedScale > 0.0 && walkingSpeedScale.isFinite()) { "walking-speed-scale must be positive" }
        require(flyingSpeedScale > 0.0 && flyingSpeedScale.isFinite()) { "flying-speed-scale must be positive" }
        require(swimmingSpeedScale > 0.0 && swimmingSpeedScale.isFinite()) { "swimming-speed-scale must be positive" }
        motionTiming
        require(sprintMultiplier >= 1.0 && sprintMultiplier.isFinite()) { "Mount sprint-multiplier must be at least 1" }
        require(jumpVelocity > 0.0 && jumpVelocity.isFinite()) { "Mount jump-velocity must be positive" }
        require(verticalSpeedRatio > 0.0 && verticalSpeedRatio.isFinite()) { "Mount vertical-speed-ratio must be positive" }
        require(maximumVerticalSpeed > 0.0 && maximumVerticalSpeed.isFinite()) {
            "Mount maximum-vertical-speed must be positive"
        }
        require(flightPitchInfluence in 0.0..1.0) { "Mount flight-pitch-influence must be between 0 and 1" }
        require(maximumSpeedBlocksPerTick.isFinite() && maximumSpeedBlocksPerTick in 0.2..2.0) {
            "Mount maximum-speed-blocks-per-tick must be between 0.2 and 2.0"
        }
        require(maximumHeightAboveWorld in 0..256) { "Mount maximum-height-above-world must be between 0 and 256" }
        tuning
        MountGuiItemRole.entries.forEach(::guiStyle)
        catalog()
        return this
    }

    open fun message(path: String, fallback: String): String = config.string("messages.$path", fallback)

    private fun motion(root: String): MountMotionOverride =
        MountMotionOverride(
            accelerationTime = config.durationOrNull("$root.acceleration-time"),
            decelerationTime = config.durationOrNull("$root.deceleration-time"),
            turnTime = config.durationOrNull("$root.turn-time"),
        )

    private fun levelList(root: String, mountId: String): List<MountLevelDefinition> =
        config.list<Map<String, Any?>>("$root.levels").mapIndexed { index, raw ->
            val level = index + 1
            MountLevelDefinition(
                speed = requiredDouble(raw["speed"], "Mount '$mountId' level $level speed"),
                price = nullableDouble(raw["price"], "Mount '$mountId' level $level price"),
                handlingMultiplier = optionalDouble(raw["handling"], 1.0, "Mount '$mountId' level $level handling"),
                sprintMultiplier = optionalDouble(raw["sprint"], 1.0, "Mount '$mountId' level $level sprint"),
                scaleMultiplier = optionalDouble(raw["scale"], 1.0, "Mount '$mountId' level $level scale"),
            )
        }

    private fun skinList(root: String, mountId: String): List<MountSkinDefinition> {
        val baseAppearance = appearance("$root.appearance")
        return config.keys("$root.skins").map { rawId ->
            val id = rawId.lowercase(Locale.ROOT)
            require(id == rawId && MountDefinition.validId(id)) { "Mount '$mountId' skin id '$rawId' must be normalized" }
            val path = "$root.skins.$id"
            val presetId = config.stringOrNull("$path.preset")?.trim()?.lowercase(Locale.ROOT)
            if (presetId != null) require(MountDefinition.validId(presetId) && presetId in config.keys("cosmetics")) {
                "Mount '$mountId' skin '$id' has unknown cosmetic preset '$presetId'"
            }
            val presetPath = presetId?.let { "cosmetics.$it" }
            val presetAppearance = presetPath?.let { appearance("$it.appearance", baseAppearance) } ?: baseAppearance
            MountSkinDefinition(
                id = id,
                displayName =
                    config.stringOrNull("$path.name")?.trim()
                        ?: presetPath?.let { config.string("$it.name", id).trim() }
                        ?: id,
                iconMaterial =
                    (config.stringOrNull("$path.item")
                        ?: presetPath?.let { config.string("$it.item", "LEATHER_HORSE_ARMOR") }
                        ?: "LEATHER_HORSE_ARMOR").trim().uppercase(Locale.ROOT),
                price = config.doubleOrNull("$path.price"),
                appearance = appearance("$path.appearance", presetAppearance),
                trail = trail("$path.trail") ?: presetPath?.let { trail("$it.trail") },
            )
        }
    }

    private fun appearance(path: String, fallback: MountAppearance = MountAppearance()): MountAppearance {
        val equipment =
            MountEquipmentSlot.entries.mapNotNull { slot ->
                config.stringOrNull("$path.equipment.${slot.configKey}")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.uppercase(Locale.ROOT)
                    ?.let { slot to it }
            }.toMap()
        return MountAppearance(
            baby = config.booleanOrNull("$path.baby") ?: fallback.baby,
            scale = config.doubleOrNull("$path.scale") ?: fallback.scale,
            variant = normalizedAppearanceValue(config.stringOrNull("$path.variant")) ?: fallback.variant,
            secondaryVariant =
                normalizedAppearanceValue(config.stringOrNull("$path.secondary-variant")) ?: fallback.secondaryVariant,
            equipment = if (equipment.isEmpty()) fallback.equipment else equipment,
        )
    }

    private fun abilityUpgrades(): Map<String, MountAbilityUpgradeDefinition> =
        config.keys("ability-upgrades").associate { rawId ->
            val id = rawId.lowercase(Locale.ROOT)
            require(id == rawId && MountDefinition.validId(id)) { "Mount ability id '$rawId' must be normalized" }
            val path = "ability-upgrades.$id"
            val rawEffect = config.string("$path.effect", id).trim().uppercase(Locale.ROOT).replace('-', '_')
            val effect = runCatching { MountAbilityEffect.valueOf(rawEffect) }
                .getOrElse { throw IllegalArgumentException("Mount ability '$id' has invalid effect '$rawEffect'") }
            id to
                MountAbilityUpgradeDefinition(
                    id = id,
                    displayName = config.string("$path.name", id).trim(),
                    description = config.stringList("$path.description").map(String::trim).filter(String::isNotEmpty),
                    iconMaterial = config.string("$path.item", "PAPER").trim().uppercase(Locale.ROOT),
                    price = config.doubleOrNull("$path.price")
                        ?: throw IllegalArgumentException("Mount ability '$id' price is required"),
                    effect = effect,
                    speedMultiplier = config.double("$path.speed-multiplier", 1.0),
                )
        }

    private fun abilities(path: String, availableUpgrades: Map<String, MountAbilityUpgradeDefinition>): MountAbilities {
        val abilityIds = config.keys(path)
        require(abilityIds.all { it == "high-jump" || it == "upgrades" }) {
            "Unknown mount abilities: ${abilityIds - setOf("high-jump", "upgrades")}"
        }
        val upgrades = config.stringList("$path.upgrades").map { rawId ->
            val id = rawId.trim().lowercase(Locale.ROOT)
            availableUpgrades[id] ?: throw IllegalArgumentException("Unknown mount ability upgrade '$rawId'")
        }
        val highJumpPath = "$path.high-jump"
        if (config.keys(highJumpPath).isEmpty()) return MountAbilities(upgrades = upgrades)
        val displayName = config.stringOrNull("$highJumpPath.name")?.trim().orEmpty()
        val multiplier = config.doubleOrNull("$highJumpPath.multiplier")
            ?: throw IllegalArgumentException("Mount high-jump multiplier is required")
        return MountAbilities(MountHighJumpAbility(displayName, multiplier), upgrades)
    }

    private fun trail(path: String): MountTrailDefinition? {
        val particle = config.stringOrNull("$path.particle")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return MountTrailDefinition(
            particle = particle.uppercase(Locale.ROOT),
            intervalTicks = config.integer("$path.interval-ticks", 4),
            count = config.integer("$path.count", 1),
        )
    }

    private fun normalizedAppearanceValue(raw: String?): String? =
        raw?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)

    private fun intList(path: String, fallback: List<Int>): List<Int> =
        config.stringList(path, fallback.map(Int::toString)).map { raw ->
            raw.trim().toIntOrNull() ?: throw IllegalArgumentException("Mount '$path' contains invalid integer '$raw'")
        }

    private fun decimalHundredthsList(path: String, fallback: List<String>): List<Int> =
        config.stringList(path, fallback).map { raw ->
            val value = raw.trim().toBigDecimalOrNull()
                ?: throw IllegalArgumentException("Mount '$path' contains invalid decimal '$raw'")
            runCatching { value.movePointRight(2).intValueExact() }
                .getOrElse { throw IllegalArgumentException("Mount '$path' value '$raw' must use at most two decimal places") }
        }

    private fun requiredDouble(value: Any?, label: String): Double =
        nullableDouble(value, label) ?: throw IllegalArgumentException("$label is required")

    private fun optionalDouble(value: Any?, fallback: Double, label: String): Double =
        if (value == null) fallback else requiredDouble(value, label)

    private fun nullableDouble(value: Any?, label: String): Double? {
        if (value == null || value.toString().equals("null", ignoreCase = true)) return null
        return value.toString().toDoubleOrNull() ?: throw IllegalArgumentException("$label is not a number")
    }

    private fun strictMovement(raw: String, mountId: String): MountMovement =
        runCatching { MountMovement.valueOf(raw.trim().uppercase(Locale.ROOT)) }
            .getOrElse { throw IllegalArgumentException("Mount '$mountId' has invalid type '$raw'") }

    private fun strictRarity(raw: String, mountId: String): MountRarity =
        runCatching { MountRarity.valueOf(raw.trim().uppercase(Locale.ROOT)) }
            .getOrElse { throw IllegalArgumentException("Mount '$mountId' has invalid rarity '$raw'") }

    companion object {
        fun load(dataPath: Path): MountModuleConfig =
            MountModuleConfig(ConfigManager.ofModule(dataPath, "mounts.yml")).validated()
    }
}
