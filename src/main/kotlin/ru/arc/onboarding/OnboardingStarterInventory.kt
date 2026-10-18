package ru.arc.onboarding

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Containers.CMIPlayerInventory.CMIInventorySlot
import org.bukkit.Bukkit
import org.bukkit.event.inventory.InventoryType
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.paper.playerstate.PaperPlayerStateSnapshot
import ru.arc.persistence.AtomicFileStore
import ru.arc.util.Common
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-test replacement only. Capture/encoding and the fixed 42 item slots belong to Paper;
 * checksum/JSON/atomic backup run on ARC's worker. A successful future means the backup was
 * read back, the caller's durable enrollment finished, and native kit items were verified.
 * CMI owns the accepted kit's command task on the following tick; it exposes no completion
 * receipt, and later ARC cancellation does not revoke that already accepted native grant.
 * Backups are retained per operation. Lifecycle cancellation never authorizes a late clear.
 */
internal class OnboardingStarterInventory(
    private val root: Path,
    private val worker: BoundedAsyncExecutor,
    private val tasks: LifecycleTaskScope,
    private val resolveKit: (Player) -> (() -> Unit) = ::nativeStarterKit,
    private val codec: PaperPlayerStateCodec = PaperPlayerStateCodec(),
    private val persistPlayerData: (Player) -> Unit = Player::saveData,
) {
    private val states = PaperPlayerStateService(codec, persistPlayerData = persistPlayerData)
    private val replacing = ConcurrentHashMap<UUID, UUID>()

    class AlreadyReplacing : IllegalStateException("An inventory restart is already pending")
    class InventoryChanged : IllegalStateException("The inventory changed while its backup was pending")
    class CraftingItemsPresent : IllegalStateException("Remove crafting input and result items before restarting")

    fun isPending(playerId: UUID): Boolean = replacing.containsKey(playerId)

    /** [valid] and [beforeReplace] are invoked only on Paper's owning thread. */
    fun replace(
        player: Player,
        valid: () -> Boolean,
        beforeReplace: () -> CompletableFuture<Unit>,
    ): CompletableFuture<Path> {
        val playerId = player.uniqueId
        val operationId = UUID.randomUUID()
        if (replacing.putIfAbsent(playerId, operationId) != null) {
            return CompletableFuture.failedFuture(AlreadyReplacing())
        }
        val replacement: CompletableFuture<Path> = try {
            requireCurrent(player, valid)
            requireEmptyCrafting(player)
            val grant = resolveKit(player)
            val snapshot = states.capture(player, System.currentTimeMillis())
            val payload = codec.encodePayload(snapshot)
            worker.submit { backup(playerId, operationId, payload) }.thenCompose { path ->
                tasks.callSync {
                    requireCurrent(player, valid)
                    requireEmptyCrafting(player)
                    requireUnchanged(player, snapshot)
                    beforeReplace()
                }.thenCompose { it }.thenCompose {
                    tasks.callSync {
                        requireCurrent(player, valid)
                        requireEmptyCrafting(player)
                        requireUnchanged(player, snapshot)
                        try { apply(player, snapshot, grant) }
                        catch (failure: Throwable) {
                            throw IllegalStateException("Starter inventory replacement failed; backup=$path", failure)
                        }
                        path
                    }
                }
            }
        } catch (failure: Throwable) {
            CompletableFuture.failedFuture(failure)
        }
        return replacement.whenComplete { _, _ -> replacing.remove(playerId, operationId) }
    }

    private fun requireCurrent(player: Player, valid: () -> Boolean) {
        check(Bukkit.isPrimaryThread()) { "Inventory restart requires the Paper thread" }
        if (!player.isOnline || player.isDead || Bukkit.getPlayer(player.uniqueId) !== player || !valid()) {
            throw CancellationException("Inventory restart was superseded")
        }
    }

    private fun requireUnchanged(player: Player, snapshot: PaperPlayerStateSnapshot) {
        if (states.inventoryMismatches(player, snapshot).isNotEmpty()) throw InventoryChanged()
    }

    private fun requireEmptyCrafting(player: Player) {
        val crafting = player.openInventory.topInventory
        if (crafting.type in setOf(InventoryType.CRAFTING, InventoryType.WORKBENCH) &&
            crafting.contents.any { it != null && !it.isEmpty }) throw CraftingItemsPresent()
    }

    private fun apply(player: Player, snapshot: PaperPlayerStateSnapshot, grant: () -> Unit) {
        try {
            // Do not close a container while its old cursor could be returned or dropped.
            player.setItemOnCursor(ItemStack.empty())
            player.inventory.storageContents = arrayOfNulls(PaperPlayerStateSnapshot.STORAGE_SLOT_COUNT)
            player.inventory.armorContents = arrayOfNulls(PaperPlayerStateSnapshot.ARMOR_SLOT_COUNT)
            player.inventory.setItemInOffHand(ItemStack.empty())
            player.inventory.heldItemSlot = 0
            grant()
            player.updateInventory()
            persistPlayerData(player)
        } catch (failure: Throwable) {
            try { states.restoreInventoryAndVerify(player, snapshot) }
            catch (restoreFailure: Throwable) { failure.addSuppressed(restoreFailure) }
            throw failure
        }
    }

    /** Worker-only: AtomicFileStore includes fsync, atomic replacement and validated readback. */
    private fun backup(playerId: UUID, operationId: UUID, payload: ByteArray): Path {
        check(!Bukkit.isPrimaryThread()) { "Inventory backup must not run on the Paper thread" }
        val file = AtomicFileStore(
            root = root,
            relativePath = Path.of("data/onboarding-inventory-backups/$playerId/$operationId.json"),
            maxBytes = 24L * 1024L * 1024L,
            encode = { state: PaperPlayerStateEnvelope -> Common.gson.toJson(state).toByteArray(UTF_8) },
            decode = { bytes -> Common.gson.fromJson(bytes.toString(UTF_8), PaperPlayerStateEnvelope::class.java) },
            validate = { state -> require(codec.validateEnvelope(state) == playerId) { "Inventory backup belongs to another player" } },
        )
        file.write(codec.wrapPayload(payload))
        return file.path
    }
}

/** CMI 9.8.10.4's raw grant applies items and kit commands without changing normal kit usage. */
internal fun nativeStarterKit(player: Player): () -> Unit {
    check(Bukkit.getPluginManager().isPluginEnabled("CMI")) { "CMI is unavailable" }
    val manager = requireNotNull(CMI.getInstance()) { "CMI is unavailable" }.kitsManager
    val kit = requireNotNull(manager.getKit("start", true)) { "CMI starter kit 'start' is unavailable" }
    fun preflight() {
        check(kit.isEnabled && kit.cost == 0.0 && kit.expCost == 0) { "CMI starter kit must be enabled and free" }
    }
    preflight()
    fun copied(item: ItemStack?): ItemStack? = item?.takeUnless(ItemStack::isEmpty)?.clone()
    val items = kit.getItems(player).map(::copied)
    check(items.size == PaperPlayerStateSnapshot.STORAGE_SLOT_COUNT) { "CMI starter kit has an invalid slot count" }
    val slots = listOf(CMIInventorySlot.Boots, CMIInventorySlot.Pants, CMIInventorySlot.ChestPlate,
        CMIInventorySlot.Helmet, CMIInventorySlot.OffHand)
    val equipment = slots.map { copied(kit.getExtraItem(player, it)) }
    val commands = kit.getCommands(player).orEmpty().toList()
    check(items.any { it != null } || equipment.any { it != null }) { "CMI starter kit has no verifiable items" }
    return {
        preflight()
        check(manager.getKit("start", true) === kit && kit.getItems(player).map(::copied) == items &&
            slots.map { copied(kit.getExtraItem(player, it)) } == equipment && kit.getCommands(player).orEmpty() == commands) {
            "CMI starter kit changed while its inventory backup was pending"
        }
        manager.giveKit(player, kit)
        // giveKit is void and an acquire event may silently cancel it. Check the native slot
        // mapping before acknowledging replacement; command-created items may use other slots.
        val receivedItems = items.withIndex().all { (slot, expected) ->
            expected == null || player.inventory.getItem(if (slot >= 27) slot - 27 else slot + 9) == expected
        }
        val receivedEquipment = (player.inventory.armorContents.asList() + player.inventory.itemInOffHand).map(::copied)
        check(receivedItems && receivedEquipment == equipment) { "CMI starter kit was cancelled or did not apply its items" }
    }
}
