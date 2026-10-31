package ru.arc.enchanting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class EnchantmentMiningLootTest : StringSpec({
    "settings own immutable collections and reject invalid combined odds" {
        val worlds = mutableSetOf(" Survival ")
        val materials = mutableSetOf(Material.STONE)
        val settings = EnchantmentMiningLootSettings(true, worlds, materials, .02, .001)
        worlds.clear(); materials.clear()
        settings.allowsWorld("SURVIVAL") shouldBe true
        settings.materials shouldBe setOf(Material.STONE)
        for (pair in listOf(-1.0 to 0.0, Double.NaN to 0.0, 99.0 to 2.0, .02 to -1.0)) {
            shouldThrow<IllegalArgumentException> { EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), pair.first, pair.second) }
        }
    }

    "accepted manual mining reveals two embedded props and only the next host break pays once" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.breakMining()
                f.items().size shouldBe 0
                f.visualItems.map(ItemStack::getType) shouldBe listOf(Material.CHEST, Material.CHEST)
                f.miningClaim() shouldBe 1.toByte()
                f.finds.isFind(f.host, EnchantmentFindSource.MINING) shouldBe true
                f.breakMining(f.host)
                f.items().map { it.itemStack.type } shouldBe listOf(Material.PAPER)
                f.host.type = Material.STONE
                f.breakMining(f.host)
                f.items().size shouldBe 1
                f.resolved.size shouldBe 1
            }
        }
    }

    "cancelled drops placed blocks wrong tools and creative cannot reveal a find" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.breakMining(cancelDrop = true)
                f.origin.type = Material.STONE
                f.natural = false
                f.breakMining()
                f.origin.type = Material.STONE
                f.natural = true
                f.player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_AXE))
                f.breakMining()
                f.origin.type = Material.STONE
                f.player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_PICKAXE))
                f.player.gameMode = GameMode.CREATIVE
                f.breakMining()
                f.miningClaim() shouldBe null
                f.visualItems.size shouldBe 0
                f.items().size shouldBe 0
            }
        }
    }

    "one roll per player tick and existing v1 markers keep their authority" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.mining.onBlockBreak(BlockBreakEvent(f.origin, f.player))
                f.mining.onBlockBreak(BlockBreakEvent(f.host, f.player))
                f.rolls shouldBe 1
                f.advance()
                f.host.chunk.persistentDataContainer.set(enchantmentMiningChunkClaimKey, PersistentDataType.BYTE, 2.toByte())
                f.breakMining()
                f.miningClaim() shouldBe 2.toByte()
                f.visualItems.size shouldBe 0
            }
        }
    }

    "mining and farming share one roll in the same tick while collecting an existing find bypasses that gate" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                val crop = f.world.getBlockAt(7, 64, 6)
                f.mature(crop)
                val farming = f.farming()
                f.mining.onBlockBreak(BlockBreakEvent(f.origin, f.player))
                farming.onBlockBreak(BlockBreakEvent(crop, f.player))
                f.rolls shouldBe 1
                f.advance()
                farming.onBlockBreak(BlockBreakEvent(crop, f.player))
                f.mining.onBlockBreak(BlockBreakEvent(f.origin, f.player))
                f.rolls shouldBe 2
                f.revealMining()
                f.breakMining(f.host)
                f.items().single().itemStack.type shouldBe Material.PAPER
                f.rolls shouldBe 2
                farming.close()
            }
        }
    }

    "a neighbour across a chunk boundary never spends another chunk allowance" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.origin.type = Material.AIR
                f.host.type = Material.AIR
                val origin = f.world.getBlockAt(15, 64, 6).apply { type = Material.STONE }
                val across = f.world.getBlockAt(16, 64, 6).apply { type = Material.STONE }
                f.breakMining(origin)
                f.finds.isFind(across, EnchantmentFindSource.MINING) shouldBe false
                origin.chunk.persistentDataContainer.has(enchantmentMiningChunkClaimKey) shouldBe false
                across.chunk.persistentDataContainer.has(enchantmentMiningChunkClaimKey) shouldBe false
                f.resolved.size shouldBe 0
            }
        }
    }
})
