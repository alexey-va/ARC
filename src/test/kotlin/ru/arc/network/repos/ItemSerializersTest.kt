package ru.arc.network.repos

import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase
import ru.arc.util.Common

class ItemSerializersTest :
    KotestTestBase({
        describe("item serializers") {
            it("round-trips an ItemStack with JDK Base64") {
                val original = ItemStack(Material.DIAMOND, 3)

                val json = Common.gson.toJson(original, ItemStack::class.java)
                val restored = Common.gson.fromJson(json, ItemStack::class.java)

                restored.type shouldBe Material.DIAMOND
                restored.amount shouldBe 3
            }

            it("round-trips nullable entries in an ItemList") {
                val original =
                    ItemList().apply {
                        add(ItemStack(Material.GOLD_INGOT, 2))
                        add(null)
                    }

                val json = Common.gson.toJson(original, ItemList::class.java)
                val restored = Common.gson.fromJson(json, ItemList::class.java)

                restored.size shouldBe 2
                restored[0]?.type shouldBe Material.GOLD_INGOT
                restored[0]?.amount shouldBe 2
                restored[1] shouldBe null
            }
        }
    })
