package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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

    "bossbar and hologram expose the same localized name and exact id" {
        val target = ItemInfoTarget(Component.text("Дубовый стол"), "itemsadder:oak_table")
        val settings = ItemInfoSettings(
            enabled = true,
            targetDistance = 5.0,
            hologramTemplate = "<white><name><newline><gray><id>",
            bossbarTemplate = "<white><name> <dark_gray>· <gray><id>",
        )
        val plain = PlainTextComponentSerializer.plainText()
        plain.serialize(settings.hologramText(target)) shouldBe "Дубовый стол\nitemsadder:oak_table"
        plain.serialize(settings.bossbarText(target)) shouldBe "Дубовый стол · itemsadder:oak_table"
    }
})
