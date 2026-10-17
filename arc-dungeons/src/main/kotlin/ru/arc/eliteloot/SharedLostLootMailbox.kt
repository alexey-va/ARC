package ru.arc.eliteloot

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.dungeons.menu.DungeonsMenuSchema as ArcMenuSchema
import ru.arc.dungeons.menu.DungeonsMenus as ArcMenus
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.logging.ArcLogging as Logging
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** SQL owns each item/receivable; native mutations require a durable receipt before SQL acknowledgement. */
internal class SharedLostLootMailbox(
    private val repository: SharedLostLootRepository,
    private val server: String,
    private val text: (Player, String, String) -> Component,
    private val credits: LostLootCreditGateway,
    private val playerData: PaperPlayerDataPersistence = NativePaperPlayerDataPersistence,
    private val nativeWork: BudgetedTaskQueue,
    private val worker: BoundedAsyncExecutor,
    private val nativeDecode: (ByteArray) -> ItemStack = ItemStack::deserializeBytes,
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private var busy = mutableSetOf<UUID>()
    private val incidents = mutableSetOf<String>()
    private var views = mutableMapOf<UUID, UUID>()
    private val receiptKey = NamespacedKey("arc", "lost_loot_receipt")
    @Volatile private var closed = false
    private var settling: CompletableFuture<Unit>? = null

    fun start() {
        tasks.runTimer(100, 100) {
            if (closed || settling?.isDone == false) return@runTimer
            val players = Bukkit.getOnlinePlayers().iterator()
            settling = nativeWork.submit({ !closed }) {
                if (!players.hasNext()) true else { settle(players.next()); false }
            }
        }
    }
    @EventHandler fun joined(event: PlayerJoinEvent) { tasks.runLater(40) { settle(event.player) } }

    internal data class PreparedCards(val cards: List<PaperMenuEntry>, val pendingCredit: Double, val hasPending: Boolean, val empty: Boolean)
    private data class Payload(val record: SharedLootRecord, val bytes: ByteArray?, val failure: Throwable?)
    private data class PreparedPayloads(
        val values: List<Payload>, val cards: ArrayList<PaperMenuEntry>,
        val pendingCredit: Double, val hasPending: Boolean, val empty: Boolean,
    )

    fun open(player: Player) {
        if (closed || !player.isOnline) return
        val request = UUID.randomUUID()
        views[player.uniqueId] = request
        val previous = player.openInventory.topInventory
        fun current() = !closed && player.isOnline && views[player.uniqueId] == request && player.openInventory.topInventory === previous
        repository.list(player.uniqueId).whenCompleteNative { entries, failure ->
            if (!current()) return@whenCompleteNative
            if (failure != null) {
                views.remove(player.uniqueId, request)
                incident("list", failure)
                player.sendMessage(text(player, "unavailable", "<#f4d87a>Хранилище добычи временно недоступно."))
                return@whenCompleteNative
            }
            prepareCards(player, requireNotNull(entries), ::current).whenCompleteNative { prepared, preparationFailure ->
                if (!current()) return@whenCompleteNative
                views.remove(player.uniqueId, request)
                if (preparationFailure != null) {
                    incident("view", preparationFailure)
                    player.sendMessage(text(player, "unavailable", "<#f4d87a>Хранилище добычи временно недоступно."))
                    return@whenCompleteNative
                }
                render(player, requireNotNull(prepared))
                settle(player)
            }
        }
    }

    /** Hash/Base64 are detached worker work; one metadata-bearing native item is bound per step. */
    internal fun prepareCards(player: Player, entries: List<SharedLootRecord>, current: () -> Boolean): CompletableFuture<PreparedCards> =
        worker.submit {
            var pending = 0.0
            var hasPending = false
            val payloads = ArrayList<Payload>()
            for (record in entries) {
                if (record.state == SharedLootState.SOLD || record.state == SharedLootState.CREDITING) {
                    pending += record.salePrice ?: 0.0
                    hasPending = true
                }
                if (record.item != null) {
                    val bytes = runCatching { decodePayload(record) }
                    payloads.add(Payload(record, bytes.getOrNull(), bytes.exceptionOrNull()))
                }
            }
            // Allocate the backing capacity here; later native adds never resize/copy all cards.
            PreparedPayloads(payloads, ArrayList(payloads.size), pending, hasPending, entries.isEmpty())
        }.thenCompose { prepared ->
            nativeWork.call({ !closed && current() }) {
                val cards = prepared.cards
                val cursor = prepared.values.iterator()
                nativeWork.submit({ !closed && current() }) {
                    if (!cursor.hasNext()) true
                    else {
                        val payload = cursor.next()
                        val record = payload.record
                        val item = runCatching {
                            payload.failure?.let { throw it }
                            decode(record, requireNotNull(payload.bytes))
                        }.getOrElse { incident("decode:${record.id}", it); null }
                        if (item != null) {
                            item.editMeta { meta ->
                                val hints = if (record.state == SharedLootState.AVAILABLE) buildList {
                                    add(text(player, "claim-hint", "<#90dfc8>ЛКМ • Забрать предмет"))
                                    record.nativePrice?.let { add(text(player, "sell-hint", "<#f4d87a>ПКМ • Продать за ").append(Component.text(price(it)))) }
                                } else listOf(text(player, "pending-item", "<#f4d87a>⌛ Выдача на проверке"))
                                meta.lore(meta.lore().orEmpty() + hints)
                            }
                            cards.add(ArcMenus.entryWithContext(item, enabled = record.state == SharedLootState.AVAILABLE,
                                acceptedClicks = setOf(ClickType.LEFT, ClickType.RIGHT)) { click ->
                                if (click.event.click == ClickType.RIGHT) sell(click.player, record) else claim(click.player, record.id)
                            })
                        }
                        false
                    }
                }.thenApply { PreparedCards(cards, prepared.pendingCredit, prepared.hasPending, prepared.empty) }
            }.thenCompose { it }
        }

    private fun render(player: Player, prepared: PreparedCards) {
        val info = ArcMenus.item(ArcMenuSchema.LOST_LOOT, "info")
        if (prepared.hasPending) info.editMeta { meta ->
            meta.lore(meta.lore().orEmpty() + text(player, "awaiting-credit", "<#f4d87a>✦ К зачислению: ").append(Component.text(price(prepared.pendingCredit))))
        }
        ArcMenus.open(player, ArcMenuSchema.LOST_LOOT, text(player, "title", "<#c4a7e7>Потерянная добыча"),
            elements = mapOf(
                "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.LOST_LOOT, "back")) { it.closeInventory() },
                "info" to ArcMenus.entry(info, enabled = false),
            ),
            regions = mapOf(ArcMenuSchema.LOST_LOOT_ITEMS to prepared.cards),
        )
        if (prepared.empty) player.sendMessage(text(player, "empty", "<#f2eee8>Потерянной добычи пока нет."))
    }

    internal fun claim(player: Player, id: String) {
        if (!begin(player)) return
        if (emptySlot(player) < 0) { busy.remove(player.uniqueId); full(player); return }
        repository.reserve(id, player.uniqueId, SharedLootState.CLAIMING, UUID.randomUUID(), server, null)
            .thenCompose { record ->
                if (record == null) CompletableFuture.completedFuture<Payload?>(null)
                else worker.submit { decodePayload(record) }.handle { bytes, failure -> Payload(record, bytes, failure) }
            }.whenCompleteNative { payload, failure ->
                if (failure != null) { done(player); failed(player, "reserve:$id", failure); return@whenCompleteNative }
                if (payload == null) { done(player); return@whenCompleteNative }
                val record = payload.record
                val slot = emptySlot(player)
                if (!player.isOnline || player.isDead || slot < 0) { release(player, record); return@whenCompleteNative }
                val stack = runCatching {
                    payload.failure?.let { throw it }
                    decode(record, requireNotNull(payload.bytes))
                }.getOrElse {
                    // No native mutation was attempted. Release only this exact reservation.
                    release(player, record); incident("decode:$id", it); return@whenCompleteNative
                }
                runCatching {
                    player.inventory.setItem(slot, stack)
                    player.persistentDataContainer.set(receiptKey, PersistentDataType.STRING, requireNotNull(record.token))
                    player.updateInventory()
                    check(player.inventory.getItem(slot) == stack)
                    // Paper saveData is the indivisible native durability barrier. SQL must not
                    // acknowledge a claim before the item + exact receipt reach player storage.
                    playerData.persist(player)
                }.onSuccess { finish(player, record, SharedLootState.CLAIMED) }
                    .onFailure { done(player); failed(player, "claim:$id", it) }
            }
    }

    internal fun sell(player: Player, record: SharedLootRecord) {
        val value = record.nativePrice?.takeIf { it.isFinite() && it > 0 } ?: return
        if (record.owner != player.uniqueId.toString() || !begin(player)) return
        repository.sell(record.id, player.uniqueId, UUID.randomUUID(), value).whenCompleteNative { sold, failure ->
            done(player)
            if (failure != null) { failed(player, "sell:${record.id}", failure); return@whenCompleteNative }
            if (sold == true) {
                player.sendMessage(text(player, "sold", "<#90dfc8>Предмет продан. К зачислению: ").append(Component.text(price(value))))
                if (!credits.ready(player)) player.sendMessage(text(player, "credit-later", "<#f2eee8>Кристаллы зачислятся автоматически на сервере с EliteMobs."))
                sound(player)
            }
            refresh(player)
            settle(player)
        }
    }

    /** One durable owner gate serializes native claims and payouts across all servers. */
    internal fun settle(player: Player) {
        if (!begin(player)) return
        repository.nextPending(player.uniqueId).whenCompleteNative { next, failure ->
            if (failure != null) { done(player); incident("settle:${player.uniqueId}", failure); return@whenCompleteNative }
            if (!player.isOnline) { done(player); return@whenCompleteNative }
            val pending = next?.takeIf { it.state == SharedLootState.CLAIMING || it.state == SharedLootState.CREDITING }
            if (pending != null) {
                when (pending.state) {
                    SharedLootState.CLAIMING -> {
                        if (player.persistentDataContainer.get(receiptKey, PersistentDataType.STRING) != pending.token) {
                            done(player); incident("claim:${pending.id}", IllegalStateException("Missing native item receipt; operator reconciliation required"))
                        } else runCatching { playerData.persist(player) }
                            .onSuccess { finish(player, pending, SharedLootState.CLAIMED) }
                            .onFailure { done(player); incident("claim:${pending.id}", it) }
                    }
                    SharedLootState.CREDITING -> {
                        if (!credits.ready(player) || !credits.hasReceipt(player, requireNotNull(pending.token))) {
                            done(player)
                            if (credits.ready(player)) incident("credit:${pending.id}", IllegalStateException("Missing synchronized currency receipt; operator reconciliation required"))
                        } else awaitCredit(player, pending) { credits.persistReceipt(player) }
                    }
                    else -> done(player)
                }
                return@whenCompleteNative
            }
            val sold = next?.takeIf { it.state == SharedLootState.SOLD }
            if (sold == null || !credits.ready(player)) { done(player); return@whenCompleteNative }
            repository.reserve(sold.id, player.uniqueId, SharedLootState.CREDITING, UUID.randomUUID(), server, null)
                .whenCompleteNative { credit, reserveFailure ->
                    if (reserveFailure != null) { done(player); incident("credit:${sold.id}", reserveFailure); return@whenCompleteNative }
                    if (credit == null) { done(player); return@whenCompleteNative }
                    if (!player.isOnline || !credits.ready(player)) { release(player, credit); return@whenCompleteNative }
                    awaitCredit(player, credit) { credits.credit(player, requireNotNull(credit.salePrice), requireNotNull(credit.token)) }
                }
        }
    }

    private fun awaitCredit(player: Player, record: SharedLootRecord, action: () -> CompletableFuture<Unit>) {
        val result = runCatching(action).getOrElse { done(player); failed(player, "credit:${record.id}", it); return }
        result.whenCompleteNative { _, failure ->
            if (failure != null) { done(player); failed(player, "credit:${record.id}", failure) }
            else if (!player.isOnline) done(player) // Leave the gate for receipt reconciliation after transfer.
            else finish(player, record, SharedLootState.PAID)
        }
    }

    private fun finish(player: Player, record: SharedLootRecord, state: SharedLootState) {
        repository.finish(record.copy(state = state, item = null)).whenCompleteNative { committed, failure ->
            done(player)
            if (failure != null || committed != true) {
                failed(player, "finish:${record.id}", failure ?: IllegalStateException("Shared acknowledgement did not match"))
                return@whenCompleteNative
            }
            if (state == SharedLootState.CLAIMED && player.persistentDataContainer.get(receiptKey, PersistentDataType.STRING) == record.token)
                player.persistentDataContainer.remove(receiptKey)
            if (state == SharedLootState.PAID) {
                if (player.isOnline) player.sendMessage(text(player, "credited", "<#90dfc8>✦ Кристаллы зачислены: ").append(Component.text(price(requireNotNull(record.salePrice)))))
            }
            incidents.remove("claim:${record.id}"); incidents.remove("credit:${record.id}")
            sound(player)
            refresh(player)
        }
    }

    private fun release(player: Player, record: SharedLootRecord) {
        repository.release(record.id, player.uniqueId, UUID.fromString(record.token), record.state).whenCompleteNative { _, failure ->
            done(player)
            if (failure != null) failed(player, "release:${record.id}", failure)
            else if (player.isOnline) { if (emptySlot(player) < 0) full(player); refresh(player) }
        }
    }
    private fun begin(player: Player) = !closed && player.isOnline && !player.isDead && busy.add(player.uniqueId)
    private fun done(player: Player) { busy.remove(player.uniqueId) }
    private fun emptySlot(player: Player) = player.inventory.storageContents.indexOfFirst { it == null || it.type.isAir }
    private fun full(player: Player) = player.sendMessage(text(player, "full", "<#f4d87a>Освободите один слот в инвентаре, чтобы забрать предмет."))
    private fun sound(player: Player) { if (player.isOnline) player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, 1.2f) }
    // Refresh only our visible screen; asynchronous completion must not reopen a closed or unrelated menu.
    private fun refresh(player: Player) {
        if (player.isOnline && player.openInventory.title() == text(player, "title", "<#c4a7e7>Потерянная добыча")) open(player)
    }
    private fun failed(player: Player, key: String, failure: Throwable) {
        incident(key, failure)
        if (player.isOnline) player.sendMessage(text(player, "pending", "<#f4d87a>Операция требует проверки. Запись сохранена; повторная выдача заблокирована."))
    }
    private fun incident(key: String, failure: Throwable) {
        if (incidents.add(key)) Logging.error("SharedLostLoot operation={} retained=true", key, failure)
    }
    private fun decodePayload(record: SharedLootRecord): ByteArray {
        val encoded = requireNotNull(record.item)
        check(MySqlSharedLostLootRepository.itemHash(encoded) == record.itemHash)
        return Base64.getDecoder().decode(encoded)
    }
    private fun decode(record: SharedLootRecord, bytes: ByteArray): ItemStack {
        val stack = nativeDecode(bytes)
        check(!stack.type.isAir && stack.amount > 0)
        check(stack.itemMeta.persistentDataContainer.get(NamespacedKey("elitemobs", "soulbind"), PersistentDataType.STRING) == record.owner)
        check(!stack.itemMeta.persistentDataContainer.has(NamespacedKey("arc", "dungeon_case_reward")))
        return stack
    }
    /** Every SQL completion joins the same native budget; late/overloaded work cannot mutate a player. */
    private fun <T> CompletableFuture<T>.whenCompleteNative(action: (T?, Throwable?) -> Unit): CompletableFuture<T> =
        whenComplete { value, failure ->
            nativeWork.call({ !closed }) { action(value, failure) }.whenComplete { _, nativeFailure ->
                if (nativeFailure != null && !closed)
                    Logging.error("SharedLostLoot native completion failed; durable owner gate retained", nativeFailure)
            }
        }

    override fun close() { closed = true; tasks.close(); views = mutableMapOf(); busy = mutableSetOf() }
    private fun price(value: Double) = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() + " ✦"
}
