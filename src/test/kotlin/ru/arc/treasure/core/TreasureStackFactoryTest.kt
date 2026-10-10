package ru.arc.treasure.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase
import ru.arc.enchanting.AdvancedBookCandidate

class TreasureStackFactoryTest :
    KotestTestBase({
        describe("physical treasure materialization") {
            it("clones and splits item quantities without mutating the authored stack") {
                val source = ItemStack(Material.DIAMOND)
                val factory = TreasureStackFactory(nextInt = { 0 })

                val result = factory.create(Treasure.Item(source, min = 130, max = 130), mockk<Player>(relaxed = true))

                result.map(ItemStack::getAmount) shouldBe listOf(64, 64, 2)
                source.amount shouldBe 1
                result.first() shouldBe source.clone().apply { amount = 64 }
            }

            it("resolves the named preset live and splits its returned stack quantity") {
                var resolved: Pair<String, Int>? = null
                val factory = TreasureStackFactory(
                    presetResolver = { name, amount ->
                        resolved = name to amount
                        listOf(ItemStack(Material.SUGAR, amount))
                    },
                )

                val result = factory.create(Treasure.Preset("potion_token", amount = 5), mockk<Player>(relaxed = true))

                resolved shouldBe ("potion_token" to 5)
                result shouldHaveSize 1
                result.single().amount shouldBe 5
            }

            it("uses a native fresh AE book from the selected group and real level cap") {
                var created = ""
                val factory = TreasureStackFactory(
                    bookCandidatesProvider = {
                        mapOf("SIMPLE" to listOf(AdvancedBookCandidate("efficiency", listOf(1, 2, 4))))
                    },
                    createBook = { id, level, _ ->
                        created = "$id:$level"
                        ItemStack(Material.ENCHANTED_BOOK)
                    },
                    nextInt = { 0 },
                )

                val result = factory.create(
                    Treasure.Ae(AeKind.RANDOM_BOOK, amount = 2, group = "SIMPLE", maxLevel = 1),
                    mockk<Player>(relaxed = true),
                )

                result shouldHaveSize 2
                created shouldBe "efficiency:1"
                result.all { it.type == Material.ENCHANTED_BOOK } shouldBe true
            }

            it("rejects effect rewards and recursive pool cycles instead of returning partial loot") {
                val player = mockk<Player>(relaxed = true)
                val factory = TreasureStackFactory(nextInt = { 0 })

                runCatching { factory.create(Treasure.Money(10.0, 10.0), player) }
                    .exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
                runCatching { factory.create(Treasure.Command(listOf("say no")), player) }
                    .exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
                runCatching { factory.createFromPool(Treasure.SubPool("root"), player, "root") }
                    .exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
            }
        }
    })
