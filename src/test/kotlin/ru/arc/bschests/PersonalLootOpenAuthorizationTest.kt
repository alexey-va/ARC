package ru.arc.bschests

import com.jeff_media.customblockdata.CustomBlockData
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.World
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.config.Config
import ru.arc.listeners.BlockListener
import ru.arc.repository.CachedRepository
import ru.arc.repository.RepoResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class PersonalLootOpenAuthorizationTest : TestBase() {
    private var openScope: CoroutineScope? = null
    private lateinit var repository: CachedRepository<CustomLootData>
    private lateinit var uuidKey: NamespacedKey
    private lateinit var playerListKey: NamespacedKey
    private lateinit var chestWorld: World

    @AfterEach
    fun clearOpenHarness() {
        setModuleField("repo", null)
        setModuleField("repositoryScope", null)
        openScope?.cancel()
        openScope = null
    }

    @Test
    fun `cancelled event is ignored before repository work and listener runs after access gates`() {
        installHarness()
        val player = server.addPlayer()
        val block = markedChest(UUID.randomUUID())
        val event = openEvent(player, block, cancelled = true)

        PersonalLootModule.processChestOpen(event) { _, _ -> true }

        coVerify(exactly = 0) { repository.getOrCreate(any(), any()) }
        verify(exactly = 0) { event.inventory }
        val annotation = BlockListener::class.java.getMethod("onChestClick", InventoryOpenEvent::class.java)
            .getAnnotation(EventHandler::class.java)
        annotation.priority shouldBe EventPriority.HIGHEST
        annotation.ignoreCancelled shouldBe true
    }

    @Test
    fun `denied or failing protection stops before the first personal loot lookup`() {
        installHarness()
        val player = server.addPlayer()
        val block = markedChest(UUID.randomUUID())

        val denied = openEvent(player, block)
        PersonalLootModule.processChestOpen(denied) { _, _ -> false }
        verify { denied.isCancelled = true }

        val failed = openEvent(player, block)
        PersonalLootModule.processChestOpen(failed) { _, _ -> error("protection API unavailable") }
        verify { failed.isCancelled = true }

        coVerify(exactly = 0) { repository.getOrCreate(any(), any()) }
        coVerify(exactly = 0) { repository.get(any()) }
    }

    @Test
    fun `permission revoked while repository lookup waits cannot mutate player state or open loot`() = runBlocking {
        installHarness()
        val player = server.addPlayer()
        val chestUuid = UUID.randomUUID()
        val block = markedChest(chestUuid)
        val started = CompletableDeferred<Unit>()
        val loaded = CompletableDeferred<RepoResult<CustomLootData>>()
        coEvery { repository.getOrCreate(any(), any()) } coAnswers {
            started.complete(Unit)
            loaded.await()
        }
        val checks = AtomicInteger()

        val event = openEvent(player, block)
        val originalInventorySize = player.openInventory.topInventory?.size ?: 0
        PersonalLootModule.processChestOpen(event) { _, _ -> checks.incrementAndGet() == 1 }
        withTimeout(3_000) { started.await() }
        loaded.complete(RepoResult.success(loot(player.uniqueId, chestUuid)))
        server.scheduler.performTicks(1)

        checks.get() shouldBe 2
        readPlayerList(block) shouldBe ""
        (player.openInventory.topInventory?.size ?: 0) shouldBe originalInventorySize
        player.openInventory.topInventory?.contents.orEmpty().filterNotNull().none { it.type == Material.DIAMOND } shouldBe true
        coVerify(exactly = 1) { repository.getOrCreate(any(), any()) }
        verify(exactly = 0) { repository.markDirty(any()) }
    }

    @Test
    fun `allowed ordinary and key-authorized locked chest opens viewer loot`() {
        installHarness()
        val player = server.addPlayer()
        val ordinaryUuid = UUID.randomUUID()
        val ordinary = markedChest(ordinaryUuid, 4)
        val lockedUuid = UUID.randomUUID()
        val locked = markedChest(lockedUuid, 8)
        coEvery { repository.getOrCreate(any(), any()) } coAnswers {
            val lootId = invocation.args[0] as String
            val playerUuid = UUID.fromString(lootId.substringBefore(":::"))
            val chestUuid = UUID.fromString(lootId.substringAfter(":::"))
            RepoResult.success(loot(playerUuid, chestUuid))
        }

        for (block in listOf(ordinary, locked)) {
            val event = openEvent(player, block, lockedByKey = block === locked)
            PersonalLootModule.processChestOpen(event) { _, _ -> true }
            server.scheduler.performTicks(1)
            verify { event.isCancelled = true }
            readPlayerList(block) shouldBe player.uniqueId.toString()
            player.openInventory.topInventory.size shouldBe 27
            player.openInventory.topInventory.contents.filterNotNull().any { it.type == Material.DIAMOND } shouldBe true
        }

        coVerify(exactly = 2) { repository.getOrCreate(any(), any()) }
    }

    @Test
    fun `legacy single marker double chest checks both halves and rejects denied or conflicting companion`() {
        installHarness()
        val player = server.addPlayer()
        val chestUuid = UUID.randomUUID()
        val (legacyLeft, legacyRight, legacyHolder) = doubleChest(chestUuid, null, 12)
        coEvery { repository.getOrCreate(any(), any()) } coAnswers {
            val lootId = invocation.args[0] as String
            RepoResult.success(loot(UUID.fromString(lootId.substringBefore(":::")), chestUuid))
        }

        val authorizedHalves = mutableListOf<org.bukkit.block.Block>()
        PersonalLootModule.processChestOpen(openEvent(player, legacyLeft, physicalHolder = legacyHolder)) { _, block ->
            authorizedHalves += block
            true
        }
        server.scheduler.performTicks(1)
        authorizedHalves.toSet() shouldBe setOf(legacyLeft, legacyRight)
        readPlayerList(legacyLeft) shouldBe player.uniqueId.toString()
        readPlayerList(legacyRight) shouldBe player.uniqueId.toString()

        val deniedUuid = UUID.randomUUID()
        val (deniedLeft, deniedRight, deniedHolder) = doubleChest(deniedUuid, null, 20)
        val deniedHalves = mutableListOf<org.bukkit.block.Block>()
        PersonalLootModule.processChestOpen(openEvent(player, deniedLeft, physicalHolder = deniedHolder)) { _, block ->
            deniedHalves += block
            block != deniedRight
        }
        deniedHalves shouldBe listOf(deniedLeft, deniedRight)

        val (conflictLeft, _, conflictHolder) = doubleChest(UUID.randomUUID(), UUID.randomUUID(), 28)
        PersonalLootModule.processChestOpen(openEvent(player, conflictLeft, physicalHolder = conflictHolder)) { _, _ -> true }

        coVerify(exactly = 1) { repository.getOrCreate(any(), any()) }
    }

    @Test
    fun `world switch during lookup prevents completion effects`() = runBlocking {
        installHarness()
        val player = server.addPlayer()
        val chestUuid = UUID.randomUUID()
        val block = markedChest(chestUuid)
        val started = CompletableDeferred<Unit>()
        val loaded = CompletableDeferred<RepoResult<CustomLootData>>()
        coEvery { repository.getOrCreate(any(), any()) } coAnswers {
            started.complete(Unit)
            loaded.await()
        }
        val checks = AtomicInteger()

        PersonalLootModule.processChestOpen(openEvent(player, block)) { _, _ ->
            checks.incrementAndGet()
            true
        }
        withTimeout(3_000) { started.await() }
        val originalInventorySize = player.openInventory.topInventory?.size ?: 0
        player.teleport(server.addSimpleWorld("loot-auth-other-world").spawnLocation)
        loaded.complete(RepoResult.success(loot(player.uniqueId, chestUuid)))
        server.scheduler.performTicks(1)

        checks.get() shouldBe 1
        readPlayerList(block) shouldBe ""
        (player.openInventory.topInventory?.size ?: 0) shouldBe originalInventorySize
        player.openInventory.topInventory?.contents.orEmpty().filterNotNull().none { it.type == Material.DIAMOND } shouldBe true
        verify(exactly = 0) { repository.markDirty(any()) }
    }

    private fun installHarness() {
        chestWorld = server.addSimpleWorld("loot-auth-${UUID.randomUUID()}")
        repository = mockk(relaxed = true)
        openScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        uuidKey = NamespacedKey(plugin, "ploot_uuid")
        playerListKey = NamespacedKey(plugin, "ploot")
        setModuleField("repo", repository)
        setModuleField("repositoryScope", openScope)
        setModuleField("config", mockk<Config>(relaxed = true))
        setModuleField("key", playerListKey)
        setModuleField("uuidKey", uuidKey)
        setModuleField("poolKey", NamespacedKey(plugin, "ploot_pool"))
        setModuleField("breakKey", NamespacedKey(plugin, "ploot_break"))
        setModuleField("inventories", setOf(InventoryType.CHEST))
        setModuleField("maxPlayers", 5)
        setModuleField("useBsLoot", true)
    }

    private fun markedChest(chestUuid: UUID, x: Int = 4) = chestBlock(x, chestUuid)

    private fun chestBlock(x: Int, chestUuid: UUID?) = chestWorld.getBlockAt(x, 64, 4)
        .apply {
            type = Material.CHEST
            CustomBlockData(this, plugin).apply {
                chestUuid?.let { set(uuidKey, PersistentDataType.STRING, it.toString()) }
                set(playerListKey, PersistentDataType.STRING, "")
                set(NamespacedKey(plugin, "ploot_pool"), PersistentDataType.STRING, "default")
            }
        }

    private fun openEvent(
        player: Player,
        block: org.bukkit.block.Block,
        lockedByKey: Boolean = false,
        cancelled: Boolean = false,
        physicalHolder: InventoryHolder? = null,
    ): InventoryOpenEvent {
        if (player.world.uid != block.world.uid) player.teleport(block.world.spawnLocation)
        val holder: InventoryHolder = physicalHolder ?: if (lockedByKey) mockk<Chest>(relaxed = true).also {
            every { it.block } returns block
            every { it.blockData } returns block.blockData
            every { it.isLocked } returns true
        } else block.state as Chest
        val inventory = mockk<Inventory>(relaxed = true).also {
            every { it.type } returns InventoryType.CHEST
            every { it.holder } returns holder
            every { it.location } returns block.location
        }
        return mockk<InventoryOpenEvent>(relaxed = true).also {
            every { it.isCancelled } returns cancelled
            every { it.inventory } returns inventory
            every { it.player } returns player
        }
    }

    private fun doubleChest(
        leftUuid: UUID,
        rightUuid: UUID?,
        x: Int,
    ): Triple<org.bukkit.block.Block, org.bukkit.block.Block, InventoryHolder> {
        val left = chestBlock(x, leftUuid)
        val right = chestBlock(x + 1, rightUuid)
        val leftData = left.blockData as ChestData
        val rightData = right.blockData as ChestData
        leftData.type = ChestData.Type.LEFT
        rightData.type = ChestData.Type.RIGHT
        rightData.facing = leftData.facing
        left.blockData = leftData
        right.blockData = rightData
        val holder = mockk<DoubleChest>(relaxed = true).also {
            every { it.leftSide } returns left.state as Chest
            every { it.rightSide } returns right.state as Chest
        }
        return Triple(left, right, holder)
    }

    private fun loot(playerUuid: UUID, chestUuid: UUID) = CustomLootData(
        playerUuid = playerUuid,
        chestUuid = chestUuid,
        items = ru.arc.network.repos.ItemList().apply { add(ItemStack(Material.DIAMOND)) },
        filled = true,
        debrisAdded = true,
    )

    private fun readPlayerList(block: org.bukkit.block.Block): String =
        CustomBlockData(block, plugin).get(playerListKey, PersistentDataType.STRING).orEmpty()

    private fun setModuleField(name: String, value: Any?) {
        PersonalLootModule.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(PersonalLootModule, value)
    }
}
