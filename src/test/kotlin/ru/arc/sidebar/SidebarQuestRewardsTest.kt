package ru.arc.sidebar

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SidebarQuestRewardsTest {
    @Test
    fun `rewards share the frame right edge with Cyrillic bold and supplementary glyphs`() {
        val rows = listOf(
            SidebarQuestRewardRow(Component.text("★ Играть 41/60"), Component.text("500 💰 +2 жет.")),
            SidebarQuestRewardRow(Component.text("Собрать пшеницу 9/64").decorate(TextDecoration.BOLD), Component.text("125 💰")),
            SidebarQuestRewardRow(Component.text("Третья строка")),
        )
        val title = Component.text("RusCrafting").decorate(TextDecoration.BOLD)
        val result = alignSidebarQuestRewards(title, rows)
        val expected = maxOf(sidebarTextWidth(title)!!, rows.maxOf {
            sidebarTextWidth(it.content)!! + (it.reward?.let { reward -> 8 + sidebarTextWidth(reward)!! } ?: 0)
        })
        assertEquals(expected, sidebarTextWidth(result[0]))
        assertEquals(expected, sidebarTextWidth(result[1]))
        assertEquals(rows[2].content, result[2])
        assertEquals(10, sidebarTextWidth(Component.text("💰")))
        assertTrue(PlainTextComponentSerializer.plainText().serialize(result[0]).endsWith("500 💰 +2 жет."))
    }

    @Test
    fun `padding uses rendered width instead of character count and follows changing progress`() {
        val reward = Component.text("75 💰")
        fun aligned(value: String) = alignSidebarQuestRewards(Component.text("Широкий заголовок панели"), listOf(
            SidebarQuestRewardRow(Component.text(value), reward),
        )).single()
        assertNotEquals(sidebarTextWidth(Component.text("iii")), sidebarTextWidth(Component.text("WWW")))
        assertEquals(sidebarTextWidth(aligned("iii")), sidebarTextWidth(aligned("WWW")))
        assertEquals(sidebarTextWidth(aligned("9/60")), sidebarTextWidth(aligned("41/60")))
        assertEquals(0, sidebarTextWidth(Component.empty()))
    }

    @Test
    fun `hidden rewards retain original rows and missing glyphs retain visible rewards`() {
        val row = Component.text("Квест 1/3")
        assertEquals(listOf(row), alignSidebarQuestRewards(Component.text("Title"), listOf(SidebarQuestRewardRow(row))))
        val unsupported = Component.translatable("custom.key")
        val result = alignSidebarQuestRewards(Component.empty(), listOf(SidebarQuestRewardRow(unsupported, Component.text("50 💰"))))
        assertEquals(1, result.size)
        assertTrue(result.single().children().contains(Component.text("50 💰")))
    }

    @Test
    fun `all board slots and compact styles map to matching reward placeholders`() {
        (1..3).forEach { slot ->
            assertEquals("%arcranks_quest_board_reward_${slot}%", SidebarQuestRewards.placeholder("&6| %arcranks_quest_board_${slot}%"))
        }
        assertEquals("%arcranks_quest_reward%", SidebarQuestRewards.placeholder("%arcranks_quest_compact%"))
        assertNull(SidebarQuestRewards.placeholder("%arcranks_quest_board_header%"))
        assertTrue(SidebarQuestRewards.enabled(null))
        assertFalse(SidebarQuestRewards.enabled("false"))
    }
}
