package ru.arc.staffspells

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
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
import ru.arc.dungeons.ArcDungeonsPlugin
import java.util.UUID

/** Click-toggle charges use one bounded tick clock for strength and channel progress. */
private const val STAFF_CHARGE_DELAY_TICKS = 4
private const val STAFF_CHARGE_RAMP_TICKS = 32
private const val STAFF_MAX_CHARGE_TICKS = 200
private val STAFF_CHARGED_PRIMARY_SPELLS = setOf(StaffSpell.EMBER, StaffSpell.ICICLE)

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
    private val lastAttackTick = mutableMapOf<UUID, Int>()
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
        if (event.previousSlot != event.newSlot) cancel(event.player.uniqueId)
        upgradeStaffSlot(event.player, event.newSlot)
    }

    // FMM owns HIGHEST: consume the replacement input before its native projectile handler.
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
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
            attack(player, spell)
            return
        }
        if (event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) return

        upgradeStaffSlot(player, player.inventory.heldItemSlot)
        val item = player.inventory.itemInMainHand
        if (event.useItemInHand() == Event.Result.DENY) return
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.useInteractedBlock() == Event.Result.DENY) return
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        // Both DENY results are required by FMM's air-click protection policy.
        if (StaffEliteItems.resolve(item) != null) event.isCancelled = true
        defend(player, spell)
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
        upgradeStaffSlot(player, player.inventory.heldItemSlot)
        val spell = StaffSpell.from(player.inventory.itemInMainHand) ?: return
        if (event.willAttack()) event.isCancelled = true
        attack(player, spell)
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
    fun onQuit(event: PlayerQuitEvent) {
        cancel(event.player.uniqueId)
        lastDefenseTick.remove(event.player.uniqueId)
        lastAttackTick.remove(event.player.uniqueId)
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
        tasks.close()
    }

    internal fun interactEntity(player: Player, hand: EquipmentSlot, cancelEvent: () -> Unit) {
        if (hand != EquipmentSlot.HAND) return
        upgradeStaffSlot(player, player.inventory.heldItemSlot)
        val firstItem = player.inventory.itemInMainHand
        val spell = StaffSpell.from(firstItem) ?: return
        cancelEvent()
        defend(player, spell)
    }

    private fun beginCharge(player: Player, spell: StaffSpell) {
        if (player.uniqueId in charges || !actions.canCharge(player, spell)) return
        if (isChannel(spell) && !actions.beginChannel(player, spell)) return
        val charge = Charge(spell, player.inventory.heldItemSlot, player.world.uid,
            player.isSneaking, player.inventory.itemInMainHand.clone())
        charges[player.uniqueId] = charge
        if (!isChannel(spell)) actions.charge(player, spell, 0.0)
    }

    private fun defend(player: Player, spell: StaffSpell) {
        val tick = Bukkit.getCurrentTick()
        if (lastDefenseTick.put(player.uniqueId, tick) == tick) return
        cancel(player.uniqueId)
        actions.defend(player, spell)
    }

    private fun attack(player: Player, spell: StaffSpell) {
        val tick = Bukkit.getCurrentTick()
        if (lastAttackTick.put(player.uniqueId, tick) == tick) return

        if (player.uniqueId in charges) {
            releaseCharge(player)
            return
        }
        if (isChannel(spell) || requiresCharge(spell, player.isSneaking)) {
            beginCharge(player, spell)
            return
        }
        if (!actions.canCharge(player, spell)) return
        actions.cast(player, StaffCastInput.of(player.isSneaking, quick = false), 0.0)
    }

    private fun releaseCharge(player: Player) {
        val charge = charges.remove(player.uniqueId) ?: return
        val stillCurrent = isCurrentCharge(player, charge)
        try {
            if (isChannel(charge.spell)) {
                if (stillCurrent) actions.releaseChannel(player) else actions.cancelChannel(player)
                return
            }
            if (stillCurrent && actions.canCharge(player, charge.spell)) {
                actions.cast(player, StaffCastInput.of(charge.sneaking, quick = false),
                    staffChargeProgress(charge.ticksHeld))
            }
        } finally {
            actions.cancelCharge(player)
        }
    }

    internal fun attackModeledEntity(player: Player, cancelEvent: () -> Unit) {
        upgradeStaffSlot(player, player.inventory.heldItemSlot)
        val spell = StaffSpell.from(player.inventory.itemInMainHand) ?: return
        cancelEvent()
        attack(player, spell)
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

    private fun isChannel(spell: StaffSpell) = spell == StaffSpell.GRAVITY

    private fun requiresCharge(spell: StaffSpell, secondary: Boolean) =
        !secondary && spell in STAFF_CHARGED_PRIMARY_SPELLS

    private fun upgradeHeldStaff(player: Player) = upgradeStaffSlot(player, player.inventory.heldItemSlot)

    /** Migrate the original stack before its first input, including items taken from old storage. */
    private fun upgradeStaffSlot(player: Player, slot: Int): Boolean {
        if (slot !in 0..8) return false
        val item = player.inventory.getItem(slot) ?: return false
        val migrated = StaffEliteItems.migrate(item)
        if (!migrated) return false
        player.inventory.setItem(slot, item)
        return true
    }
}
