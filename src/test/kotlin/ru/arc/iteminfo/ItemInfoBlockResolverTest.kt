package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.block.Block

class ItemInfoBlockResolverTest : StringSpec({
    "vanilla blocks are never exposed" {
        val block = mockk<Block>()
        val resolver = ItemInfoBlockResolver(
            itemsAdder = { null },
            slimefun = { null },
        )

        resolver.resolve(block) shouldBe null
    }

    "ItemsAdder block wins when both integrations recognize the block" {
        val block = mockk<Block>()
        val itemsAdder = ItemInfoTarget(Component.text("Стул"), "itemsadder:chair")
        val slimefun = ItemInfoTarget(Component.text("Механизм"), "slimefun:machine")
        val resolver = ItemInfoBlockResolver(
            itemsAdder = { itemsAdder },
            slimefun = { slimefun },
        )

        resolver.resolve(block) shouldBe itemsAdder
    }

    "Slimefun block is used when ItemsAdder does not recognize it" {
        val block = mockk<Block>()
        val slimefun = ItemInfoTarget(Component.text("Механизм"), "slimefun:machine")
        val resolver = ItemInfoBlockResolver(
            itemsAdder = { null },
            slimefun = { slimefun },
        )

        resolver.resolve(block) shouldBe slimefun
    }
})
