package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.passives.PassiveCondition
import com.magmaguy.elitemobs.advancedcombat.passives.PassiveProfile
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

internal class DungeonClassLocalization(
    private val config: Config = ConfigManager.of(ru.arc.dungeons.ArcDungeonsPlugin.instance.legacyDataPath, "modules/elitemobs.yml"),
) {
    fun formName(formId: String, fallback: String): String =
        config.string("dungeon-qol.classes.catalog.$formId.name", fallback)

    /** Shares the exact typed registry installed in native combat; YAML only supplies labels. */
    fun passive(formId: String, fallbackSource: String, contributionLevel: Int): DungeonClassPassive {
        val registry = PassiveClassContent.registry()
        val traits = registry.traits(formId)
        val baseline = traits.filter { it.unconditional() }.fold(registry.require(formId).atContributionLevel(contributionLevel)) { sum, trait ->
            sum.plus(trait.profile().atContributionLevel(contributionLevel))
        }
        val effects = buildList {
            addAll(effects(baseline, emptySet()))
            traits.filterNot { it.unconditional() }.groupBy { it.conditions() }.forEach { (conditions, matching) ->
                val contribution = matching.map { it.profile().atContributionLevel(contributionLevel) }.reduce { sum, item -> sum.plus(item) }
                addAll(effects(contribution, conditions))
            }
            registry.mechanicTraits(formId).forEach { trait ->
                val mechanics = trait.atContributionLevel(contributionLevel)
                effect("control-resistance", "Сопротивление контролю", mechanics.controlResistanceFraction(), trait.conditions())?.let(::add)
                effect("party-speed", "Скорость группы рядом", mechanics.partyMovementSpeedAdjustment(), trait.conditions())?.let(::add)
            }
        }
        return DungeonClassPassive(formName(formId, fallbackSource), contributionLevel, effects)
    }

    private fun effects(value: PassiveProfile.Contribution, conditions: Set<PassiveCondition>) = listOfNotNull(
        effect("damage", "Наносимый урон", value.outgoingDamage(), conditions),
        effect("incoming-damage", "Получаемый урон", value.incomingDamage(), conditions, lowerIsBetter = true),
        effect("speed", "Скорость движения", value.movementSpeed(), conditions),
        effect("healing-received", "Получаемое лечение", value.healingReceived(), conditions),
    )

    private fun effect(
        key: String,
        fallback: String,
        fraction: Double,
        conditions: Set<PassiveCondition>,
        lowerIsBetter: Boolean = false,
    ): DungeonClassPassiveEffect? {
        if (abs(fraction) < .0000001) return null
        val condition = conditions.filterNot { it == PassiveCondition.ALWAYS }.sortedBy { it.ordinal }.joinToString("; ", transform = ::condition)
        val stat = config.string("dungeon-qol.classes.stats.$key", fallback)
        return DungeonClassPassiveEffect(if (condition.isEmpty()) stat else "$stat ($condition)", fraction, (fraction > 0) != lowerIsBetter)
    }

    private fun condition(value: PassiveCondition): String = config.string(
        "dungeon-qol.classes.conditions.${value.name.lowercase().replace('_', '-')}",
        when (value) {
            PassiveCondition.ALWAYS -> "постоянно"
            PassiveCondition.HEALTH_BELOW_75 -> "здоровье < 75%"
            PassiveCondition.HEALTH_BELOW_50 -> "здоровье < 50%"
            PassiveCondition.HEALTH_BELOW_25 -> "здоровье < 25%"
            PassiveCondition.MOVING -> "в движении"
            PassiveCondition.STANDING -> "без движения"
            PassiveCondition.DRAWING_RANGED_WEAPON -> "при натяжении лука или арбалета"
            PassiveCondition.RECENTLY_HIT -> "3 с после удара элиты"
            PassiveCondition.NOT_RECENTLY_HIT -> "без ударов элит 3 с"
            PassiveCondition.GROUPED -> "рядом с участником группы"
            PassiveCondition.SOLO -> "без участников группы рядом"
            PassiveCondition.TARGET_WOUNDED -> "здоровье цели < 50%"
            PassiveCondition.TARGET_HEALTHY -> "здоровье цели ≥ 50%"
            PassiveCondition.TARGET_BOSS -> "по боссам"
            PassiveCondition.TARGET_ORDINARY -> "по обычным элитам"
            PassiveCondition.TARGET_CONTROLLED -> "цель под контролем"
            PassiveCondition.TARGET_UNCONTROLLED -> "цель без контроля"
            PassiveCondition.TARGET_ISOLATED -> "по одиночной цели"
            PassiveCondition.TARGET_GROUPED -> "по цели рядом с другими элитами"
            PassiveCondition.CLOSE_RANGE -> "цель не дальше 6 блоков"
            PassiveCondition.LONG_RANGE -> "цель не ближе 18 блоков"
            PassiveCondition.CRITICAL_HIT -> "критический удар"
            PassiveCondition.RANGED_ATTACK -> "дальняя атака"
            PassiveCondition.SPELL_DAMAGE -> "посохи и жезлы"
            PassiveCondition.RECENT_ELITE_KILL -> "6 с после убийства элиты"
            // Compatibility labels for the official API; the passive-only registry no longer emits these conditions.
            PassiveCondition.CLASS_ABILITY_DAMAGE -> "классовое умение"
            PassiveCondition.AREA_CLASS_ABILITY_DAMAGE -> "классовое умение по области"
            PassiveCondition.TRAP_CLASS_ABILITY_DAMAGE -> "классовая ловушка"
            PassiveCondition.BLAST_CLASS_ABILITY_DAMAGE -> "классовой взрыв"
            PassiveCondition.WARD_BROKEN -> "после разрушения щита"
        },
    )
}

internal fun dungeonPassivePercent(fraction: Double): String {
    val value = BigDecimal.valueOf(abs(fraction) * 100).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')
    return "${if (fraction < 0) "−" else "+"}$value%"
}
