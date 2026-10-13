package ru.arc.sidebar

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.InputStreamReader

class SidebarQuestRewardsTest {
    private fun config(): YamlConfiguration = requireNotNull(javaClass.classLoader.getResourceAsStream("modules/scoreboard.yml")).use {
        YamlConfiguration.loadConfiguration(InputStreamReader(it))
    }

    @Test
    fun `daily total section follows quests before the online ping footer`() {
        val config = config()
        val sections = requireNotNull(config.getConfigurationSection("sections"))
        val order = sections.getKeys(false).toList()
        val rewards = sections.getStringList("rewards")
        assertEquals(1, rewards.count { SidebarQuestRewards.SUMMARY_PLACEHOLDER in it })
        assertTrue(order.indexOf("quests") < order.indexOf("rewards"))
        assertTrue(order.indexOf("rewards") < order.indexOf("footer"))
        assertTrue(sections.getStringList("footer").last().contains("Пинг:"))
    }

    @Test
    fun `default sections have one aggregate reward row and no per quest payouts`() {
        val config = config()
        val quests = config.getStringList("sections.quests")
        val rewards = config.getStringList("sections.rewards")
        assertTrue(quests.any { "%arcranks_quest_board_header%" in it })
        assertEquals(1, rewards.count { SidebarQuestRewards.SUMMARY_PLACEHOLDER in it })
        assertFalse((quests + rewards).any { "%arcranks_quest_board_reward_" in it || "%arcranks_quest_reward%" in it })
    }

    @Test
    fun `preference hides only totals and preserves quest rows and footer`() {
        val config = config()
        val lines = listOf("quests", "rewards", "footer").flatMap { config.getStringList("sections.$it") }
        val visible = lines.mapNotNull { SidebarQuestRewards.visibleLine(it, false) }
        assertEquals(lines.filterNot { SidebarQuestRewards.SUMMARY_PLACEHOLDER in it }, visible)
        assertEquals(lines, lines.mapNotNull { SidebarQuestRewards.visibleLine(it, true) })
        assertTrue(SidebarQuestRewards.enabled(null))
        assertFalse(SidebarQuestRewards.enabled("false"))
    }

    @Test
    fun `totals remain under the quest header when all goal rows are empty`() {
        val config = config()
        val lines = listOf("quests", "rewards", "footer").flatMap { config.getStringList("sections.$it") }
        val resolved = lines.mapNotNull { line ->
            resolveOptionalSidebarLine(line) { placeholder ->
                when (placeholder) {
                    "%arcranks_quest_board_header%" -> "Квесты"
                    SidebarQuestRewards.SUMMARY_PLACEHOLDER -> "Итог: 980/980 💰 · 2/2 жет."
                    else -> if (placeholder.startsWith("%arcranks_quest_board_")) "" else placeholder
                }
            }
        }
        val header = resolved.indexOfFirst { "%arcranks_quest_board_header%" in it }
        val summary = resolved.indexOfFirst { SidebarQuestRewards.SUMMARY_PLACEHOLDER in it }
        val footer = resolved.indexOfFirst { "Пинг:" in it }
        assertTrue(header >= 0)
        assertEquals(header + 1, summary)
        assertTrue(footer > summary)
    }
}
