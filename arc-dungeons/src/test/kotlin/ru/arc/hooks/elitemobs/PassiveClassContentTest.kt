package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.content.BuiltInClassContent
import com.magmaguy.elitemobs.advancedcombat.passives.PassiveCondition
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class PassiveClassContentTest : FreeSpec({
    "all 76 forms retain the accepted balance while the stock singleton remains unchanged" {
        val stock = BuiltInClassContent.passiveRegistry()
        val balanced = PassiveClassContent.registry()
        balanced.mappedFormIds().size shouldBe 76
        balanced.mappedFormIds() shouldBe stock.mappedFormIds()
        PassiveClassContent.fingerprint(balanced) shouldBe PassiveClassContent.BALANCE_FINGERPRINT
        PassiveClassContent.fingerprint(stock) shouldBe PassiveClassContent.STOCK_FINGERPRINT
        (balanced === stock) shouldBe false
    }

    "no passive depends on removed casts while staff damage and ordinary combat mechanics remain" {
        val registry = PassiveClassContent.registry()
        val removed = setOf(PassiveCondition.CLASS_ABILITY_DAMAGE, PassiveCondition.AREA_CLASS_ABILITY_DAMAGE,
            PassiveCondition.TRAP_CLASS_ABILITY_DAMAGE, PassiveCondition.BLAST_CLASS_ABILITY_DAMAGE, PassiveCondition.WARD_BROKEN)
        registry.mappedFormIds().forEach { id ->
            (listOf(registry.require(id)) + registry.traits(id).map { it.profile() }).forEach { profile ->
                profile.healingDoneBase() shouldBe 0.0
                profile.healingDonePerLevel() shouldBe 0.0
                profile.abilityCostReductionBase() shouldBe 0.0
                profile.abilityCostReductionPerLevel() shouldBe 0.0
            }
            registry.traits(id).none { it.conditions().any(removed::contains) } shouldBe true
            registry.mechanicTraits(id).none { it.conditions().any(removed::contains) } shouldBe true
        }
        registry.traits("occultist").single { it.conditions() == setOf(PassiveCondition.SPELL_DAMAGE) }
            .profile().atContributionLevel(9).outgoingDamage() shouldBe .0129
        registry.require("cleric").atContributionLevel(1).healingReceived() shouldBe .0455
        registry.mechanicTraits("trapper").single().atContributionLevel(30).controlResistanceFraction() shouldBe .1
        registry.mechanicTraits("pathfinder").single().atContributionLevel(30).partyMovementSpeedAdjustment() shouldBe .04
    }

    "compatible native additions survive while retired override forms are skipped with one warning" {
        val stock = BuiltInClassContent.passiveRegistry()
        val ids = stock.mappedFormIds() - "cleric"
        val profiles = ids.associateWith(stock::require) + ("new_native_form" to stock.require("ranger"))
        val traits = ids.associateWith(stock::traits) + ("new_native_form" to stock.traits("ranger"))
        val mechanics = ids.associateWith(stock::mechanicTraits) + ("new_native_form" to stock.mechanicTraits("ranger"))
        val current = EliteMobsPassiveAccess.newRegistry(profiles, traits, mechanics)
        val warnings = mutableListOf<String>()
        val balanced = PassiveClassContent.applyBalance(current) { warnings += it }
        balanced.mappedFormIds() shouldBe current.mappedFormIds()
        balanced.require("new_native_form") shouldBe current.require("new_native_form")
        balanced.traits("new_native_form") shouldBe current.traits("new_native_form")
        balanced.mechanicTraits("new_native_form") shouldBe current.mechanicTraits("new_native_form")
        warnings.size shouldBe 1
        warnings.single().contains("cleric") shouldBe true
    }
})
