package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.content.BuiltInClassContent
import com.magmaguy.elitemobs.advancedcombat.progression.InputProfile
import com.magmaguy.elitemobs.advancedcombat.progression.ProfileSnapshot
import com.magmaguy.elitemobs.advancedcombat.progression.RunSelection
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.SkillType.*
import com.magmaguy.elitemobs.skills.SkillXPCalculator
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class DungeonSharedSkillRoutingTest : FreeSpec({
    val catalog = BuiltInClassContent.catalog()

    fun profile(selected: String?, locked: String? = null) = ProfileSnapshot(
        UUID.randomUUID(), selected, InputProfile.DEFAULT, catalog.persistenceVersion(),
        locked?.let { UUID.randomUUID() }, locked?.let { RunSelection(it, InputProfile.DEFAULT) },
        null, emptyMap(),
    )

    fun route(
        selected: String?,
        source: SkillType,
        vararg levels: Pair<SkillType, Int>,
        locked: String? = null,
    ): SharedSkillTarget? {
        val xp = levels.associate { (skill, level) -> skill to SkillXPCalculator.totalXPForLevel(level) }
        return DungeonSharedSkillRouting.target(catalog, profile(selected, locked), source) { xp[it] ?: 0L }
    }

    fun capped(skill: SkillType, level: Int) = SharedSkillTarget(skill, SkillXPCalculator.totalXPForLevel(level))

    "every native two-weapon foundation shares in both directions within its current band" {
        val pairedForms = catalog.forms().filter { form ->
            form.foundationSkills().asList().count { it.isWeaponSkill } == 2
        }
        pairedForms.isNotEmpty() shouldBe true
        for (form in pairedForms) {
            val pair = form.foundationSkills().asList()
            val level = maxOf(form.requiredFoundationSkillLevel(), form.band().effectiveStart()) + 1
            val currentXp = SkillXPCalculator.totalXPForLevel(level)
            val ancestorXp = SkillXPCalculator.totalXPForLevel(90)
            val ceiling = if (form.band().isTerminal) Long.MAX_VALUE
                else SkillXPCalculator.totalXPForLevel(form.band().effectiveEnd())
            for (source in pair) {
                DungeonSharedSkillRouting.target(catalog, profile(form.id()), source) {
                    if (it in pair) currentXp else ancestorXp
                } shouldBe SharedSkillTarget(pair.single { it != source }, ceiling)
            }
        }
    }

    "starter and missing selection bootstrap swords from any supported weapon" {
        route("adventurer", WANDS, WANDS to 5) shouldBe capped(SWORDS, 10)
        route(null, HOES, HOES to 5) shouldBe capped(SWORDS, 10)
        route("adventurer", SWORDS, SWORDS to 5) shouldBe null
    }

    "completed starter prepares the root matching the earned weapon without training armor" {
        route("adventurer", WANDS, SWORDS to 10, WANDS to 4) shouldBe capped(STAVES, 10)
        route("adventurer", AXES, SWORDS to 10, AXES to 4) shouldBe capped(MACES, 10)
        route("adventurer", HOES, SWORDS to 10) shouldBe null
    }

    "paladin alternate mace affinity repairs its sword prerequisite" {
        route("paladin", MACES, SWORDS to 12, MACES to 20) shouldBe capped(SWORDS, 30)
        route("paladin", BOWS, SWORDS to 12) shouldBe null
    }

    "spellcaster shares only its current band until native selection advances" {
        route("spellcaster", STAVES, SWORDS to 10, STAVES to 20, WANDS to 15) shouldBe capped(WANDS, 30)
        route("mage", WANDS, SWORDS to 10, STAVES to 43, WANDS to 43) shouldBe capped(STAVES, 60)
        route("spellcaster", STAVES, SWORDS to 10, STAVES to 5, WANDS to 30) shouldBe null
        route("spellcaster", STAVES, SWORDS to 10, STAVES to 30, WANDS to 30) shouldBe null
    }

    "sniper prepares bowmaster spear requirement before class XP or selection catches up" {
        route("sniper", BOWS, SWORDS to 10, BOWS to 60, CROSSBOWS to 60) shouldBe capped(SPEARS, 60)
        route("sniper", SPEARS, SWORDS to 10, BOWS to 60, CROSSBOWS to 60) shouldBe null
        route("sniper", SPEARS, SWORDS to 10, BOWS to 60, CROSSBOWS to 59) shouldBe null
    }

    "priest admits oracle trident affinity only after current weapon prerequisites are complete" {
        route("priest", TRIDENTS, SWORDS to 10, MACES to 60, TRIDENTS to 30) shouldBe capped(SPEARS, 60)
        route("priest", TRIDENTS, SWORDS to 10, MACES to 59, TRIDENTS to 30) shouldBe null
    }

    "ancestors are repaired in order and alternate sources choose the weakest required weapon" {
        route("oracle", SPEARS, SWORDS to 9, MACES to 5, TRIDENTS to 8) shouldBe capped(SWORDS, 10)
        route("oracle", SPEARS, SWORDS to 10, MACES to 5, TRIDENTS to 8) shouldBe capped(MACES, 30)
        route("oracle", SPEARS, SWORDS to 10, MACES to 30, TRIDENTS to 30) shouldBe capped(MACES, 60)
    }

    "nearest child wins and equal missing XP uses stable form ID then moves to the next child" {
        val base = arrayOf(SWORDS to 10, BOWS to 90, SPEARS to 90)
        route("bowmaster", BOWS, *base, CROSSBOWS to 60, HOES to 60) shouldBe capped(CROSSBOWS, 90)
        route("bowmaster", BOWS, *base, CROSSBOWS to 60, HOES to 89) shouldBe capped(HOES, 90)
        route("bowmaster", BOWS, *base, CROSSBOWS to 90, HOES to 60) shouldBe capped(HOES, 90)
    }

    "routing never reaches grandchildren or changes armor" {
        route("ranger", BOWS, SWORDS to 10, BOWS to 30, CROSSBOWS to 30) shouldBe null
        route("paladin", SWORDS, SWORDS to 30) shouldBe capped(SPEARS, 30)
        route("paladin", ARMOR, SWORDS to 12) shouldBe null
        route("bulwark", MACES, SWORDS to 90, SPEARS to 60, MACES to 90) shouldBe null
    }

    "terminal pair keeps sharing beyond admission without an artificial level ceiling" {
        route("pyromancer", STAVES, SWORDS to 10, STAVES to 100, WANDS to 100) shouldBe SharedSkillTarget(WANDS)
    }

    "locked run form wins over selected form even without an unlocked lineage snapshot" {
        route("ranger", STAVES, SWORDS to 10, STAVES to 43, WANDS to 43, locked = "mage") shouldBe capped(WANDS, 60)
        route("unknown", STAVES, SWORDS to 10) shouldBe null
    }
})
