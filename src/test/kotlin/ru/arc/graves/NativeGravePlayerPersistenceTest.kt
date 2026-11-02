package ru.arc.graves

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import net.william278.husksync.HuskSync
import net.william278.husksync.api.BukkitHuskSyncAPI
import net.william278.husksync.data.Data
import net.william278.husksync.data.DataSnapshot
import net.william278.husksync.data.Identifier
import net.william278.husksync.data.Serializer
import net.william278.husksync.data.UserDataHolder
import net.william278.husksync.database.Database
import net.william278.husksync.event.BukkitDataSaveEvent
import net.william278.husksync.user.BukkitUser
import org.bukkit.Material
import org.bukkit.event.HandlerList
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Actual ARC adapter and worker; vendor capture/SQL and native NBT are bounded test doubles. */
class NativeGravePlayerPersistenceTest : FunSpec({
    test("persist captures AFTER with its receipt on Paper before exact SQL acknowledgement on the worker") {
        withHuskGravePersistence { f ->
            f.player.inventory.setItem(0, ItemStack(Material.DIAMOND, 3))
            f.player.level = 1
            f.player.totalExperience = 7
            val after = NativeGraveItems.capture(f.player)
            val release = f.holdWorker()

            val saved = f.adapter.persist(f.player, 12)

            saved.isDone shouldBe false
            f.adapter.sequence(f.player) shouldBe 12
            val captured = f.captures.single()
            captured.inventory shouldBe after
            captured.receipt shouldBe "12"
            (captured.thread === f.owner) shouldBe true
            // A later state cannot change the already-captured operation snapshot.
            f.player.inventory.setItem(0, ItemStack(Material.EMERALD, 9))
            release.countDown()
            saved.get(5, TimeUnit.SECONDS)

            f.writes.single().id shouldBe captured.packed.id
            f.sqlThreads.size shouldBe 2
            f.sqlThreads.all { it !== f.owner } shouldBe true
            f.serializer.serialize(requireNotNull(f.customData[GRAVE_RECEIPT])) shouldBe "12"
            // A stateless serializer can read a previous module generation's Data class.
            val oldGeneration = object : Data {
                override fun apply(holder: UserDataHolder, plugin: HuskSync) {}
                override fun toString() = "arc-grave-receipt:12"
            }
            f.serializer.serialize(oldGeneration) shouldBe "12"
            f.adapter.close()
            f.serializer.serialize(oldGeneration) shouldBe "12"
        }
    }

    test("a swallowed write and same-ID readbacks with changed inventory XP receipt or other data are rejected") {
        withHuskGravePersistence { f ->
            f.player.inventory.setItem(0, ItemStack(Material.DIAMOND, 2))
            val corruptFields = listOf(null, Identifier.INVENTORY.asMinimalString(),
                Identifier.EXPERIENCE.asMinimalString(), GRAVE_RECEIPT.asMinimalString(), Identifier.HEALTH.asMinimalString())
            corruptFields.forEachIndexed { index, field ->
                f.readBack = { id ->
                    if (field == null) Optional.empty()
                    else {
                        val written = f.writes.single { it.id == id }
                        val json = JsonParser.parseString(written.asJson(f.husk)).asJsonObject
                        json.getAsJsonObject("data").addProperty(field, "wrong durable value")
                        Optional.of(f.packed(id, json.toString()))
                    }
                }

                val failure = shouldThrow<ExecutionException> {
                    f.adapter.persist(f.player, 20L + index).get(5, TimeUnit.SECONDS)
                }

                (failure.cause is IllegalStateException) shouldBe true
                failure.cause?.message.orEmpty() shouldContain "no exact durable payload readback"
            }
            f.writes.size shouldBe corruptFields.size
            f.sqlThreads.all { it !== f.owner } shouldBe true
        }
    }

    test("world saves begun before the receipt capture are rejected while newer saves and disconnect remain allowed") {
        withHuskGravePersistence { f ->
            val before = OffsetDateTime.now().minusMinutes(1)
            f.adapter.persist(f.player, 31).get(5, TimeUnit.SECONDS)
            val after = OffsetDateTime.now().plusMinutes(1)
            val oldWorldSave = f.saveEvent(DataSnapshot.SaveCause.WORLD_SAVE, before)
            val newerWorldSave = f.saveEvent(DataSnapshot.SaveCause.WORLD_SAVE, after)
            val disconnect = f.saveEvent(DataSnapshot.SaveCause.DISCONNECT, before)

            f.paper.callEvent(oldWorldSave)
            f.paper.callEvent(newerWorldSave)
            f.paper.callEvent(disconnect)

            oldWorldSave.isCancelled shouldBe true
            newerWorldSave.isCancelled shouldBe false
            disconnect.isCancelled shouldBe false
        }
    }

    test("a delayed world save remains fenced after ARC stop and the next adapter reuses one vendor-owned listener") {
        withHuskGravePersistence { f ->
            val arc = f.paper.createSimplePlugin("ArcGraveOwner")
            val delayed = f.saveEvent(DataSnapshot.SaveCause.WORLD_SAVE, OffsetDateTime.now().minusMinutes(1))
            f.adapter.persist(f.player, 32).get(5, TimeUnit.SECONDS)
            fun fenceCount() = BukkitDataSaveEvent.getHandlerList().registeredListeners.count { it.plugin === f.huskOwner }
            fenceCount() shouldBe 1

            f.adapter.close()
            HandlerList.unregisterAll(arc)
            f.paper.server.pluginManager.disablePlugin(arc)
            f.paper.callEvent(delayed)

            delayed.isCancelled shouldBe true
            f.huskOwner.isEnabled shouldBe true
            fenceCount() shouldBe 1
            f.nextAdapter().use {
                fenceCount() shouldBe 1
                val priorCapture = f.saveEvent(DataSnapshot.SaveCause.WORLD_SAVE, delayed.data.timestamp)
                f.paper.callEvent(priorCapture)
                priorCapture.isCancelled shouldBe true
            }
            f.paper.server.pluginManager.disablePlugin(f.huskOwner)
            fenceCount() shouldBe 0
        }
    }

    test("stale SQL uses the acknowledged checkpoint while newer receipts before or during a read prevent rollback") {
        withHuskGravePersistence { f ->
            f.player.inventory.setItem(0, ItemStack(Material.DIAMOND, 4))
            val checkpoint = NativeGraveItems.capture(f.player)
            val watermark = NativeGraveWatermark(f.player.uniqueId, 8, checkpoint)
            f.player.inventory.setItem(0, ItemStack(Material.COBBLESTONE, 1))
            val stale = NativeGraveItems.capture(f.player)
            f.setReceipt(2)

            val repaired = f.adapter.reconcile(f.player, watermark)
            repaired.isDone shouldBe false
            NativeGraveItems.capture(f.player) shouldBe stale
            f.latest.complete(Optional.of(f.candidate(3)))
            f.flush()
            repaired.get(5, TimeUnit.SECONDS)
            f.flush()

            NativeGraveItems.capture(f.player) shouldBe checkpoint
            f.adapter.sequence(f.player) shouldBe 8
            f.captures.single().inventory shouldBe checkpoint
            f.captures.single().receipt shouldBe "8"
            verify(exactly = 0) { f.user.setInventory(any()) }
            verify(exactly = 0) { f.user.setExperience(any()) }

            f.setReceipt(10)
            f.latest = CompletableFuture()
            val delayed = f.adapter.reconcile(f.player, watermark.copy(sequence = 12))
            delayed.isDone shouldBe false
            f.player.inventory.setItem(0, ItemStack(Material.EMERALD, 6))
            val newer = NativeGraveItems.capture(f.player)
            f.setReceipt(13)
            f.latest.complete(Optional.of(f.candidate(3)))
            f.flush()
            delayed.get(5, TimeUnit.SECONDS)

            NativeGraveItems.capture(f.player) shouldBe newer
            f.adapter.sequence(f.player) shouldBe 13
            f.writes.size shouldBe 1
            f.adapter.reconcile(f.player, watermark.copy(sequence = 12)).get(5, TimeUnit.SECONDS)
            verify(exactly = 2) { f.api.getLatestSnapshot(f.user) }
            NativeGraveItems.capture(f.player) shouldBe newer
        }
    }

    test("a late recovery result cannot touch a disconnected player or its replacement session") {
        withHuskGravePersistence { f ->
            f.player.inventory.setItem(0, ItemStack(Material.DIAMOND, 5))
            val checkpoint = NativeGraveItems.capture(f.player)
            f.player.inventory.setItem(0, ItemStack(Material.COBBLESTONE, 2))
            val before = NativeGraveItems.capture(f.player)
            f.setReceipt(1)
            val repaired = f.adapter.reconcile(f.player, NativeGraveWatermark(f.player.uniqueId, 9, checkpoint))

            f.player.disconnect() shouldBe true
            val replacement = PlayerMock(f.paper.server, f.player.name, f.player.uniqueId)
            f.paper.server.addPlayer(replacement)
            replacement.inventory.setItem(0, ItemStack(Material.EMERALD, 3))
            val replacementBefore = NativeGraveItems.capture(replacement)
            f.latest.complete(Optional.of(f.candidate(2)))
            f.flush()

            val failure = shouldThrow<ExecutionException> { repaired.get(5, TimeUnit.SECONDS) }
            failure.cause?.message.orEmpty() shouldContain "session retired"
            NativeGraveItems.capture(f.player) shouldBe before
            NativeGraveItems.capture(replacement) shouldBe replacementBefore
            f.adapter.sequence(f.player) shouldBe 1
            f.captures shouldBe emptyList()
            f.writes shouldBe emptyList()
        }
    }
})

private val GRAVE_RECEIPT = Identifier.from("arc", "grave_receipt")

private fun withHuskGravePersistence(body: (HuskGravePersistenceFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper ->
        val items = mutableListOf<ItemStack>()
        mockkObject(NativePaperItemStackBinaryCodec)
        every { NativePaperItemStackBinaryCodec.encodeItem(any()) } answers {
            val item = firstArg<ItemStack>()
            val existing = items.indexOfFirst { it.amount == item.amount && it.isSimilar(item) }
            val id = if (existing >= 0) existing else items.size.also { items += item.clone() }
            id.toString().toByteArray(Charsets.UTF_8)
        }
        every { NativePaperItemStackBinaryCodec.decodeItem(any()) } answers {
            items[firstArg<ByteArray>().toString(Charsets.UTF_8).toInt()].clone()
        }
        try {
            HuskGravePersistenceFixture(paper).use(body)
        } finally {
            unmockkObject(NativePaperItemStackBinaryCodec)
        }
    }
}

private class HuskGravePersistenceFixture(val paper: MockBukkitTestRuntime) : AutoCloseable {
    val owner: Thread = Thread.currentThread()
    val player = paper.addPlayer("GraveReceipt")
    val huskOwner = paper.createSimplePlugin("HuskSync")
    val api = mockk<BukkitHuskSyncAPI>()
    val husk = mockk<HuskSync>()
    val user = mockk<BukkitUser>()
    private val database = mockk<Database>()
    val customData = mutableMapOf<Identifier, Data>()
    private val serializerSlot = slot<Serializer<Data>>()
    val serializer: Serializer<Data> get() = serializerSlot.captured
    val scheduler = TestTaskScheduler()
    private val worker = BoundedAsyncExecutor("grave-persistence-test", 1, 8)
    private val releases = mutableListOf<CountDownLatch>()
    val captures = mutableListOf<CapturedGraveSnapshot>()
    val writes = CopyOnWriteArrayList<DataSnapshot.Packed>()
    val sqlThreads = CopyOnWriteArrayList<Thread>()
    var latest = CompletableFuture<Optional<DataSnapshot.Unpacked>>()
    var readBack: (UUID) -> Optional<DataSnapshot.Packed> = { id -> Optional.ofNullable(writes.find { it.id == id }) }
    val adapter: HuskSyncGravePersistence

    init {
        every { api.plugin } returns husk
        every { husk.database } returns database
        every { api.getUser(player) } returns user
        every { user.uuid } returns player.uniqueId
        every { user.isLocked } returns false
        every { user.customDataStore } returns customData
        every { api.registerDataSerializer<Data>(GRAVE_RECEIPT, capture(serializerSlot)) } just Runs
        every { api.getLatestSnapshot(user) } answers { latest }
        every { user.createSnapshot(DataSnapshot.SaveCause.API) } answers {
            check(Thread.currentThread() === owner) { "Native capture escaped Paper" }
            val inventory = NativeGraveItems.capture(player)
            val receipt = serializer.serialize(requireNotNull(customData[GRAVE_RECEIPT]))
            val data = JsonObject().apply {
                addProperty(Identifier.INVENTORY.asMinimalString(), Gson().toJson(inventory.slots))
                addProperty(Identifier.EXPERIENCE.asMinimalString(), "${inventory.level}:${inventory.progress}:${inventory.totalExperience}")
                addProperty(Identifier.HEALTH.asMinimalString(), player.health.toString())
                addProperty(GRAVE_RECEIPT.asMinimalString(), receipt)
            }
            packed(UUID.randomUUID(), JsonObject().apply { add("data", data) }.toString()).also {
                captures += CapturedGraveSnapshot(it, inventory, receipt, Thread.currentThread())
            }
        }
        every { database.addSnapshot(user, any()) } answers {
            check(Thread.currentThread() !== owner) { "SQL insert ran on Paper" }
            sqlThreads += Thread.currentThread()
            writes += secondArg<DataSnapshot.Packed>()
            Unit
        }
        every { database.getSnapshot(user, any()) } answers {
            check(Thread.currentThread() !== owner) { "SQL readback ran on Paper" }
            sqlThreads += Thread.currentThread()
            readBack(secondArg())
        }
        adapter = HuskSyncGravePersistence(worker, api, scheduler)
    }

    fun setReceipt(value: Long) { customData[GRAVE_RECEIPT] = serializer.deserialize(value.toString()) }

    fun nextAdapter() = HuskSyncGravePersistence(worker, api, scheduler)

    fun candidate(receipt: Long): DataSnapshot.Unpacked = mockk<DataSnapshot.Unpacked>().also {
        every { it.data } returns mapOf(GRAVE_RECEIPT to serializer.deserialize(receipt.toString()))
    }

    fun packed(id: UUID, json: String): DataSnapshot.Packed = mockk<DataSnapshot.Packed>().also {
        every { it.id } returns id
        every { it.asJson(husk) } returns json
    }

    fun saveEvent(cause: DataSnapshot.SaveCause, timestamp: OffsetDateTime): BukkitDataSaveEvent {
        val snapshot = packed(UUID.randomUUID(), "{\"data\":{}}")
        every { snapshot.saveCause } returns cause
        every { snapshot.timestamp } returns timestamp
        return object : BukkitDataSaveEvent(user, snapshot, husk) {}
    }

    fun holdWorker(): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also(releases::add)
        worker.submit {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test worker barrier timed out" }
        }
        check(entered.await(5, TimeUnit.SECONDS)) { "Test worker did not reach its barrier" }
        return release
    }

    fun flush() {
        repeat(30) {
            if (scheduler.pendingCount() == 0) return
            scheduler.executeImmediate()
        }
        error("Persistence callbacks did not settle")
    }

    override fun close() {
        releases.forEach(CountDownLatch::countDown)
        adapter.close()
        worker.closeAsync().get(5, TimeUnit.SECONDS)
    }
}

private data class CapturedGraveSnapshot(
    val packed: DataSnapshot.Packed,
    val inventory: NativeGraveInventory,
    val receipt: String,
    val thread: Thread,
)
