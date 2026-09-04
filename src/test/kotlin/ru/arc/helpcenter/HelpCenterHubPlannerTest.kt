package ru.arc.helpcenter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HelpCenterHubPlannerTest {
    @Test
    fun `goal routes are small ordered catalog selections`() {
        assertEquals(listOf("jobs", "sell", "shops", "auction", "bank"), HelpCenterHubPlanner.goalActions(HelpCenterGoal.EARN))
        assertEquals(listOf("events", "duels", "dungeons", "battle-pass"), HelpCenterHubPlanner.goalActions(HelpCenterGoal.FIGHT))
        assertTrue(HelpCenterHubPlanner.goalActions(HelpCenterGoal.EXPLORE).size <= 5)
    }

    @Test
    fun `custom held item gets exact recipe while vanilla item does not`() {
        val custom = HelpCenterHeldItem("Кобальтовая кирка", "DIAMOND_PICKAXE", 1, "ruscrafting:cobalt_pickaxe")
        val vanilla = HelpCenterHeldItem("Алмазная кирка", "DIAMOND_PICKAXE", 1, null)

        assertEquals("iarecipe ruscrafting:cobalt_pickaxe", HelpCenterHubPlanner.itemRecipeCommand(custom))
        assertEquals(null, HelpCenterHubPlanner.itemRecipeCommand(vanilla))
    }

    @Test
    fun `diagnostics use facts and never invent state`() {
        val context = HelpCenterContext(
            server = "survival",
            world = "world",
            worldKind = HelpCenterWorldKind.VANILLA,
            x = 10,
            y = 65,
            z = -20,
            heldItem = null,
            landName = null,
            landOwner = false,
            features = setOf(HelpCenterFeature.HUSK_HOMES),
        )
        val facts = HelpCenterHubPlanner.diagnosticFacts(HelpCenterProblem.CANNOT_CLAIM, context, homesLoaded = true)

        assertTrue(facts.any { it.id == "outside-land" && it.positive })
        assertFalse(facts.any { it.id == "lands-ready" && it.positive })
    }
}
