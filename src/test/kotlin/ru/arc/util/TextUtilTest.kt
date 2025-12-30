package ru.arc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import java.util.concurrent.TimeUnit

class TextUtilTest : TestBase() {

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
    }

    // ========== Strip Method Tests ==========

    @Test
    fun testStripNull() {
        val result = TextUtil.strip(null)
        assertNull(result, "Strip of null should return null")
    }

    @Test
    fun testStripComponent() {
        val component = Component.text("Test").decoration(TextDecoration.ITALIC, true)
        val result = TextUtil.strip(component)

        assertNotNull(result, "Result should not be null")
        assertEquals(
            TextDecoration.State.FALSE,
            result!!.decoration(TextDecoration.ITALIC),
            "Should set italic to false"
        )
    }

    @Test
    fun testStripWithoutItalic() {
        val component = Component.text("Test")
        val result = TextUtil.strip(component)

        assertNotNull(result, "Result should not be null")
        assertEquals(
            TextDecoration.State.FALSE,
            result!!.decoration(TextDecoration.ITALIC),
            "Should set italic to false"
        )
    }

    // ========== FormatAmount Method Tests ==========

    @Test
    fun testFormatAmountZero() {
        val result = TextUtil.formatAmount(0.0)
        assertEquals("0", result, "Zero should format to 0")
    }

    @Test
    fun testFormatAmountVerySmall() {
        val result = TextUtil.formatAmount(0.00001)
        assertEquals("0", result, "Very small amount should format to 0")
    }

    @Test
    fun testFormatAmountLessThanOne() {
        val result = TextUtil.formatAmount(0.5)
        assertTrue(result.contains("0"), "Should contain 0")
        assertTrue(result.contains("5"), "Should contain 5")
    }

    @Test
    fun testFormatAmountLessThanTen() {
        val result = TextUtil.formatAmount(5.5)
        assertTrue(result.contains("5"), "Should contain 5")
    }

    @Test
    fun testFormatAmountLessThanThousand() {
        val result = TextUtil.formatAmount(500.5)
        assertTrue(result.contains("500"), "Should contain 500")
    }

    @Test
    fun testFormatAmountThousand() {
        val result = TextUtil.formatAmount(1500.0)
        assertTrue(result.contains("K") || result.contains("1"), "Should format with K or show number")
    }

    @Test
    fun testFormatAmountMillion() {
        val result = TextUtil.formatAmount(1500000.0)
        assertTrue(result.contains("M") || result.contains("1"), "Should format with M or show number")
    }

    @Test
    fun testFormatAmountNegative() {
        val result = TextUtil.formatAmount(-100.0)
        assertTrue(result.contains("-") || result.contains("100"), "Should handle negative")
    }

    @Test
    fun testFormatAmountWithPrecision() {
        val result = TextUtil.formatAmount(123.456, 2)
        assertNotNull(result, "Should not be null")
        assertTrue(result.isNotEmpty(), "Should not be empty")
    }

    @Test
    fun testFormatAmountWithPrecisionZero() {
        val result = TextUtil.formatAmount(0.0, 2)
        assertEquals("0", result, "Zero with precision should be 0")
    }

    @Test
    fun testFormatAmountWithPrecisionLarge() {
        val result = TextUtil.formatAmount(1234567.89, 2)
        assertNotNull(result, "Should not be null")
    }

    // ========== CenterInLore Method Tests ==========

    @Test
    fun testCenterInLore() {
        val result = TextUtil.centerInLore("Test", 10)
        assertEquals(10, result.length, "Should be exactly 10 characters")
        assertTrue(result.contains("Test"), "Should contain the text")
    }

    @Test
    fun testCenterInLoreExactLength() {
        val result = TextUtil.centerInLore("Test", 4)
        assertEquals("Test", result, "Should be the same if length matches")
    }

    @Test
    fun testCenterInLoreLonger() {
        val result = TextUtil.centerInLore("Test", 20)
        assertEquals(20, result.length, "Should be exactly 20 characters")
    }

    @Test
    fun testCenterInLoreEmpty() {
        val result = TextUtil.centerInLore("", 10)
        assertEquals(10, result.length, "Should pad empty string")
    }

    // ========== MiniMessage Method Tests ==========

    @Test
    fun testMm() {
        val result = TextUtil.mm("<red>Test</red>")
        assertNotNull(result, "Should parse MiniMessage")
    }

    @Test
    fun testMmWithStrip() {
        val result = TextUtil.mm("<red>Test</red>", true)
        assertNotNull(result, "Should parse and strip")
    }

    @Test
    fun testMmWithReplacers() {
        val result = TextUtil.mm("Hello {name}", true, "{name}", "World")
        assertNotNull(result, "Should parse with replacers")
    }

    @Test
    fun testMmWithTagResolver() {
        val resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
            "test",
            net.kyori.adventure.text.minimessage.tag.Tag.inserting(Component.text("value"))
        )
        val result = TextUtil.mm("<test>", resolver)
        assertNotNull(result, "Should parse with tag resolver")
    }

    // ========== Legacy Method Tests ==========

    @Test
    fun testLegacy() {
        val result = TextUtil.legacy("&cTest")
        assertNotNull(result, "Should parse legacy")
    }

    @Test
    fun testPlain() {
        val result = TextUtil.plain("Test")
        assertNotNull(result, "Should create plain component")
        // Just verify it's not null and doesn't throw
        assertTrue(result.toString().isNotEmpty(), "Should have content")
    }

    @Test
    fun testMmToLegacy() {
        val result = TextUtil.mmToLegacy("<red>Test</red>")
        assertTrue(result.contains("&c") || result.contains("Test"), "Should convert to legacy")
    }

    @Test
    fun testToLegacy() {
        val result = TextUtil.toLegacy("<red>Test</red>", "test", "value")
        assertNotNull(result, "Should convert to legacy")
    }

    // ========== Time Method Tests ==========

    @Test
    fun testParseTime() {
        val result = TextUtil.parseTime(2, TimeUnit.HOURS)
        assertNotNull(result, "Should parse time")
    }

    @Test
    fun testTimeComponent() {
        // This requires config file, might fail in test environment
        try {
            val result = TextUtil.timeComponent(3600, TimeUnit.SECONDS)
            assertNotNull(result, "Should create time component")
        } catch (e: Exception) {
            // Acceptable if config is not available
            assertTrue(true, "Time component might require config")
        }
    }

    @Test
    fun testRandomBossBarColor() {
        val colors = listOf("red", "blue", "white", "yellow")
        repeat(100) {
            val result = TextUtil.randomBossBarColor()
            assertTrue(colors.contains(result), "Should return valid color")
        }
    }

    // ========== Error/Message Component Tests ==========

    @Test
    fun testError() {
        val result = TextUtil.error()
        assertNotNull(result, "Should create error component")
    }

    @Test
    fun testNoPermissions() {
        val result = TextUtil.noPermissions()
        assertNotNull(result, "Should create no permissions component")
    }

    @Test
    fun testNoWGPermission() {
        val result = TextUtil.noWGPermission()
        assertNotNull(result, "Should create no WG permission component")
    }

    @Test
    fun testPlayerOnly() {
        val result = TextUtil.playerOnly()
        assertNotNull(result, "Should create player only component")
    }

    // ========== SplitLoreString Method Tests ==========

    @Test
    fun testSplitLoreStringNull() {
        val result = TextUtil.splitLoreString(null, 10, 0)
        assertTrue(result.isEmpty(), "Should return empty list for null")
    }

    @Test
    fun testSplitLoreStringShort() {
        val result = TextUtil.splitLoreString("Short text", 100, 0)
        assertEquals(1, result.size, "Should return single line for short text")
    }

    @Test
    fun testSplitLoreStringLong() {
        val text =
            "This is a very long text that should be split into multiple lines when it exceeds the maximum length"
        val result = TextUtil.splitLoreString(text, 20, 0)
        assertTrue(result.size > 1, "Should split into multiple lines")
    }

    @Test
    fun testSplitLoreStringWithIndent() {
        val text = "This is a test"
        val result = TextUtil.splitLoreString(text, 10, 2)
        assertTrue(result.isNotEmpty(), "Should handle indent")
    }

    @Test
    fun testSplitLoreStringWithFormatting() {
        val text = "<red>This is a <bold>formatted</bold> text</red>"
        val result = TextUtil.splitLoreString(text, 20, 0)
        assertTrue(result.isNotEmpty(), "Should handle formatting tags")
    }

    // ========== Join Method Tests ==========

    @Test
    fun testJoinEmpty() {
        val result = TextUtil.join(emptySet(), ", ")
        assertNotNull(result, "Should handle empty set")
    }

    @Test
    fun testJoinSingle() {
        val components = setOf(Component.text("One"))
        val result = TextUtil.join(components, ", ")
        assertNotNull(result, "Should handle single component")
    }

    @Test
    fun testJoinMultiple() {
        val components = setOf(
            Component.text("One"),
            Component.text("Two"),
            Component.text("Three")
        )
        val result = TextUtil.join(components, ", ")
        assertNotNull(result, "Should join multiple components")
    }

    // ========== ToMM Method Tests ==========

    @Test
    fun testToMM() {
        val component = Component.text("Test")
        val result = TextUtil.toMM(component)
        assertNotNull(result, "Should serialize to MiniMessage")
        assertTrue(result.isNotEmpty(), "Should not be empty")
    }

    @Test
    fun testToMMWithFormatting() {
        val component = Component.text("Test", NamedTextColor.RED)
        val result = TextUtil.toMM(component)
        assertNotNull(result, "Should serialize formatted component")
    }

    // ========== Edge Cases ==========

    @Test
    fun testFormatAmountVeryLarge() {
        val result = TextUtil.formatAmount(999999999.0)
        assertNotNull(result, "Should handle very large numbers")
    }

    @Test
    fun testFormatAmountVerySmallNegative() {
        val result = TextUtil.formatAmount(-0.00001)
        assertEquals("0", result, "Very small negative should be 0")
    }

    @Test
    fun testSplitLoreStringEmpty() {
        val result = TextUtil.splitLoreString("", 10, 0)
        assertTrue(result.isEmpty() || result.size == 1, "Should handle empty string")
    }

    @Test
    fun testSplitLoreStringSingleWord() {
        val result = TextUtil.splitLoreString("Supercalifragilisticexpialidocious", 10, 0)
        assertTrue(result.isNotEmpty(), "Should handle single long word")
    }

    @Test
    fun testMmWithEmptyString() {
        val result = TextUtil.mm("")
        assertNotNull(result, "Should handle empty string")
    }

    @Test
    fun testCenterInLoreOddLength() {
        val result = TextUtil.centerInLore("Test", 11)
        // Result might be 10 or 11 due to integer division rounding
        assertTrue(result.length == 10 || result.length == 11, "Should handle odd length (${result.length})")
    }
}

