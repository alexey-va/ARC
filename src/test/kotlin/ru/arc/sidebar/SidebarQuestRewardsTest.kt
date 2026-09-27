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
    fun `daily totals follow the last quest row before the online ping footer`() {
        val lines = config().getStringList("styles.style01.lines")
        val summary = lines.indexOfFirst { SidebarQuestRewards.SUMMARY_PLACEHOLDER in it }
        val lastQuest = lines.indexOfFirst { "%arcranks_quest_board_3%" in it }
        val footer = lines.indexOfFirst { "Пинг:" in it }
        assertEquals(lastQuest + 1, summary)
        assertTrue(summary < footer)
        assertTrue(lines.last().contains("Пинг:"))
    }

    @Test
    fun `all styles have one totals row and remain within fifteen rows`() {
        val styles = requireNotNull(config().getConfigurationSection("styles"))
        styles.getKeys(false).forEach { style ->
            val lines = styles.getStringList("$style.lines")
            assertTrue(lines.size <= 15, style)
            assertEquals(1, lines.count { SidebarQuestRewards.SUMMARY_PLACEHOLDER in it }, style)
            val lastQuest = lines.indexOfLast { Regex("%arcranks_quest_(board_[1-3]|compact)%").containsMatchIn(it) }
            assertTrue(SidebarQuestRewards.SUMMARY_PLACEHOLDER in lines[lastQuest + 1], style)
            assertFalse(lines.any { "%arcranks_quest_board_reward_" in it || "%arcranks_quest_reward%" in it }, style)
        }
    }

    @Test
    fun `preference hides only totals and preserves quest rows and footer`() {
        val lines = config().getStringList("styles.style01.lines")
        val visible = lines.mapNotNull { SidebarQuestRewards.visibleLine(it, false) }
        assertEquals(lines.filterNot { SidebarQuestRewards.SUMMARY_PLACEHOLDER in it }, visible)
        assertEquals(lines, lines.mapNotNull { SidebarQuestRewards.visibleLine(it, true) })
        assertTrue(SidebarQuestRewards.enabled(null))
        assertFalse(SidebarQuestRewards.enabled("false"))
    }

    @Test
    fun `totals remain under the quest header when all goal rows are empty`() {
        val resolved = config().getStringList("styles.style01.lines").mapNotNull { line ->
            resolveOptionalSidebarLine(line) { placeholder ->
                when (placeholder) {
                    "%arcranks_quest_board_header%" -> "Квесты"
                    SidebarQuestRewards.SUMMARY_PLACEHOLDER -> "Итог: 980/980 💰 · 2/2 жет."
                    else -> if (placeholder.startsWith("%arcranks_quest_board_")) "" else placeholder
                }
            }
        }
        val summary = resolved.indexOfFirst { SidebarQuestRewards.SUMMARY_PLACEHOLDER in it }
        assertTrue(summary > 0)
        assertTrue("%arcranks_quest_board_header%" in resolved[summary - 1])
        assertTrue(resolved.last().contains("Пинг:"))
    }
}
