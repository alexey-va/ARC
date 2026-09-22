package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.api.InspectionViewMode
import ru.arc.paper.api.InspectionViewPreferences
import ru.arc.paper.inspection.PaperArcInspectionService
import java.util.UUID

class ItemInfoControllerTest : StringSpec({
    "controller forwards layout to the shared inspector and follows or clears that same viewer" {
        val player = player()
        val selected = ItemInfoPreferences(
            mode = ItemInfoMode.BOSSBAR,
            showNamespacedId = true,
            hologramScale = 1.25f,
            verticalOffset = -0.25,
            horizontalOffset = 0.75,
        )
        val inspection = mockk<PaperArcInspectionService>(relaxed = true)
        val controller = ItemInfoController({ selected }, inspection)

        controller.update(player)
        controller.follow(player)
        controller.reset(player)

        verifySequence {
            inspection.update(
                player,
                InspectionViewPreferences(InspectionViewMode.BOSSBAR, 1.25f, -0.25, 0.75),
            )
            inspection.follow(player)
            inspection.clear(player)
        }
    }

    "item info source preserves both formatted modes and the existing technical-id preference" {
        val player = player()
        val target = ItemInfoTarget(Component.text("Дубовый стол"), "itemsadder:oak_table")
        val settings = ItemInfoSettings(
            enabled = true,
            targetDistance = 5.0,
            nameOnlyTemplate = "<white><name>",
            hologramTemplate = "<white><name><newline><gray><id>",
            bossbarTemplate = "<white><name> <dark_gray>· <gray><id>",
        )
        var selected = ItemInfoPreferences.DEFAULT
        val provider = ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = { target },
            preferences = { selected },
        )
        val plain = PlainTextComponentSerializer.plainText()

        val nameOnly = provider.resolve(player) ?: error("Expected the block source to resolve")
        plain.serialize(nameOnly.hologram) shouldBe "Дубовый стол"
        plain.serialize(nameOnly.bossbar) shouldBe "Дубовый стол"

        selected = selected.copy(showNamespacedId = true)
        val withId = provider.resolve(player) ?: error("Expected the block source to resolve")
        plain.serialize(withId.hologram) shouldBe "Дубовый стол\nitemsadder:oak_table"
        plain.serialize(withId.bossbar) shouldBe "Дубовый стол · itemsadder:oak_table"
    }

    "a block source miss or disabled source falls through to lower sources" {
        val player = player()
        val settings = ItemInfoSettings(true, 5.0, "<name>", "<name>", "<name>")
        var selectedTarget: ItemInfoTarget? = null
        fun provider(enabled: Boolean = true) = ItemInfoInspectionProvider(
            settings = settings.copy(enabled = enabled),
            resolveTarget = { selectedTarget },
            preferences = { ItemInfoPreferences.DEFAULT },
        )

        provider().resolve(player) shouldBe null
        provider(enabled = false).resolve(player) shouldBe null
    }

    "dead, spectator, and onboarding suppression clears the shared view before arbitration" {
        val player = player()
        var suppressed = true
        val inspection = mockk<PaperArcInspectionService>(relaxed = true)
        val controller = ItemInfoController(
            preferences = { ItemInfoPreferences.DEFAULT },
            inspection = inspection,
            suppressed = { suppressed },
        )

        controller.update(player)
        verify(exactly = 1) { inspection.clear(player) }
        verify(exactly = 0) { inspection.update(any(), any()) }

        suppressed = false
        controller.update(player)
        verify(exactly = 1) { inspection.update(player, ItemInfoPreferences.DEFAULT.toInspectionViewPreferences()) }
    }
}) {
    companion object {
        private fun player(): Player = mockk {
            every { uniqueId } returns UUID.randomUUID()
        }
    }
}
