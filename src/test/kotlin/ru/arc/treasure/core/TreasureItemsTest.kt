package ru.arc.treasure.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase

class TreasureItemsTest :
    KotestTestBase({
        describe("TreasureItems") {
            it("stores Slimefun items by canonical registry ID") {
                val stack = ItemStack(Material.IRON_INGOT, 7)

                val treasure = TreasureItems.fromStack(stack, amount = 5, weight = 9) { "NTW_OPTIC_CABLE" }

                treasure.shouldBeInstanceOf<Treasure.Slimefun>()
                treasure.itemId shouldBe "NTW_OPTIC_CABLE"
                treasure.min shouldBe 5
                treasure.max shouldBe 5
                treasure.weight shouldBe 9
                treasure.toMap()["item-id"] shouldBe "NTW_OPTIC_CABLE"
                treasure.toMap().containsKey("stack") shouldBe false
            }

            it("keeps vanilla items as ItemStack treasures") {
                val stack = ItemStack(Material.DIAMOND, 4)

                val treasure = TreasureItems.fromStack(stack, amount = 4, weight = 2)

                treasure.shouldBeInstanceOf<Treasure.Item>()
                treasure.stack.type shouldBe Material.DIAMOND
                treasure.stack.amount shouldBe 1
                treasure.min shouldBe 4
                treasure.weight shouldBe 2
            }
        }
    })
