package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.entity.Entity
import org.bukkit.Location
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

    "registered lootbox location suppresses every fallback target" {
        val block = mockk<Block>()
        val resolver = ItemInfoBlockResolver(
            itemsAdder = { ItemInfoTarget(Component.text("Кейс"), "itemsadder:daily_case") },
            slimefun = { ItemInfoTarget(Component.text("Механизм"), "slimefun:machine") },
            excluded = { it === block },
        )

        resolver.resolve(block) shouldBe null
    }

    "registered lootbox furniture entity is suppressed while ordinary furniture remains" {
        val crate = mockk<Location>()
        val ordinary = mockk<Location>()
        val target = ItemInfoTarget(Component.text("Кейс"), "itemsadder:daily_case")
        val policy = ItemInfoTargetPolicy { it === crate }

        policy.filter(crate, target) shouldBe null
        policy.filter(ordinary, target) shouldBe target
        policy.filter(null, target) shouldBe target
    }

    "entity target resolution uses the exact entity returned by the ray trace" {
        val returnedHit = mockk<Entity>()
        val expected = ItemInfoTarget(Component.text("Стол"), "furniture:table")
        val decoy = ItemInfoTarget(Component.text("Кресло"), "furniture:chair")
        val resolvedEntities = mutableListOf<Entity>()

        val selection = ItemInfoHitTargetSelection.fromRayHit(
            hitEntity = returnedHit,
            distanceSquared = 1.0,
            blockDistanceSquared = 4.0,
        ) { entity ->
            resolvedEntities += entity
            if (entity === returnedHit) expected else decoy
        }

        selection?.target shouldBe expected
        resolvedEntities shouldBe listOf(returnedHit)
    }

    "nearest resolved ray target wins while targets behind a block are discarded" {
        val block = ItemInfoLocatedTarget(ItemInfoTarget(Component.text("Блок"), "block:known"), 1.0)
        val nearEntity = ItemInfoLocatedTarget(ItemInfoTarget(Component.text("Стол"), "furniture:table"), 1.25)
        val farEntity = ItemInfoHitTargetSelection.fromRayHit(
            hitEntity = mockk(),
            distanceSquared = 2.0,
            blockDistanceSquared = 1.0,
        ) { ItemInfoTarget(Component.text("Позади"), "furniture:behind") }

        ItemInfoHitTargetSelection.closest(block, nearEntity, farEntity)?.namespacedId shouldBe "block:known"
        ItemInfoHitTargetSelection.closest(nearEntity, null)?.namespacedId shouldBe "furniture:table"
    }
})
