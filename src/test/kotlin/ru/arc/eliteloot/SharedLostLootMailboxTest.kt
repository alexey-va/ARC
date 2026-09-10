package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture

class SharedLostLootMailboxTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var scheduler: TestTaskScheduler
    lateinit var repository: MemoryLostLootRepository
    lateinit var gateway: TestLootCredits
    val services = mutableListOf<SharedLostLootMailbox>()
    beforeEach { scheduler = TestTaskScheduler(); Tasks.install(scheduler); paper = MockBukkitTestRuntime.open(); repository = MemoryLostLootRepository(); gateway = TestLootCredits() }
    afterEach { services.forEach { it.close() }; services.clear(); Tasks.reset(); paper.close() }
    fun drain() { repeat(12) { scheduler.executeImmediate() } }
    fun service(server: String = "survival", persist: PaperPlayerDataPersistence = PaperPlayerDataPersistence {}) =
        SharedLostLootMailbox(repository, server, { _, fallback -> Component.text(fallback) }, gateway, persist).also(services::add)
    fun item(player: Player) = ItemStack.of(Material.DIAMOND_HELMET).also { item -> item.editMeta {
        it.displayName(Component.text("Сокровище босса")); it.tooltipStyle = NamespacedKey("lzblocks", "tooltip/rare")
        it.persistentDataContainer.set(NamespacedKey("elitemobs", "soulbind"), PersistentDataType.STRING, player.uniqueId.toString())
    } }
    fun put(player: Player, value: Double = 35.2): SharedLootRecord {
        val record = LostLootRecord(UUID.randomUUID().toString(), player.uniqueId.toString(),
            Base64.getEncoder().encodeToString(item(player).serializeAsBytes()), 1000, nativePrice = value)
        repository.publish(record, "dungeon-1").join()
        return repository.rows.getValue(record.id)
    }

    "claim and sale from different servers have only one winner and preserve original metadata" {
        val player = paper.addPlayer("owner")
        val first = service("spawn")
        val second = service("parkour")
        val record = put(player)
        first.claim(player, record.id)
        second.sell(player, record)
        drain()
        repository.rows.getValue(record.id).state shouldBe SharedLootState.CLAIMED
        player.inventory.getItem(0) shouldBe item(player)
        gateway.total shouldBe 0.0
        second.claim(player, record.id); drain()
        player.inventory.storageContents.filterNotNull().size shouldBe 1
    }

    "sale wins before a stale claim and creates one exact crystal entitlement" {
        val player = paper.addPlayer("seller")
        val first = service("parkour")
        val second = service("spawn")
        val record = put(player)
        first.sell(player, record); second.claim(player, record.id); drain()
        first.sell(player, record); drain()
        repository.rows.getValue(record.id).state shouldBe SharedLootState.SOLD
        repository.rows.getValue(record.id).salePrice shouldBe 35.2
        player.inventory.storageContents.filterNotNull().size shouldBe 0
        gateway.total shouldBe 0.0
        gateway.available = true
        second.settle(player); drain()
        repository.rows.getValue(record.id).state shouldBe SharedLootState.PAID
        gateway.total shouldBe 35.2
        first.settle(player); second.settle(player); drain()
        gateway.calls shouldBe 1
    }

    "foreign owner and full inventory do not consume a reward" {
        val owner = paper.addPlayer("owner")
        val foreign = paper.addPlayer("foreign")
        val loot = service()
        val record = put(owner)
        loot.claim(foreign, record.id); loot.sell(foreign, record); drain()
        for (slot in 0..35) owner.inventory.setItem(slot, ItemStack.of(Material.STONE, 64))
        loot.claim(owner, record.id); drain()
        repository.rows.getValue(record.id).state shouldBe SharedLootState.AVAILABLE
    }

    "a failed native player save keeps the owner gate until a receipt is reconciled without reissuing" {
        val player = paper.addPlayer("save-failure")
        val record = put(player)
        val other = put(player)
        val failing = service(persist = PaperPlayerDataPersistence { error("save failed") })
        failing.claim(player, record.id); drain()
        repository.rows.getValue(record.id).state shouldBe SharedLootState.CLAIMING
        service("other-server").claim(player, other.id); drain()
        repository.rows.getValue(other.id).state shouldBe SharedLootState.AVAILABLE
        player.inventory.storageContents.filterNotNull().size shouldBe 1
        service().settle(player); drain()
        repository.rows.getValue(record.id).state shouldBe SharedLootState.CLAIMED
        player.inventory.storageContents.filterNotNull().size shouldBe 1
    }

    "ambiguous item delivery without its receipt cannot be delivered or credited again" {
        val player = paper.addPlayer("ambiguous")
        val record = put(player)
        repository.reserve(record.id, player.uniqueId, SharedLootState.CLAIMING, UUID.randomUUID(), "old", null).join()
        val loot = service()
        loot.settle(player); drain(); loot.claim(player, record.id); drain()
        repository.rows.getValue(record.id).state shouldBe SharedLootState.CLAIMING
        player.inventory.storageContents.filterNotNull().size shouldBe 0
    }

    "an uncertain native credit is never added twice and its matching receipt can finish payment" {
        val player = paper.addPlayer("credit-failure")
        val record = put(player)
        val loot = service()
        loot.sell(player, record); drain()
        gateway.available = true
        gateway.failPersist = true
        loot.settle(player); drain()
        repository.rows.getValue(record.id).state shouldBe SharedLootState.CREDITING
        gateway.total shouldBe 35.2
        val receipt = gateway.receipt
        gateway.receipt = null
        loot.settle(player); drain()
        gateway.calls shouldBe 1
        repository.rows.getValue(record.id).state shouldBe SharedLootState.CREDITING
        gateway.receipt = receipt; gateway.failPersist = false
        loot.settle(player); drain()
        gateway.calls shouldBe 1
        repository.rows.getValue(record.id).state shouldBe SharedLootState.PAID
    }

    "closing a service before a reservation callback cannot issue its item" {
        val player = paper.addPlayer("closed")
        val loot = service()
        val record = put(player)
        loot.claim(player, record.id)
        loot.close(); drain()
        repository.rows.getValue(record.id).state shouldBe SharedLootState.CLAIMING
        player.inventory.storageContents.filterNotNull().size shouldBe 0
    }
})

private class TestLootCredits : LostLootCreditGateway {
    var available = false
    var failPersist = false
    var total = 0.0
    var calls = 0
    var receipt: String? = null
    override fun ready(player: Player) = available
    override fun hasReceipt(player: Player, token: String) = receipt == token
    override fun credit(player: Player, amount: Double, token: String): CompletableFuture<Unit> {
        calls++; total += amount; receipt = token
        return persistReceipt(player)
    }
    override fun persistReceipt(player: Player): CompletableFuture<Unit> = if (failPersist) CompletableFuture.failedFuture(IllegalStateException("Redis unavailable")) else CompletableFuture.completedFuture(Unit)
}

/** Deterministic shared-state test double: two service instances contend for the same item/owner. */
private class MemoryLostLootRepository : SharedLostLootRepository {
    val rows = linkedMapOf<String, SharedLootRecord>()
    private val gates = mutableMapOf<String, String>()
    override fun publish(record: LostLootRecord, sourceServer: String): CompletableFuture<Unit> {
        rows.putIfAbsent(record.id, SharedLootRecord(record.id, record.owner, record.item,
            MySqlSharedLostLootRepository.itemHash(requireNotNull(record.item)), record.capturedAt, sourceServer,
            SharedLootState.AVAILABLE, null, null, null, record.nativePrice))
        return CompletableFuture.completedFuture(Unit)
    }
    override fun list(owner: UUID) = CompletableFuture.completedFuture(rows.values.filter { it.owner == owner.toString() && it.state !in setOf(SharedLootState.CLAIMED, SharedLootState.PAID) })
    override fun nextPending(owner: UUID) = list(owner).thenApply { records ->
        records.firstOrNull { it.state == SharedLootState.CLAIMING || it.state == SharedLootState.CREDITING }
            ?: records.firstOrNull { it.state == SharedLootState.SOLD }
    }
    override fun reserve(id: String, owner: UUID, state: SharedLootState, token: UUID, server: String, salePrice: Double?): CompletableFuture<SharedLootRecord?> {
        val row = rows[id]?.takeIf { it.owner == owner.toString() && it.owner !in gates && it.state == if (state == SharedLootState.CLAIMING) SharedLootState.AVAILABLE else SharedLootState.SOLD }
        val pending = row?.copy(state = state, token = token.toString(), operationServer = server)
        if (pending != null) { rows[id] = pending; gates[pending.owner] = token.toString() }
        return CompletableFuture.completedFuture(pending)
    }
    override fun sell(id: String, owner: UUID, token: UUID, expectedPrice: Double): CompletableFuture<Boolean> {
        val row = rows[id]?.takeIf { it.owner == owner.toString() && it.state == SharedLootState.AVAILABLE && it.nativePrice == expectedPrice }
        if (row != null) rows[id] = row.copy(state = SharedLootState.SOLD, token = token.toString(), item = null, salePrice = expectedPrice)
        return CompletableFuture.completedFuture(row != null)
    }
    override fun release(id: String, owner: UUID, token: UUID, state: SharedLootState): CompletableFuture<Boolean> {
        val row = rows[id]?.takeIf { it.owner == owner.toString() && it.token == token.toString() && it.state == state }
        if (row != null) { rows[id] = row.copy(state = if (state == SharedLootState.CLAIMING) SharedLootState.AVAILABLE else SharedLootState.SOLD, token = null); gates.remove(row.owner, row.token) }
        return CompletableFuture.completedFuture(row != null)
    }
    override fun finish(record: SharedLootRecord): CompletableFuture<Boolean> {
        val row = rows[record.id]?.takeIf { it.owner == record.owner && it.token == record.token && it.state == if (record.state == SharedLootState.CLAIMED) SharedLootState.CLAIMING else SharedLootState.CREDITING }
        if (row != null) { rows[row.id] = record; gates.remove(row.owner, row.token) }
        return CompletableFuture.completedFuture(row != null)
    }
    override fun close() {}
}
