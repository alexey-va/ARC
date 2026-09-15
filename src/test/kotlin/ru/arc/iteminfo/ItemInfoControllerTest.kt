package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.UUID

class ItemInfoControllerTest : StringSpec({
    "active target renders only through the selected surface" {
        val player = player()
        val target = ItemInfoTarget(Component.text("Дубовый стол"), "itemsadder:oak_table")
        val renderer = RecordingRenderer()
        val controller = ItemInfoController(
            preferences = { ItemInfoPreferences(mode = ItemInfoMode.BOSSBAR) },
            target = { target },
            renderer = renderer,
        )

        controller.update(player)
        controller.follow(player)

        renderer.actions.shouldContainExactly(
            "render:${player.uniqueId}:bossbar:itemsadder:oak_table",
            "follow:${player.uniqueId}",
        )
    }

    "disabled mode and lost target clear every stale surface" {
        val player = player()
        val renderer = RecordingRenderer()
        var preferences = ItemInfoPreferences(mode = ItemInfoMode.HOLOGRAM)
        var target: ItemInfoTarget? = ItemInfoTarget(Component.text("Механизм"), "slimefun:machine")
        val controller = ItemInfoController({ preferences }, { target }, renderer)

        controller.update(player)
        target = null
        controller.update(player)
        preferences = ItemInfoPreferences(mode = ItemInfoMode.OFF)
        controller.update(player)

        renderer.actions.shouldContainExactly(
            "render:${player.uniqueId}:hologram:slimefun:machine",
            "clear:${player.uniqueId}",
            "clear:${player.uniqueId}",
        )
    }

    "reset and shutdown remove viewer state" {
        val first = player()
        val second = player()
        val renderer = RecordingRenderer()
        val controller = ItemInfoController(
            preferences = { ItemInfoPreferences(mode = ItemInfoMode.HOLOGRAM) },
            target = { ItemInfoTarget(Component.text("Механизм"), "slimefun:machine") },
            renderer = renderer,
        )

        controller.update(first)
        controller.update(second)
        controller.reset(first)
        controller.close()

        renderer.actions.takeLast(2).shouldContainExactly("clear:${first.uniqueId}", "close")
    }
}) {
    companion object {
        private fun player(): Player = mockk<Player> {
            every { uniqueId } returns UUID.randomUUID()
        }
    }

    private class RecordingRenderer : ItemInfoRenderer {
        val actions = mutableListOf<String>()

        override fun render(player: Player, preferences: ItemInfoPreferences, target: ItemInfoTarget) {
            actions += "render:${player.uniqueId}:${preferences.mode.id}:${target.namespacedId}"
        }

        override fun clear(player: Player) {
            actions += "clear:${player.uniqueId}"
        }

        override fun follow(player: Player) {
            actions += "follow:${player.uniqueId}"
        }

        override fun close() {
            actions += "close"
        }
    }
}
