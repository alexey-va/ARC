package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule
import com.magmaguy.elitemobs.advancedcombat.classes.ClassFormDefinition
import com.magmaguy.elitemobs.advancedcombat.progression.ClassProgressionSetResult
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.ArmorSkillHealthBonus
import com.magmaguy.elitemobs.skills.CombatLevelDisplay
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.SkillXPCalculator
import org.bukkit.entity.Player
import java.util.Locale

internal data class DungeonClassSkillIncrease(
    val name: String,
    val previousLevel: Int,
    val newLevel: Int,
)

internal data class DungeonClassGrantResult(
    val status: Status,
    val formId: String,
    val formName: String = formId,
    val skillIncreases: List<DungeonClassSkillIncrease> = emptyList(),
) {
    internal enum class Status {
        APPLIED,
        ALREADY_GRANTED,
        DISABLED,
        NOT_READY,
        UNKNOWN_FORM,
        RUN_LOCKED,
        FAILED,
    }
}

internal interface DungeonClassGrantService {
    fun formIds(): List<String>
    fun grant(player: Player, formId: String): DungeonClassGrantResult
}

internal object NativeDungeonClassGrantService : DungeonClassGrantService {
    private val localization by lazy { DungeonClassLocalization() }

    override fun formIds(): List<String> {
        if (!AdvancedCombatModule.isInitialized()) return emptyList()
        return AdvancedCombatModule.get().catalog().forms().map { it.id() }
    }

    override fun grant(player: Player, formId: String): DungeonClassGrantResult {
        val normalizedId = formId.lowercase(Locale.ROOT)
        if (!AdvancedCombatModule.isInitialized()) {
            return DungeonClassGrantResult(DungeonClassGrantResult.Status.DISABLED, normalizedId)
        }
        val module = AdvancedCombatModule.get()
        if (!PlayerData.isDataLoaded(player.uniqueId)) {
            return DungeonClassGrantResult(DungeonClassGrantResult.Status.NOT_READY, normalizedId)
        }
        val profile = module.profile(player.uniqueId).orElse(null)
            ?: return DungeonClassGrantResult(DungeonClassGrantResult.Status.NOT_READY, normalizedId)
        val form = module.catalog().find(normalizedId).orElse(null)
            ?: return DungeonClassGrantResult(DungeonClassGrantResult.Status.UNKNOWN_FORM, normalizedId)
        val localizedName = localization.formName(form.id(), form.displayName())
        if (profile.forms()[form.id()]?.unlocked() == true) {
            return DungeonClassGrantResult(
                DungeonClassGrantResult.Status.ALREADY_GRANTED,
                form.id(),
                localizedName,
            )
        }
        if (profile.lockedRunSelection() != null) {
            return DungeonClassGrantResult(DungeonClassGrantResult.Status.RUN_LOCKED, form.id(), localizedName)
        }

        val requiredLevels = requiredDungeonClassSkillLevels(module.catalog().progressionPathOf(form.id()))
        val originalXp = requiredLevels.keys.associateWith { PlayerData.getSkillXP(player.uniqueId, it) }
        val changedSkills = mutableSetOf<SkillType>()
        val increases = requiredLevels.mapNotNull { (skill, requiredLevel) ->
            val previousLevel = PlayerData.getSkillLevel(player.uniqueId, skill)
            if (previousLevel >= requiredLevel) return@mapNotNull null
            PlayerData.setSkillXP(player.uniqueId, skill, SkillXPCalculator.totalXPForLevel(requiredLevel))
            changedSkills += skill
            DungeonClassSkillIncrease(dungeonSkillName(skill), previousLevel, requiredLevel)
        }

        val result = module.setClassLevelForAdministration(player, form.id(), form.band().effectiveStart())
        if (!result.applied()) {
            originalXp.forEach { (skill, xp) -> PlayerData.setSkillXP(player.uniqueId, skill, xp) }
            refreshSkillEffects(player, originalXp.keys)
            return DungeonClassGrantResult(result.toGrantStatus(), form.id(), localizedName)
        }

        refreshSkillEffects(player, changedSkills)
        return DungeonClassGrantResult(
            DungeonClassGrantResult.Status.APPLIED,
            form.id(),
            localizedName,
            increases,
        )
    }

    private fun refreshSkillEffects(player: Player, changed: Collection<SkillType>) {
        if (changed.isEmpty()) return
        CombatLevelDisplay.updateDisplay(player)
        if (SkillType.ARMOR in changed) ArmorSkillHealthBonus.updateHealthBonus(player)
    }

    private fun ClassProgressionSetResult.toGrantStatus(): DungeonClassGrantResult.Status = when (status()) {
        ClassProgressionSetResult.Status.NOT_READY -> DungeonClassGrantResult.Status.NOT_READY
        ClassProgressionSetResult.Status.UNKNOWN_FORM -> DungeonClassGrantResult.Status.UNKNOWN_FORM
        ClassProgressionSetResult.Status.RUN_LOCKED -> DungeonClassGrantResult.Status.RUN_LOCKED
        ClassProgressionSetResult.Status.APPLIED,
        ClassProgressionSetResult.Status.LEVEL_OUTSIDE_FORM_BAND,
        ClassProgressionSetResult.Status.FOUNDATION_SKILL_CAP,
        -> DungeonClassGrantResult.Status.FAILED
    }
}

internal fun requiredDungeonClassSkillLevels(forms: List<ClassFormDefinition>): Map<SkillType, Int> =
    buildMap {
        forms.forEach { form ->
            form.foundationSkills().asList().forEach { skill ->
                merge(skill, form.requiredFoundationSkillLevel(), ::maxOf)
            }
        }
    }
