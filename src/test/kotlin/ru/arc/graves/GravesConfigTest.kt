package ru.arc.graves

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.arc.config.ConfigManager

class GravesConfigTest {
    @Test
    fun `defaults expose fee tracking and presentation policy`() {
        val settings = GravesConfig(ConfigManager.empty()).snapshot()

        assertEquals(100.0, settings.defaultFee)
        assertEquals("arc.graves.teleport.free", settings.freePermission)
        assertEquals(32, settings.nearbyGlowRadius)
        assertEquals(2, settings.hudRefreshTicks)
        assertEquals(true, settings.trackingEnabledByDefault)
        assertEquals("Бесплатно по праву", PlainTextComponentSerializer.plainText().serialize(settings.text("free-price")))
    }
}
