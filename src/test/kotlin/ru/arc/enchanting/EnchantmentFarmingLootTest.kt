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
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.BlockData
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

class EnchantmentFarmingLootTest : StringSpec({
    "settings validate chance cooldown and immutable snapshots" {
        val worlds = mutableSetOf(" Survival ")
        val materials = mutableSetOf(Material.WHEAT)
        val settings = EnchantmentFarmingLootSettings(true, worlds, materials, 0.05, 86_400)
        worlds.clear()
        materials.clear()

        settings.worldNames shouldBe setOf("survival")
        settings.materials shouldBe setOf(Material.WHEAT)
        settings.chancePercent shouldBe 0.05
        settings.cooldownSeconds shouldBe 86_400L
        settings.allowsWorld("SURVIVAL") shouldBe true
        settings.allowsWorld("spawn") shouldBe false
        EnchantmentFarmingLootSettings(false, setOf("survival"), setOf(Material.WHEAT), 1.0, 1)
            .allowsWorld("survival") shouldBe false
        shouldThrow<UnsupportedOperationException> { (settings.worldNames as MutableSet).clear() }
        shouldThrow<UnsupportedOperationException> { (settings.materials as MutableSet).clear() }
        for (chance in listOf(-0.1, 100.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            shouldThrow<IllegalArgumentException> { EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), chance, 1) }
        }
        for (cooldown in listOf(0L, -1L, 31_536_001L, Long.MAX_VALUE)) {
            shouldThrow<IllegalArgumentException> { EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), 1.0, cooldown) }
        }
        for (material in listOf(Material.AIR, Material.CAVE_AIR, Material.DIAMOND_HOE)) {
            shouldThrow<IllegalArgumentException> { EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(material), 1.0, 1) }
        }
        shouldThrow<IllegalArgumentException> { EnchantmentFarmingLootSettings(true, setOf("survival", "SURVIVAL"), setOf(Material.WHEAT), 1.0, 1) }
        shouldThrow<IllegalArgumentException> { EnchantmentFarmingLootSettings(true, setOf(" "), setOf(Material.WHEAT), 1.0, 1) }
        MockBukkitTestRuntime.open().use {
            val listener = FarmingLootFixture().listener(settings = settings)
            listener.settingsSnapshot() shouldBe settings
            listener.close()
        }
    }

    "ineligible breaks do not draw or read chunk data" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("backend", "chance", "cancelled", "no-drops", "creative", "adventure", "offline", "world", "moved", "material", "immature", "unsupported")) {
                val fixture = FarmingLootFixture()
                when (case) {
                    "cancelled" -> fixture.event.isCancelled = true
                    "no-drops" -> fixture.event.isDropItems = false
                    "creative" -> fixture.gameMode = GameMode.CREATIVE
                    "adventure" -> fixture.gameMode = GameMode.ADVENTURE
                    "offline" -> fixture.online = false
                    "world" -> fixture.worldName = "spawn"
                    "moved" -> fixture.playerWorldId = UUID.randomUUID()
                    "material" -> fixture.blockMaterial = Material.STONE
                    "immature" -> fixture.age--
                    "unsupported" -> fixture.ageable = false
                }
                val settings = EnchantmentFarmingLootSettings(case != "backend", setOf("survival"), setOf(Material.WHEAT),
                    if (case == "chance") 0.0 else 100.0, 86_400)
                val listener = fixture.listener(settings = settings)
                listener.onBlockBreak(fixture.event)
                fixture.rolls shouldBe 0
                fixture.pdcReads shouldBe 0
                fixture.callbacks.size shouldBe 0
                listener.close()
            }
        }
    }

    "hand harvest and every tool receive the same one item without changing native drops or XP" {
        MockBukkitTestRuntime.open().use {
            for (tool in listOf(Material.AIR, Material.DIAMOND_HOE, Material.DIAMOND_PICKAXE, Material.DIAMOND_SWORD)) {
                val fixture = FarmingLootFixture().apply { this.tool = ItemStack(tool) }
                if (tool != Material.AIR) fixture.tool.addUnsafeEnchantment(Enchantment.FORTUNE, 3)
                fixture.event.expToDrop = 7
                val listener = fixture.listener()
                listener.onBlockBreak(fixture.event)
                fixture.blockMaterial shouldBe Material.WHEAT
                fixture.event.isDropItems shouldBe true
                fixture.event.expToDrop shouldBe 7
                fixture.completeBreak()
                fixture.spawnedStacks.size shouldBe 1
                fixture.spawnedStacks.single().amount shouldBe 1
                (fixture.spawnedStacks.single() === fixture.reward) shouldBe false
                fixture.spawnedStacks.single() shouldBe fixture.reward
                verify(exactly = 0) { fixture.player.inventory }
                listener.close()
            }
        }
    }

    "chance boundaries are exclusive and chunk data is read only after winning with a loaded chunk" {
        MockBukkitTestRuntime.open().use {
            for ((roll, wins) in listOf(0.0 to true, 0.00049999 to true, 0.0005 to false, 0.9 to false)) {
                val fixture = FarmingLootFixture().apply { this.roll = roll }
                val listener = fixture.listener(chancePercent = 0.05)
                listener.onBlockBreak(fixture.event)
                fixture.rolls shouldBe 1
                fixture.pdcReads shouldBe if (wins) 1 else 0
                fixture.callbacks.size shouldBe if (wins) 1 else 0
                listener.close()
            }
            val fixture = FarmingLootFixture().apply { loaded = false }
            val listener = fixture.listener()
            listener.onBlockBreak(fixture.event)
            fixture.rolls shouldBe 1
            fixture.pdcReads shouldBe 0
            fixture.callbacks.size shouldBe 0
            verify(exactly = 0) { fixture.block.chunk }
            verify(exactly = 0) { fixture.world.getChunkAt(any<Int>(), any<Int>()) }
            listener.close()

            val certain = FarmingLootFixture().apply { roll = 0.99999999 }
            certain.listener().use {
                it.onBlockBreak(certain.event)
                certain.completeBreak()
                certain.spawnCalls shouldBe 1
            }
        }
    }

    "invalid random values fail closed" {
        MockBukkitTestRuntime.open().use {
            for (roll in listOf(-0.1, 1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                val fixture = FarmingLootFixture().apply { this.roll = roll }
                fixture.listener().use {
                    it.onBlockBreak(fixture.event)
                    fixture.callbacks.size shouldBe 0
                    fixture.pdcReads shouldBe 0
                    fixture.claim shouldBe null
                }
            }
        }
    }

    "one roll per player per tick and a shared chunk cooldown survive a fresh listener and expire exactly" {
        MockBukkitTestRuntime.open().use {
            val fixture = FarmingLootFixture()
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
            fixture.claim shouldBe fixture.now
            listener.close()

            val replacement = fixture.listener()
            fixture.blockMaterial = Material.WHEAT
            replacement.onBlockBreak(fixture.event)
            fixture.callbacks.size shouldBe 0
            fixture.now += 86_400_000L - 1
            fixture.tick++
            replacement.onBlockBreak(fixture.event)
            fixture.callbacks.size shouldBe 0
            fixture.now++
            fixture.tick++
            replacement.onBlockBreak(fixture.event)
            fixture.completeBreak()
            fixture.spawnCalls shouldBe 2
            fixture.claim shouldBe fixture.now
            replacement.close()
        }
    }

    "wrong marker types negative timestamps future timestamps and clock rollback fail closed" {
        MockBukkitTestRuntime.open().use {
            val referenceTime = 1_000_000_000L
            for (marker in listOf("unknown", 1.toByte(), -1L, Long.MIN_VALUE, referenceTime + 1, Long.MAX_VALUE)) {
                val fixture = FarmingLootFixture().apply { claim = marker }
                fixture.listener().use {
                    it.onBlockBreak(fixture.event)
                    fixture.callbacks.size shouldBe 0
                    fixture.spawnCalls shouldBe 0
                    fixture.claim shouldBe marker
                }
            }
            val fixture = FarmingLootFixture().apply { now = -1L }
            fixture.listener().use {
                it.onBlockBreak(fixture.event)
                fixture.callbacks.size shouldBe 0
                fixture.claim shouldBe null
            }
        }
    }

    "completion rechecks cancellation drops block chunk player world and cooldown" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("cancelled", "no-drops", "unchanged", "unloaded", "offline", "moved", "creative", "cooldown", "valid")) {
                val fixture = FarmingLootFixture()
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
                    "cooldown" -> fixture.claim = fixture.now
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

    "factory failure and invalid AE rewards leave an expired claim unchanged and allow retry" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("null", "exception", "amount", "air", "missing-key", "wrong-key", "reader-failure")) {
                val fixture = FarmingLootFixture()
                if (case == "wrong-key") fixture.readTreasureKey = { "sf" }
                if (case == "reader-failure") fixture.readTreasureKey = { error("Custom data unavailable") }
                val previousClaim = fixture.now - 86_400_000L
                fixture.claim = previousClaim
                var failed = true
                val listener = fixture.listener(factory = {
                    if (!failed) fixture.reward else when (case) {
                        "null" -> null
                        "exception" -> throw IllegalStateException("factory failed")
                        "amount" -> fixture.reward.clone().apply { amount = 2 }
                        "air" -> ItemStack(Material.AIR)
                        "missing-key", "wrong-key" -> ItemStack(Material.CHEST)
                        else -> fixture.reward
                    }
                })
                listener.onBlockBreak(fixture.event)
                fixture.completeBreak()
                fixture.claim shouldBe previousClaim
                fixture.spawnCalls shouldBe 0

                failed = false
                fixture.readTreasureKey = { "ae" }
                fixture.tick++
                fixture.blockMaterial = Material.WHEAT
                listener.onBlockBreak(fixture.event)
                fixture.completeBreak()
                fixture.spawnCalls shouldBe 1
                fixture.claim shouldBe fixture.now
                listener.close()
            }
        }
    }

    "normally rejected spawns clear a new reservation or restore the previous expired timestamp" {
        MockBukkitTestRuntime.open().use {
            for (previousClaim in listOf(null, 0L, 1_000_000_000L - 86_400_000L)) {
                val fixture = FarmingLootFixture().apply { claim = previousClaim; spawnValid = false }
                val listener = fixture.listener()
                listener.onBlockBreak(fixture.event)
                fixture.completeBreak()
                fixture.spawnCalls shouldBe 1
                fixture.claim shouldBe previousClaim
                fixture.spawnValid = true
                fixture.tick++
                fixture.blockMaterial = Material.WHEAT
                listener.onBlockBreak(fixture.event)
                fixture.completeBreak()
                fixture.spawnCalls shouldBe 2
                fixture.claim shouldBe fixture.now
                listener.close()
            }
        }
    }

    "uncertain spawn outcomes keep the timestamp across a fresh listener" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("before-candidate", "after-candidate", "missing-candidate")) {
                val fixture = FarmingLootFixture().apply {
                    spawnFailure = case.takeUnless { it == "missing-candidate" }
                    exposeCandidate = case != "missing-candidate"
                }
                val listener = fixture.listener()
                listener.onBlockBreak(fixture.event)
                fixture.completeBreak()
                fixture.spawnCalls shouldBe 1
                fixture.claim shouldBe fixture.now
                listener.close()

                fixture.spawnFailure = null
                fixture.exposeCandidate = true
                fixture.blockMaterial = Material.WHEAT
                fixture.listener().use {
                    it.onBlockBreak(fixture.event)
                    fixture.completeBreak()
                    fixture.spawnCalls shouldBe 1
                    fixture.claim shouldBe fixture.now
                }
            }
        }
    }

    "close cancels owned scheduling and fences captured callbacks" {
        MockBukkitTestRuntime.open().use {
            val fixture = FarmingLootFixture()
            val listener = fixture.listener()
            listener.onBlockBreak(fixture.event)
            fixture.callbacks.size shouldBe 1
            listener.close()
            listener.close()
            fixture.completeBreak()
            fixture.spawnCalls shouldBe 0
            fixture.claim shouldBe null
            fixture.blockMaterial = Material.WHEAT
            listener.onBlockBreak(fixture.event)
            fixture.rolls shouldBe 1
            verify(exactly = 1) { fixture.tasks.close() }
        }
    }
})

private class FarmingLootFixture {
    val world = mockk<World>()
    private val playerWorld = mockk<World>()
    private val worldId = UUID.randomUUID()
    private val chunk = mockk<Chunk>()
    private val data = mockk<PersistentDataContainer>()
    val block = mockk<Block>()
    private val crop = mockk<Ageable>()
    private val otherData = mockk<BlockData>()
    val player = mockk<Player>()
    private val inventory = mockk<PlayerInventory>()
    private val item = mockk<Item>()
    val tasks = mockk<LifecycleTaskScope>(relaxed = true)
    val event: BlockBreakEvent
    val callbacks = mutableListOf<() -> Unit>()
    val spawnedStacks = mutableListOf<ItemStack>()
    val reward = ItemStack(Material.CHEST)
    var readTreasureKey: (ItemStack) -> String? = { if (it === reward) "ae" else null }
    var tool = ItemStack(Material.AIR)
    var playerId = UUID.randomUUID()
    var playerWorldId = worldId
    var worldName = "survival"
    var gameMode = GameMode.SURVIVAL
    var online = true
    var loaded = true
    var blockMaterial = Material.WHEAT
    var ageable = true
    var age = 7
    var tick = 10
    var now = 1_000_000_000L
    var roll = 0.0
    var rolls = 0
    var pdcReads = 0
    var spawnCalls = 0
    var spawnValid = true
    var spawnFailure: String? = null
    var exposeCandidate = true
    var claim: Any? = null

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
        every { block.blockData } answers { if (ageable) crop else otherData }
        every { crop.age } answers { age }
        every { crop.maximumAge } returns 7
        every { block.chunk } answers { check(loaded); chunk }
        every { block.location } answers { Location(world, 4.0, 20.0, 6.0) }
        every { chunk.persistentDataContainer } answers { pdcReads++; data }
        every { data.has(enchantmentFarmingChunkClaimKey) } answers { claim != null }
        every { data.get(enchantmentFarmingChunkClaimKey, PersistentDataType.LONG) } answers { claim as? Long }
        every { data.set(enchantmentFarmingChunkClaimKey, PersistentDataType.LONG, any()) } answers { claim = thirdArg<Long>(); Unit }
        every { data.remove(enchantmentFarmingChunkClaimKey) } answers { claim = null; Unit }
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
            check(claim == now) { "Current timestamp reservation must precede spawn" }
            spawnCalls++
            if (spawnFailure == "before-candidate") throw IllegalStateException("spawn outcome unknown")
            if (exposeCandidate) thirdArg<Consumer<in Item>>().accept(item)
            if (spawnFailure == "after-candidate") throw IllegalStateException("spawn outcome unknown")
            spawnedStacks += secondArg<ItemStack>()
            item
        }
        event = BlockBreakEvent(block, player)
    }

    fun listener(
        chancePercent: Double = 100.0,
        settings: EnchantmentFarmingLootSettings = EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), chancePercent, 86_400),
        factory: () -> ItemStack? = { reward },
    ): EnchantmentFarmingLoot = EnchantmentFarmingLoot(settings, createLootbox = factory,
        nextDouble = { rolls++; roll }, currentTick = { tick }, clockMillis = { now }, tasks = tasks,
        afterEvent = { callbacks += it }, readTreasureKey = { this.readTreasureKey(it) })

    fun completeBreak(broken: Boolean = true) {
        if (broken) blockMaterial = Material.AIR
        val pending = callbacks.toList()
        callbacks.clear()
        pending.forEach { it() }
    }
}
