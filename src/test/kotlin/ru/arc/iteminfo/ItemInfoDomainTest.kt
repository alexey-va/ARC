package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.World
import ru.arc.onboarding.claimGuideLabelLocation

class ItemInfoDomainTest : StringSpec({
    "hologram is the default while every explicit mode roundtrips" {
        ItemInfoMode.fromStored(null) shouldBe ItemInfoMode.HOLOGRAM
        ItemInfoMode.fromStored("") shouldBe ItemInfoMode.HOLOGRAM
        ItemInfoMode.fromStored("unknown") shouldBe ItemInfoMode.HOLOGRAM
        ItemInfoMode.entries.forEach { mode ->
            ItemInfoMode.fromStored(mode.id) shouldBe mode
            ItemInfoMode.fromStored(mode.id.uppercase()) shouldBe mode
        }
    }

    "hologram uses the exact Lands onboarding default anchor" {
        val world = mockk<World>()
        listOf(
            Location(world, 10.0, 70.0, 20.0, 0f, 0f),
            Location(world, -4.5, 63.2, 9.5, 90f, -35f),
            Location(world, 2.0, 100.0, -8.0, -170f, 55f),
        ).forEach { eye ->
            itemInfoHologramLocation(eye) shouldBe claimGuideLabelLocation(eye)
        }
    }

    "personal presentation defaults to GrocerMC's unobtrusive hologram layout" {
        val preferences = ItemInfoPreferences.fromStored { null }

        preferences shouldBe ItemInfoPreferences.DEFAULT
        preferences.showNamespacedId shouldBe false
        preferences.hologramScale shouldBe 0.90f
        preferences.verticalOffset shouldBe 0.50
        preferences.horizontalOffset shouldBe 0.0
    }

    "personal hologram layout roundtrips and rejects malformed metadata" {
        val selected = ItemInfoPreferences(
            mode = ItemInfoMode.HOLOGRAM,
            showNamespacedId = true,
            hologramScale = 0.85f,
            verticalOffset = 0.35,
            horizontalOffset = -0.60,
        )
        val stored = mapOf(
            ItemInfoPreferences.SHOW_ID_META_KEY to "true",
            ItemInfoPreferences.LAYOUT_META_KEY to selected.storedLayout(),
        )

        ItemInfoPreferences.fromStored(stored::get) shouldBe selected
        ItemInfoPreferences.fromStored { "broken" } shouldBe ItemInfoPreferences.DEFAULT
    }

    "positive personal offsets move the Lands anchor up and to screen right" {
        val world = mockk<World>()
        val eye = Location(world, 10.0, 70.0, 20.0, 0f, 0f)
        val base = claimGuideLabelLocation(eye)
        val adjusted = itemInfoHologramLocation(eye, verticalOffset = 0.40, horizontalOffset = 0.75)

        adjusted.y shouldBe (base.y + 0.40 plusOrMinus 1.0e-9)
        adjusted.x shouldBe (base.x - 0.75 plusOrMinus 1.0e-9)
        adjusted.z shouldBe (base.z plusOrMinus 1.0e-9)
    }

    "presentation hides the technical id by default and can explicitly reveal it" {
        val target = ItemInfoTarget(Component.text("Дубовый стол"), "itemsadder:oak_table")
        val settings = ItemInfoSettings(
            enabled = true,
            targetDistance = 5.0,
            nameOnlyTemplate = "<white><name>",
            hologramTemplate = "<white><name><newline><gray><id>",
            bossbarTemplate = "<white><name> <dark_gray>· <gray><id>",
        )
        val plain = PlainTextComponentSerializer.plainText()
        plain.serialize(settings.hologramText(target, showNamespacedId = false)) shouldBe "Дубовый стол"
        plain.serialize(settings.bossbarText(target, showNamespacedId = false)) shouldBe "Дубовый стол"
        plain.serialize(settings.hologramText(target, showNamespacedId = true)) shouldBe "Дубовый стол\nitemsadder:oak_table"
        plain.serialize(settings.bossbarText(target, showNamespacedId = true)) shouldBe "Дубовый стол · itemsadder:oak_table"
    }
})
