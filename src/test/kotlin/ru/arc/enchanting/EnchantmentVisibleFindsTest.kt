package ru.arc.enchanting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Item
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier

class EnchantmentVisibleFindsTest : StringSpec({
    "reward bands are exclusive absolute probabilities" {
        enchantmentFindReward(0.0, .02, .001) shouldBe EnchantmentFindReward.KEY
        enchantmentFindReward(.00001, .02, .001) shouldBe EnchantmentFindReward.LOOTBOX
        enchantmentFindReward(.000209999, .02, .001) shouldBe EnchantmentFindReward.LOOTBOX
        enchantmentFindReward(.00021, .02, .001) shouldBe null
        enchantmentFindReward(0.0, 0.0, 0.0) shouldBe null
    }

    "async key preparation coalesces a chunk and revalidates the host before reserving anything" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                val pending = CompletableFuture<ItemStack?>()
                f.resolve = { pending }
                f.finds.reveal(f.host, EnchantmentFindSource.MINING, BlockFace.WEST, EnchantmentFindReward.KEY)
                f.finds.reveal(f.host, EnchantmentFindSource.MINING, BlockFace.WEST, EnchantmentFindReward.KEY)
                f.resolved.size shouldBe 1
                f.miningClaim() shouldBe null
                f.visualItems.size shouldBe 0
                f.host.type = Material.AIR
                pending.complete(f.key)
                f.scheduler.executeImmediate()
                f.miningClaim() shouldBe null
                f.visualItems.size shouldBe 0
                f.items().size shouldBe 0
            }
        }
    }

    "null timeout and retired generation cannot turn an unavailable key into a box" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.resolve = { CompletableFuture.completedFuture(null) }
                f.finds.reveal(f.host, EnchantmentFindSource.MINING, BlockFace.WEST, EnchantmentFindReward.KEY)
                f.scheduler.executeImmediate()
                f.miningClaim() shouldBe null
                val pending = CompletableFuture<ItemStack?>()
                f.resolve = { pending }
                f.finds.reveal(f.host, EnchantmentFindSource.MINING, BlockFace.WEST, EnchantmentFindReward.KEY)
                f.scheduler.tick(201)
                pending.complete(f.key)
                f.scheduler.executeImmediate()
                f.miningClaim() shouldBe null
                val retired = CompletableFuture<ItemStack?>()
                f.resolve = { retired }
                f.finds.reveal(f.host, EnchantmentFindSource.MINING, BlockFace.WEST, EnchantmentFindReward.KEY)
                f.finds.close()
                retired.complete(f.key)
                f.scheduler.executeImmediate()
                f.miningClaim() shouldBe null
                f.visualItems.size shouldBe 0
                f.scheduler.timerCount() shouldBe 0
            }
        }
    }

    "chunk unload and a fresh generation restore the same find and exact reward without a new factory call" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.revealMining()
                f.resolved.size shouldBe 1
                f.finds.onChunkUnload(ChunkUnloadEvent(f.host.chunk, true))
                f.handles.take(2).forEach { verify(exactly = 1) { it.remove() } }
                f.finds.onChunkLoad(ChunkLoadEvent(f.host.chunk, false))
                f.visualItems.size shouldBe 4
                f.finds.closeAsync().join()
                f.resolve = { CompletableFuture.completedFuture(ItemStack(Material.DIAMOND)) }
                f.finds = f.newFinds()
                f.mining = f.newMining()
                f.finds.onChunkLoad(ChunkLoadEvent(f.host.chunk, false))
                f.visualItems.size shouldBe 6
                f.breakMining(f.host)
                f.items().single().itemStack.type shouldBe Material.PAPER
                f.items().single().itemStack.itemMeta.displayName() shouldBe Component.text("saved AE box")
                f.resolved.size shouldBe 1
                f.miningClaim() shouldBe 1.toByte()
                f.finds.onChunkLoad(ChunkLoadEvent(f.host.chunk, false))
                f.visualItems.size shouldBe 6
            }
        }
    }

    "cancelled break preserves a visible find while cancelled native drops cannot award it" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.revealMining()
                val cancelled = BlockBreakEvent(f.host, f.player)
                f.mining.onBlockBreak(cancelled)
                cancelled.isCancelled = true
                f.advance()
                f.finds.isFind(f.host, EnchantmentFindSource.MINING) shouldBe true
                f.items().size shouldBe 0
                f.breakMining(f.host, cancelDrop = true)
                f.finds.isFind(f.host, EnchantmentFindSource.MINING) shouldBe false
                f.items().size shouldBe 0
                f.miningClaim() shouldBe 1.toByte()
            }
        }
    }

    "retirement drains accepted claims after native dispatch even when consumer tasks have been reset" {
        for (cancelAfterClose in listOf(false, true)) MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.revealMining()
                val state = f.host.state
                f.mining.onBlockBreak(BlockBreakEvent(f.host, f.player))
                f.host.type = Material.AIR
                val drop = BlockDropItemEvent(f.host, state, f.player, mutableListOf())
                f.finds.onBlockDrop(drop)
                f.mining.close()
                val drain = f.finds.closeAsync()
                drain.isDone shouldBe false
                f.items().size shouldBe 0
                // A later listener may still cancel this same native event after retirement was requested.
                drop.isCancelled = cancelAfterClose
                f.scheduler.cancelAll()
                f.cleanupScheduler.tick()
                drain.join()
                f.items().size shouldBe if (cancelAfterClose) 0 else 1
                if (!cancelAfterClose) f.items().single().itemStack.itemMeta.displayName() shouldBe Component.text("saved AE box")
                f.finds = f.newFinds()
                f.mining = f.newMining()
                f.finds.onChunkLoad(ChunkLoadEvent(f.host.chunk, false))
                f.advance()
                f.items().size shouldBe if (cancelAfterClose) 0 else 1
                f.resolved.size shouldBe 1
                f.miningClaim() shouldBe 1.toByte()
            }
        }
    }

    "full server stop settles accepted claims synchronously without depending on another tick" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.revealMining()
                val state = f.host.state
                f.mining.onBlockBreak(BlockBreakEvent(f.host, f.player))
                f.host.type = Material.AIR
                f.finds.onBlockDrop(BlockDropItemEvent(f.host, state, f.player, mutableListOf()))
                f.stopping = true
                f.finds.closeAsync().isDone shouldBe true
                f.items().single().itemStack.itemMeta.displayName() shouldBe Component.text("saved AE box")
                f.scheduler.cancelAll()
                f.cleanupScheduler.pendingCount() shouldBe 0
                f.finds.close()
                f.items().size shouldBe 1
            }
        }
    }

    "server stop overtaking a retired generation settles its receipts before native cleanup is cancelled" {
        for (cancelAfterClose in listOf(false, true)) MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.revealMining()
                val state = f.host.state
                f.mining.onBlockBreak(BlockBreakEvent(f.host, f.player))
                f.host.type = Material.AIR
                val drop = BlockDropItemEvent(f.host, state, f.player, mutableListOf())
                f.finds.onBlockDrop(drop)
                val drain = f.finds.closeAsync()
                drain.isDone shouldBe false
                f.scheduler.cancelAll()
                drop.isCancelled = cancelAfterClose
                f.stopping = true
                f.finds.flushRetiredClaimsForServerStop()
                f.items().size shouldBe if (cancelAfterClose) 0 else 1
                f.finds.isFind(f.host, EnchantmentFindSource.MINING) shouldBe false
                // No cleanup tick occurs. The host cancels its queue during full shutdown.
                f.stopNativeCleanup()
                drain.isCompletedExceptionally shouldBe true
                f.finds.closeAsync()
                f.finds.flushRetiredClaimsForServerStop()
                f.items().size shouldBe if (cancelAfterClose) 0 else 1
                f.resolved.size shouldBe 1
                f.miningClaim() shouldBe 1.toByte()
            }
        }
    }

    "creative and piston removal consume a find without moving or multiplying its reward" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.revealMining()
                f.player.gameMode = GameMode.CREATIVE
                f.breakMining(f.host, emitDrop = false)
                f.items().size shouldBe 0
                f.finds.isFind(f.host, EnchantmentFindSource.MINING) shouldBe false
                f.miningClaim() shouldBe 1.toByte()
            }
        }
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.revealMining()
                val event = BlockPistonExtendEvent(f.origin, listOf(f.host), BlockFace.EAST)
                f.finds.onPistonExtend(event)
                // Same-material piston chains can leave stone at the old coordinate; the event still invalidates it.
                f.advance()
                f.finds.isFind(f.host, EnchantmentFindSource.MINING) shouldBe false
                f.items().size shouldBe 0
                f.miningClaim() shouldBe 1.toByte()
            }
        }
    }

    "normal and opted-in Creative claims honor final native break drop and no-drop vetoes" {
        for (mode in listOf(GameMode.SURVIVAL, GameMode.CREATIVE)) {
            for (veto in listOf("cancel-break", "cancel-drop", "no-drop", "missing-drop")) MockBukkitTestRuntime.open().use { paper ->
                EnchantmentFindFixture(paper).use { f ->
                    f.player.gameMode = mode
                    f.mining.close()
                    f.mining = f.newMining(EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), 0.0, 0.0,
                        mapOf("Finder" to EnchantmentFindChances(50.0, 50.0)), setOf("Finder")))
                    f.revealMining()
                    val state = f.host.state
                    val event = BlockBreakEvent(f.host, f.player)
                    f.mining.onBlockBreak(event)
                    f.host.type = Material.AIR
                    val drop = BlockDropItemEvent(f.host, state, f.player, mutableListOf())
                    if (veto != "missing-drop") f.finds.onBlockDrop(drop)
                    when (veto) {
                        "cancel-break" -> event.isCancelled = true
                        "cancel-drop" -> drop.isCancelled = true
                        "no-drop" -> event.isDropItems = false
                    }
                    f.advance()
                    f.items().size shouldBe 0
                    f.miningClaim() shouldBe 1.toByte()
                }
            }
        }
    }

    "a Creative claim cannot pay for an unbroken host even with an empty native drop event" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.player.gameMode = GameMode.CREATIVE
                f.mining.close()
                f.mining = f.newMining(EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), 0.0, 0.0,
                    mapOf("Finder" to EnchantmentFindChances(50.0, 50.0)), setOf("Finder")))
                f.revealMining()
                f.mining.onBlockBreak(BlockBreakEvent(f.host, f.player))
                f.finds.onBlockDrop(BlockDropItemEvent(f.host, f.host.state, f.player, mutableListOf()))
                f.advance()
                f.finds.isFind(f.host, EnchantmentFindSource.MINING) shouldBe true
                f.items().size shouldBe 0
            }
        }
    }

    "retired Creative claims retain captured eligibility exact item PDC and final cancellation without replay after reload" {
        for (veto in listOf("none", "cancel-break", "cancel-drop", "no-drop", "missing-drop")) MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.player.gameMode = GameMode.CREATIVE
                f.key.editMeta { it.persistentDataContainer.set(NamespacedKey("fixture", "native_key"), PersistentDataType.STRING, "saved-native-pdc") }
                f.mining.close()
                f.mining = f.newMining(EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), 0.0, 0.0,
                    mapOf("Finder" to EnchantmentFindChances(50.0, 50.0)), setOf("Finder")))
                f.revealMining(EnchantmentFindReward.KEY)
                val state = f.host.state
                val event = BlockBreakEvent(f.host, f.player)
                f.mining.onBlockBreak(event)
                f.host.type = Material.AIR
                val drop = BlockDropItemEvent(f.host, state, f.player, mutableListOf())
                if (veto != "missing-drop") f.finds.onBlockDrop(drop)
                val drain = f.finds.closeAsync()
                drain.isDone shouldBe false
                when (veto) {
                    "cancel-break" -> event.isCancelled = true
                    "cancel-drop" -> drop.isCancelled = true
                    "no-drop" -> event.isDropItems = false
                }
                f.scheduler.cancelAll()
                f.cleanupScheduler.tick()
                drain.join()
                f.items().size shouldBe if (veto == "none") 1 else 0
                if (veto == "none") f.items().single().itemStack.isSimilar(f.key) shouldBe true
                f.finds = f.newFinds()
                // A replacement generation has no Creative opt-in; the already captured claim stays settled.
                f.mining = f.newMining()
                f.finds.onChunkLoad(ChunkLoadEvent(f.host.chunk, false))
                f.advance()
                f.items().size shouldBe if (veto == "none") 1 else 0
                f.resolved.size shouldBe 1
                f.miningClaim() shouldBe 1.toByte()
            }
        }
    }

    "explosion water and other host changes retire visuals through bounded reconciliation without a reward" {
        MockBukkitTestRuntime.open().use { paper ->
            EnchantmentFindFixture(paper).use { f ->
                f.revealMining()
                f.host.type = Material.AIR
                f.advance()
                f.finds.isFind(f.host, EnchantmentFindSource.MINING) shouldBe false
                f.handles.forEach { verify(exactly = 1) { it.remove() } }
                f.items().size shouldBe 0
                f.miningClaim() shouldBe 1.toByte()
            }
        }
    }
})

/** Real chunk PDC/item serialization; only the shared packet renderer is recorded. */
internal class EnchantmentFindFixture(paper: MockBukkitTestRuntime) : AutoCloseable {
    val world = paper.addSimpleWorld("survival")
    val plugin = paper.createSimplePlugin("FindsTest")
    val player = paper.addPlayer("Finder")
    val scheduler = TestTaskScheduler()
    val cleanupScheduler = TestTaskScheduler()
    private val cleanupScope = LifecycleTaskScope(cleanupScheduler)
    private val cleanupQueue = BudgetedTaskQueue(cleanupScope)
    private val cleanup = object : ArcNativeCleanupService {
        override fun submit(owner: String, step: BooleanSupplier) = cleanupQueue.submit({ true }, step::getAsBoolean).thenApply<Void> { null }
    }
    val displays = mockk<PaperPacketDisplays>()
    val visualItems = mutableListOf<ItemStack>()
    val visualLocations = mutableListOf<Location>()
    val handles = mutableListOf<PacketItemDisplay>()
    val resolved = mutableListOf<EnchantmentFindReward>()
    val origin: Block = world.getBlockAt(4, 64, 6)
    val host: Block = world.getBlockAt(5, 64, 6)
    val box = ItemStack(Material.PAPER).apply { editMeta { it.displayName(Component.text("saved AE box")) } }
    val key = ItemStack(Material.TRIPWIRE_HOOK).apply { editMeta { it.displayName(Component.text("native daily key")) } }
    var resolve: (EnchantmentFindReward) -> CompletableFuture<ItemStack?> = {
        CompletableFuture.completedFuture(if (it == EnchantmentFindReward.LOOTBOX) box.clone() else key.clone())
    }
    var now = 1_000_000_000L
    var tick = 0
    var rolls = 0
    var natural = true
    var stopping = false
    var finds: EnchantmentVisibleFinds
    var mining: EnchantmentMiningLoot

    init {
        world.getChunkAt(0, 0).load()
        world.isChunkLoaded(0, 0) shouldBe true
        player.teleport(Location(world, 4.0, 64.0, 4.0))
        player.gameMode = GameMode.SURVIVAL
        player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_PICKAXE))
        origin.type = Material.STONE
        host.type = Material.STONE
        every { displays.spawnItem(any(), any()) } answers {
            visualLocations += firstArg<Location>().clone()
            visualItems += secondArg<ItemStack>().clone()
            mockk<PacketItemDisplay>(relaxed = true).also { handles += it }
        }
        every { displays.closeAsync() } returns CompletableFuture.completedFuture(null)
        finds = newFinds()
        mining = newMining()
    }

    fun newFinds() = EnchantmentVisibleFinds(plugin, { kind -> resolved += kind; resolve(kind) }, displays,
        LifecycleTaskScope(scheduler), { now }, { if (it.type == Material.PAPER) "ae" else null }, cleanup, { stopping })

    fun newMining(settings: EnchantmentMiningLootSettings = EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), 100.0)) =
        EnchantmentMiningLoot(settings, finds, { natural }, { rolls++; 0.0 }, { tick })

    fun farming(settings: EnchantmentFarmingLootSettings = EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), 100.0, 86_400)) =
        EnchantmentFarmingLoot(settings, finds, { rolls++; 0.0 }, { tick })

    fun mature(block: Block) {
        block.type = Material.WHEAT
        block.blockData = (Material.WHEAT.createBlockData() as Ageable).apply { age = maximumAge }
    }

    fun revealMining(reward: EnchantmentFindReward = EnchantmentFindReward.LOOTBOX) {
        finds.reveal(host, EnchantmentFindSource.MINING, BlockFace.WEST, reward)
        scheduler.executeImmediate()
        finds.isFind(host, EnchantmentFindSource.MINING) shouldBe true
    }

    fun breakMining(block: Block = origin, cancelDrop: Boolean = false, emitDrop: Boolean = true) =
        breakBlock(block, mining::onBlockBreak, cancelDrop, emitDrop)

    fun breakBlock(block: Block, listener: (BlockBreakEvent) -> Unit, cancelDrop: Boolean = false, emitDrop: Boolean = true) {
        val state = block.state
        val event = BlockBreakEvent(block, player)
        listener(event)
        block.type = Material.AIR
        if (emitDrop) finds.onBlockDrop(BlockDropItemEvent(block, state, player, mutableListOf()).also { it.isCancelled = cancelDrop })
        advance()
    }

    fun advance() { tick++; scheduler.tick(); scheduler.executeImmediate(); cleanupScheduler.tick() }
    fun items(): List<Item> = world.entities.filterIsInstance<Item>()
    fun miningClaim(): Byte? = host.chunk.persistentDataContainer.get(enchantmentMiningChunkClaimKey, PersistentDataType.BYTE)
    fun farmingClaim(): Long? = host.chunk.persistentDataContainer.get(enchantmentFarmingChunkClaimKey, PersistentDataType.LONG)
    fun stopNativeCleanup() { cleanupQueue.close(); cleanupScope.close() }

    override fun close() {
        mining.close(); finds.close(); cleanupScheduler.tick()
        stopNativeCleanup()
    }
}
