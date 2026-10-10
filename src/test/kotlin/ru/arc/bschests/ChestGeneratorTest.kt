package ru.arc.bschests

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.KotestTestBase
import ru.arc.config.Config
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.TreasureStackFactory
import ru.arc.treasure.core.AeKind
import ru.arc.treasure.core.AeNativeItems
import java.io.File

class ChestGeneratorTest :
    KotestTestBase({
        describe("personal chest physical rewards") {
            it("loads every shipped acquisition pool through the native parser without losing rewards") {
                for (id in listOf("structures_common", "structures_special", "enchant_supply", "enchant_boss_supply")) {
                    val yaml = YamlConfiguration.loadConfiguration(File("src/main/resources/treasures/$id.yml"))
                    // MockBukkit aborts unsupported legacy deserialization; a shipped pool must fail visibly instead of skipping.
                    val pool = runCatching { requireNotNull(TreasurePool.fromMap(yaml.getValues(false))) }
                        .getOrElse { throw AssertionError("Invalid shipped pool $id", it) }
                    pool.id shouldBe id
                    pool.treasures.size shouldBe yaml.getMapList("treasures").size
                    pool.treasures.sumOf { it.weight } shouldBe 100
                    pool.treasures.forEach { treasure ->
                        when (treasure) {
                            is Treasure.Item -> {
                                treasure.stack.type.isAir shouldBe false
                                (treasure.max <= treasure.stack.maxStackSize) shouldBe true
                            }
                            is Treasure.Ae -> if (treasure.kind == AeKind.RANDOM_BOOK) {
                                treasure.maxLevel shouldBe 1
                            } else AeNativeItems.supports(treasure) shouldBe true
                            is Treasure.Preset -> treasure.preset shouldBe "potion_token"
                            else -> error("Unexpected non-physical reward in $id")
                        }
                    }
                }
            }

            it("maps a missing legacy pool name to configured common loot and rolls the configured count") {
                val config = chestTestConfig(minRolls = 3, maxRolls = 3, bonusChance = 0.0)
                val requestedPools = mutableListOf<String>()
                val pool = itemPool("structures_common")
                val generator = ChestGenerator(
                    config = config,
                    poolProvider = { id -> requestedPools += id; pool.takeIf { id == "structures_common" } },
                    nextInt = { 0 },
                )

                val result = generator.generate(mockk<Player>(relaxed = true), null)

                requestedPools shouldBe listOf("structures_common")
                result shouldHaveSize 3
                result.all { it?.type == Material.STONE } shouldBe true
            }

            it("preserves the generic_bs alias and adds at most one independently rolled bonus") {
                val config = chestTestConfig(minRolls = 3, maxRolls = 5, bonusChance = 1.0)
                val requestedPools = mutableListOf<String>()
                val generator = ChestGenerator(
                    config = config,
                    stackFactory = TreasureStackFactory(nextInt = { 0 }),
                    poolProvider = { id -> requestedPools += id; itemPool(id) },
                    nextInt = { bound -> bound - 1 },
                    nextDouble = { 0.0 },
                )

                val result = generator.generate(mockk<Player>(relaxed = true), "generic_bs")

                requestedPools shouldBe listOf("structures_common", "structures_special")
                result shouldHaveSize 6
            }

            it("fails the whole materialization when a selected entry has a non-physical effect") {
                val generator = ChestGenerator(
                    config = chestTestConfig(minRolls = 1, maxRolls = 1, bonusChance = 0.0),
                    poolProvider = { TreasurePool(it, listOf(Treasure.Command(listOf("say no")))) },
                    nextInt = { 0 },
                )

                runCatching { generator.generate(mockk<Player>(relaxed = true), "custom_pool") }
                    .exceptionOrNull()?.message shouldBe "Command treasure cannot be placed in a physical chest pool"
            }
        }
    }) {
}

private fun chestTestConfig(minRolls: Int, maxRolls: Int, bonusChance: Double): Config = mockk {
    every { string("loot.common-pool", "structures_common") } returns "structures_common"
    every { integer("loot.min-rolls", 3) } returns minRolls
    every { integer("loot.max-rolls", 5) } returns maxRolls
    every { string("loot.bonus-pool", "structures_special") } returns "structures_special"
    every { double("loot.bonus-chance", 0.10) } returns bonusChance
}

private fun itemPool(id: String): TreasurePool =
    TreasurePool(id, listOf(Treasure.Item(ItemStack(Material.STONE))))
