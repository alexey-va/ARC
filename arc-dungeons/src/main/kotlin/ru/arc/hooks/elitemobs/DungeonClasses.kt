package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule
import com.magmaguy.elitemobs.advancedcombat.progression.ProgressionCapReason
import com.magmaguy.elitemobs.advancedcombat.progression.SelectionResult
import com.magmaguy.elitemobs.combatsystem.combattag.DungeonCombatRuntime
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.SkillXPCalculator
import org.bukkit.entity.Player

internal enum class DungeonClassAvailability { READY, LOADING, DISABLED }

internal data class DungeonClassPassiveEffect(val label: String, val fraction: Double, val beneficial: Boolean)
internal data class DungeonClassPassive(
    val source: String,
    val contributionLevel: Int,
    val effects: List<DungeonClassPassiveEffect>,
)
internal data class DungeonClassRequirement(val name: String, val progress: String)

internal data class DungeonClassForm(
    val id: String,
    val name: String,
    val rootId: String,
    val parentId: String?,
    val children: List<String>,
    val path: List<String>,
    val unlocked: Boolean,
    val selected: Boolean,
    val active: Boolean,
    val requiredLevel: Int,
    val level: Int,
    val cap: Int,
    val xp: String,
    val foundations: List<DungeonClassRequirement>,
    val blockers: List<DungeonClassRequirement>,
    val weapons: List<String>,
    val passives: List<DungeonClassPassive>,
)

internal data class DungeonClassesView(
    val availability: DungeonClassAvailability,
    val runLocked: Boolean = false,
    val combatLocked: Boolean = false,
    val activeFormId: String? = null,
    val roots: List<String> = emptyList(),
    val forms: Map<String, DungeonClassForm> = emptyMap(),
) {
    val canChange: Boolean get() = availability == DungeonClassAvailability.READY && !runLocked && !combatLocked
    val activeForm: DungeonClassForm? get() = activeFormId?.let(forms::get)
}

internal enum class DungeonClassChange {
    APPLIED,
    UNCHANGED,
    NOT_READY,
    LOCKED,
    UNKNOWN,
}

internal interface DungeonClassService {
    fun view(player: Player): DungeonClassesView
    fun select(player: Player, formId: String): DungeonClassChange
    fun clear(player: Player): DungeonClassChange
}

internal object NativeDungeonClassService : DungeonClassService {
    private val localization by lazy { DungeonClassLocalization() }

    override fun view(player: Player): DungeonClassesView {
        if (!AdvancedCombatModule.isInitialized()) {
            return DungeonClassesView(DungeonClassAvailability.DISABLED)
        }
        val module = AdvancedCombatModule.get()
        val profile = module.profile(player.uniqueId).orElse(null)
            ?: return DungeonClassesView(DungeonClassAvailability.LOADING)
        val catalog = module.catalog()
        val activeLineage = profile.activeLineage()
        val activeFormId = activeLineage?.activeFormId()
        val forms = catalog.forms().associate { definition ->
            val progress = profile.forms()[definition.id()]
                ?: error("EliteMobs class profile is missing ${definition.id()}")
            val lineage = catalog.lineageOf(definition.id())
            val active = activeLineage?.takeIf { it.activeFormId() == definition.id() }
            val displayedLevel = active?.activeEffectiveLevel()
                ?: if (progress.unlocked()) progress.effectiveLevel() else definition.band().effectiveStart()
            val contributionLevels = active?.contributionLevels() ?: lineage.passiveContributionLevels(displayedLevel)
            val foundations = definition.foundationSkills().asList().map { skill ->
                DungeonClassRequirement(
                    dungeonSkillName(skill),
                    "${PlayerData.getSkillLevel(player.uniqueId, skill)} / ${definition.requiredFoundationSkillLevel()}",
                )
            }
            val blockers = progress.unlockBlockers().map { blocker ->
                when (blocker.kind()) {
                    com.magmaguy.elitemobs.advancedcombat.progression.UnlockBlocker.Kind.FOUNDATION_SKILL ->
                        DungeonClassRequirement(dungeonSkillName(blocker.skillType()), "${blocker.currentLevel()} / ${blocker.requiredLevel()}")
                    com.magmaguy.elitemobs.advancedcombat.progression.UnlockBlocker.Kind.PARENT_LOCAL_LEVEL -> {
                        val required = catalog.require(blocker.formId())
                        DungeonClassRequirement(
                            localization.formName(required.id(), required.displayName()),
                            "${effectiveLevel(required.band(), blocker.currentLevel())} / ${required.band().toEffectiveLevel(blocker.requiredLevel())}",
                        )
                    }
                    com.magmaguy.elitemobs.advancedcombat.progression.UnlockBlocker.Kind.CLASS_CHALLENGE ->
                        DungeonClassRequirement("Испытание", "Победить наставника этого класса")
                    com.magmaguy.elitemobs.advancedcombat.progression.UnlockBlocker.Kind.CONTENT_REQUIREMENT ->
                        DungeonClassRequirement("Оснащение", "Требуется доступное оружие этого класса")
                }
            }
            definition.id() to DungeonClassForm(
                id = definition.id(),
                name = localization.formName(definition.id(), definition.displayName()),
                rootId = catalog.rootOf(definition.id()).id(),
                parentId = definition.parentId(),
                children = catalog.childrenOf(definition.id()).map { it.id() },
                path = catalog.progressionPathOf(definition.id()).map { localization.formName(it.id(), it.displayName()) },
                unlocked = progress.unlocked(),
                selected = definition.id() == profile.selectedFormId(),
                active = definition.id() == activeFormId,
                requiredLevel = definition.band().effectiveStart(),
                level = if (progress.unlocked()) displayedLevel else 0,
                cap = progress.effectiveCap(),
                xp = xpSummary(definition.band().effectiveStart(), progress),
                foundations = foundations,
                blockers = blockers,
                weapons = definition.weaponAffinities().map(::dungeonSkillName),
                passives = lineage.forms().map {
                    localization.passive(it.id(), it.displayName(), contributionLevels.getValue(it.id()))
                },
            )
        }
        return DungeonClassesView(
            availability = DungeonClassAvailability.READY,
            runLocked = profile.lockedRunSelection() != null,
            combatLocked = DungeonCombatRuntime.getInstance().isInCombat(player.uniqueId),
            activeFormId = activeFormId,
            roots = catalog.roots().map { it.id() },
            forms = forms,
        )
    }

    override fun select(player: Player, formId: String): DungeonClassChange {
        val current = view(player)
        if (current.availability != DungeonClassAvailability.READY) return DungeonClassChange.NOT_READY
        if (!current.canChange) return DungeonClassChange.LOCKED
        return change(AdvancedCombatModule.get().selectForm(player, formId))
    }

    override fun clear(player: Player): DungeonClassChange {
        val current = view(player)
        if (current.availability != DungeonClassAvailability.READY) return DungeonClassChange.NOT_READY
        if (!current.canChange) return DungeonClassChange.LOCKED
        return change(AdvancedCombatModule.get().clearSelectedForm(player))
    }

    private fun change(result: SelectionResult): DungeonClassChange = when (result.status()) {
        SelectionResult.Status.APPLIED -> DungeonClassChange.APPLIED
        SelectionResult.Status.UNCHANGED -> DungeonClassChange.UNCHANGED
        SelectionResult.Status.NOT_READY -> DungeonClassChange.NOT_READY
        SelectionResult.Status.LOCKED_FORM -> DungeonClassChange.LOCKED
        SelectionResult.Status.UNKNOWN_FORM -> DungeonClassChange.UNKNOWN
    }

    private fun xpSummary(effectiveStart: Int, progress: com.magmaguy.elitemobs.advancedcombat.progression.FormProgressSnapshot): String {
        if (!progress.unlocked()) return "Недоступен"
        if (progress.xp() >= progress.xpAtCap()) {
            return if (progress.capReason() == ProgressionCapReason.BAND_COMPLETE) "Предел ветки"
            else "Предел навыков"
        }
        val curveBaseline = SkillXPCalculator.totalXPForLevel(effectiveStart)
        val currentLevelStart = SkillXPCalculator.totalXPForLevel(progress.effectiveLevel()) - curveBaseline
        val current = (progress.xp() - currentLevelStart).coerceAtLeast(0)
        return "$current / ${SkillXPCalculator.xpToNextLevel(progress.effectiveLevel())}"
    }

    private fun effectiveLevel(band: com.magmaguy.elitemobs.advancedcombat.classes.ClassBand, localLevel: Int): Int =
        if (localLevel == 0) 0 else band.toEffectiveLevel(localLevel)

}

internal fun dungeonSkillName(skill: SkillType): String = when (skill) {
    SkillType.ARMOR -> "Броня"
    SkillType.SWORDS -> "Мечи"
    SkillType.AXES -> "Топоры"
    SkillType.BOWS -> "Луки"
    SkillType.CROSSBOWS -> "Арбалеты"
    SkillType.TRIDENTS -> "Трезубцы"
    SkillType.HOES -> "Косы"
    SkillType.MACES -> "Булавы"
    SkillType.SPEARS -> "Копья"
    SkillType.STAVES -> "Посохи"
    SkillType.WANDS -> "Жезлы"
}
