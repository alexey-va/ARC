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
import ru.arc.core.whenCompleteSync
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.util.Logging
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** SQL owns each item/receivable; native mutations require a durable receipt before SQL acknowledgement. */
internal class SharedLostLootMailbox(
    private val repository: SharedLostLootRepository,
    private val server: String,
    private val text: (String, String) -> Component,
    private val credits: LostLootCreditGateway,
    private val playerData: PaperPlayerDataPersistence = NativePaperPlayerDataPersistence,
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val busy = mutableSetOf<UUID>()
    private val incidents = mutableSetOf<String>()
    private val views = mutableMapOf<UUID, UUID>()
    private val receiptKey = NamespacedKey("arc", "lost_loot_receipt")
    private var closed = false

    fun start() { tasks.runTimer(100, 100) { Bukkit.getOnlinePlayers().forEach(::settle) } }
    @EventHandler fun joined(event: PlayerJoinEvent) { tasks.runLater(40) { settle(event.player) } }

    fun open(player: Player) {
        if (closed || !player.isOnline) return
        val request = UUID.randomUUID()
        views[player.uniqueId] = request
        val previous = player.openInventory.topInventory
        repository.list(player.uniqueId).whenCompleteSync(tasks) { entries, failure ->
            if (!player.isOnline || views[player.uniqueId] != request || player.openInventory.topInventory !== previous) return@whenCompleteSync
            views.remove(player.uniqueId, request)
            if (failure != null) { incident("list", failure); player.sendMessage(text("unavailable", "<#f4d87a>Хранилище добычи временно недоступно.")); return@whenCompleteSync }
            render(player, requireNotNull(entries))
            settle(player)
        }
    }

    private fun render(player: Player, entries: List<SharedLootRecord>) {
        val pending = entries.filter { it.state == SharedLootState.SOLD || it.state == SharedLootState.CREDITING }
        val info = ArcMenus.item(ArcMenuSchema.LOST_LOOT, "info")
        if (pending.isNotEmpty()) info.editMeta { meta ->
            meta.lore(meta.lore().orEmpty() + text("awaiting-credit", "<#f4d87a>✦ К зачислению: ").append(Component.text(price(pending.sumOf { it.salePrice ?: 0.0 }))))
        }
        ArcMenus.open(player, ArcMenuSchema.LOST_LOOT, text("title", "<#c4a7e7>Потерянная добыча"),
            elements = mapOf(
                "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.LOST_LOOT, "back")) { it.closeInventory() },
                "info" to ArcMenus.entry(info, enabled = false),
            ),
            regions = mapOf(ArcMenuSchema.LOST_LOOT_ITEMS to entries.filter { it.item != null }.mapNotNull { record ->
                val item = runCatching { decode(record) }.getOrElse { incident("decode:${record.id}", it); return@mapNotNull null }
                item.editMeta { meta ->
                    val hints = if (record.state == SharedLootState.AVAILABLE) buildList {
                        add(text("claim-hint", "<#90dfc8>ЛКМ • Забрать предмет"))
                        record.nativePrice?.let { add(text("sell-hint", "<#f4d87a>ПКМ • Продать за ").append(Component.text(price(it)))) }
                    } else listOf(text("pending-item", "<#f4d87a>⌛ Выдача на проверке"))
                    meta.lore(meta.lore().orEmpty() + hints)
                }
                ArcMenus.entryWithContext(item, enabled = record.state == SharedLootState.AVAILABLE,
                    acceptedClicks = setOf(ClickType.LEFT, ClickType.RIGHT)) { click ->
                    if (click.event.click == ClickType.RIGHT) sell(click.player, record) else claim(click.player, record.id)
                }
            }),
        )
        if (entries.isEmpty()) player.sendMessage(text("empty", "<#f2eee8>Потерянной добычи пока нет."))
    }

    internal fun claim(player: Player, id: String) {
        if (!begin(player)) return
        if (emptySlot(player) < 0) { busy.remove(player.uniqueId); full(player); return }
        repository.reserve(id, player.uniqueId, SharedLootState.CLAIMING, UUID.randomUUID(), server, null)
            .whenCompleteSync(tasks) { record, failure ->
                if (failure != null) { done(player); failed(player, "reserve:$id", failure); return@whenCompleteSync }
                if (record == null) { done(player); return@whenCompleteSync }
                val slot = emptySlot(player)
                if (!player.isOnline || player.isDead || slot < 0) { release(player, record); return@whenCompleteSync }
                val stack = runCatching { decode(record) }.getOrElse {
                    // Corrupt payload remains visible to operators; no native mutation was attempted.
                    release(player, record); incident("decode:$id", it); return@whenCompleteSync
                }
                runCatching {
                    player.inventory.setItem(slot, stack)
                    player.persistentDataContainer.set(receiptKey, PersistentDataType.STRING, requireNotNull(record.token))
                    player.updateInventory()
                    check(player.inventory.getItem(slot) == stack)
                    playerData.persist(player)
                }.onSuccess { finish(player, record, SharedLootState.CLAIMED) }
                    .onFailure { done(player); failed(player, "claim:$id", it) }
            }
    }

    internal fun sell(player: Player, record: SharedLootRecord) {
        val value = record.nativePrice?.takeIf { it.isFinite() && it > 0 } ?: return
        if (record.owner != player.uniqueId.toString() || !begin(player)) return
        repository.sell(record.id, player.uniqueId, UUID.randomUUID(), value).whenCompleteSync(tasks) { sold, failure ->
            done(player)
            if (failure != null) { failed(player, "sell:${record.id}", failure); return@whenCompleteSync }
            if (sold == true) {
                player.sendMessage(text("sold", "<#90dfc8>Предмет продан. К зачислению: ").append(Component.text(price(value))))
                if (!credits.ready(player)) player.sendMessage(text("credit-later", "<#f2eee8>Кристаллы зачислятся автоматически на сервере с EliteMobs."))
                sound(player)
            }
            refresh(player)
            settle(player)
        }
    }

    /** One durable owner gate serializes native claims and payouts across all servers. */
    internal fun settle(player: Player) {
        if (!begin(player)) return
        repository.nextPending(player.uniqueId).whenCompleteSync(tasks) { next, failure ->
            if (failure != null) { done(player); incident("settle:${player.uniqueId}", failure); return@whenCompleteSync }
            if (!player.isOnline) { done(player); return@whenCompleteSync }
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
                return@whenCompleteSync
            }
            val sold = next?.takeIf { it.state == SharedLootState.SOLD }
            if (sold == null || !credits.ready(player)) { done(player); return@whenCompleteSync }
            repository.reserve(sold.id, player.uniqueId, SharedLootState.CREDITING, UUID.randomUUID(), server, null)
                .whenCompleteSync(tasks) { credit, reserveFailure ->
                    if (reserveFailure != null) { done(player); incident("credit:${sold.id}", reserveFailure); return@whenCompleteSync }
                    if (credit == null) { done(player); return@whenCompleteSync }
                    if (!player.isOnline || !credits.ready(player)) { release(player, credit); return@whenCompleteSync }
                    awaitCredit(player, credit) { credits.credit(player, requireNotNull(credit.salePrice), requireNotNull(credit.token)) }
                }
        }
    }

    private fun awaitCredit(player: Player, record: SharedLootRecord, action: () -> CompletableFuture<Unit>) {
        val result = runCatching(action).getOrElse { done(player); failed(player, "credit:${record.id}", it); return }
        result.whenCompleteSync(tasks) { _, failure ->
            if (failure != null) { done(player); failed(player, "credit:${record.id}", failure) }
            else if (!player.isOnline) done(player) // Leave the gate for receipt reconciliation after transfer.
            else finish(player, record, SharedLootState.PAID)
        }
    }

    private fun finish(player: Player, record: SharedLootRecord, state: SharedLootState) {
        repository.finish(record.copy(state = state, item = null)).whenCompleteSync(tasks) { committed, failure ->
            done(player)
            if (failure != null || committed != true) {
                failed(player, "finish:${record.id}", failure ?: IllegalStateException("Shared acknowledgement did not match"))
                return@whenCompleteSync
            }
            if (state == SharedLootState.CLAIMED && player.persistentDataContainer.get(receiptKey, PersistentDataType.STRING) == record.token)
                player.persistentDataContainer.remove(receiptKey)
            if (state == SharedLootState.PAID) {
                if (player.isOnline) player.sendMessage(text("credited", "<#90dfc8>✦ Кристаллы зачислены: ").append(Component.text(price(requireNotNull(record.salePrice)))))
            }
            incidents.remove("claim:${record.id}"); incidents.remove("credit:${record.id}")
            sound(player)
            refresh(player)
        }
    }

    private fun release(player: Player, record: SharedLootRecord) {
        repository.release(record.id, player.uniqueId, UUID.fromString(record.token), record.state).whenCompleteSync(tasks) { _, failure ->
            done(player)
            if (failure != null) failed(player, "release:${record.id}", failure)
            else if (player.isOnline) { if (emptySlot(player) < 0) full(player); refresh(player) }
        }
    }
    private fun begin(player: Player) = !closed && player.isOnline && !player.isDead && busy.add(player.uniqueId)
    private fun done(player: Player) { busy.remove(player.uniqueId) }
    private fun emptySlot(player: Player) = player.inventory.storageContents.indexOfFirst { it == null || it.type.isAir }
    private fun full(player: Player) = player.sendMessage(text("full", "<#f4d87a>Освободите один слот в инвентаре, чтобы забрать предмет."))
    private fun sound(player: Player) { if (player.isOnline) player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, 1.2f) }
    // Refresh only our visible screen; asynchronous completion must not reopen a closed or unrelated menu.
    private fun refresh(player: Player) {
        if (player.isOnline && player.openInventory.title() == text("title", "<#c4a7e7>Потерянная добыча")) open(player)
    }
    private fun failed(player: Player, key: String, failure: Throwable) {
        incident(key, failure)
        if (player.isOnline) player.sendMessage(text("pending", "<#f4d87a>Операция требует проверки. Запись сохранена; повторная выдача заблокирована."))
    }
    private fun incident(key: String, failure: Throwable) {
        if (incidents.add(key)) Logging.error("SharedLostLoot operation={} retained=true", key, failure)
    }
    private fun decode(record: SharedLootRecord): ItemStack {
        val encoded = requireNotNull(record.item)
        check(MySqlSharedLostLootRepository.itemHash(encoded) == record.itemHash)
        val stack = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded))
        check(!stack.type.isAir && stack.amount > 0)
        check(stack.itemMeta.persistentDataContainer.get(NamespacedKey("elitemobs", "soulbind"), PersistentDataType.STRING) == record.owner)
        check(!stack.itemMeta.persistentDataContainer.has(NamespacedKey("arc", "dungeon_case_reward")))
        return stack
    }
    override fun close() { closed = true; tasks.close(); views.clear(); busy.clear() }
    private fun price(value: Double) = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() + " ✦"
}
