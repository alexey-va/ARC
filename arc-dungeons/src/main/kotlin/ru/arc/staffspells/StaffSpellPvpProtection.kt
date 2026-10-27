package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.dungeons.api.ArcPvpService
import ru.arc.dungeons.api.ArcPvpService.Result
import ru.arc.logging.ArcLogging
import java.util.concurrent.atomic.AtomicBoolean

/** Spells share ARC's actual settlement/clan policy; no spell-only Lands role checks remain. */
internal object StaffSpellPvpProtection {
    private val failureLogged = AtomicBoolean()

    fun canAttack(attacker: Player, victim: Player): Boolean =
        result(attacker, victim) == Result.ALLOWED

    fun notifyDenied(attacker: Player, victim: Player) {
        val service = Bukkit.getServicesManager().load(ArcPvpService::class.java) ?: return
        val result = service.check(attacker, victim, attacker)
        if (result != Result.ALLOWED) service.notifyDenied(attacker, result)
    }

    private fun result(attacker: Player, victim: Player): Result {
        val service = Bukkit.getServicesManager().load(ArcPvpService::class.java)
        if (service != null) return service.check(attacker, victim, attacker)
        if (failureLogged.compareAndSet(false, true))
            ArcLogging.error("Staff spell PvP rejected: ARC's shared PvP service is unavailable")
        return Result.UNAVAILABLE
    }
}
