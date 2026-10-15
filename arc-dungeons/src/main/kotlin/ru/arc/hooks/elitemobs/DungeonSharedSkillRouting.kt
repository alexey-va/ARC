package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule
import com.magmaguy.elitemobs.advancedcombat.classes.ClassCatalog
import com.magmaguy.elitemobs.advancedcombat.classes.ClassFormDefinition
import com.magmaguy.elitemobs.advancedcombat.progression.ProfileSnapshot
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.SkillXPCalculator
import org.bukkit.entity.Player

/** Reads the native selection and prerequisites; never selects, unlocks or persists a class. */
internal object DungeonSharedSkillRouting {
    fun target(player: Player, source: SkillType): SharedSkillTarget? {
        if (!AdvancedCombatModule.isInitialized() || !PlayerData.isDataLoaded(player.uniqueId)) return null
        val module = AdvancedCombatModule.get()
        val profile = module.profile(player.uniqueId).orElse(null) ?: return null
        return target(module.catalog(), profile, source) { PlayerData.getSkillXP(player.uniqueId, it) }
    }

    internal fun target(
        catalog: ClassCatalog,
        profile: ProfileSnapshot,
        source: SkillType,
        xp: (SkillType) -> Long,
    ): SharedSkillTarget? {
        if (!source.isWeaponSkill) return null
        // activeFormId retains the run selection even when lowered skills lock its lineage.
        val active = catalog.find(profile.activeFormId().orElse("adventurer")).orElse(null) ?: return null
        val supported = source in active.weaponAffinities()

        for (stage in catalog.progressionPathOf(active.id())) {
            val maxXp = if (stage.band().isTerminal) Long.MAX_VALUE
                else SkillXPCalculator.totalXPForLevel(stage.band().effectiveEnd())
            val missing = deficientWeapons(stage, maxXp, xp)
            if (missing.isNotEmpty()) {
                // An unrelated weapon must not bypass an unfinished active prerequisite.
                return if (supported) recipient(missing, source, maxXp, xp) else null
            }
        }

        // Only direct children are prepared. Class XP, trials and selection remain native.
        val next = catalog.childrenOf(active.id())
            .filter { source in it.weaponAffinities() }
            .map { child ->
                val maxXp = SkillXPCalculator.totalXPForLevel(child.requiredFoundationSkillLevel())
                val missing = deficientWeapons(child, maxXp, xp)
                Triple(child, maxXp, missing)
            }
            .filter { it.third.isNotEmpty() }
            .minWithOrNull(compareBy<Triple<ClassFormDefinition, Long, List<SkillType>>> {
                (_, maxXp, missing) -> missing.sumOf { maxXp - xp(it) }
            }.thenBy { it.first.id() })

        if (next != null) return recipient(next.third, source, next.second, xp)
        // Keep the current pair together while class XP or the normal class choice catches up.
        val pair = active.foundationSkills().asList().filter { it.isWeaponSkill }
        return if (supported && pair.size == 2 && source in pair) recipient(pair, source, Long.MAX_VALUE, xp)
            else null
    }

    private fun deficientWeapons(form: ClassFormDefinition, maxXp: Long, xp: (SkillType) -> Long) =
        form.foundationSkills().asList().filter { it.isWeaponSkill && xp(it) < maxXp }

    private fun recipient(
        missing: List<SkillType>,
        source: SkillType,
        maxXp: Long,
        xp: (SkillType) -> Long,
    ): SharedSkillTarget? = missing.asSequence()
        .filter { it != source }
        .minWithOrNull(compareBy<SkillType> { xp(it) }.thenBy { it.name })
        ?.let { SharedSkillTarget(it, maxXp) }
}
