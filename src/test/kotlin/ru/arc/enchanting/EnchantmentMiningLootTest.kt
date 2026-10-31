package ru.arc.enchanting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.function.Consumer

class EnchantmentMiningLootTest : StringSpec({
    "settings validate probability and own immutable collections" {
        val worlds = mutableSetOf(" Survival ")
        val materials = mutableSetOf(Material.STONE)
        val settings = EnchantmentMiningLootSettings(true, worlds, materials, 0.05)
        worlds.clear()
        materials.clear()

        settings.worldNames shouldBe setOf("survival")
        settings.materials shouldBe setOf(Material.STONE)
        settings.allowsWorld("SURVIVAL") shouldBe true
        settings.allowsWorld("spawn") shouldBe false
        EnchantmentMiningLootSettings(false, setOf("survival"), setOf(Material.STONE), 1.0)
            .allowsWorld("survival") shouldBe false
        shouldThrow<UnsupportedOperationException> { (settings.materials as MutableSet).clear() }
        for (chance in listOf(-0.1, 100.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            shouldThrow<IllegalArgumentException> { EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), chance) }
        }
        shouldThrow<IllegalArgumentException> { EnchantmentMiningLootSettings(true, setOf("survival", "SURVIVAL"), setOf(Material.STONE), 1.0) }
        shouldThrow<IllegalArgumentException> { EnchantmentMiningLootSettings(true, setOf(" "), setOf(Material.STONE), 1.0) }
        shouldThrow<IllegalArgumentException> { EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.AIR), 1.0) }
    }

    "ineligible breaks do not draw or consult provenance" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("backend", "chance", "cancelled", "no-drops", "creative", "adventure", "offline", "world", "material", "tool")) {
                val fixture = MiningLootFixture()
                when (case) {
                    "cancelled" -> fixture.event.isCancelled = true
                    "no-drops" -> fixture.event.isDropItems = false
                    "creative" -> fixture.gameMode = GameMode.CREATIVE
                    "adventure" -> fixture.gameMode = GameMode.ADVENTURE
                    "offline" -> fixture.online = false
                    "world" -> fixture.worldName = "spawn"
                    "material" -> fixture.blockMaterial = Material.DIRT
                    "tool" -> fixture.tool = ItemStack(Material.DIAMOND_SWORD)
                }
                val listener = fixture.listener(
                    survivalBackend = case != "backend",
                    chancePercent = if (case == "chance") 0.0 else 100.0,
                )
                listener.onBlockBreak(fixture.event)
                fixture.rolls shouldBe 0
                fixture.provenanceChecks shouldBe 0
                fixture.callbacks.size shouldBe 0
                listener.close()
            }
        }
    }

    "chance boundary is exclusive and provenance is consulted only for a winning roll" {
        MockBukkitTestRuntime.open().use {
            for ((roll, wins) in listOf(0.0 to true, 0.00049999 to true, 0.0005 to false, 0.9 to false)) {
                val fixture = MiningLootFixture().apply { this.roll = roll }
                val listener = fixture.listener(chancePercent = 0.05)
                listener.onBlockBreak(fixture.event)
                fixture.rolls shouldBe 1
                fixture.provenanceChecks shouldBe if (wins) 1 else 0
                fixture.callbacks.size shouldBe if (wins) 1 else 0
                listener.close()
            }
        }
    }

    "provenance is captured before cleanup and unknown or failing provenance denies issuance" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("natural", "placed", "unknown", "error")) {
                val fixture = MiningLootFixture()
                val listener = fixture.listener(provenance = when (case) {
                    "natural" -> { _ -> fixture.natural }
                    "placed" -> { _ -> false }
                    "unknown" -> null
                    else -> { _ -> throw IllegalStateException("provider unavailable") }
                })
                listener.onBlockBreak(fixture.event)
                fixture.natural = false // Later provider cleanup must not change the captured decision.
                fixture.completeBreak()
                fixture.spawnCalls shouldBe if (case == "natural") 1 else 0
                listener.close()
            }
        }
    }

    "completion rejects late cancellation unchanged blocks unloaded chunks and departed players" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("cancelled", "no-drops", "unchanged", "unloaded", "offline", "moved", "creative", "valid")) {
                val fixture = MiningLootFixture()
                val listener = fixture.listener()
                listener.onBlockBreak(fixture.event)
                fixture.spawnCalls shouldBe 0
                when (case) {
                    "cancelled" -> fixture.event.isCancelled = true
                    "no-drops" -> fixture.event.isDropItems = false
                    "unloaded" -> fixture.loaded = false
                    "offline" -> fixture.online = false
                    "moved" -> fixture.playerWorldId = UUID.randomUUID()
                    "creative" -> fixture.gameMode = GameMode.CREATIVE
                }
                fixture.completeBreak(broken = case != "unchanged")
                fixture.spawnCalls shouldBe if (case == "valid") 1 else 0
                if (case == "unloaded") {
                    verify(exactly = 0) { fixture.world.getChunkAt(any<Int>(), any<Int>()) }
                    verify(exactly = 0) { fixture.world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
                }
                listener.close()
            }
        }
    }

    "a player gets one chance per tick and a chunk is shared by players and listener generations" {
        MockBukkitTestRuntime.open().use {
            val fixture = MiningLootFixture()
            val listener = fixture.listener()
            listener.onBlockBreak(fixture.event)
            listener.onBlockBreak(fixture.event)
            fixture.rolls shouldBe 1
            fixture.tick++
            listener.onBlockBreak(fixture.event)
            fixture.rolls shouldBe 2
            fixture.playerId = UUID.randomUUID()
            listener.onBlockBreak(fixture.event)
            fixture.rolls shouldBe 3
            fixture.completeBreak()
            fixture.spawnCalls shouldBe 1
            fixture.claim shouldBe 2.toByte()
            listener.close()

            val replacement = fixture.listener()
            fixture.blockMaterial = Material.STONE
            replacement.onBlockBreak(fixture.event)
            fixture.completeBreak()
            fixture.spawnCalls shouldBe 1
            replacement.close()
        }
    }

    "Fortune still creates one physical cloned lootbox and leaves native drops and experience alone" {
        MockBukkitTestRuntime.open().use {
            val fixture = MiningLootFixture()
            fixture.tool.addUnsafeEnchantment(Enchantment.FORTUNE, 3)
            fixture.event.expToDrop = 7
            val listener = fixture.listener()
            listener.onBlockBreak(fixture.event)
            fixture.blockMaterial shouldBe Material.STONE
            fixture.event.isDropItems shouldBe true
            fixture.event.expToDrop shouldBe 7
            fixture.completeBreak()
            fixture.spawnedStacks.size shouldBe 1
            fixture.spawnedStacks.single().amount shouldBe 1
            (fixture.spawnedStacks.single() === fixture.reward) shouldBe false
            fixture.spawnedStacks.single() shouldBe fixture.reward
            listener.close()
        }
    }

    "factory failure or invalid quantity does not reserve a chunk and permits a later attempt" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("null", "exception", "amount", "air", "missing-key", "wrong-key", "reader-failure")) {
                val fixture = MiningLootFixture()
                if (case == "wrong-key") fixture.readTreasureKey = { "sf" }
                if (case == "reader-failure") fixture.readTreasureKey = { error("Custom data unavailable") }
                var failed = true
                val listener = fixture.listener(factory = {
                    if (!failed) fixture.reward else when (case) {
                        "null" -> null
                        "exception" -> throw IllegalStateException("factory failed")
                        "amount" -> ItemStack(Material.CHEST, 2)
                        "air" -> ItemStack(Material.AIR)
                        "missing-key", "wrong-key" -> ItemStack(Material.CHEST)
                        else -> fixture.reward
                    }
                })
                listener.onBlockBreak(fixture.event)
                fixture.completeBreak()
                fixture.claim shouldBe null
                fixture.spawnCalls shouldBe 0

                failed = false
                fixture.readTreasureKey = { "ae" }
                fixture.tick++
                fixture.blockMaterial = Material.STONE
                listener.onBlockBreak(fixture.event)
                fixture.completeBreak()
                fixture.spawnCalls shouldBe 1
                fixture.claim shouldBe 2.toByte()
                listener.close()
            }
        }
    }

    "rejected spawn rolls back the reservation while a throwing spawn remains pending across restart" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("rejected", "exception")) {
                val fixture = MiningLootFixture()
                fixture.spawnValid = false
                fixture.spawnFailure = (case == "exception")
                val listener = fixture.listener()
                listener.onBlockBreak(fixture.event)
                fixture.completeBreak()
                fixture.claim shouldBe if (case == "exception") 1.toByte() else null
                listener.close()

                fixture.spawnValid = true
                fixture.spawnFailure = false
                fixture.blockMaterial = Material.STONE
                val replacement = fixture.listener()
                replacement.onBlockBreak(fixture.event)
                fixture.completeBreak()
                fixture.spawnCalls shouldBe if (case == "exception") 1 else 2
                fixture.claim shouldBe if (case == "exception") 1.toByte() else 2.toByte()
                replacement.close()
            }
        }
    }

    "close cancels owned scheduling and fences already captured callbacks" {
        MockBukkitTestRuntime.open().use {
            val fixture = MiningLootFixture()
            val listener = fixture.listener()
            listener.onBlockBreak(fixture.event)
            fixture.callbacks.size shouldBe 1
            listener.close()
            listener.close()
            fixture.completeBreak()
            fixture.spawnCalls shouldBe 0
            fixture.claim shouldBe null
            fixture.blockMaterial = Material.STONE
            listener.onBlockBreak(fixture.event)
            fixture.rolls shouldBe 1
            verify(exactly = 1) { fixture.tasks.close() }
        }
    }
})

private class MiningLootFixture {
    val world = mockk<World>()
    private val playerWorld = mockk<World>()
    private val worldId = UUID.randomUUID()
    private val chunk = mockk<Chunk>()
    private val data = mockk<PersistentDataContainer>()
    private val block = mockk<Block>()
    private val player = mockk<Player>()
    private val inventory = mockk<PlayerInventory>()
    private val item = mockk<Item>()
    val tasks = mockk<LifecycleTaskScope>(relaxed = true)
    val event: BlockBreakEvent
    val callbacks = mutableListOf<() -> Unit>()
    val spawnedStacks = mutableListOf<ItemStack>()
    val reward = ItemStack(Material.CHEST)
    var readTreasureKey: (ItemStack) -> String? = { if (it === reward) "ae" else null }
    var tool = ItemStack(Material.DIAMOND_PICKAXE)
    var playerId = UUID.randomUUID()
    var playerWorldId = worldId
    var worldName = "survival"
    var gameMode = GameMode.SURVIVAL
    var online = true
    var loaded = true
    var blockMaterial = Material.STONE
    var natural = true
    var tick = 10
    var roll = 0.0
    var rolls = 0
    var provenanceChecks = 0
    var spawnCalls = 0
    var spawnValid = true
    var spawnFailure = false
    var claim: Byte? = null

    init {
        every { world.uid } returns worldId
        every { world.name } answers { worldName }
        every { world.isChunkLoaded(0, 0) } answers { loaded }
        every { world.getChunkAt(0, 0) } answers { check(loaded); chunk }
        every { world.getBlockAt(4, 20, 6) } answers { check(loaded); block }
        every { block.world } returns world
        every { block.x } returns 4
        every { block.y } returns 20
        every { block.z } returns 6
        every { block.type } answers { blockMaterial }
        every { block.chunk } answers { check(loaded); chunk }
        every { block.location } answers { Location(world, 4.0, 20.0, 6.0) }
        every { chunk.persistentDataContainer } returns data
        every { data.has(enchantmentMiningChunkClaimKey) } answers { claim != null }
        every { data.set(enchantmentMiningChunkClaimKey, PersistentDataType.BYTE, any()) } answers { claim = thirdArg(); Unit }
        every { data.remove(enchantmentMiningChunkClaimKey) } answers { claim = null; Unit }
        every { player.uniqueId } answers { playerId }
        every { player.world } returns playerWorld
        every { playerWorld.uid } answers { playerWorldId }
        every { player.isOnline } answers { online }
        every { player.gameMode } answers { gameMode }
        every { player.inventory } returns inventory
        every { inventory.itemInMainHand } answers { tool }
        every { item.isValid } answers { spawnValid }
        every { item.isDead } answers { !spawnValid }
        every { world.dropItem(any(), any(), any()) } answers {
            check(claim == 1.toByte()) { "Reservation must precede spawn" }
            spawnCalls++
            thirdArg<Consumer<in Item>>().accept(item)
            if (spawnFailure) throw IllegalStateException("spawn outcome unknown")
            spawnedStacks += secondArg<ItemStack>()
            item
        }
        event = BlockBreakEvent(block, player)
    }

    fun listener(
        survivalBackend: Boolean = true,
        chancePercent: Double = 100.0,
        provenance: ((Block) -> Boolean)? = { natural },
        factory: () -> ItemStack? = { reward },
    ): EnchantmentMiningLoot {
        val settings = EnchantmentMiningLootSettings(survivalBackend, setOf("survival"), setOf(Material.STONE), chancePercent)
        val draw = { rolls++; roll }
        return if (provenance == null) {
            EnchantmentMiningLoot(settings, createLootbox = factory, nextDouble = draw, currentTick = { tick }, tasks = tasks,
                afterEvent = { callbacks += it }, readTreasureKey = { this.readTreasureKey(it) })
        } else {
            EnchantmentMiningLoot(settings, isNaturalBlock = { provenanceChecks++; provenance(it) }, createLootbox = factory, nextDouble = draw,
                currentTick = { tick }, tasks = tasks, afterEvent = { callbacks += it }, readTreasureKey = { this.readTreasureKey(it) })
        }
    }

    fun completeBreak(broken: Boolean = true) {
        if (broken) blockMaterial = Material.AIR
        val pending = callbacks.toList()
        callbacks.clear()
        pending.forEach { it() }
    }
}
