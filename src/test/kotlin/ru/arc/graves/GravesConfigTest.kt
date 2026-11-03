package ru.arc.graves

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.config.ConfigManager
import java.nio.file.Path

class GravesConfigTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `defaults expose fee tracking and presentation policy`() {
        val settings = GravesConfig(ConfigManager.of(root, "graves-test.yml")).snapshot()

        assertEquals(100.0, settings.defaultFee)
        assertEquals("arc.graves.teleport.free", settings.freePermission)
        assertEquals(32, settings.nearbyGlowRadius)
        assertEquals(2, settings.hudRefreshTicks)
        assertEquals(true, settings.trackingEnabledByDefault)
        assertEquals(false, settings.creationMessageEnabled)
        assertEquals("Бесплатно по праву", PlainTextComponentSerializer.plainText().serialize(settings.text("free-price")))
        assertEquals(GraveAppearanceSettings.DEFAULT_WEIGHTS, settings.appearance.weights)
        assertEquals(GraveAppearanceSettings.DEFAULT_EPITAPHS, settings.appearance.epitaphs)
        assertEquals(0.5, settings.appearance.engravedChance)
    }

    @Test
    fun `engraved chance accepts endpoints and falls back for invalid input`() {
        val source = ConfigManager.of(root, "graves-test.yml")
        for (chance in listOf(0.0, 0.25, 1.0)) {
            source.setDouble("appearance.engraved-chance", chance)
            assertEquals(chance, GravesConfig(source).snapshot().appearance.engravedChance)
        }
        for (chance in listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            source.setDouble("appearance.engraved-chance", chance)
            assertEquals(0.5, GravesConfig(source).snapshot().appearance.engravedChance)
        }
    }

    @Test
    fun `creation notice requires explicit opt in`() {
        val source = ConfigManager.of(root, "graves-test.yml")
        assertEquals(false, GravesConfig(source).snapshot().creationMessageEnabled)
        source.setBoolean("notifications.creation-message", true)
        assertEquals(true, GravesConfig(source).snapshot().creationMessageEnabled)
        source.setBoolean("notifications.creation-message", false)
        assertEquals(false, GravesConfig(source).snapshot().creationMessageEnabled)
    }

    @Test
    fun `cosmetic weights preserve zero and fall back individually for nonfinite and negative values`() {
        val source = ConfigManager.of(root, "graves-test.yml")
        source.setDouble("appearance.weights.slate", 0.0)
        source.setDouble("appearance.weights.obelisk", Double.NaN)
        source.setDouble("appearance.weights.cairn", Double.POSITIVE_INFINITY)
        source.setDouble("appearance.weights.timber", -1.0)
        source.setDouble("appearance.weights.broken", 2.5)

        val settings = GravesConfig(source).snapshot()

        assertEquals(mapOf("slate" to 0.0, "obelisk" to 15.0, "cairn" to 20.0, "timber" to 20.0, "broken" to 2.5), settings.appearance.weights)
        assertEquals(18, settings.maxGraves)
    }

    @Test
    fun `epitaphs are bounded plain lines and wholly invalid input retains defaults`() {
        val source = ConfigManager.of(root, "graves-test.yml")
        source.setStringList("appearance.epitaphs", listOf("  Я допрыгну.  ", "", "<click:run_command:/op>нет</click>", "x".repeat(97), "две\nстроки", "§aцвет"))
        assertEquals(listOf("Я допрыгну."), GravesConfig(source).snapshot().appearance.epitaphs)

        source.setStringList("appearance.epitaphs", listOf("", "x".repeat(97)))
        assertEquals(GraveAppearanceSettings.DEFAULT_EPITAPHS, GravesConfig(source).snapshot().appearance.epitaphs)

        source.setStringList("appearance.epitaphs", emptyList())
        assertEquals(emptyList<String>(), GravesConfig(source).snapshot().appearance.epitaphs)

        source.setStringList("appearance.epitaphs", (1..200).map { "Фраза $it" })
        val bounded = GravesConfig(source).snapshot().appearance.epitaphs
        assertEquals(128, bounded.size)
        assertTrue(bounded.all { it.length <= 96 })
    }
}
