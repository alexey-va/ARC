package ru.arc.staffspells

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.logging.ArcLogging
import java.util.concurrent.atomic.AtomicBoolean

/** Spell-only role checks preserve the network's general Lands bypass permissions. */
internal object StaffSpellLandsProtection {
    private val failureLogged = AtomicBoolean()

    fun canAttack(attacker: Player, victim: Player): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled("Lands")) return true
        return try {
            ActiveLandsSpellProtection.canAttack(attacker, victim)
        } catch (failure: RuntimeException) {
            reject(failure)
        } catch (failure: LinkageError) {
            reject(failure)
        }
    }

    private fun reject(failure: Throwable): Boolean {
        if (failureLogged.compareAndSet(false, true))
            ArcLogging.error("Staff spell PvP rejected: Lands protection could not be checked", failure)
        return false
    }
}

/** Loaded only with an enabled Lands provider; optional API types stay out of the caller. */
private object ActiveLandsSpellProtection {
    fun canAttack(attacker: Player, victim: Player): Boolean {
        val plugin = Bukkit.getPluginManager().getPlugin("ArcDungeons") ?: return false
        val integration = LandsIntegration.of(plugin)
        val world = integration.getWorld(victim.world) ?: return true
        val at = victim.location
        // UUID overloads check the actual roles without lands.bypass.*. Native damage
        // events still enforce Lands' remaining PvP/war rules and other protections.
        return world.hasRoleFlag(attacker.uniqueId, at, Flags.ATTACK_PLAYER) &&
            world.hasRoleFlag(victim.uniqueId, at, Flags.ATTACK_PLAYER)
    }
}
