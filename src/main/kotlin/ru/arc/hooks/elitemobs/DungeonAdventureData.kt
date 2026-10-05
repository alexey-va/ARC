package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.PlayerPreTeleportEvent
import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.combatsystem.ArmorDefenseCalculator
import com.magmaguy.elitemobs.combatsystem.WeaponOffenseCalculator
import com.magmaguy.elitemobs.commands.DungeonCommands
import com.magmaguy.elitemobs.config.DungeonsConfig
import com.magmaguy.elitemobs.config.SkillsConfig
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfigFields.ContentType
import com.magmaguy.elitemobs.config.menus.premade.PlayerStatusMenuConfig
import com.magmaguy.elitemobs.config.menus.premade.SkillBonusMenuConfig
import com.magmaguy.elitemobs.config.skillbonuses.SkillBonusConfigFields
import com.magmaguy.elitemobs.config.skillbonuses.SkillBonusesConfig
import com.magmaguy.elitemobs.dungeons.CombatContent
import com.magmaguy.elitemobs.dungeons.DynamicDungeonPackage
import com.magmaguy.elitemobs.dungeons.EMPackage
import com.magmaguy.elitemobs.dungeons.WorldDungeonPackage
import com.magmaguy.elitemobs.dungeons.WorldInstancedDungeonPackage
import com.magmaguy.elitemobs.instanced.MatchInstance
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.instanced.dungeons.DynamicDungeonInstance
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.playerdata.ElitePlayerInventory
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.playerdata.statusscreen.StatsPage
import com.magmaguy.elitemobs.skills.CombatLevelCalculator
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.SkillXPCalculator
import com.magmaguy.elitemobs.skills.WeaponIdentityResolver
import com.magmaguy.elitemobs.skills.bonuses.PlayerSkillSelection
import com.magmaguy.elitemobs.skills.bonuses.SkillBonus
import com.magmaguy.elitemobs.skills.bonuses.SkillBonusRegistry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

internal data class DungeonAdventureValue(val key: String, val value: String)

internal data class DungeonEquipmentInfo(val slot: String, val name: String, val level: Int, val lore: List<String>)

internal data class DungeonGearView(
    val summary: List<DungeonAdventureValue>,
    val equipment: List<DungeonEquipmentInfo>,
    val weaponPerks: List<String>,
    val armorPerks: List<String>,
)

internal data class DungeonSkillInfo(
    val id: String,
    val name: String,
    val level: Int,
    val xp: Long,
    val nextXp: Long,
    val progress: Double,
)

internal data class DungeonSkillPerkInfo(
    val id: String,
    val name: String,
    val tier: Int,
    val requiredLevel: Int,
    val active: Boolean,
    val description: List<String>,
    val bonus: String,
)

internal data class DungeonSkillPerkView(
    val skill: DungeonSkillInfo,
    val maxActive: Int,
    val perks: List<DungeonSkillPerkInfo>,
)

internal data class DungeonDifficultyInfo(val id: String, val name: String, val level: Int?)

internal data class DungeonCatalogEntry(
    val id: String,
    val name: String,
    val type: String,
    val level: Int,
    val lowestLevel: Int,
    val highestLevel: Int,
    val description: List<String>,
    val difficulties: List<DungeonDifficultyInfo>,
    val permissionGranted: Boolean,
    val maxPlayers: Int?,
    val dynamic: Boolean,
    val availableLevels: List<Int> = emptyList(),
)

internal data class DungeonBossInfo(val id: UUID, val name: String, val world: String, val level: Int)

internal data class DungeonLobbyInfo(
    val id: String,
    val name: String,
    val difficulty: String,
    val level: Int,
    val players: List<String>,
    val waiting: Boolean,
    val spectatable: Boolean,
)

internal enum class DungeonAdventureActionResult {
    REQUESTED,
    SPECTATING,
    TRACKED,
    UNTRACKED,
    PERK_ACTIVATED,
    PERK_DEACTIVATED,
    LEVEL_REQUIRED,
    PERK_LIMIT,
    MISSING,
    NO_PERMISSION,
    IN_INSTANCE,
    INVALID_SELECTION,
    CHANGED,
    FAILED,
}

internal interface DungeonAdventureService {
    fun stats(player: Player): List<DungeonAdventureValue>?
    fun gear(player: Player): DungeonGearView?
    fun skills(player: Player): List<DungeonSkillInfo>?
    fun perks(player: Player, skillId: String): DungeonSkillPerkView?
    fun togglePerk(player: Player, skillId: String, perkId: String): DungeonAdventureActionResult
    fun catalog(player: Player): List<DungeonCatalogEntry>
    fun bosses(player: Player): List<DungeonBossInfo>
    fun enter(player: Player, id: String, difficultyId: String?, level: Int?): DungeonAdventureActionResult
    fun lobbies(player: Player, contentId: String): List<DungeonLobbyInfo>
    fun join(player: Player, contentId: String, lobbyId: String, spectator: Boolean): DungeonAdventureActionResult
    fun track(player: Player, bossId: UUID): DungeonAdventureActionResult
}

/** Pure counterpart of DynamicDungeonBrowser's native level-selection range. */
internal fun dynamicDungeonAvailableLevels(combatLevel: Int): List<Int> {
    val baseLevel = max(1, combatLevel)
    val minLevel = max(5, baseLevel - 5)
    val maxLevel = minOf(200, baseLevel + 5)
    return buildList {
        var level = minLevel
        while (level <= maxLevel) {
            add(level)
            level += 5
        }
    }
}

internal fun recommendedDynamicDungeonLevel(availableLevels: List<Int>, combatLevel: Int): Int? =
    availableLevels.minByOrNull { abs(it - max(1, combatLevel)) }

/** Keeps the native difficulty name separate from the stable id selected by ARC's UI. */
internal fun nativeDungeonDifficulty(raw: Map<*, *>): DungeonDifficultyInfo? {
    val name = raw["name"]?.toString()?.trim().orEmpty()
    if (name.isBlank()) return null
    val id = raw["id"]?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: name
    val level = (raw["level"] as? Number)?.toInt() ?: raw["level"]?.toString()?.toIntOrNull()
    return DungeonDifficultyInfo(id = id, name = name, level = level)
}

/** Mirrors SkillBonusMenu's unlock-before-toggle and per-skill-type active-slot checks. */
internal fun skillPerkToggleGuard(
    playerLevel: Int,
    requiredLevel: Int,
    isActive: Boolean,
    activeCount: Int,
    maxActive: Int,
): DungeonAdventureActionResult = when {
    playerLevel < requiredLevel -> DungeonAdventureActionResult.LEVEL_REQUIRED
    isActive -> DungeonAdventureActionResult.PERK_DEACTIVATED
    activeCount >= maxActive -> DungeonAdventureActionResult.PERK_LIMIT
    else -> DungeonAdventureActionResult.PERK_ACTIVATED
}

internal fun skillTypeFromId(skillId: String): SkillType? = try {
    SkillType.valueOf(skillId.trim().uppercase(Locale.ROOT))
} catch (_: IllegalArgumentException) {
    null
}

internal object NativeDungeonAdventureService : DungeonAdventureService {
    override fun stats(player: Player): List<DungeonAdventureValue>? {
        val playerId = player.uniqueId
        if (!PlayerData.isDataLoaded(playerId)) return null
        val snapshot = StatsPage.StatsSnapshot.capture(player)
        fun stat(key: String, placeholder: String) =
            DungeonAdventureValue(key, snapshot.replacePlaceholders("\$$placeholder"))
        val activeQuests = PlayerData.getQuests(playerId).orEmpty().count {
            it.isAccepted() && !it.getQuestObjectives().isTurnedIn()
        }
        return listOf(
            stat("rank", "rank"),
            stat("score", "score"),
            stat("dungeons", "dungeonsCompleted"),
            stat("combatLevel", "combatLevel"),
            stat("kills", "kills"),
            stat("highestKill", "highestkill"),
            stat("quests", "questsCompleted"),
            stat("deaths", "deaths"),
            stat("money", "money"),
            DungeonAdventureValue("activeQuests", activeQuests.toString()),
        )
    }

    override fun gear(player: Player): DungeonGearView? {
        val playerId = player.uniqueId
        if (!PlayerData.isDataLoaded(playerId)) return null

        val weapon = player.inventory.itemInMainHand
        val combatLevel = CombatLevelCalculator.calculateCombatLevel(playerId)
        val referenceLevel = max(1, combatLevel)
        val weaponLevel = WeaponOffenseCalculator.getEffectiveWeaponLevel(weapon)
        val weaponSkill = WeaponIdentityResolver.progressionSkill(weapon)
        val skillsExcluded = SkillsConfig.isWorldExcludedFromSkills(player)
        val weaponSkillLevel = if (skillsExcluded || weaponSkill == null) 1 else max(
            1,
            SkillXPCalculator.levelFromTotalXP(PlayerData.getSkillXP(playerId, weaponSkill)),
        )
        val armorSkillLevel = if (skillsExcluded) 1 else max(
            1,
            SkillXPCalculator.levelFromTotalXP(PlayerData.getSkillXP(playerId, SkillType.ARMOR)),
        )
        val armorLevel = ArmorDefenseCalculator.getGearScore(player, ArmorDefenseCalculator.DamageType.MELEE)
        val weaponFactor = WeaponOffenseCalculator.getWeaponAdjustment(weaponLevel, referenceLevel)
        val defenseMatch = Math.round(ArmorDefenseCalculator.getGearReduction(armorLevel, referenceLevel) * 100.0).toInt()
        val inventory = ElitePlayerInventory.getPlayer(player)
        val critChance = (inventory?.getCritChance(true) ?: 0.0) * 100.0
        val enchantmentBonus = (inventory?.getEliteEnchantmentDamage(true) ?: 0.0) * 100.0
        val threatMultiplier = 1.0 + (inventory?.getLoudStrikesBonusMultiplier(true) ?: 0.0)
        val maxHealth = max(0.0, player.getAttribute(Attribute.MAX_HEALTH)?.value ?: player.health)
        val health = player.health.coerceIn(0.0, maxHealth)

        val summary = listOf(
            DungeonAdventureValue("combatLevel", combatLevel.toString()),
            DungeonAdventureValue("referenceLevel", referenceLevel.toString()),
            DungeonAdventureValue("weaponLevel", formatNumber(weaponLevel)),
            DungeonAdventureValue("weaponSkill", weaponSkill?.let(SkillBonusMenuConfig::getSkillTypeDisplayName)
                ?: PlayerStatusMenuConfig.getGearUnarmedLabel()),
            DungeonAdventureValue("weaponSkillLevel", weaponSkillLevel.toString()),
            DungeonAdventureValue("armorSkillLevel", armorSkillLevel.toString()),
            DungeonAdventureValue("armorLevel", formatNumber(armorLevel)),
            DungeonAdventureValue("weaponFactor", "${formatMultiplier(weaponFactor)}×"),
            DungeonAdventureValue("critChance", "${formatNumber(critChance)}%"),
            DungeonAdventureValue("enchantmentBonus", "${formatNumber(enchantmentBonus)}%"),
            DungeonAdventureValue("threatMultiplier", "${formatMultiplier(threatMultiplier)}×"),
            DungeonAdventureValue("health", formatNumber(health)),
            DungeonAdventureValue("maxHealth", formatNumber(maxHealth)),
            DungeonAdventureValue("defenseMatch", "$defenseMatch%"),
        )
        val equipment = listOf<Pair<String, ItemStack?>>(
            "helmet" to player.inventory.helmet,
            "chestplate" to player.inventory.chestplate,
            "leggings" to player.inventory.leggings,
            "boots" to player.inventory.boots,
            "mainhand" to player.inventory.itemInMainHand,
            "offhand" to player.inventory.itemInOffHand,
        ).map { (slot, item) -> equipmentInfo(slot, item) }
        val weaponPerks = weaponSkill?.let { SkillBonusRegistry.getFormattedBonuses(player, it) }.orEmpty()
        val armorPerks = SkillBonusRegistry.getFormattedBonuses(player, SkillType.ARMOR)
        return DungeonGearView(summary, equipment, weaponPerks, armorPerks)
    }

    override fun skills(player: Player): List<DungeonSkillInfo>? {
        val playerId = player.uniqueId
        if (!PlayerData.isDataLoaded(playerId)) return null
        return SkillType.values().map { type ->
            val totalXp = PlayerData.getSkillXP(playerId, type)
            val level = SkillXPCalculator.levelFromTotalXP(totalXp)
            DungeonSkillInfo(
                id = type.name,
                name = SkillBonusMenuConfig.getSkillTypeDisplayName(type),
                level = level,
                xp = SkillXPCalculator.xpProgressInCurrentLevel(totalXp),
                nextXp = SkillXPCalculator.xpToNextLevel(level),
                progress = SkillXPCalculator.levelProgress(totalXp),
            )
        }
    }

    override fun perks(player: Player, skillId: String): DungeonSkillPerkView? {
        if (!SkillsConfig.isSkillSystemEnabled()) return null
        val playerId = player.uniqueId
        if (!PlayerData.isDataLoaded(playerId)) return null
        val skillType = skillTypeFromId(skillId) ?: return null
        val totalXp = PlayerData.getSkillXP(playerId, skillType)
        val level = SkillXPCalculator.levelFromTotalXP(totalXp)
        val activeSkills = PlayerSkillSelection.getActiveSkills(playerId, skillType)
        val perks = SkillBonusesConfig.getEnabledBySkillType(skillType)
            .sortedWith(compareBy<SkillBonusConfigFields> { it.unlockTier }.thenBy { it.skillId.lowercase(Locale.ROOT) })
            .map { config ->
                val registered = SkillBonusRegistry.getSkillById(config.skillId)
                val bonus = if (level < config.requiredLevel || registered == null) {
                    ""
                } else {
                    registered.getFormattedBonus(level).ifEmpty {
                        String.format(Locale.ROOT, "%.1f", registered.getBonusValue(level))
                    }
                }
                DungeonSkillPerkInfo(
                    id = config.skillId,
                    name = config.name,
                    tier = config.unlockTier,
                    requiredLevel = config.requiredLevel,
                    active = activeSkills.any { it.equals(config.skillId, ignoreCase = true) },
                    description = config.description.orEmpty(),
                    bonus = bonus,
                )
            }
        return DungeonSkillPerkView(
            skill = DungeonSkillInfo(
                id = skillType.name,
                name = SkillBonusMenuConfig.getSkillTypeDisplayName(skillType),
                level = level,
                xp = SkillXPCalculator.xpProgressInCurrentLevel(totalXp),
                nextXp = SkillXPCalculator.xpToNextLevel(level),
                progress = SkillXPCalculator.levelProgress(totalXp),
            ),
            maxActive = PlayerSkillSelection.MAX_ACTIVE_SKILLS,
            perks = perks,
        )
    }

    override fun togglePerk(
        player: Player,
        skillId: String,
        perkId: String,
    ): DungeonAdventureActionResult {
        if (!SkillsConfig.isSkillSystemEnabled()) return DungeonAdventureActionResult.FAILED
        val playerId = player.uniqueId
        if (!PlayerData.isDataLoaded(playerId)) return DungeonAdventureActionResult.FAILED
        val skillType = skillTypeFromId(skillId) ?: return DungeonAdventureActionResult.INVALID_SELECTION
        // Re-resolve from enabled configs at mutation time so stale UI cannot activate a disabled perk.
        val config = SkillBonusesConfig.getEnabledBySkillType(skillType)
            .firstOrNull { it.skillId.equals(perkId, ignoreCase = true) }
            ?: return DungeonAdventureActionResult.MISSING
        val playerLevel = SkillXPCalculator.levelFromTotalXP(PlayerData.getSkillXP(playerId, skillType))
        val activeSkills = PlayerSkillSelection.getActiveSkills(playerId, skillType)
        when (skillPerkToggleGuard(
            playerLevel = playerLevel,
            requiredLevel = config.requiredLevel,
            isActive = activeSkills.any { it.equals(config.skillId, ignoreCase = true) },
            activeCount = activeSkills.size,
            maxActive = PlayerSkillSelection.MAX_ACTIVE_SKILLS,
        )) {
            DungeonAdventureActionResult.LEVEL_REQUIRED -> return DungeonAdventureActionResult.LEVEL_REQUIRED
            DungeonAdventureActionResult.PERK_LIMIT -> return DungeonAdventureActionResult.PERK_LIMIT
            DungeonAdventureActionResult.PERK_DEACTIVATED -> {
                if (!PlayerSkillSelection.removeActiveSkill(playerId, skillType, config.skillId))
                    return DungeonAdventureActionResult.CHANGED
                SkillBonusRegistry.getSkillById(config.skillId)?.onDeactivate(player)
                return DungeonAdventureActionResult.PERK_DEACTIVATED
            }
            DungeonAdventureActionResult.PERK_ACTIVATED -> {
                if (!PlayerSkillSelection.addActiveSkill(playerId, skillType, config.skillId))
                    return DungeonAdventureActionResult.CHANGED
                SkillBonusRegistry.getSkillById(config.skillId)?.onActivate(player)
                return DungeonAdventureActionResult.PERK_ACTIVATED
            }
            else -> return DungeonAdventureActionResult.FAILED
        }
    }

    override fun catalog(player: Player): List<DungeonCatalogEntry> {
        val combatLevel = CombatLevelCalculator.calculateCombatLevel(player.uniqueId)
        return EMPackage.getEmPackages().values.asSequence()
            .filter(::isCatalogPackage)
            .mapNotNull { pkg -> catalogEntry(player, pkg, combatLevel) }
            .sortedWith(compareBy<DungeonCatalogEntry> { it.lowestLevel }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
            .toList()
    }

    override fun bosses(player: Player): List<DungeonBossInfo> = CustomBossEntity.getTrackableCustomBosses().asSequence()
        .filter { it.exists() }
        .mapNotNull { boss ->
            val location = boss.location ?: return@mapNotNull null
            val world = location.world?.name ?: return@mapNotNull null
            val name = boss.customBossesConfigFields?.name?.takeIf(String::isNotBlank) ?: boss.name
            DungeonBossInfo(boss.eliteUUID, name, world, boss.level)
        }
        .sortedWith(compareBy<DungeonBossInfo> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
        .toList()

    override fun enter(
        player: Player,
        id: String,
        difficultyId: String?,
        level: Int?,
    ): DungeonAdventureActionResult {
        val pkg = EMPackage.getEmPackages()[id]?.takeIf(::isCatalogPackage)
            ?: return DungeonAdventureActionResult.MISSING
        val fields = pkg.contentPackagesConfigFields
        val permission = fields.permission
        if (!permission.isNullOrBlank() && !player.hasPermission(permission)) return DungeonAdventureActionResult.NO_PERMISSION
        if (MatchInstance.getAnyPlayerInstance(player) != null) return DungeonAdventureActionResult.IN_INSTANCE

        if (pkg is WorldDungeonPackage && fields.contentType == ContentType.OPEN_DUNGEON) {
            if (fields.teleportLocation?.world == null) return DungeonAdventureActionResult.FAILED
            // This native entry point directly teleports open-world packages when the source is NONE;
            // it does not open the legacy browser and preserves native party and teleport checks.
            DungeonCommands.teleport(player, id, DungeonCommands.TeleportMenuSource.NONE)
            return DungeonAdventureActionResult.REQUESTED
        }

        val difficulties = fields.difficulties.orEmpty().mapNotNull(::nativeDungeonDifficulty)
        val difficultyName = if (difficulties.isEmpty()) {
            if (!difficultyId.isNullOrBlank()) return DungeonAdventureActionResult.INVALID_SELECTION
            null
        } else {
            difficulties.firstOrNull { it.id == difficultyId }?.name
                ?: return DungeonAdventureActionResult.INVALID_SELECTION
        }

        when (pkg) {
            is DynamicDungeonPackage -> {
                val availableLevels = dynamicDungeonAvailableLevels(CombatLevelCalculator.calculateCombatLevel(player.uniqueId))
                val selectedLevel = level ?: recommendedDynamicDungeonLevel(availableLevels, CombatLevelCalculator.calculateCombatLevel(player.uniqueId))
                    ?: return DungeonAdventureActionResult.CHANGED
                if (selectedLevel !in availableLevels) return DungeonAdventureActionResult.INVALID_SELECTION
                DynamicDungeonInstance.setupDynamicDungeon(player, id, difficultyName, selectedLevel)
                return DungeonAdventureActionResult.REQUESTED
            }
            is WorldInstancedDungeonPackage -> {
                if (level != null) return DungeonAdventureActionResult.INVALID_SELECTION
                DungeonInstance.setupInstancedDungeon(player, id, difficultyName)
                return DungeonAdventureActionResult.REQUESTED
            }
            else -> return DungeonAdventureActionResult.MISSING
        }
    }

    override fun lobbies(player: Player, contentId: String): List<DungeonLobbyInfo> {
        val pkg = EMPackage.getEmPackages()[contentId]?.takeIf(::isCatalogPackage) ?: return emptyList()
        val currentPermission = pkg.contentPackagesConfigFields.permission
        if (!currentPermission.isNullOrBlank() && !player.hasPermission(currentPermission)) return emptyList()
        return DungeonInstance.getDungeonInstances().asSequence()
            .filter { !it.isDefunct() && it.world != null }
            .filter { it.contentPackagesConfigFields === pkg.contentPackagesConfigFields }
            .filter { it.contentPackagesConfigFields.filename == contentId }
            .mapNotNull { instance -> lobbyInfo(instance) }
            .filter { it.waiting || it.spectatable }
            .sortedWith(compareBy<DungeonLobbyInfo> { !it.waiting }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
            .toList()
    }

    override fun join(
        player: Player,
        contentId: String,
        lobbyId: String,
        spectator: Boolean,
    ): DungeonAdventureActionResult {
        val pkg = EMPackage.getEmPackages()[contentId]?.takeIf(::isCatalogPackage)
            ?: return DungeonAdventureActionResult.MISSING
        val permission = pkg.contentPackagesConfigFields.permission
        if (!permission.isNullOrBlank() && !player.hasPermission(permission)) return DungeonAdventureActionResult.NO_PERMISSION
        if (MatchInstance.getAnyPlayerInstance(player) != null) return DungeonAdventureActionResult.IN_INSTANCE
        val runtimeId = lobbyId.toUuidOrNull() ?: return DungeonAdventureActionResult.CHANGED
        val instance = DungeonInstance.getDungeonInstances().firstOrNull { it.runtimeId == runtimeId }
            ?: return DungeonAdventureActionResult.CHANGED
        if (instance.isDefunct() || instance.world == null ||
            instance.contentPackagesConfigFields !== pkg.contentPackagesConfigFields ||
            instance.contentPackagesConfigFields.filename != contentId)
            return DungeonAdventureActionResult.CHANGED

        return if (spectator) {
            if (!DungeonsConfig.isAllowSpectatorsInInstancedContent()) return DungeonAdventureActionResult.CHANGED
            if (instance.state !in setOf(MatchInstance.InstancedRegionState.STARTING, MatchInstance.InstancedRegionState.ONGOING))
                return DungeonAdventureActionResult.CHANGED
            instance.addSpectator(player, false)
            DungeonAdventureActionResult.SPECTATING
        } else {
            if (!instance.isAcceptingNewPlayers()) return DungeonAdventureActionResult.CHANGED
            if (instance.requestPartyEntry(player)) DungeonAdventureActionResult.REQUESTED else DungeonAdventureActionResult.FAILED
        }
    }

    override fun track(player: Player, bossId: UUID): DungeonAdventureActionResult {
        if (!player.hasPermission("elitemobs.boss.track")) return DungeonAdventureActionResult.NO_PERMISSION
        val boss = CustomBossEntity.getTrackableCustomBosses().firstOrNull { it.eliteUUID == bossId && it.exists() }
            ?: return DungeonAdventureActionResult.MISSING
        // EliteMobs' public addTrackingPlayer is itself a toggle; keep its state authoritative.
        boss.bossTrackingBar.addTrackingPlayer(player)
        return DungeonAdventureActionResult.TRACKED
    }

    private fun isCatalogPackage(pkg: EMPackage): Boolean {
        val fields = pkg.contentPackagesConfigFields
        return pkg.isInstalled() && pkg is CombatContent && fields.isListedInTeleports() && !fields.isEnchantmentChallenge()
    }

    private fun catalogEntry(player: Player, pkg: EMPackage, combatLevel: Int): DungeonCatalogEntry? {
        val fields = pkg.contentPackagesConfigFields
        val contentType = fields.contentType ?: return null
        val dynamic = pkg is DynamicDungeonPackage
        val levels = if (dynamic) dynamicDungeonAvailableLevels(combatLevel) else emptyList()
        val permission = fields.permission
        val info = fields.playerInfo?.takeIf(String::isNotBlank)
            ?.replace("$" + "bossCount", pkg.customBossEntityList.size.toString())
            ?.replace("$" + "lowestTier", (pkg as CombatContent).lowestLevel.toString())
            ?.replace("$" + "highestTier", (pkg as CombatContent).highestLevel.toString())
        val description = (info?.lineSequence()?.map(String::trim)?.filter(String::isNotBlank)?.toList()
            ?: fields.customInfo.orEmpty().map(String::trim).filter(String::isNotBlank))
        val difficulties = fields.difficulties.orEmpty().mapNotNull(::nativeDungeonDifficulty)
        val baseLevel = max(1, combatLevel)
        return DungeonCatalogEntry(
            id = fields.filename,
            name = fields.name?.takeIf(String::isNotBlank) ?: fields.filename,
            type = contentType.name,
            level = if (dynamic) recommendedDynamicDungeonLevel(levels, baseLevel) ?: baseLevel else (pkg as CombatContent).lowestLevel,
            lowestLevel = (pkg as CombatContent).lowestLevel,
            highestLevel = (pkg as CombatContent).highestLevel,
            description = description,
            difficulties = difficulties,
            permissionGranted = permission.isNullOrBlank() || player.hasPermission(permission),
            maxPlayers = fields.maxPlayerCount.takeIf { it > 0 },
            dynamic = dynamic,
            availableLevels = levels,
        )
    }

    private fun lobbyInfo(instance: DungeonInstance): DungeonLobbyInfo? {
        val fields = instance.contentPackagesConfigFields
        val waiting = instance.state == MatchInstance.InstancedRegionState.WAITING && instance.isAcceptingNewPlayers()
        val spectatable = DungeonsConfig.isAllowSpectatorsInInstancedContent() &&
            instance.state in setOf(MatchInstance.InstancedRegionState.STARTING, MatchInstance.InstancedRegionState.ONGOING)
        if (!waiting && !spectatable) return null
        val difficulty = fields.difficulties.orEmpty().mapNotNull(::nativeDungeonDifficulty)
            .firstOrNull { it.id == instance.getDifficultyID() }?.name
            ?: instance.getDifficultyID()?.takeIf(String::isNotBlank)
            ?: "Без выбора"
        val level = (instance as? DynamicDungeonInstance)?.selectedLevel
            ?: instance.levelSync.takeIf { it > 0 }
            ?: fields.contentLevel.takeIf { it > 0 }
            ?: (EMPackage.getEmPackages()[fields.filename] as? CombatContent)?.lowestLevel
            ?: 1
        return DungeonLobbyInfo(
            id = instance.runtimeId.toString(),
            name = fields.name?.takeIf(String::isNotBlank) ?: fields.filename,
            difficulty = difficulty,
            level = level,
            players = instance.players.map(Player::getDisplayName).sortedBy { it.lowercase(Locale.ROOT) },
            waiting = waiting,
            spectatable = spectatable,
        )
    }

    private fun equipmentInfo(slot: String, item: ItemStack?): DungeonEquipmentInfo {
        if (item == null || item.type.isAir) return DungeonEquipmentInfo(slot, "Пусто", 0, emptyList())
        val meta = item.itemMeta
        val name = meta?.takeIf { it.hasDisplayName() }?.displayName ?: item.type.key.key
        return DungeonEquipmentInfo(
            slot = slot,
            name = name,
            level = EliteItemManager.getRoundedItemLevel(item),
            lore = meta?.lore.orEmpty(),
        )
    }

    private fun formatNumber(value: Double): String {
        if (!value.isFinite()) return "0"
        if (abs(value - Math.rint(value)) < 0.05) return Math.round(value).toString()
        return String.format(Locale.ROOT, "%.1f", value)
    }

    private fun formatMultiplier(value: Double): String =
        if (!value.isFinite()) "1.00" else String.format(Locale.ROOT, "%.2f", value)

    private fun String.toUuidOrNull(): UUID? = try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        null
    }
}
