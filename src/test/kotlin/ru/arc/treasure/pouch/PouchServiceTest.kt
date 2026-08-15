package ru.arc.treasure.pouch

import com.google.gson.JsonParser
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase
import ru.arc.treasure.core.GiveResult
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool

class PouchServiceTest :
    KotestTestBase({
        describe("PouchService") {
            val definition =
                PouchDefinition(
                    id = "test_pouch",
                    description = null,
                    item = JsonParser.parseString("{\"material\":\"CHEST\"}").asJsonObject,
                    rewards =
                        listOf(
                            PouchRewardSource("common", PouchRolls(2, 2)),
                            PouchRewardSource("bonus", PouchRolls(1, 1), 0.25),
                        ),
                    open = PouchOpenPresentation(),
                )

            it("gives every guaranteed roll and a successful bonus") {
                val common = Treasure.Item(ItemStack(Material.IRON_INGOT))
                val bonus = Treasure.Item(ItemStack(Material.DIAMOND))
                val pools = mapOf(
                    "common" to TreasurePool("common", listOf(common)),
                    "bonus" to TreasurePool("bonus", listOf(bonus)),
                )
                val given = mutableListOf<Treasure>()
                val service =
                    PouchService(
                        poolProvider = pools::get,
                        giveTreasure = { treasure, _, _ ->
                            given += treasure
                            GiveResult.Success(treasure)
                        },
                        nextDouble = { 0.1 },
                        nextIntInclusive = { min, _ -> min },
                    )

                val result = service.open(definition, mockk<Player>(relaxed = true))

                result.attempted shouldBe 3
                result.awarded shouldBe 3
                result.shouldConsume shouldBe true
                given.count { it === common } shouldBe 2
                given.count { it === bonus } shouldBe 1
            }

            it("skips a missed bonus without failing the pouch") {
                val common = Treasure.Item(ItemStack(Material.IRON_INGOT))
                val pools = mapOf(
                    "common" to TreasurePool("common", listOf(common)),
                    "bonus" to TreasurePool("bonus", listOf(common)),
                )
                val service = PouchService(
                    poolProvider = pools::get,
                    giveTreasure = { treasure, _, _ -> GiveResult.Success(treasure) },
                    nextDouble = { 0.9 },
                    nextIntInclusive = { min, _ -> min },
                )

                val result = service.open(definition, mockk<Player>(relaxed = true))

                result.attempted shouldBe 2
                result.awarded shouldBe 2
                result.failures.isEmpty() shouldBe true
            }

            it("does not consume when any referenced pool is unavailable") {
                val service = PouchService(
                    poolProvider = { null },
                    giveTreasure = { treasure, _, _ -> GiveResult.Success(treasure) },
                )

                val result = service.open(definition, mockk<Player>(relaxed = true))

                result.shouldConsume shouldBe false
                result.awarded shouldBe 0
                result.failures shouldContain "Pool unavailable: bonus"
                result.failures shouldContain "Pool unavailable: common"
            }

            it("still consumes after a committed reward when a later handler throws") {
                val treasure = Treasure.Item(ItemStack(Material.IRON_INGOT))
                val pool = TreasurePool("common", listOf(treasure))
                val safeDefinition = definition.copy(
                    rewards = listOf(PouchRewardSource("common", PouchRolls(2, 2))),
                )
                var calls = 0
                val service = PouchService(
                    poolProvider = { pool },
                    giveTreasure = { reward, _, _ ->
                        calls++
                        if (calls == 2) error("simulated adapter failure")
                        GiveResult.Success(reward)
                    },
                    nextIntInclusive = { min, _ -> min },
                )

                val result = service.open(safeDefinition, mockk<Player>(relaxed = true))

                result.awarded shouldBe 1
                result.shouldConsume shouldBe true
                result.failures.single().contains("Reward handler failed") shouldBe true
            }
        }
    })
