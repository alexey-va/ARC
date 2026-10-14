package ru.arc.integrations.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import ru.arc.PortalVisualStyle
import ru.arc.origin.api.ArcOriginPortalVisuals

class OriginPortalSettingsTest : StringSpec({
    "permanent portals preserve style models, opening offset and bounded particle load" {
        val settings = originPortalSettings(request()).shouldNotBeNull()
        settings.defaultStyle shouldBe PortalVisualStyle.ASTRAL
        settings.itemIds[PortalVisualStyle.ORIGIN] shouldBe "arc:origin_gate"
        settings.itemIds[PortalVisualStyle.ASTRAL] shouldBe "arc:astral_gate"
        settings.itemIds[PortalVisualStyle.VOID] shouldBe "arc:void_gate"
        settings.entryTick shouldBe 12
        settings.openingDurationTicks shouldBe 1
        settings.openingSoundEnabled shouldBe false
        settings.height shouldBe 18.0f
        settings.suctionStreams shouldBe 8
        settings.suctionPointsPerStream shouldBe 4
    }

    "invalid style and dimensions fail before any entity can be spawned" {
        originPortalSettings(request(style = "missing")) shouldBe null
        originPortalSettings(request(height = Double.NaN)) shouldBe null
        originPortalSettings(request(height = 21.0)) shouldBe null
        originPortalSettings(request(models = mapOf("origin" to "arc:origin_gate"))) shouldBe null
    }
})

private fun request(
    style: String = "astral",
    height: Double = 18.0,
    models: Map<String, String> = mapOf("origin" to "arc:origin_gate", "astral" to "arc:astral_gate", "void" to "arc:void_gate"),
) = ArcOriginPortalVisuals.Settings(
    style, models, 4.0, height, 1.0, true, 10, 4, 6, 2, 2.0, 2.0, 1.0, 1.0, 1,
)
