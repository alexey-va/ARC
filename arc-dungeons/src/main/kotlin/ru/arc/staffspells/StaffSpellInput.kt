package ru.arc.staffspells

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import io.papermc.paper.event.player.PlayerStopUsingItemEvent
import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.dungeons.ArcDungeonsPlugin
import java.util.UUID

/** Native held-release input shares one bounded clock for charged shots and carried Gravity blocks. */
private const val STAFF_CHARGE_DELAY_TICKS = 4
private const val STAFF_CHARGE_RAMP_TICKS = 32
private const val STAFF_MAX_CHARGE_TICKS = 200
internal val STAFF_CHARGED_PRIMARY_SPELLS = setOf(StaffSpell.EMBER, StaffSpell.ICICLE, StaffSpell.GRAVITY)

internal enum class StaffSpellInputButton(val configKey: String) { LEFT("left-click"), RIGHT("right-click") }

/** Activation type is shared by input routing and player-facing instructions. */
internal enum class StaffAttackInputMode { INSTANT, HOLD_RELEASE }

/** Shared by input and HUD so instructions describe the same attack path the controller executes. */
internal fun staffAttackInputMode(spell: StaffSpell, secondary: Boolean): StaffAttackInputMode = when {
    !secondary && spell in STAFF_CHARGED_PRIMARY_SPELLS -> StaffAttackInputMode.HOLD_RELEASE
    else -> StaffAttackInputMode.INSTANT
}

internal fun staffAttackInputButton(@Suppress("UNUSED_PARAMETER") spell: StaffSpell) =
    StaffSpellInputButton.RIGHT

internal fun staffDefenseInputButton(@Suppress("UNUSED_PARAMETER") spell: StaffSpell) =
    StaffSpellInputButton.LEFT

internal fun staffChargeProgress(ticksHeld: Int): Double =
    ((ticksHeld - STAFF_CHARGE_DELAY_TICKS).coerceAtLeast(0).toDouble() / STAFF_CHARGE_RAMP_TICKS)
        .coerceIn(0.0, 1.0)

internal interface StaffSpellInputActions {
    fun canCharge(player: Player, spell: StaffSpell, secondary: Boolean = player.isSneaking): Boolean
    fun canHotkey(player: Player, spell: StaffSpell, key: StaffHotkey): Boolean = false
    fun hotkey(player: Player, spell: StaffSpell, key: StaffHotkey) = Unit
    /** Refresh only ARC-owned text on prototype stacks; implementations without config keep old stacks. */
    fun refreshPrototypeLore(item: ItemStack, spell: StaffSpell): Boolean = false
    fun defend(player: Player, spell: StaffSpell) = cast(player, StaffCastInput.of(player.isSneaking, true), 0.0)
    fun beginChannel(player: Player, spell: StaffSpell): Boolean = false
    fun updateChannel(player: Player, ticksHeld: Int): Boolean = false
    fun releaseChannel(player: Player) {}
    fun cancelChannel(player: Player) {}
    fun cast(player: Player, input: StaffCastInput, charge: Double)
    fun charge(player: Player, spell: StaffSpell, progress: Double)
    fun cancelCharge(player: Player)
}

/** Owns staff input events and one bounded, lifecycle-scoped charge clock. */
internal class StaffSpellInput(
    private val actions: StaffSpellInputActions,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
) : Listener, AutoCloseable {
    private data class Charge(
        val spell: StaffSpell,
        val slot: Int,
        val worldId: UUID,
        val sneaking: Boolean,
        val item: ItemStack,
        val releaseOnItemStop: Boolean,
        var ticksHeld: Int = 0,
    )

    private data class PendingHotkey(
        val spell: StaffSpell,
        val slot: Int,
        val worldId: UUID,
        val item: ItemStack,
    )

    private val charges = mutableMapOf<UUID, Charge>()
    private val lastDefenseTick = mutableMapOf<UUID, Int>()
    private val lastAttackTick = mutableMapOf<UUID, Int>()
    private val pendingHotkeys = mutableMapOf<UUID, PendingHotkey>()
    private val lastHotkeyTick = mutableMapOf<UUID, Int>()
    private val inventoryDropTick = mutableMapOf<UUID, Int>()
    private var started = false
    private var closed = false
    private var modeledInput: StaffModeledInput? = null

    /** Register this listener from the owning module before calling start. */
    fun start() {
        check(!closed) { "Staff spell input is closed" }
        if (started) return
        started = true
        if (Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels")) {
            modeledInput = StaffModeledInput(this).also {
                Bukkit.getPluginManager().registerEvents(it, ArcDungeonsPlugin.instance)
            }
        }
        Bukkit.getOnlinePlayers().forEach(::upgradeHeldStaff)
        tasks.runTimer(1, 1, ::tickCharges)
    }

    /** Lets the module cancel input state alongside its other player-owned effects. */
    fun cancel(playerId: UUID) {
        val charge = charges.remove(playerId) ?: return
        Bukkit.getPlayer(playerId)?.let { player ->
            if (isChannel(charge.spell)) actions.cancelChannel(player)
            actions.cancelCharge(player)
            if (player.hasActiveItem() && player.activeItemHand == EquipmentSlot.HAND &&
                StaffSpell.from(player.activeItem) == charge.spell) player.clearActiveItem()
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onJoin(event: PlayerJoinEvent) { upgradeHeldStaff(event.player) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHeldSlot(event: PlayerItemHeldEvent) {
        if (event.previousSlot != event.newSlot) {
            clearPendingHotkey(event.player.uniqueId)
            cancel(event.player.uniqueId)
        }
        upgradeStaffSlot(event.player, event.newSlot)
    }

    // FMM owns HIGHEST: consume the replacement input before its native projectile handler.
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        clearPendingHotkey(player.uniqueId)
        upgradeStaffSlot(player, player.inventory.heldItemSlot)
        val firstItem = player.inventory.itemInMainHand
        val spell = StaffSpell.from(firstItem) ?: return
        if (event.useItemInHand() == Event.Result.DENY) return
        if ((event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_BLOCK) &&
            event.useInteractedBlock() == Event.Result.DENY) return

        if (event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK) {
            event.isCancelled = true
            event.setUseItemInHand(Event.Result.DENY)
            event.setUseInteractedBlock(Event.Result.DENY)
            routeLeftClick(player, spell)
            return
        }
        if (event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) return

        upgradeStaffSlot(player, player.inventory.heldItemSlot)
        val item = player.inventory.itemInMainHand
        if (event.useItemInHand() == Event.Result.DENY) return
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.useInteractedBlock() == Event.Result.DENY) return
        event.setUseInteractedBlock(Event.Result.DENY)
        if (!player.isSneaking) {
            // Primary right-click is the attack path; held charges start native use here.
            // Deny both channels before FMM's HIGHEST-priority handler.
            attack(player, spell, startNativeUse = true)
            event.setUseItemInHand(Event.Result.DENY)
            event.setUseInteractedBlock(Event.Result.DENY)
            if (StaffEliteItems.resolve(item) != null) event.isCancelled = true
            return
        }

        event.setUseItemInHand(Event.Result.DENY)
        // Both DENY results are required by FMM's air-click protection policy.
        if (StaffEliteItems.resolve(item) != null) event.isCancelled = true
        attack(player, spell)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityInteract(event: PlayerInteractEntityEvent) = interactEntity(event.player, event.hand) {
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityInteractAt(event: PlayerInteractAtEntityEvent) = interactEntity(event.player, event.hand) {
        event.isCancelled = true
    }

    /** Stops vanilla melee before damage is calculated; interaction and attack events own input. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPreAttack(event: PrePlayerAttackEntityEvent) {
        if (event.isCancelled) return
        val player = event.player
        clearPendingHotkey(player.uniqueId)
        upgradeStaffSlot(player, player.inventory.heldItemSlot)
        val spell = StaffSpell.from(player.inventory.itemInMainHand) ?: return
        if (event.willAttack()) event.isCancelled = true
        routeLeftClick(player, spell)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockDamage(event: BlockDamageEvent) {
        if (StaffSpell.from(event.player.inventory.itemInMainHand) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (StaffSpell.from(event.player.inventory.itemInMainHand) != null) event.isCancelled = true
    }

    /** Legacy staff stacks must never consume when an old native-use packet is still in flight. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onItemConsume(event: PlayerItemConsumeEvent) {
        if (StaffSpell.from(event.item) == null) return
        event.isCancelled = true
        cancel(event.player.uniqueId)
    }

    /** The Paper event carries the native item-use duration and is the authoritative release signal. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onStopUsingItem(event: PlayerStopUsingItemEvent) {
        val player = event.player
        val charge = charges[player.uniqueId] ?: return
        if (!charge.releaseOnItemStop || StaffSpell.from(event.item) != charge.spell ||
            !event.item.isSimilar(charge.item)) return
        releaseCharge(player, event.ticksHeldFor)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSneak(event: PlayerToggleSneakEvent) {
        clearPendingHotkey(event.player.uniqueId)
        charges[event.player.uniqueId]?.takeIf { it.sneaking != event.isSneaking }
            ?.let { cancel(event.player.uniqueId) }
    }

    /** Q drops the selected stack before this event, so restore is checked on the next server tick. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHotkeyDrop(event: PlayerDropItemEvent) {
        val dropped = event.itemDrop.itemStack
        val spell = StaffSpell.from(dropped) ?: return
        val player = event.player
        val playerId = player.uniqueId
        val tick = Bukkit.getCurrentTick()
        // Paper reports the player inventory as CREATIVE in creative mode, even without a screen.
        // Inventory-origin drops have their own preceding click marker; containers remain native.
        if (inventoryDropTick[playerId] == tick) return
        if (player.openInventory.type !in setOf(InventoryType.CRAFTING, InventoryType.CREATIVE)) return

        event.isCancelled = true

        // Multi-item drops identify many Ctrl+Q cases, but max-stack-one Ctrl+Q looks like Q.
        if (dropped.amount != 1) return
        if (pendingHotkeys.containsKey(playerId) || lastHotkeyTick[playerId] == tick) return

        val pending = PendingHotkey(spell, player.inventory.heldItemSlot,
            player.world.uid, dropped.clone())
        pendingHotkeys[playerId] = pending
        lastHotkeyTick[playerId] = tick

        if (tasks.runLater(1) {
                if (pendingHotkeys[playerId] !== pending) return@runLater
                pendingHotkeys.remove(playerId)
                val restored = Bukkit.getPlayer(playerId) ?: return@runLater
                if (!isCurrentHotkeyItem(restored, pending) || !actions.canHotkey(restored, spell, StaffHotkey.Q)) {
                    return@runLater
                }
                cancel(playerId)
                actions.hotkey(restored, spell, StaffHotkey.Q)
            } == null) {
            pendingHotkeys.remove(playerId)
            if (lastHotkeyTick[playerId] == tick) lastHotkeyTick.remove(playerId)
        }
    }

    /** F is staff-owned unless Shift is held, which remains the existing menu shortcut. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHotkeySwap(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        val playerId = player.uniqueId
        if (player.isSneaking) {
            clearPendingHotkey(playerId)
            return
        }

        val spell = StaffSpell.from(player.inventory.itemInMainHand)
        if (spell == null) {
            clearPendingHotkey(playerId)
            return
        }
        event.isCancelled = true
        if (pendingHotkeys.containsKey(playerId) || lastHotkeyTick[playerId] == Bukkit.getCurrentTick()) return
        if (!actions.canHotkey(player, spell, StaffHotkey.F)) return

        lastHotkeyTick[playerId] = Bukkit.getCurrentTick()
        cancel(playerId)
        actions.hotkey(player, spell, StaffHotkey.F)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        clearPendingHotkey(event.player.uniqueId)
        cancel(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        clearPendingHotkey(event.player.uniqueId)
        cancel(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        (event.whoClicked as? Player)?.let {
            clearPendingHotkey(it.uniqueId)
            cancel(it.uniqueId)
        }
    }

    /** Inventory Q is a ClickType.DROP, not the staff hotkey; guard its paired drop event. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryDropClick(event: InventoryClickEvent) {
        val creativeDrop = event is InventoryCreativeEvent && event.slotType == InventoryType.SlotType.OUTSIDE
        if (!creativeDrop && event.click != org.bukkit.event.inventory.ClickType.DROP &&
            event.click != org.bukkit.event.inventory.ClickType.CONTROL_DROP) return
        (event.whoClicked as? Player)?.let { inventoryDropTick[it.uniqueId] = Bukkit.getCurrentTick() }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        (event.whoClicked as? Player)?.let {
            clearPendingHotkey(it.uniqueId)
            cancel(it.uniqueId)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        clearPendingHotkey(event.player.uniqueId)
        inventoryDropTick.remove(event.player.uniqueId)
        cancel(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        clearPendingHotkey(event.player.uniqueId)
        inventoryDropTick.remove(event.player.uniqueId)
        cancel(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) {
        clearPendingHotkey(event.player.uniqueId)
        cancel(event.player.uniqueId)
        lastDefenseTick.remove(event.player.uniqueId)
        lastAttackTick.remove(event.player.uniqueId)
        lastHotkeyTick.remove(event.player.uniqueId)
        inventoryDropTick.remove(event.player.uniqueId)
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        modeledInput?.let(HandlerList::unregisterAll)
        modeledInput = null
        charges.keys.toList().forEach(::cancel)
        lastDefenseTick.clear()
        lastAttackTick.clear()
        pendingHotkeys.clear()
        lastHotkeyTick.clear()
        inventoryDropTick.clear()
        tasks.close()
    }

    internal fun interactEntity(player: Player, hand: EquipmentSlot, cancelEvent: () -> Unit) {
        if (hand != EquipmentSlot.HAND) return
        clearPendingHotkey(player.uniqueId)
        upgradeStaffSlot(player, player.inventory.heldItemSlot)
        val firstItem = player.inventory.itemInMainHand
        val spell = StaffSpell.from(firstItem) ?: return
        cancelEvent()
        routeRightClick(player, spell, startNativeUse = true)
    }

    private fun beginCharge(
        player: Player,
        spell: StaffSpell,
        releaseOnItemStop: Boolean = false,
        startNativeUse: Boolean = false,
    ): Boolean {
        if (player.uniqueId in charges || !actions.canCharge(player, spell)) return false
        if (isChannel(spell) && !actions.beginChannel(player, spell)) return false
        val charge = Charge(spell, player.inventory.heldItemSlot, player.world.uid,
            player.isSneaking, player.inventory.itemInMainHand.clone(), releaseOnItemStop)
        charges[player.uniqueId] = charge
        if (releaseOnItemStop && startNativeUse && !player.hasActiveItem()) {
            player.startUsingItem(EquipmentSlot.HAND)
        }
        if (!isChannel(spell)) actions.charge(player, spell, 0.0)
        return true
    }

    private fun defend(player: Player, spell: StaffSpell) {
        val tick = Bukkit.getCurrentTick()
        if (lastDefenseTick.put(player.uniqueId, tick) == tick) return
        cancel(player.uniqueId)
        actions.defend(player, spell)
    }

    private fun attack(player: Player, spell: StaffSpell, startNativeUse: Boolean = false): Boolean {
        val mode = staffAttackInputMode(spell, player.isSneaking)
        val activeCharge = charges[player.uniqueId]
        if (mode == StaffAttackInputMode.HOLD_RELEASE && activeCharge?.releaseOnItemStop == true) {
            if (startNativeUse && !player.hasActiveItem()) player.startUsingItem(EquipmentSlot.HAND)
            return true
        }

        val tick = Bukkit.getCurrentTick()
        if (lastAttackTick.put(player.uniqueId, tick) == tick) return false
        if (activeCharge != null) {
            cancel(player.uniqueId)
            return true
        }

        return when (mode) {
            StaffAttackInputMode.HOLD_RELEASE -> beginCharge(player, spell,
                releaseOnItemStop = true, startNativeUse = startNativeUse)
            StaffAttackInputMode.INSTANT -> {
                if (!actions.canCharge(player, spell)) return false
                actions.cast(player, StaffCastInput.of(player.isSneaking, quick = false), 0.0)
                true
            }
        }
    }

    private fun routeLeftClick(player: Player, spell: StaffSpell) {
        defend(player, spell)
    }

    private fun routeRightClick(player: Player, spell: StaffSpell, startNativeUse: Boolean = false) {
        attack(player, spell, startNativeUse)
    }

    private fun releaseCharge(player: Player, itemUseTicks: Int? = null) {
        val charge = charges.remove(player.uniqueId) ?: return
        val stillCurrent = isCurrentCharge(player, charge)
        val releasedTicks = itemUseTicks?.coerceAtLeast(0) ?: charge.ticksHeld
        try {
            if (isChannel(charge.spell)) {
                if (stillCurrent) actions.releaseChannel(player) else actions.cancelChannel(player)
                return
            }
            if (stillCurrent && actions.canCharge(player, charge.spell, charge.sneaking)) {
                actions.cast(player, StaffCastInput.of(charge.sneaking, quick = false),
                    staffChargeProgress(maxOf(charge.ticksHeld, releasedTicks)))
            }
        } finally {
            actions.cancelCharge(player)
            if (player.hasActiveItem() && player.activeItemHand == EquipmentSlot.HAND &&
                StaffSpell.from(player.activeItem) == charge.spell) player.clearActiveItem()
        }
    }

    internal fun leftClickModeledEntity(player: Player, cancelEvent: () -> Unit) {
        upgradeStaffSlot(player, player.inventory.heldItemSlot)
        val spell = StaffSpell.from(player.inventory.itemInMainHand) ?: return
        cancelEvent()
        routeLeftClick(player, spell)
    }

    private fun tickCharges() {
        charges.toMap().forEach { (playerId, charge) ->
            val player = Bukkit.getPlayer(playerId)
            if (player == null || !isCurrentCharge(player, charge)) {
                cancel(playerId)
                return@forEach
            }

            charge.ticksHeld++
            if (charge.ticksHeld > STAFF_MAX_CHARGE_TICKS) {
                cancel(playerId)
                return@forEach
            }
            if (isChannel(charge.spell)) {
                if (!actions.updateChannel(player, charge.ticksHeld)) cancel(playerId)
            } else if (charge.ticksHeld % STAFF_CHARGE_DELAY_TICKS == 0) {
                actions.charge(player, charge.spell, staffChargeProgress(charge.ticksHeld))
            }
        }
    }

    private fun isCurrentCharge(player: Player, charge: Charge): Boolean {
        val held = player.inventory.itemInMainHand
        return player.isOnline && !player.isDead && player.inventory.heldItemSlot == charge.slot &&
            player.world.uid == charge.worldId && player.isSneaking == charge.sneaking &&
            StaffSpell.from(held) == charge.spell && held.isSimilar(charge.item)
    }

    private fun clearPendingHotkey(playerId: UUID) { pendingHotkeys.remove(playerId) }

    private fun isCurrentHotkeyItem(player: Player, pending: PendingHotkey): Boolean {
        val held = player.inventory.itemInMainHand
        return player.isOnline && player.isValid && !player.isDead &&
            player.inventory.heldItemSlot == pending.slot && player.world.uid == pending.worldId &&
            StaffSpell.from(held) == pending.spell && held.isSimilar(pending.item)
    }

    private fun isChannel(spell: StaffSpell) = spell == StaffSpell.GRAVITY

    private fun upgradeHeldStaff(player: Player) = upgradeStaffSlot(player, player.inventory.heldItemSlot)

    /** Migrate the original stack before its first input, including items taken from old storage. */
    private fun upgradeStaffSlot(player: Player, slot: Int): Boolean {
        if (slot !in 0..8) return false
        val item = player.inventory.getItem(slot) ?: return false
        val migrated = StaffEliteItems.migrate(item)
        val spell = StaffSpell.from(item)
        val useComponentUpdated = spell != null && ensureStaffChargeUseComponent(item, spell)
        val loreUpdated = spell != null && actions.refreshPrototypeLore(item, spell)
        if (!migrated && !useComponentUpdated && !loreUpdated) return false
        player.inventory.setItem(slot, item)
        return true
    }
}
