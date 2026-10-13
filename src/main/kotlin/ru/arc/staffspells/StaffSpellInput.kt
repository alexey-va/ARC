package ru.arc.staffspells

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Consumable
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import io.papermc.paper.event.player.PlayerStopUsingItemEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.key.Key
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
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import java.util.UUID

/** The charged item is never consumed; its long native use duration only supplies hold/release input. */
private const val STAFF_USE_SECONDS = 3_600f
private const val STAFF_CHARGE_DELAY_TICKS = 4
private const val STAFF_CHARGE_RAMP_TICKS = 32
private const val STAFF_MAX_HOLD_TICKS = 200
private val STAFF_CHARGED_PRIMARY_SPELLS = setOf(StaffSpell.EMBER, StaffSpell.ICICLE)

private val STAFF_SILENT_USE_SOUND = Key.key("minecraft:intentionally_empty")

/** Mutates only the native use component, preserving ItemsAdder and ARC item metadata. */
internal fun configureStaffUse(item: ItemStack): Boolean {
    if (StaffSpell.from(item) == null) return false
    val existing = item.getData(DataComponentTypes.CONSUMABLE)
    if (existing != null && existing.consumeSeconds() == STAFF_USE_SECONDS &&
        existing.animation() == ItemUseAnimation.NONE && existing.sound() == STAFF_SILENT_USE_SOUND &&
        !existing.hasConsumeParticles() && existing.consumeEffects().isEmpty()) return false

    val use = Consumable.consumable()
        .consumeSeconds(STAFF_USE_SECONDS)
        .animation(ItemUseAnimation.NONE)
        .sound(STAFF_SILENT_USE_SOUND)
        .hasConsumeParticles(false)
        .build()
    item.setData(DataComponentTypes.CONSUMABLE, use)
    return true
}

internal fun staffChargeProgress(ticksHeld: Int): Double =
    ((ticksHeld - STAFF_CHARGE_DELAY_TICKS).coerceAtLeast(0).toDouble() / STAFF_CHARGE_RAMP_TICKS)
        .coerceIn(0.0, 1.0)

internal interface StaffSpellInputActions {
    fun canCharge(player: Player, spell: StaffSpell): Boolean
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
        var ticksHeld: Int = 0,
    )

    private val charges = mutableMapOf<UUID, Charge>()
    private val lastDefenseTick = mutableMapOf<UUID, Int>()
    private var started = false
    private var closed = false

    /** Register this listener from the owning module before calling start. */
    fun start() {
        check(!closed) { "Staff spell input is closed" }
        if (started) return
        started = true
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
        if (event.previousSlot != event.newSlot) cancel(event.player.uniqueId)
        upgradeStaffSlot(event.player, event.newSlot)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val firstItem = player.inventory.itemInMainHand
        val spell = StaffSpell.from(firstItem) ?: return

        if (event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK) {
            if (event.action == Action.LEFT_CLICK_BLOCK) event.setUseInteractedBlock(Event.Result.DENY)
            castQuick(player, spell)
            return
        }
        if (event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) return

        upgradeStaffSlot(player, player.inventory.heldItemSlot)
        val item = player.inventory.itemInMainHand
        if (event.useItemInHand() == Event.Result.DENY) return
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.useInteractedBlock() == Event.Result.DENY) return
        if (event.action == Action.RIGHT_CLICK_BLOCK) event.setUseInteractedBlock(Event.Result.DENY)

        if (!beginCharge(player, spell, item)) {
            event.setUseItemInHand(Event.Result.DENY)
            return
        }
        startNativeUse(player, spell)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityInteract(event: PlayerInteractEntityEvent) = interactEntity(event.player, event.hand) {
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityInteractAt(event: PlayerInteractAtEntityEvent) = interactEntity(event.player, event.hand) {
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onStopUsing(event: PlayerStopUsingItemEvent) {
        val player = event.player
        val charge = charges.remove(player.uniqueId) ?: return
        val held = player.inventory.itemInMainHand
        val ticksHeld = event.ticksHeldFor.coerceAtLeast(0)
        val stillCurrent = ticksHeld <= STAFF_MAX_HOLD_TICKS &&
            player.inventory.heldItemSlot == charge.slot && player.world.uid == charge.worldId &&
            player.isSneaking == charge.sneaking && StaffSpell.from(held) == charge.spell &&
            held.isSimilar(charge.item) && StaffSpell.from(event.item) == charge.spell &&
            event.item.isSimilar(charge.item)

        try {
            if (isChannel(charge.spell)) {
                if (stillCurrent) actions.releaseChannel(player) else actions.cancelChannel(player)
                return
            }
            if (stillCurrent && actions.canCharge(player, charge.spell)) {
                actions.cast(player, StaffCastInput.of(charge.sneaking, quick = false), staffChargeProgress(ticksHeld))
            }
        } finally {
            actions.cancelCharge(player)
        }
    }

    /** Stops vanilla melee before damage is calculated; interaction and attack events own input. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPreAttack(event: PrePlayerAttackEntityEvent) {
        val player = event.player
        val spell = StaffSpell.from(player.inventory.itemInMainHand) ?: return
        if (event.willAttack()) event.isCancelled = true
        castQuick(player, spell)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockDamage(event: BlockDamageEvent) {
        if (StaffSpell.from(event.player.inventory.itemInMainHand) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (StaffSpell.from(event.player.inventory.itemInMainHand) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onItemConsume(event: PlayerItemConsumeEvent) {
        if (StaffSpell.from(event.item) == null) return
        event.isCancelled = true
        cancel(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSneak(event: PlayerToggleSneakEvent) {
        charges[event.player.uniqueId]?.takeIf { it.sneaking != event.isSneaking }
            ?.let { cancel(event.player.uniqueId) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) = cancel(event.player.uniqueId)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwap(event: PlayerSwapHandItemsEvent) = cancel(event.player.uniqueId)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        (event.whoClicked as? Player)?.let { cancel(it.uniqueId) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        (event.whoClicked as? Player)?.let { cancel(it.uniqueId) }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onWorldChange(event: PlayerChangedWorldEvent) = cancel(event.player.uniqueId)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) = cancel(event.player.uniqueId)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) { cancel(event.player.uniqueId); lastDefenseTick.remove(event.player.uniqueId) }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        charges.keys.toList().forEach(::cancel)
        lastDefenseTick.clear()
        tasks.close()
    }

    private fun interactEntity(player: Player, hand: EquipmentSlot, cancelEvent: () -> Unit) {
        if (hand != EquipmentSlot.HAND) return
        val firstItem = player.inventory.itemInMainHand
        val spell = StaffSpell.from(firstItem) ?: return
        cancelEvent()
        upgradeStaffSlot(player, player.inventory.heldItemSlot)
        val item = player.inventory.itemInMainHand
        if (beginCharge(player, spell, item)) startNativeUse(player, spell)
    }

    private fun beginCharge(player: Player, spell: StaffSpell, item: ItemStack): Boolean {
        if (player.uniqueId in charges) return true
        if (!actions.canCharge(player, spell)) return false

        if (!isChannel(spell) && !requiresCharge(spell, player.isSneaking)) {
            actions.cast(player, StaffCastInput.of(player.isSneaking, quick = false), 0.0)
            return false
        }
        if (isChannel(spell) && !actions.beginChannel(player, spell)) return false
        val charge = Charge(spell, player.inventory.heldItemSlot, player.world.uid,
            player.isSneaking, item.clone())
        charges[player.uniqueId] = charge
        if (!isChannel(spell)) actions.charge(player, spell, 0.0)
        return true
    }

    private fun startNativeUse(player: Player, spell: StaffSpell) {
        if (!player.hasActiveItem() || player.activeItemHand != EquipmentSlot.HAND ||
            StaffSpell.from(player.activeItem) != spell) player.startUsingItem(EquipmentSlot.HAND)
    }

    private fun castQuick(player: Player, spell: StaffSpell) {
        val tick = Bukkit.getCurrentTick()
        if (lastDefenseTick.put(player.uniqueId, tick) == tick) return
        cancel(player.uniqueId)
        actions.defend(player, spell)
    }

    private fun tickCharges() {
        charges.toMap().forEach { (playerId, charge) ->
            val player = Bukkit.getPlayer(playerId)
            if (player == null || !isCurrentCharge(player, charge, requireActiveUse = true)) {
                cancel(playerId)
                return@forEach
            }

            charge.ticksHeld++
            if (charge.ticksHeld > STAFF_MAX_HOLD_TICKS) {
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

    private fun isCurrentCharge(player: Player, charge: Charge, requireActiveUse: Boolean): Boolean {
        val held = player.inventory.itemInMainHand
        return player.isOnline && !player.isDead && player.inventory.heldItemSlot == charge.slot &&
            player.world.uid == charge.worldId && player.isSneaking == charge.sneaking &&
            StaffSpell.from(held) == charge.spell && held.isSimilar(charge.item) &&
            (!requireActiveUse || (player.hasActiveItem() && player.activeItemHand == EquipmentSlot.HAND &&
                StaffSpell.from(player.activeItem) == charge.spell))
    }

    private fun isChannel(spell: StaffSpell) = spell == StaffSpell.INFERNO || spell == StaffSpell.GRAVITY

    private fun requiresCharge(spell: StaffSpell, secondary: Boolean) =
        !secondary && spell in STAFF_CHARGED_PRIMARY_SPELLS

    private fun upgradeHeldStaff(player: Player) = upgradeStaffSlot(player, player.inventory.heldItemSlot)

    /** Replaces the hotbar slot only when a legacy staff needs the single use-component migration. */
    private fun upgradeStaffSlot(player: Player, slot: Int): Boolean {
        if (slot !in 0..8) return false
        val item = player.inventory.getItem(slot) ?: return false
        if (!configureStaffUse(item)) return false
        player.inventory.setItem(slot, item)
        return true
    }
}
