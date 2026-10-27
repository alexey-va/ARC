package ru.arc.pvp

import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.dungeons.api.ArcPvpService.Result
import java.util.UUID

/** Exact justTeams 2.8.1 public cached getters; no storage loads or native event changes. */
internal class JustTeamsPvpBridge(private val plugin: JavaPlugin) {
    private val manager = plugin.javaClass.getMethod("getTeamManager").invoke(plugin)
    private val playerTeam = manager.javaClass.getMethod("getPlayerTeamCached", UUID::class.java)
    private val teamType = playerTeam.returnType
    private val teamId = teamType.getMethod("getId")
    private val pvpEnabled = teamType.getMethod("isPvpEnabled")
    private val allies = teamType.getMethod("getAllies")

    fun check(attacker: Player, victim: Player, source: Entity): Result = try {
        checkCached(attacker, victim, source)
    } catch (failure: ReflectiveOperationException) {
        throw IllegalStateException("The native justTeams cached PvP API failed", failure)
    }

    private fun checkCached(attacker: Player, victim: Player, source: Entity): Result {
        val victimTeam = playerTeam.invoke(manager, victim.uniqueId) ?: return Result.ALLOWED
        val attackerTeam = playerTeam.invoke(manager, attacker.uniqueId) ?: return Result.ALLOWED
        val victimId = teamId.invoke(victimTeam) as Int
        val attackerId = teamId.invoke(attackerTeam) as Int
        if (victimId == attackerId) {
            if (pvpEnabled.invoke(victimTeam) != true) return Result.TEAM
            if ((source is Projectile || source is AreaEffectCloud) &&
                plugin.config.getBoolean("settings.friendly_fire.block_projectiles", false)) return Result.TEAM_PROJECTILE
            if (source is TNTPrimed && plugin.config.getBoolean("settings.friendly_fire.block_tnt", false)) return Result.TEAM_TNT
            return Result.ALLOWED
        }
        val attackerAllies = allies.invoke(attackerTeam) as Collection<*>
        val victimAllies = allies.invoke(victimTeam) as Collection<*>
        return if (victimId in attackerAllies || attackerId in victimAllies) Result.ALLY else Result.ALLOWED
    }
}
