package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.staffspells.api.ArcStaffSpellService
import ru.arc.staffspells.api.StaffSpellDuelContext
import ru.arc.staffspells.api.StaffSpellDuelPolicy
import ru.arc.util.Logging
import java.util.UUID

internal data class DuelSpellAuthorization(
    internal val registrationId: UUID,
    val context: StaffSpellDuelContext,
)

/** Main-thread admission contract used by duel-only staff casts. */
internal interface StaffSpellDuelAccess {
    fun context(caster: Player): DuelSpellAuthorization?
    fun isCurrent(authorization: DuelSpellAuthorization, caster: Player): Boolean
    fun canHit(authorization: DuelSpellAuthorization, caster: Player, target: Player): Boolean
}

/** Stable across StaffSpells module reloads so consumers keep their exact policy registrations. */
internal class ArcStaffSpellServiceProvider(
    private val plugin: Plugin,
    private val controller: () -> StaffSpellController?,
) : ArcStaffSpellService, StaffSpellDuelAccess, Listener, AutoCloseable {
    private data class Registration(
        val id: UUID,
        val owner: Plugin,
        val policy: StaffSpellDuelPolicy,
    )

    private val registrations = linkedMapOf<UUID, Registration>()
    @Volatile private var lastFingerprint: String? = null
    @Volatile private var closed = false

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    override fun createDuelStaff(spellId: String): ItemStack {
        check(Bukkit.isPrimaryThread()) { "Duel staff items must be created on the server thread" }
        val spell = StaffSpell.entries.firstOrNull { it.id == spellId }
            ?: throw IllegalArgumentException("Unknown staff spell: $spellId")
        return requireNotNull(controller()) { "ARC staff spells are not ready" }.duelItem(spell)
    }

    override fun fingerprint(): String {
        check(Bukkit.isPrimaryThread()) { "Staff spell fingerprints must be read on the server thread" }
        controller()?.let { it.duelFingerprint(plugin.description.version).also { value -> lastFingerprint = value } }
        return lastFingerprint ?: StaffSpellSettings.fingerprint(plugin.description.version, null)
    }

    override fun isAvailable(): Boolean {
        check(Bukkit.isPrimaryThread()) { "Staff spell availability must be checked on the server thread" }
        return !closed && controller() != null
    }

    override fun registerDuelPolicy(owner: Plugin, policy: StaffSpellDuelPolicy): AutoCloseable {
        check(Bukkit.isPrimaryThread()) { "Duel policies must be registered on the server thread" }
        check(!closed) { "ARC staff spells service is closed" }
        require(owner.isEnabled) { "Duel policy owner must be enabled" }
        val id = UUID.randomUUID()
        val registration = Registration(id, owner, policy)
        registrations[id] = registration
        return AutoCloseable { remove(registration) }
    }

    override fun resetPlayer(playerId: UUID) {
        check(Bukkit.isPrimaryThread()) { "Player staff spell state must be reset on the server thread" }
        if (closed) return
        controller()?.resetPlayer(playerId)
    }

    override fun context(caster: Player): DuelSpellAuthorization? {
        if (closed || !Bukkit.isPrimaryThread() || !caster.isOnline || !caster.isValid || caster.isDead) return null
        for (registration in registrations.values.toList()) {
            if (!registration.owner.isEnabled) continue
            val context = try {
                registration.policy.context(caster)
            } catch (failure: RuntimeException) {
                logPolicyFailure("context", registration, failure)
                null
            } catch (failure: LinkageError) {
                logPolicyFailure("context", registration, failure)
                null
            } ?: continue
            if (context.isValid()) return DuelSpellAuthorization(registration.id, context)
        }
        return null
    }

    override fun canHit(authorization: DuelSpellAuthorization, caster: Player, target: Player): Boolean {
        if (closed || !Bukkit.isPrimaryThread() || !caster.isOnline || !caster.isValid || caster.isDead ||
            !target.isOnline || !target.isValid || target.isDead || target.isInvulnerable ||
            target.uniqueId == caster.uniqueId || target.world != caster.world) return false
        if (!isCurrent(authorization, caster)) return false
        val registration = registrations[authorization.registrationId] ?: return false
        return try {
            registration.policy.canHit(caster, target, authorization.context) && isCurrent(authorization, caster)
        } catch (failure: RuntimeException) {
            logPolicyFailure("hit authorization", registration, failure)
            false
        } catch (failure: LinkageError) {
            logPolicyFailure("hit authorization", registration, failure)
            false
        }
    }

    override fun isCurrent(authorization: DuelSpellAuthorization, caster: Player): Boolean {
        if (closed || !Bukkit.isPrimaryThread() || !caster.isOnline || !caster.isValid || caster.isDead) return false
        val registration = registrations[authorization.registrationId] ?: return false
        if (!registration.owner.isEnabled) return false
        val current = try {
            registration.policy.context(caster)
        } catch (failure: RuntimeException) {
            logPolicyFailure("context recheck", registration, failure)
            return false
        } catch (failure: LinkageError) {
            logPolicyFailure("context recheck", registration, failure)
            return false
        } ?: return false
        return current == authorization.context && current.isValid()
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        registrations.values.filter { it.owner === event.plugin }.forEach(::remove)
    }

    private fun remove(registration: Registration) {
        if (registrations[registration.id] === registration) registrations.remove(registration.id)
    }

    private fun logPolicyFailure(operation: String, registration: Registration, failure: Throwable) {
        Logging.warn(
            "Duel staff policy from {} failed during {}; rejecting spell hit",
            registration.owner.name,
            operation,
            failure,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        registrations.clear()
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    private fun StaffSpellDuelContext.isValid() =
        matchId() != null && round() >= 0 && damageMultiplier().isFinite() &&
            damageMultiplier() > 0.0 && damageMultiplier() <= StaffSpellDuelContext.MAX_DAMAGE_MULTIPLIER
}
