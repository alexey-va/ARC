package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.api.InspectionViewMode
import ru.arc.paper.api.InspectionViewPreferences

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

    "shared inspection preferences preserve every mode and personal layout option" {
        ItemInfoMode.entries.forEach { mode ->
            ItemInfoPreferences(
                mode = mode,
                hologramScale = 1.25f,
                verticalOffset = -0.25,
                horizontalOffset = 0.75,
            ).toInspectionViewPreferences() shouldBe InspectionViewPreferences(
                mode = when (mode) {
                    ItemInfoMode.HOLOGRAM -> InspectionViewMode.HOLOGRAM
                    ItemInfoMode.BOSSBAR -> InspectionViewMode.BOSSBAR
                    ItemInfoMode.OFF -> InspectionViewMode.OFF
                },
                scale = 1.25f,
                verticalOffset = -0.25,
                horizontalOffset = 0.75,
            )
        }
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
