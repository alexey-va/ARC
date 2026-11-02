package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.damage.DamageSource
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.config.ConfigManager
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Engine contracts; native NBT and terrain collision search are outside MockBukkit's oracle. */
class NativeGravesRuntimeTest : FunSpec({
    test("death keeps actual items until the grave intent is durable and waits for the player save") {
        withGraveRuntime { f ->
            val dropped = namedStack(Material.DIAMOND, 5, "Death drop")
            val retained = namedStack(Material.IRON_SWORD, 1, "Retained item")
            f.player.inventory.setItem(0, dropped)
            f.player.inventory.setItem(1, retained)
            val before = NativeGraveItems.capture(f.player)
            f.start()
            val insert = CompletableFuture<Unit>().also { f.store.nextInsert = it }
            val save = CompletableFuture<Unit>().also { f.persistence.nextSave = it }
            val event = PlayerDeathEvent(f.player, mockk<DamageSource>(), mutableListOf(dropped.clone()), 5, Component.empty(), true)

            // BlockMock has no native collision bounding box; this case owns persistence ordering.
            mockkObject(GraveDestinations)
            try {
                every { GraveDestinations.nearby(f.world, 0, 64, 0) } returns Location(f.world, .5, 64.0, .5)
                f.paper.callEvent(event)
            } finally {
                unmockkObject(GraveDestinations)
            }
            f.scheduler.advanceMs(50)
            f.flush()

            event.keepInventory shouldBe true
            event.keepLevel shouldBe true
            event.drops shouldBe emptyList()
            event.droppedExp shouldBe 0
            NativeGraveItems.capture(f.player) shouldBe before
            f.store.rows() shouldBe emptyList()
            f.engine.blocked(f.player.uniqueId) shouldBe true

            insert.complete(Unit)
            f.flush()

            f.player.inventory.getItem(0) shouldBe null
            f.player.inventory.getItem(1) shouldBe retained
            f.store.rows().single().phase shouldBe NativeGravePhase.PREPARED
            f.engine.views() shouldBe emptyList()
            f.engine.blocked(f.player.uniqueId) shouldBe true

            save.complete(Unit)
            f.flush()

            val grave = f.store.rows().single()
            grave.phase shouldBe NativeGravePhase.ACTIVE
            grave.items.map(NativeGraveItems::decode) shouldBe listOf(dropped)
            grave.checkpoint shouldBe NativeGraveItems.capture(f.player)
            f.persistence.saves.size shouldBe 1
            f.engine.blocked(f.player.uniqueId) shouldBe false
        }
    }

    test("death cancellation after the grave handler leaves no durable operation or frozen inventory") {
        withGraveRuntime { f ->
            val dropped = namedStack(Material.DIAMOND, 5, "Rescued inventory")
            f.player.inventory.setItem(0, dropped)
            val before = NativeGraveItems.capture(f.player)
            f.start()
            val event = PlayerDeathEvent(f.player, mockk<DamageSource>(), mutableListOf(dropped.clone()), 0, Component.empty(), true)
            mockkObject(GraveDestinations)
            try {
                every { GraveDestinations.nearby(f.world, 0, 64, 0) } returns Location(f.world, .5, 64.0, .5)
                f.paper.callEvent(event)
            } finally {
                unmockkObject(GraveDestinations)
            }
            f.engine.blocked(f.player.uniqueId) shouldBe true

            event.isCancelled = true
            f.scheduler.advanceMs(50)
            f.flush()

            f.store.rows() shouldBe emptyList()
            NativeGraveItems.capture(f.player) shouldBe before
            f.persistence.saves shouldBe emptyList()
            f.engine.blocked(f.player.uniqueId) shouldBe false
        }
    }

    test("a cached CLAIMING row cannot replay after the authoritative row is CLAIMED") {
        withGraveRuntime { f ->
            val before = NativeGraveItems.capture(f.player)
            val after = before.copy(slots = before.slots.toMutableList().also {
                it[0] = NativeGraveItems.encode(namedStack(Material.DIAMOND, 3, "Already claimed"))
            })
            val stale = f.pending(before, after)
            f.store.backing.insert(stale).join() shouldBe true
            val delayedRead = CompletableFuture<List<NativeGraveRecord>>().also { f.store.nextCurrent = it }
            // A later repair is deliberately held: this test observes admission before any repair.
            f.persistence.nextRepair = CompletableFuture()
            f.start()
            val completed = stale.copy(revision = stale.revision + 1, phase = NativeGravePhase.CLAIMED,
                operation = null, completedSequence = requireNotNull(stale.operation).sequence, checkpoint = after)
            f.store.backing.replace(stale, completed).join() shouldBe true

            delayedRead.complete(listOf(stale))
            f.flush()

            NativeGraveItems.capture(f.player) shouldBe before
            f.persistence.saves shouldBe emptyList()
            f.store.rows().single() shouldBe completed
            f.engine.views() shouldBe emptyList()
            f.persistence.repairs.last().second?.sequence shouldBe completed.completedSequence
            f.engine.blocked(f.player.uniqueId) shouldBe true
        }
    }

    test("a partial claim preserves metadata and overflow and does not apply twice") {
        withGraveRuntime { f ->
            f.player.inventory.storageContents = Array(36) { ItemStack(Material.COBBLESTONE, 64) }
            val stack = namedStack(Material.DIAMOND, 60, "Matching stack")
            f.player.inventory.setItem(0, stack)
            val contents = listOf(stack.clone().also { it.amount = 10 }, namedStack(Material.DIAMOND, 2, "Different item"))
            val grave = f.active(contents, experience = 7)
            f.store.backing.insert(grave).join() shouldBe true
            f.start()
            val view = f.engine.views().single()
            val save = CompletableFuture<Unit>().also { f.persistence.nextSave = it }

            f.engine.claim(f.player, view)
            f.flush()
            val once = NativeGraveItems.capture(f.player)
            f.engine.claim(f.player, view)
            f.flush()

            f.player.inventory.getItem(0)?.amount shouldBe 64
            f.player.totalExperience shouldBe 7
            f.persistence.saves.size shouldBe 1
            f.engine.blocked(f.player.uniqueId) shouldBe true
            NativeGraveItems.capture(f.player) shouldBe once

            save.complete(Unit)
            f.flush()
            f.engine.synchronized(f.player.uniqueId)
            f.flush()

            val remaining = f.store.rows().single()
            remaining.phase shouldBe NativeGravePhase.ACTIVE
            remaining.items.map(NativeGraveItems::decode) shouldBe listOf(
                stack.clone().also { it.amount = 6 }, contents[1],
            )
            remaining.experience shouldBe 0
            remaining.operation shouldBe null
            NativeGraveItems.capture(f.player) shouldBe once
            f.persistence.saves.size shouldBe 1
            f.feedback.count { it == "claimed-partial" } shouldBe 1
            f.engine.blocked(f.player.uniqueId) shouldBe false
        }
    }

    test("minimum receipt repair gates claims and a disconnected session cannot release its replacement") {
        withGraveRuntime { f ->
            val checkpoint = NativeGraveItems.capture(f.player).let { before ->
                before.copy(slots = before.slots.toMutableList().also {
                    it[0] = NativeGraveItems.encode(namedStack(Material.EMERALD, 2, "Saved inventory"))
                })
            }
            val pending = f.pending(NativeGraveItems.capture(f.player), checkpoint)
            f.store.backing.insert(pending).join() shouldBe true
            val completed = pending.copy(revision = pending.revision + 1, phase = NativeGravePhase.CLAIMED,
                operation = null, completedSequence = requireNotNull(pending.operation).sequence, checkpoint = checkpoint)
            f.store.backing.replace(pending, completed).join() shouldBe true
            f.store.backing.insert(f.active(listOf(ItemStack(Material.DIAMOND)))).join() shouldBe true
            val retiredRepair = CompletableFuture<Unit>().also { f.persistence.nextRepair = it }
            f.start()
            val view = f.engine.views().single()

            f.engine.canClaim(f.player, view) shouldBe false
            f.engine.blocked(f.player.uniqueId) shouldBe true
            f.persistence.repairs.single().second?.sequence shouldBe completed.completedSequence
            f.player.disconnect() shouldBe true
            val replacement = PlayerMock(f.paper.server, f.player.name, f.player.uniqueId)
            replacement.setLocation(Location(f.world, .5, 64.0, .5))
            f.paper.server.addPlayer(replacement)
            f.flush()

            retiredRepair.complete(Unit)
            f.flush()
            f.engine.canClaim(replacement, view) shouldBe false
            f.persistence.saves shouldBe emptyList()
            val currentRepair = CompletableFuture<Unit>().also { f.persistence.nextRepair = it }
            f.engine.synchronized(replacement.uniqueId)
            f.flush()

            (f.persistence.repairs.last().first === replacement) shouldBe true
            f.engine.canClaim(replacement, view) shouldBe false
            // The persistence adapter completes only after restoring and acknowledging this state.
            NativeGraveItems.apply(replacement, checkpoint)
            f.persistence.receipts[replacement] = completed.completedSequence
            currentRepair.complete(Unit)
            f.flush()

            f.engine.canClaim(replacement, view) shouldBe true
            NativeGraveItems.capture(replacement) shouldBe checkpoint
            f.engine.blocked(replacement.uniqueId) shouldBe false
        }
    }

    test("an unknown inventory enters durable REVIEW without freezing unrelated player input") {
        withGraveRuntime { f ->
            val before = NativeGraveItems.capture(f.player)
            val after = before.copy(slots = before.slots.toMutableList().also {
                it[0] = NativeGraveItems.encode(namedStack(Material.DIAMOND, 1, "Preserved entitlement"))
            })
            val pending = f.pending(before, after)
            f.store.backing.insert(pending).join() shouldBe true
            f.player.inventory.setItem(0, ItemStack(Material.EMERALD, 4))
            val unrelated = NativeGraveItems.capture(f.player)
            f.start()

            val review = f.store.rows().single()
            review.phase shouldBe NativeGravePhase.REVIEW
            review.operation shouldBe pending.operation
            NativeGraveItems.capture(f.player) shouldBe unrelated
            f.engine.blocked(f.player.uniqueId) shouldBe false
            f.paper.callEvent(PlayerCommandPreprocessEvent(f.player, "/spawn")).isCancelled shouldBe false
            f.engine.synchronized(f.player.uniqueId)
            f.flush()

            f.store.rows().single() shouldBe review
            NativeGraveItems.capture(f.player) shouldBe unrelated
            f.persistence.saves shouldBe emptyList()
            f.feedback.count { it == "inventory-conflict" } shouldBe 1
        }
    }

    test("a local inventory profile ignores network recovery and claims without replacing its watermark") {
        withGraveRuntime(sharedInventory = false) { f ->
            val localItem = namedStack(Material.IRON_SWORD, 1, "Local inventory")
            f.player.inventory.setItem(0, localItem)
            val localBefore = NativeGraveItems.capture(f.player)
            val networkBefore = localBefore.copy(slots = List(41) { null })
            val networkAfter = networkBefore.copy(slots = networkBefore.slots.toMutableList().also {
                it[0] = NativeGraveItems.encode(namedStack(Material.DIAMOND, 32, "Network inventory"))
            })
            val acknowledged = f.pending(networkBefore, networkAfter)
            f.store.backing.insert(acknowledged).join() shouldBe true
            val completed = acknowledged.copy(revision = acknowledged.revision + 1, phase = NativeGravePhase.CLAIMED,
                operation = null, completedSequence = requireNotNull(acknowledged.operation).sequence, checkpoint = networkAfter)
            f.store.backing.replace(acknowledged, completed).join() shouldBe true
            val networkNext = networkAfter.copy(slots = networkAfter.slots.toMutableList().also {
                it[1] = NativeGraveItems.encode(ItemStack(Material.EMERALD, 2))
            })
            val networkPending = f.pending(networkAfter, networkNext)
            f.store.backing.insert(networkPending).join() shouldBe true
            val localGrave = f.active(listOf(ItemStack(Material.GOLD_INGOT, 3)))
            f.store.backing.insert(localGrave).join() shouldBe true
            val networkWatermark = f.store.backing.loadWatermarks(setOf(f.player.uniqueId)).join().getValue(f.player.uniqueId)
            f.start()

            NativeGraveItems.capture(f.player) shouldBe localBefore
            f.persistence.repairs.all { it.second == null } shouldBe true
            f.persistence.saves shouldBe emptyList()
            f.engine.blocked(f.player.uniqueId) shouldBe false
            f.paper.callEvent(PlayerCommandPreprocessEvent(f.player, "/spawn")).isCancelled shouldBe false
            val view = f.engine.views().single { it.id == localGrave.id }
            f.engine.canClaim(f.player, view) shouldBe true
            val save = CompletableFuture<Unit>().also { f.persistence.nextSave = it }

            f.engine.claim(f.player, view)
            f.flush()

            val localPending = f.store.rows().single { it.id == localGrave.id }
            localPending.phase shouldBe NativeGravePhase.CLAIMING
            localPending.operation?.inventoryScope shouldBe "local:survival"
            f.player.inventory.getItem(0) shouldBe localItem
            f.player.inventory.getItem(1) shouldBe ItemStack(Material.GOLD_INGOT, 3)
            f.store.rows().single { it.id == networkPending.id } shouldBe networkPending
            save.complete(Unit)
            f.flush()

            val claimed = f.store.rows().single { it.id == localGrave.id }
            claimed.phase shouldBe NativeGravePhase.CLAIMED
            claimed.completedSequence shouldBe 0L
            claimed.checkpoint shouldBe null
            f.persistence.saves.size shouldBe 1
            f.engine.blocked(f.player.uniqueId) shouldBe false
            f.store.backing.loadWatermarks(setOf(f.player.uniqueId)).join().getValue(f.player.uniqueId) shouldBe networkWatermark
            f.store.rows().single { it.id == networkPending.id } shouldBe networkPending
        }
    }

    test("repeated close returns one drain through accepted reads and repository shutdown") {
        withGraveRuntime { f ->
            val read = CompletableFuture<List<NativeGraveRecord>>().also { f.store.nextCurrent = it }
            val storeClosed = CompletableFuture<Unit>().also { f.store.closeGate = it }
            f.start()

            val first = f.engine.closeAsync()
            (f.engine.closeAsync() === first) shouldBe true
            first.isDone shouldBe false
            f.store.closeCalls shouldBe 0
            read.complete(emptyList())
            f.flush()

            f.store.closeCalls shouldBe 1
            first.isDone shouldBe false
            storeClosed.complete(Unit)
            first.join()
            (f.engine.closeAsync() === first) shouldBe true
            f.store.closeCalls shouldBe 1
            f.scheduler.timerCount() shouldBe 0
            f.engine.ready shouldBe false
        }
    }
})

private fun withGraveRuntime(sharedInventory: Boolean = true, body: (GraveRuntimeFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper ->
        // MockBukkit cannot exercise Paper's native NBT serializer. Stable cloned stacks
        // retain amounts and ItemMeta identity while the real grave transition code runs.
        val encoded = mutableListOf<ItemStack>()
        mockkObject(NativePaperItemStackBinaryCodec)
        every { NativePaperItemStackBinaryCodec.encodeItem(any()) } answers {
            val item = firstArg<ItemStack>()
            val existing = encoded.indexOfFirst { it.amount == item.amount && it.isSimilar(item) }
            val id = if (existing >= 0) existing else encoded.size.also { encoded += item.clone() }
            id.toString().toByteArray(Charsets.UTF_8)
        }
        every { NativePaperItemStackBinaryCodec.decodeItem(any()) } answers {
            encoded[firstArg<ByteArray>().toString(Charsets.UTF_8).toInt()].clone()
        }
        try {
            GraveRuntimeFixture(paper, sharedInventory).use(body)
        } finally {
            unmockkObject(NativePaperItemStackBinaryCodec)
        }
    }
}

private class GraveRuntimeFixture(val paper: MockBukkitTestRuntime, sharedInventory: Boolean) : AutoCloseable {
    val world = paper.addSimpleWorld("graves")
    val player = paper.addPlayer("GraveOwner")
    val scheduler = TestTaskScheduler()
    val store = ControlledGraveRepository()
    val persistence = ControlledGravePersistence(sharedInventory)
    val feedback = mutableListOf<String>()
    val engine = NativeGraves("survival", GravesConfig(ConfigManager.empty()).snapshot(),
        { _, key -> feedback += key }, {}, paper.createSimplePlugin("NativeGravesTest"),
        CompletableFuture.completedFuture(store), persistence, scheduler)

    init {
        player.gameMode = GameMode.SURVIVAL
        player.teleport(Location(world, .5, 64.0, .5))
    }

    fun start() { engine.start(); flush() }

    fun flush() {
        repeat(50) {
            if (scheduler.pendingCount() == 0) return
            scheduler.executeImmediate()
        }
        error("Grave callbacks did not settle within 50 deterministic scheduler turns")
    }

    fun active(items: List<ItemStack>, experience: Int = 0) = NativeGraveRecord(
        UUID.randomUUID(), player.uniqueId, player.name, "survival", world.name,
        0, 64, 0, 1, -1, experience, items.map(NativeGraveItems::encode),
    )

    fun pending(before: NativeGraveInventory, after: NativeGraveInventory): NativeGraveRecord {
        val operationId = UUID.randomUUID()
        val sequence = store.backing.reserveSequence(operationId, player.uniqueId).join()
        return active(emptyList()).copy(phase = NativeGravePhase.CLAIMING,
            operation = NativeGraveOperation(operationId, "survival", NativeGraveOperationKind.CLAIM, before, after, sequence))
    }

    override fun close() { engine.closeAsync() }
}

private class ControlledGraveRepository(
    val backing: InMemoryNativeGraveRepository = InMemoryNativeGraveRepository(),
) : NativeGraveRepository by backing {
    var nextInsert: CompletableFuture<Unit>? = null
    var nextCurrent: CompletableFuture<List<NativeGraveRecord>>? = null
    var closeGate: CompletableFuture<Unit>? = null
    var closeCalls = 0

    override fun insert(record: NativeGraveRecord): CompletableFuture<Boolean> {
        val gate = nextInsert.also { nextInsert = null } ?: CompletableFuture.completedFuture(Unit)
        return gate.thenCompose { backing.insert(record) }
    }

    override fun loadCurrent(server: String, owners: Set<UUID>, now: Long): CompletableFuture<List<NativeGraveRecord>> =
        nextCurrent.also { nextCurrent = null } ?: backing.loadCurrent(server, owners, now)

    fun rows(): List<NativeGraveRecord> = backing.loadAll().join()

    override fun closeAsync(): CompletableFuture<Unit> {
        closeCalls++
        return (closeGate ?: CompletableFuture.completedFuture(Unit)).thenCompose { backing.closeAsync() }
    }
}

private class ControlledGravePersistence(override val sharedInventory: Boolean = true) : NativeGravePlayerPersistence {
    val receipts = IdentityHashMap<Player, Long>()
    val repairs = mutableListOf<Pair<Player, NativeGraveWatermark?>>()
    val saves = mutableListOf<Pair<Player, Long>>()
    private val pending = mutableListOf<CompletableFuture<Unit>>()
    var nextRepair: CompletableFuture<Unit>? = null
    var nextSave: CompletableFuture<Unit>? = null

    override fun isSynchronized(player: Player) = true
    override fun sequence(player: Player): Long = receipts[player] ?: 0

    override fun reconcile(player: Player, watermark: NativeGraveWatermark?): CompletableFuture<Unit> {
        repairs += player to watermark
        if (watermark != null && sequence(player) < watermark.sequence) {
            nextRepair?.let { gate -> nextRepair = null; pending += gate; return gate }
            NativeGraveItems.apply(player, watermark.inventory)
            receipts[player] = watermark.sequence
        }
        return CompletableFuture.completedFuture(Unit)
    }

    override fun persist(player: Player, sequence: Long): CompletableFuture<Unit> {
        saves += player to sequence
        receipts[player] = sequence
        return (nextSave.also { nextSave = null } ?: CompletableFuture.completedFuture(Unit)).also { pending += it }
    }

    override fun close() {
        pending.filterNot { it.isDone }.forEach { it.completeExceptionally(IllegalStateException("Fixture retired")) }
    }
}

private fun namedStack(material: Material, amount: Int, name: String) = ItemStack(material, amount).also { item ->
    item.itemMeta = requireNotNull(item.itemMeta).also { it.displayName(Component.text(name)) }
}
