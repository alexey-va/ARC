package ru.arc.enchanting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class EnchantmentFarmingLootTest : StringSpec({
    "farming settings retain the shared cooldown and exclusive key probability" {
        val worlds = mutableSetOf(" Survival ")
        val settings = EnchantmentFarmingLootSettings(true, worlds, setOf(Material.WHEAT), .01, 86_400, .0005)
        worlds.clear()
        settings.allowsWorld("SURVIVAL") shouldBe true
        settings.keyChancePercent shouldBe .0005
        shouldThrow<IllegalArgumentException> { EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), .01, 0) }
        shouldThrow<IllegalArgumentException> { EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), 100.0, 86_400, 1.0) }
    }

    "manual mature harvest reveals one hanging chest on the next grown plant and reserves its day" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.mature(f.origin); f.mature(f.host)
                f.farming().use { farm ->
                    f.breakBlock(f.origin, farm::onBlockBreak)
                    f.items().size shouldBe 0
                    f.visualItems.map { it.type } shouldBe listOf(Material.CHEST)
                    f.farmingClaim() shouldBe f.now
                    f.finds.isFind(f.host, EnchantmentFindSource.FARMING) shouldBe true
                    f.breakBlock(f.host, farm::onBlockBreak)
                    f.items().single().itemStack.type shouldBe Material.PAPER
                    f.mature(f.origin); f.mature(f.host)
                    f.breakBlock(f.origin, farm::onBlockBreak)
                    f.resolved.size shouldBe 1
                    f.now += 86_400_000L
                    f.mature(f.origin)
                    f.breakBlock(f.origin, farm::onBlockBreak)
                    f.resolved.size shouldBe 2
                    f.farmingClaim() shouldBe f.now
                }
            }
        }
    }

    "growth itself immature harvest and a missing grown neighbour give no reward or reservation" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.mature(f.origin); f.mature(f.host)
                f.farming().use { farm ->
                    f.advance()
                    f.resolved.size shouldBe 0
                    f.origin.blockData = (f.origin.blockData as Ageable).apply { age = 1 }
                    f.breakBlock(f.origin, farm::onBlockBreak)
                    f.mature(f.origin)
                    f.host.blockData = (f.host.blockData as Ageable).apply { age = 1 }
                    f.breakBlock(f.origin, farm::onBlockBreak)
                    f.resolved.size shouldBe 0
                    f.farmingClaim() shouldBe null
                    f.items().size shouldBe 0
                }
            }
        }
    }

    "legacy future corrupt and unexpired cooldown markers deny both rewards" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.mature(f.origin); f.mature(f.host)
                f.farming().use { farm ->
                    for (timestamp in listOf(f.now, f.now + 1, -1L)) {
                        f.host.chunk.persistentDataContainer.set(enchantmentFarmingChunkClaimKey, PersistentDataType.LONG, timestamp)
                        f.mature(f.origin)
                        f.breakBlock(f.origin, farm::onBlockBreak)
                    }
                    f.host.chunk.persistentDataContainer.set(enchantmentFarmingChunkClaimKey, PersistentDataType.STRING, "unknown")
                    f.mature(f.origin)
                    f.breakBlock(f.origin, farm::onBlockBreak)
                    f.resolved.size shouldBe 0
                    f.visualItems.size shouldBe 0
                }
            }
        }
    }

    "a key uses the same daily reservation and remains the exact native item" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.mature(f.origin); f.mature(f.host)
                val settings = EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), 0.0, 86_400, 100.0)
                f.farming(settings).use { farm ->
                    f.breakBlock(f.origin, farm::onBlockBreak)
                    f.resolved shouldBe listOf(EnchantmentFindReward.KEY)
                    f.visualItems.single().type shouldBe Material.TRIPWIRE_HOOK
                    f.farmingClaim() shouldBe f.now
                    f.breakBlock(f.host, farm::onBlockBreak)
                    f.items().single().itemStack.isSimilar(f.key) shouldBe true
                }
            }
        }
    }

    "crop props turn toward the harvested neighbour from cardinal and diagonal approaches" {
        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1)) MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.host.type = Material.AIR
                f.mature(f.origin)
                val host = f.world.getBlockAt(f.origin.x + dx, f.origin.y, f.origin.z + dz)
                f.mature(host)
                f.farming().use { farm ->
                    f.breakBlock(f.origin, farm::onBlockBreak)
                    val location = f.visualLocations.single()
                    val towardHarvest = (location.x - host.x - .5) * -dx + (location.z - host.z - .5) * -dz
                    (towardHarvest > .09) shouldBe true
                    f.finds.isFind(host, EnchantmentFindSource.FARMING) shouldBe true
                }
            }
        }
    }

    "named farming test odds work with a zero base and retain maturity claims and daily quota" {
        for (chances in listOf(EnchantmentFindChances(100.0, 0.0), EnchantmentFindChances(0.0, 100.0))) {
            MockBukkitTestRuntime.open().use { paper ->
                EnchantmentFindFixture(paper).use { f ->
                    f.mature(f.origin); f.mature(f.host)
                    f.farming(EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), 0.0, 86_400, 0.0,
                        mapOf("AnotherPlayer" to chances))).use { farm ->
                        f.breakBlock(f.origin, farm::onBlockBreak)
                    }
                    f.rolls shouldBe 0
                    f.resolved.size shouldBe 0
                    f.mature(f.origin)
                    f.farming(EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), 0.0, 86_400, 0.0,
                        mapOf("fInDeR" to chances))).use { farm ->
                        f.origin.blockData = (f.origin.blockData as Ageable).apply { age = 1 }
                        f.breakBlock(f.origin, farm::onBlockBreak)
                        f.rolls shouldBe 0
                        f.mature(f.origin)
                        f.breakBlock(f.origin, farm::onBlockBreak)
                        val reward = if (chances.chancePercent == 100.0) EnchantmentFindReward.LOOTBOX else EnchantmentFindReward.KEY
                        f.resolved shouldBe listOf(reward)
                        f.farmingClaim() shouldBe f.now
                        f.items().size shouldBe 0
                        f.breakBlock(f.host, farm::onBlockBreak)
                        f.items().single().itemStack.isSimilar(if (reward == EnchantmentFindReward.KEY) f.key else f.box) shouldBe true
                        f.rolls shouldBe 1
                        f.mature(f.origin); f.mature(f.host)
                        f.breakBlock(f.origin, farm::onBlockBreak)
                        f.resolved shouldBe listOf(reward)
                        f.rolls shouldBe 1
                    }
                }
            }
        }
    }

    "named Creative farming still requires maturity and reserves one daily find for either native reward" {
        for (chances in listOf(EnchantmentFindChances(100.0, 0.0), EnchantmentFindChances(0.0, 100.0))) {
            MockBukkitTestRuntime.open().use { paper ->
                EnchantmentFindFixture(paper).use { f ->
                    f.player.gameMode = GameMode.CREATIVE
                    f.mature(f.origin); f.mature(f.host)
                    f.farming(EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), 0.0, 86_400, 0.0,
                        mapOf("fInDeR" to chances), setOf("FINDER"))).use { farm ->
                        f.origin.blockData = (f.origin.blockData as Ageable).apply { age = 1 }
                        f.breakBlock(f.origin, farm::onBlockBreak)
                        f.rolls shouldBe 0
                        f.mature(f.origin)
                        f.breakBlock(f.origin, farm::onBlockBreak)
                        val reward = if (chances.keyChancePercent == 100.0) EnchantmentFindReward.KEY else EnchantmentFindReward.LOOTBOX
                        f.resolved shouldBe listOf(reward)
                        f.farmingClaim() shouldBe f.now
                        f.items().size shouldBe 0
                        f.breakBlock(f.host, farm::onBlockBreak)
                        f.items().single().itemStack.isSimilar(if (reward == EnchantmentFindReward.KEY) f.key else f.box) shouldBe true
                        f.mature(f.origin); f.mature(f.host)
                        f.breakBlock(f.origin, farm::onBlockBreak)
                        f.resolved shouldBe listOf(reward)
                        f.rolls shouldBe 1
                    }
                }
            }
        }
    }
})
