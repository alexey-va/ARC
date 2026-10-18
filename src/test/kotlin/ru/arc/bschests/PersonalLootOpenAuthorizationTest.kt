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
import ru.arc.config.ConfigManager
import ru.arc.listeners.BlockListener
import ru.arc.repository.CachedRepository
import ru.arc.repository.InMemoryStorage
import ru.arc.repository.RepoResult
import ru.arc.repository.Storage
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class PersonalLootOpenAuthorizationTest : TestBase() {
    private var openScope: CoroutineScope? = null
    private lateinit var repository: CachedRepository<CustomLootData>
    private lateinit var uuidKey: NamespacedKey
    private lateinit var playerListKey: NamespacedKey
    private lateinit var chestWorld: World
    private lateinit var identityStore: PersonalLootIdentities
    private lateinit var identityBacking: InMemoryStorage<PersonalLootIdentity>

    @AfterEach
    fun clearOpenHarness() {
        setModuleField("repo", null)
        setModuleField("repositoryScope", null)
        setModuleField("identities", null)
        if (::identityStore.isInitialized) identityStore.tasks.close()
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
    fun `permission revoked while repository lookup waits cannot mutate player state or open loot`() = runBlocking<Unit> {
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
        PersonalLootModule.processChestOpen(event) { _, _ -> checks.incrementAndGet() <= 2 }
        server.scheduler.performTicks(5)
        withTimeout(3_000) { started.await() }
        loaded.complete(RepoResult.success(loot(player.uniqueId, chestUuid)))
        server.scheduler.performTicks(5)

        checks.get() shouldBe 3
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
            server.scheduler.performTicks(5)
            verify { event.isCancelled = true }
            readPlayerList(block) shouldBe player.uniqueId.toString()
            player.openInventory.topInventory.size shouldBe 27
            player.openInventory.topInventory.contents.filterNotNull().any { it.type == Material.DIAMOND } shouldBe true
        }

        coVerify(exactly = 2) { repository.getOrCreate(any(), any()) }
    }

    @Test
    fun `legacy single marker double chest checks both halves and rejects denied companion`() {
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
        server.scheduler.performTicks(5)
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

        coVerify(exactly = 1) { repository.getOrCreate(any(), any()) }
    }

    @Test
    fun `world switch during lookup prevents completion effects`() = runBlocking<Unit> {
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
        server.scheduler.performTicks(5)
        withTimeout(3_000) { started.await() }
        val originalInventorySize = player.openInventory.topInventory?.size ?: 0
        player.teleport(server.addSimpleWorld("loot-auth-other-world").spawnLocation)
        loaded.complete(RepoResult.success(loot(player.uniqueId, chestUuid)))
        server.scheduler.performTicks(5)

        checks.get() shouldBe 2
        readPlayerList(block) shouldBe ""
        (player.openInventory.topInventory?.size ?: 0) shouldBe originalInventorySize
        player.openInventory.topInventory?.contents.orEmpty().filterNotNull().none { it.type == Material.DIAMOND } shouldBe true
        verify(exactly = 0) { repository.markDirty(any()) }
    }

    @Test
    fun `old chest marker generates custom loot once for new viewer and discards physical template`() {
        installHarness()
        setModuleField("useBsLoot", false)
        val generator = mockk<ChestGenerator>()
        setModuleField("chestGenerator", generator)
        val player = server.addPlayer()
        val chestUuid = UUID.randomUUID()
        val block = markedChest(chestUuid)
        val stored = CustomLootData.create(player.uniqueId, chestUuid)
        (block.state as Chest).inventory.addItem(ItemStack(Material.NETHERITE_INGOT, 32))
        every { generator.generate(player, "default") } returns ru.arc.network.repos.ItemList().apply {
            add(ItemStack(Material.BREAD, 2))
        }
        coEvery { repository.getOrCreate(any(), any()) } returns RepoResult.success(stored)

        repeat(2) {
            PersonalLootModule.processChestOpen(openEvent(player, block)) { _, _ -> true }
            server.scheduler.performTicks(5)
        }

        verify(exactly = 1) { generator.generate(player, "default") }
        stored.filled shouldBe true
        stored.snapshotItems().filterNotNull().any { it.type == Material.BREAD && it.amount == 2 } shouldBe true
        stored.snapshotItems().filterNotNull().none { it.type == Material.NETHERITE_INGOT } shouldBe true
        (block.state as Chest).inventory.contents.filterNotNull().isEmpty() shouldBe true
        readPlayerList(block) shouldBe player.uniqueId.toString()
    }

    @Test
    fun `custom generator failure leaves registration and physical template unchanged`() {
        installHarness()
        setModuleField("useBsLoot", false)
        val generator = mockk<ChestGenerator>()
        setModuleField("chestGenerator", generator)
        val player = server.addPlayer()
        val chestUuid = UUID.randomUUID()
        val block = markedChest(chestUuid)
        val stored = CustomLootData.create(player.uniqueId, chestUuid)
        (block.state as Chest).inventory.addItem(ItemStack(Material.BREAD))
        every { generator.generate(player, "default") } throws IllegalArgumentException("pool missing")
        coEvery { repository.getOrCreate(any(), any()) } returns RepoResult.success(stored)

        PersonalLootModule.processChestOpen(openEvent(player, block)) { _, _ -> true }
        server.scheduler.performTicks(5)

        stored.needsItems() shouldBe true
        readPlayerList(block) shouldBe ""
        (block.state as Chest).inventory.contents.filterNotNull().single().type shouldBe Material.BREAD
        verify(exactly = 0) { repository.markDirty(any()) }
    }

    @Test
    fun `existing partial loot survives pool switch while exhausted or missing claimed record never rerolls`() {
        installHarness()
        setModuleField("useBsLoot", false)
        val generator = mockk<ChestGenerator>()
        setModuleField("chestGenerator", generator)
        val player = server.addPlayer()
        val chestUuid = UUID.randomUUID()
        val block = markedChest(chestUuid)
        val stored = loot(player.uniqueId, chestUuid)
        coEvery { repository.getOrCreate(any(), any()) } returns RepoResult.success(stored)

        PersonalLootModule.processChestOpen(openEvent(player, block)) { _, _ -> true }
        server.scheduler.performTicks(5)
        stored.snapshotItems().filterNotNull().single().type shouldBe Material.DIAMOND
        stored.items.clear()
        PersonalLootModule.processChestOpen(openEvent(player, block)) { _, _ -> true }
        server.scheduler.performTicks(5)
        stored.isExhausted() shouldBe true
        stored.filled = false
        PersonalLootModule.processChestOpen(openEvent(player, block)) { _, _ -> true }
        server.scheduler.performTicks(5)

        verify(exactly = 0) { generator.generate(any(), any()) }
        stored.needsItems() shouldBe true
    }

    @Test
    fun `provider refill preserves chest identity and registered viewers`() {
        installHarness()
        val chestUuid = UUID.randomUUID()
        val playerUuid = UUID.randomUUID()
        val block = markedChest(chestUuid)
        CustomBlockData(block, plugin).set(playerListKey, PersistentDataType.STRING, playerUuid.toString())

        PersonalLootModule.processChestGen(block)

        CustomBlockData(block, plugin).get(uuidKey, PersistentDataType.STRING) shouldBe chestUuid.toString()
        readPlayerList(block) shouldBe playerUuid.toString()
    }

    @Test
    fun `conflicting halves preserve both full leftovers and share claims after split`() {
        installHarness()
        setModuleField("useBsLoot", false)
        val generator = mockk<ChestGenerator>()
        setModuleField("chestGenerator", generator)
        every { generator.generate(any(), any()) } returns ru.arc.network.repos.ItemList().apply { add(ItemStack(Material.BREAD, 2)) }
        val player = server.addPlayer()
        val fresh = server.addPlayer()
        val leftUuid = UUID(0, 1)
        val rightUuid = UUID(0, 2)
        val (left, right, holder) = doubleChest(leftUuid, rightUuid, 12)
        val oldClaim = UUID.randomUUID()
        writePlayers(left, setOf(player.uniqueId))
        writePlayers(right, setOf(oldClaim, player.uniqueId))
        val firstLoot = loot(player.uniqueId, leftUuid).apply { items.clear(); repeat(54) { items.add(ItemStack(Material.DIAMOND)) } }
        val secondLoot = loot(player.uniqueId, rightUuid).apply { items.clear(); repeat(54) { items.add(ItemStack(Material.EMERALD)) } }
        val records = installLootRecords(firstLoot, secondLoot)

        PersonalLootModule.processChestOpen(openEvent(player, right, physicalHolder = holder)) { _, _ -> true }
        server.scheduler.performTicks(5)
        readUuid(left) shouldBe leftUuid
        readUuid(right) shouldBe leftUuid
        parsePersonalLootPlayers(readPlayerList(left)) shouldBe setOf(player.uniqueId, oldClaim)
        player.openInventory.topInventory.contents.filterNotNull().map { it.type }.toSet() shouldBe setOf(Material.DIAMOND)
        firstLoot.compareAndSetItems(firstLoot.snapshotItems(), List(54) { null }) shouldBe true
        PersonalLootModule.save(firstLoot)
        PersonalLootModule.preview(player.uniqueId, leftUuid, listOf(left, right)).let {
            (it as PersonalLootPreview.Contents).items.filterNotNull().map { stack -> stack.type }.toSet() shouldBe setOf(Material.EMERALD)
        }
        PersonalLootModule.processChestOpen(openEvent(player, left, physicalHolder = holder)) { _, _ -> true }
        server.scheduler.performTicks(5)
        player.openInventory.topInventory.contents.filterNotNull().map { it.type }.toSet() shouldBe setOf(Material.EMERALD)
        records[firstLoot.id()] shouldBe firstLoot
        records[secondLoot.id()] shouldBe secondLoot
        verify(exactly = 0) { generator.generate(any(), any()) }

        PersonalLootModule.processChestOpen(openEvent(fresh, left, physicalHolder = holder)) { _, _ -> true }
        server.scheduler.performTicks(5)
        verify(exactly = 1) { generator.generate(fresh, any()) }
        records.keys.count { it.startsWith(fresh.uniqueId.toString()) } shouldBe 1
        val claims = setOf(player.uniqueId, oldClaim, fresh.uniqueId)
        parsePersonalLootPlayers(readPlayerList(right)) shouldBe claims
        setModuleField("maxPlayers", 3)
        split(left, right)
        // A stale, separately saved world half still resolves the durable group after a restart.
        CustomBlockData(right, plugin).set(uuidKey, PersistentDataType.STRING, rightUuid.toString())
        writePlayers(right, setOf(oldClaim))
        replaceIdentityStore(identityBacking)
        val outsider = server.addPlayer()
        PersonalLootModule.processChestOpen(openEvent(outsider, right)) { _, _ -> true }
        server.scheduler.performTicks(5)
        parsePersonalLootPlayers(readPlayerList(right)) shouldBe claims
        readUuid(right) shouldBe leftUuid
        verify(exactly = 1) { generator.generate(any(), any()) }
    }

    @Test
    fun `same UUID halves union inconsistent historical claims before admission`() {
        installHarness()
        val chestUuid = UUID.randomUUID()
        val (left, right, holder) = doubleChest(chestUuid, chestUuid, 12)
        val claims = setOf(UUID.randomUUID(), UUID.randomUUID())
        writePlayers(left, setOf(claims.first()))
        writePlayers(right, setOf(claims.last()))
        setModuleField("maxPlayers", 2)
        val records = installLootRecords()
        val player = server.addPlayer()
        val beforeSize = player.openInventory.topInventory?.size ?: 0

        PersonalLootModule.processChestOpen(openEvent(player, left, physicalHolder = holder)) { _, _ -> true }
        server.scheduler.performTicks(5)

        parsePersonalLootPlayers(readPlayerList(left)) shouldBe claims
        parsePersonalLootPlayers(readPlayerList(right)) shouldBe claims
        (player.openInventory.topInventory?.size ?: 0) shouldBe beforeSize
        records.values.single().needsItems() shouldBe true
        identityBacking.get(chestUuid.toString())!!.players.toSet() shouldBe claims.map(UUID::toString).toSet()
    }

    @Test
    fun `duplicate and concurrent opens wait for durable reward and cannot exceed shared admission cap`() = runBlocking<Unit> {
        installHarness()
        setModuleField("useBsLoot", false)
        setModuleField("maxPlayers", 1)
        val generator = mockk<ChestGenerator>()
        setModuleField("chestGenerator", generator)
        every { generator.generate(any(), any()) } returns ru.arc.network.repos.ItemList().apply { add(ItemStack(Material.BREAD)) }
        installLootRecords()
        val player = server.addPlayer()
        val other = server.addPlayer()
        val (left, right, holder) = doubleChest(UUID(0, 1), UUID(0, 2), 12)
        val started = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<RepoResult<Unit>>()
        coEvery { repository.saveDirty() } coAnswers { started.complete(Unit); saved.await() }
        val beforeSize = player.openInventory.topInventory?.size ?: 0

        PersonalLootModule.processChestOpen(openEvent(player, left, physicalHolder = holder)) { _, _ -> true }
        PersonalLootModule.processChestOpen(openEvent(player, left, physicalHolder = holder)) { _, _ -> true }
        PersonalLootModule.processChestOpen(openEvent(other, right, physicalHolder = holder)) { _, _ -> true }
        server.scheduler.performTicks(5)
        withTimeout(3_000) { started.await() }
        readPlayerList(left) shouldBe ""
        readUuid(right) shouldBe UUID(0, 2)
        (player.openInventory.topInventory?.size ?: 0) shouldBe beforeSize
        coVerify(exactly = 1) { repository.getOrCreate(any(), any()) }
        identityBacking.get(UUID(0, 2).toString())!!.members.size shouldBe 2
        saved.complete(RepoResult.success(Unit))
        server.scheduler.performTicks(10)

        parsePersonalLootPlayers(readPlayerList(left)) shouldBe setOf(player.uniqueId)
        parsePersonalLootPlayers(readPlayerList(right)) shouldBe setOf(player.uniqueId)
        verify(exactly = 1) { generator.generate(any(), any()) }
        other.openInventory.topInventory?.contents.orEmpty().filterNotNull().none { it.type == Material.BREAD } shouldBe true
    }

    @Test
    fun `unknown alias acknowledgement keeps originals and split restart reuses the already saved reward`() {
        installHarness()
        setModuleField("useBsLoot", false)
        val generator = mockk<ChestGenerator>()
        setModuleField("chestGenerator", generator)
        every { generator.generate(any(), any()) } returns ru.arc.network.repos.ItemList().apply { add(ItemStack(Material.BREAD)) }
        val records = installLootRecords()
        val player = server.addPlayer()
        val (left, right, holder) = doubleChest(UUID(0, 1), UUID(0, 2), 12)
        var writes = 0
        val unreliable = object : Storage<PersonalLootIdentity> by identityBacking {
            override suspend fun saveMany(entities: Collection<PersonalLootIdentity>): RepoResult<Unit> {
                identityBacking.saveMany(entities).getOrThrow()
                writes++
                return if (writes == 2) RepoResult.error("Acknowledgement lost after atomic commit") else RepoResult.success(Unit)
            }
        }
        replaceIdentityStore(unreliable)
        val beforeSize = player.openInventory.topInventory?.size ?: 0

        PersonalLootModule.processChestOpen(openEvent(player, left, physicalHolder = holder)) { _, _ -> true }
        server.scheduler.performTicks(5)
        (player.openInventory.topInventory?.size ?: 0) shouldBe beforeSize
        readUuid(left) shouldBe UUID(0, 1)
        readUuid(right) shouldBe UUID(0, 2)
        readPlayerList(right) shouldBe ""
        records.values.single().filled shouldBe true
        split(left, right)
        replaceIdentityStore(identityBacking)
        PersonalLootModule.processChestOpen(openEvent(player, right)) { _, _ -> true }
        server.scheduler.performTicks(5)

        readUuid(right) shouldBe UUID(0, 1)
        player.openInventory.topInventory.contents.filterNotNull().any { it.type == Material.BREAD } shouldBe true
        verify(exactly = 1) { generator.generate(any(), any()) }
        // Expiry/loss of a claimed record never turns durable aliases into a fresh roll.
        records.clear()
        PersonalLootModule.processChestOpen(openEvent(player, right)) { _, _ -> true }
        server.scheduler.performTicks(5)
        verify(exactly = 1) { generator.generate(any(), any()) }
        records.values.single().needsItems() shouldBe true
    }

    @Test
    fun `failed base alias write prevents reward generation and allows safe retry`() {
        installHarness()
        setModuleField("useBsLoot", false)
        val generator = mockk<ChestGenerator>()
        setModuleField("chestGenerator", generator)
        every { generator.generate(any(), any()) } returns ru.arc.network.repos.ItemList().apply { add(ItemStack(Material.BREAD)) }
        val records = installLootRecords()
        val player = server.addPlayer()
        val (left, right, holder) = doubleChest(UUID(0, 1), UUID(0, 2), 12)
        identityBacking.failOnSave = true

        PersonalLootModule.processChestOpen(openEvent(player, left, physicalHolder = holder)) { _, _ -> true }
        server.scheduler.performTicks(5)
        records shouldBe emptyMap()
        verify(exactly = 0) { generator.generate(any(), any()) }
        readUuid(right) shouldBe UUID(0, 2)
        identityBacking.failOnSave = false
        PersonalLootModule.processChestOpen(openEvent(player, left, physicalHolder = holder)) { _, _ -> true }
        server.scheduler.performTicks(5)
        verify(exactly = 1) { generator.generate(any(), any()) }
        readUuid(right) shouldBe UUID(0, 1)
    }

    @Test
    fun `permission revoked during persistence and retired lifecycle cannot apply late effects`() = runBlocking<Unit> {
        installHarness()
        val player = server.addPlayer()
        val chestUuid = UUID.randomUUID()
        val block = markedChest(chestUuid)
        installLootRecords(loot(player.uniqueId, chestUuid))
        val saved = CompletableDeferred<RepoResult<Unit>>()
        val started = CompletableDeferred<Unit>()
        coEvery { repository.saveDirty() } coAnswers { started.complete(Unit); saved.await() }
        var allowed = true
        val beforeSize = player.openInventory.topInventory?.size ?: 0
        PersonalLootModule.processChestOpen(openEvent(player, block)) { _, _ -> allowed }
        server.scheduler.performTicks(5)
        withTimeout(3_000) { started.await() }
        allowed = false
        saved.complete(RepoResult.success(Unit))
        server.scheduler.performTicks(5)
        readPlayerList(block) shouldBe ""
        (player.openInventory.topInventory?.size ?: 0) shouldBe beforeSize

        val late = CompletableDeferred<RepoResult<Unit>>()
        coEvery { repository.saveDirty() } coAnswers { late.await() }
        allowed = true
        PersonalLootModule.processChestOpen(openEvent(player, block)) { _, _ -> allowed }
        server.scheduler.performTicks(5)
        identityStore.tasks.close()
        setModuleField("identities", null)
        late.complete(RepoResult.success(Unit))
        server.scheduler.performTicks(5)
        readPlayerList(block) shouldBe ""
        (player.openInventory.topInventory?.size ?: 0) shouldBe beforeSize
        identityStore.admit(player.uniqueId) shouldBe true
        identityStore.release(player.uniqueId)
    }

    @Test
    fun `generation repairs existing pair without rolling or changing pools and break counters`() {
        installHarness()
        val leftUuid = UUID(0, 1)
        val rightUuid = UUID(0, 2)
        val (left, right, _) = doubleChest(leftUuid, rightUuid, 12)
        val claims = setOf(UUID.randomUUID(), UUID.randomUUID())
        writePlayers(left, setOf(claims.first()))
        writePlayers(right, setOf(claims.last()))
        val poolKey = NamespacedKey(plugin, "ploot_pool")
        val breakKey = NamespacedKey(plugin, "ploot_break")
        CustomBlockData(right, plugin).set(poolKey, PersistentDataType.STRING, "generic_bs")
        CustomBlockData(right, plugin).set(breakKey, PersistentDataType.INTEGER, 2)

        PersonalLootModule.processChestGen(left)
        server.scheduler.performTicks(5)

        readUuid(left) shouldBe leftUuid
        readUuid(right) shouldBe leftUuid
        parsePersonalLootPlayers(readPlayerList(right)) shouldBe claims
        CustomBlockData(right, plugin).get(poolKey, PersistentDataType.STRING) shouldBe "generic_bs"
        CustomBlockData(right, plugin).get(breakKey, PersistentDataType.INTEGER) shouldBe 2
        coVerify(exactly = 0) { repository.getOrCreate(any(), any()) }
    }

    @Test
    fun `malformed UUID missing claims conflicting pool or invalid native pair fails closed`() {
        installHarness()
        val player = server.addPlayer()
        val (left, right, holder) = doubleChest(UUID(0, 1), UUID(0, 2), 12)
        val data = CustomBlockData(right, plugin)
        data.set(uuidKey, PersistentDataType.STRING, "broken-uuid")
        PersonalLootModule.processChestOpen(openEvent(player, left, physicalHolder = holder)) { _, _ -> true }
        data.set(uuidKey, PersistentDataType.STRING, UUID(0, 2).toString())
        data.remove(playerListKey)
        PersonalLootModule.processChestOpen(openEvent(player, left, physicalHolder = holder)) { _, _ -> true }
        data.set(playerListKey, PersistentDataType.STRING, "")
        data.set(NamespacedKey(plugin, "ploot_pool"), PersistentDataType.STRING, "different_custom_pool")
        PersonalLootModule.processChestOpen(openEvent(player, left, physicalHolder = holder)) { _, _ -> true }
        data.set(NamespacedKey(plugin, "ploot_pool"), PersistentDataType.STRING, "default")
        val rightData = right.blockData as ChestData
        rightData.type = ChestData.Type.SINGLE
        right.blockData = rightData
        PersonalLootModule.processChestGen(left)
        server.scheduler.performTicks(5)

        identityBacking.size() shouldBe 0
        coVerify(exactly = 0) { repository.getOrCreate(any(), any()) }
        readUuid(right) shouldBe UUID(0, 2)
    }

    @Test
    fun `server scoped personal loot repository never subscribes to delayed self updates`() {
        val redis = mockk<ru.arc.redis.RedisOperations>(relaxed = true)
        every { redis.loadMap("arc.loot-single-owner-ploot") } returns CompletableFuture.completedFuture(emptyMap())
        PersonalLootModule.prepareOnWorker(redis, "loot-single-owner")
        try {
            verify(exactly = 1) { redis.loadMap("arc.loot-single-owner-ploot") }
            verify(exactly = 0) { redis.registerChannelUnique(any(), any()) }
            verify(exactly = 0) { redis.publish(any(), any()) }
        } finally {
            PersonalLootModule.discardPreparedOnWorker()
        }
    }

    @Test
    fun `identity storage rejects present null and incomplete JSON instead of treating them as no claims`() = runBlocking<Unit> {
        val chestUuid = UUID.randomUUID()
        var payload: String? = null
        val redis = mockk<ru.arc.redis.RedisOperations>()
        every { redis.loadMapEntries("test-identities", chestUuid.toString()) } answers {
            CompletableFuture.completedFuture(listOf(payload))
        }
        val store = PersonalLootIdentities(personalLootIdentityStorage(redis, "test-identities"))
        try {
            val markers = mapOf(chestUuid to emptySet<UUID>())
            for (malformed in listOf(
                "null",
                "[]",
                """{"chestUuid":"$chestUuid","members":["$chestUuid"],"version":1}""",
                """{"chestUuid":"$chestUuid","members":["$chestUuid"],"players":[]}""",
            )) {
                payload = malformed
                runCatching { store.resolve(markers) }.isFailure shouldBe true
            }
            payload = null
            store.resolve(markers) shouldBe PersonalLootGroup(listOf(chestUuid), emptySet())
            payload = """{"chestUuid":"$chestUuid","members":["$chestUuid"],"players":[],"version":1}"""
            store.resolve(markers) shouldBe PersonalLootGroup(listOf(chestUuid), emptySet())
        } finally {
            store.tasks.close()
        }
    }

    private fun installLootRecords(vararg initial: CustomLootData): MutableMap<String, CustomLootData> {
        val records = initial.associateBy { it.id() }.toMutableMap()
        coEvery { repository.getOrCreate(any(), any()) } coAnswers {
            val id = invocation.args[0] as String
            @Suppress("UNCHECKED_CAST")
            val create = invocation.args[1] as () -> CustomLootData
            RepoResult.success(records.getOrPut(id, create))
        }
        coEvery { repository.get(any()) } coAnswers { RepoResult.success(records[invocation.args[0] as String]) }
        every { repository.getNow(any()) } answers { records[invocation.args[0] as String] }
        every { repository.markDirty(any()) } answers {
            val record = invocation.args[0] as CustomLootData
            records[record.id()] = record
        }
        return records
    }

    private fun replaceIdentityStore(storage: Storage<PersonalLootIdentity>) {
        if (::identityStore.isInitialized) identityStore.tasks.close()
        identityStore = PersonalLootIdentities(storage)
        setModuleField("identities", identityStore)
    }

    private fun writePlayers(block: org.bukkit.block.Block, players: Set<UUID>) {
        CustomBlockData(block, plugin).set(playerListKey, PersistentDataType.STRING, players.joinToString(":::"))
    }

    private fun readUuid(block: org.bukkit.block.Block): UUID? =
        parsePersonalLootUuid(CustomBlockData(block, plugin).get(uuidKey, PersistentDataType.STRING))

    private fun split(vararg blocks: org.bukkit.block.Block) {
        for (block in blocks) {
            val data = block.blockData as ChestData
            data.type = ChestData.Type.SINGLE
            block.blockData = data
        }
    }

    private fun installHarness() {
        chestWorld = server.addSimpleWorld("loot-auth-${UUID.randomUUID()}")
        repository = mockk(relaxed = true)
        coEvery { repository.saveDirty() } returns RepoResult.success(Unit)
        coEvery { repository.get(any()) } returns RepoResult.success(null)
        openScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        identityBacking = InMemoryStorage()
        identityStore = PersonalLootIdentities(identityBacking)
        uuidKey = NamespacedKey(plugin, "ploot_uuid")
        playerListKey = NamespacedKey(plugin, "ploot")
        setModuleField("repo", repository)
        setModuleField("repositoryScope", openScope)
        setModuleField("identities", identityStore)
        setModuleField("config", ConfigManager.ofModule(dataPath, "personalloot.yml"))
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
