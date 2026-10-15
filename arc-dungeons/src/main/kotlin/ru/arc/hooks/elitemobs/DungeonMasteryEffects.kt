package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.EliteSkillXpGainEvent
import com.magmaguy.elitemobs.api.PlayerDamagedByEliteMobEvent
import com.magmaguy.elitemobs.config.SkillsConfig
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.WeaponIdentityResolver
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.staffspells.StaffEliteItems
import ru.arc.staffspells.StaffSpellCast
import ru.arc.staffspells.StaffSpellsModule
import java.util.IdentityHashMap
import kotlin.math.ceil

/** Semantic keys for configured progression copy; magic applies only to migrated StaffSpells items. */
internal enum class DungeonMasteryKind(val configKey: String) {
    ARMOR_DEFENSE("armor-defense"),
    MELEE_ATTACK_SPEED("melee-attack-speed"),
    STAFF_CAST_SPEED("staff-cast-speed"),
}

internal fun dungeonMasteryPercent(level: Int): Int = (level.coerceAtLeast(0) / 10).coerceAtMost(10)

internal fun dungeonMasteryKind(skill: SkillType): DungeonMasteryKind? = when (skill) {
    SkillType.ARMOR -> DungeonMasteryKind.ARMOR_DEFENSE
    SkillType.SWORDS, SkillType.AXES, SkillType.HOES, SkillType.MACES, SkillType.SPEARS,
    SkillType.TRIDENTS -> DungeonMasteryKind.MELEE_ATTACK_SPEED
    SkillType.STAVES, SkillType.WANDS -> DungeonMasteryKind.STAFF_CAST_SPEED
    SkillType.BOWS, SkillType.CROSSBOWS -> null
}

/** Native identity is authoritative: unresolved FMM carriers never fall through to material guessing. */
internal fun dungeonHeldMasterySkill(player: Player): SkillType? =
    dungeonMasteryWeaponSkill(player.inventory.itemInMainHand)

private fun dungeonMasteryWeaponSkill(item: ItemStack?): SkillType? {
    val skill = WeaponIdentityResolver.progressionSkill(item) ?: return null
    return when (dungeonMasteryKind(skill)) {
        DungeonMasteryKind.MELEE_ATTACK_SPEED -> skill
        DungeonMasteryKind.STAFF_CAST_SPEED -> skill.takeIf {
            StaffSpellsModule.ready() && StaffEliteItems.resolve(item) != null
        }
        else -> null
    }
}

/** Native defensive skills run at HIGH; mastery must not relax their combined 85% ceiling. */
internal fun dungeonMasteryDefendedDamage(incoming: Double, current: Double, level: Int): Double {
    if (!incoming.isFinite() || incoming <= 0.0 || !current.isFinite() || current <= 0.0) return current
    val reduced = current * (1.0 - dungeonMasteryPercent(level) / 100.0)
    val nativeFloor = PlayerDamagedByEliteMobEvent.capAggregateReduction(incoming, 0.0)
    // Preserve a foreign owner's stronger reduction instead of raising its damage to our floor.
    return maxOf(reduced, minOf(current, nativeFloor))
}

/** Round toward the original cooldown so discrete ticks never grant more than the stated speed. */
internal fun dungeonMasteryCooldownTicks(ticks: Long, level: Int): Long {
    val percent = dungeonMasteryPercent(level)
    if (ticks <= 0L || percent == 0) return ticks
    return ceil(ticks.toDouble() / (1.0 + percent / 100.0)).toLong().coerceIn(1L, ticks)
}

/**
 * Server-thread-only, derived from native skill XP without another progression store. The caller
 * supplies native dungeon membership and cross-server readiness. One lifecycle owns the transient
 * attack-speed modifier, event snapshots, and a one-second reconciliation for native XP/sync updates.
 * Held-item events reconcile immediately; close/quit/death remove only this owner's modifier.
 */
internal class DungeonMasteryEffects(
    private val inDungeon: (Player) -> Boolean,
    private val ready: (Player) -> Boolean = { true },
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val incomingDamage = IdentityHashMap<PlayerDamagedByEliteMobEvent, Double>()
    private var started = false
    private var closed = false

    fun start() {
        if (started || closed) return
        started = true
        Bukkit.getOnlinePlayers().forEach(::refresh)
        tasks.runTimer(20, 20) { Bukkit.getOnlinePlayers().forEach(::refresh) }
    }

    fun refresh(player: Player) = refresh(player, player.inventory.itemInMainHand)

    private fun refresh(player: Player, held: ItemStack?) {
        val attribute = player.getAttribute(Attribute.ATTACK_SPEED) ?: return
        val skill = if (eligible(player)) dungeonMasteryWeaponSkill(held) else null
        val bonus = if (skill != null && dungeonMasteryKind(skill) == DungeonMasteryKind.MELEE_ATTACK_SPEED)
            dungeonMasteryPercent(PlayerData.getSkillLevel(player.uniqueId, skill)) / 100.0 else 0.0
        val current = attribute.getModifier(ATTACK_SPEED_KEY)
        if (bonus > 0.0 && current?.amount == bonus &&
            current.operation == AttributeModifier.Operation.MULTIPLY_SCALAR_1) return
        if (current != null) attribute.removeModifier(current)
        if (bonus > 0.0) attribute.addTransientModifier(AttributeModifier(
            ATTACK_SPEED_KEY, bonus, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
        ))
    }

    /** A native cast snapshot preserves its original family/level through channel release. */
    fun staffCooldownTicks(player: Player, cast: StaffSpellCast, ticks: Long): Long {
        if (!cast.nativeEliteWeapon || !cast.eliteMobsEnabled || cast.duelAuthorization != null ||
            cast.casterId != player.uniqueId || !eligible(player)) return ticks
        val held = dungeonHeldMasterySkill(player) ?: return ticks
        if (dungeonMasteryKind(held) != DungeonMasteryKind.STAFF_CAST_SPEED ||
            held.name != cast.progressionSkill.name) return ticks
        return dungeonMasteryCooldownTicks(ticks, cast.skillLevel)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun rememberIncoming(event: PlayerDamagedByEliteMobEvent) {
        if (!event.isCancelled && event.damage.isFinite() && event.damage > 0.0 && eligible(event.player))
            incomingDamage[event] = event.damage
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun defend(event: PlayerDamagedByEliteMobEvent) {
        val incoming = incomingDamage.remove(event) ?: return
        if (event.isCancelled || !eligible(event.player)) return
        event.damage = dungeonMasteryDefendedDamage(incoming, event.damage,
            PlayerData.getSkillLevel(event.player.uniqueId, SkillType.ARMOR))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun held(event: PlayerItemHeldEvent) {
        if (!event.isCancelled) refresh(event.player, event.player.inventory.getItem(event.newSlot))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun swapped(event: PlayerSwapHandItemsEvent) {
        if (!event.isCancelled) refresh(event.player, event.mainHandItem)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun slotChanged(event: PlayerInventorySlotChangeEvent) {
        if (event.slot == event.player.inventory.heldItemSlot) refresh(event.player, event.newItemStack)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun worldChanged(event: PlayerChangedWorldEvent) = refresh(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun joined(event: PlayerJoinEvent) = refreshLater(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun respawned(event: PlayerRespawnEvent) = refreshLater(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun xpGained(event: EliteSkillXpGainEvent) {
        if (!event.isCancelled) refreshLater(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun gameModeChanged(event: PlayerGameModeChangeEvent) {
        if (event.isCancelled) return
        if (event.newGameMode == GameMode.SPECTATOR) remove(event.player) else refreshLater(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun quit(event: PlayerQuitEvent) = remove(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun died(event: PlayerDeathEvent) = remove(event.entity)

    private fun refreshLater(player: Player) {
        if (!closed) tasks.runLater(1) { refresh(player) }
    }

    private fun eligible(player: Player): Boolean = !closed && player.isOnline && player.isValid &&
        !player.isDead && player.gameMode != GameMode.SPECTATOR && ready(player) &&
        PlayerData.isDataLoaded(player.uniqueId) && SkillsConfig.isSkillSystemEnabled() &&
        !SkillsConfig.isWorldExcludedFromSkills(player) && inDungeon(player)

    private fun remove(player: Player) {
        val attribute = player.getAttribute(Attribute.ATTACK_SPEED) ?: return
        attribute.getModifier(ATTACK_SPEED_KEY)?.let(attribute::removeModifier)
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        incomingDamage.clear()
        Bukkit.getOnlinePlayers().forEach(::remove)
    }

    internal companion object {
        val ATTACK_SPEED_KEY = NamespacedKey("arc", "dungeon_mastery_attack_speed")
    }
}
