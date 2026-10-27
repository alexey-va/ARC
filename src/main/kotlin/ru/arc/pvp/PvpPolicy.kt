package ru.arc.pvp

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.Entity
import org.bukkit.entity.EvokerFangs
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.EntityCombustByEntityEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectTypeCategory
import ru.arc.dungeons.api.ArcPvpService
import ru.arc.dungeons.api.ArcPvpService.Result
import ru.arc.logging.ArcLogging
import java.util.UUID

/** Main-thread policy shared by native damage, spell eligibility and actual-attempt feedback. */
internal class PvpPolicy(
    private val settlementProhibits: (Location) -> Boolean,
    private val teamResult: (Player, Player, Entity) -> Result,
    private val message: (Result) -> Component,
    private val clock: () -> Long = System::currentTimeMillis,
) : ArcPvpService, Listener, AutoCloseable {
    private val lastHint = mutableMapOf<UUID, Long>()
    private var failureLogged = false

    override fun check(attacker: Player, victim: Player, source: Entity): Result {
        check(Bukkit.isPrimaryThread()) { "PvP policy belongs to the server thread" }
        if (attacker.uniqueId == victim.uniqueId) return Result.ALLOWED
        return try {
            if (settlementProhibits(attacker.location) || settlementProhibits(victim.location)) Result.SETTLEMENT
            else teamResult(attacker, victim, source)
        } catch (failure: RuntimeException) {
            unavailable(failure)
        } catch (failure: LinkageError) {
            unavailable(failure)
        }
    }

    override fun notifyDenied(attacker: Player, reason: Result) {
        check(Bukkit.isPrimaryThread()) { "PvP feedback belongs to the server thread" }
        if (reason == Result.ALLOWED) return
        val now = clock()
        if (lastHint[attacker.uniqueId]?.let { now - it < 2_000 } == true) return
        lastHint[attacker.uniqueId] = now
        attacker.sendMessage(message(reason))
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        val direct = (event as? EntityDamageByEntityEvent)?.damager ?: event.damageSource.directEntity
        val attacker = pvpPlayer(event.damageSource.causingEntity) ?: pvpPlayer(direct) ?: return
        val result = check(attacker, victim, direct ?: attacker)
        if (result == Result.ALLOWED) return
        event.isCancelled = true
        notifyDenied(attacker, result)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    fun onCombust(event: EntityCombustByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = pvpPlayer(event.combuster) ?: return
        val result = check(attacker, victim, event.combuster)
        if (result == Result.ALLOWED) return
        event.isCancelled = true
        notifyDenied(attacker, result)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    fun onSplash(event: PotionSplashEvent) {
        val attacker = event.potion.shooter as? Player ?: return
        if (!harmful(event.potion.effects)) return
        event.affectedEntities.filterIsInstance<Player>().forEach { victim ->
            val result = check(attacker, victim, event.potion)
            if (result != Result.ALLOWED) {
                event.setIntensity(victim, 0.0)
                notifyDenied(attacker, result)
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    fun onCloud(event: AreaEffectCloudApplyEvent) {
        val cloud = event.entity
        val attacker = cloud.source as? Player ?: return
        if (!harmful(cloud.customEffects) && !harmful(cloud.basePotionType?.potionEffects.orEmpty())) return
        event.affectedEntities.removeIf { target ->
            val victim = target as? Player ?: return@removeIf false
            val result = check(attacker, victim, cloud)
            if (result != Result.ALLOWED) notifyDenied(attacker, result)
            result != Result.ALLOWED
        }
        // Native justTeams may already have removed a protected target at LOWEST. Never guess that lost target from nearby players.
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { lastHint.remove(event.player.uniqueId) }

    private fun unavailable(failure: Throwable): Result {
        if (!failureLogged) {
            failureLogged = true
            ArcLogging.error("PvP rejected: settlement or justTeams policy is unavailable", failure)
        }
        return Result.UNAVAILABLE
    }

    override fun close() { lastHint.clear() }
}

private fun harmful(effects: Collection<PotionEffect>): Boolean =
    effects.any { it.type.category == PotionEffectTypeCategory.HARMFUL }

/** Bounded ownership resolution, including vanilla indirect attacks attributed by DamageSource. */
internal fun pvpPlayer(entity: Entity?, depth: Int = 0): Player? {
    if (entity == null || depth >= 4) return null
    return when (entity) {
        is Player -> entity
        is Projectile -> pvpPlayer(entity.shooter as? Entity, depth + 1)
        is AreaEffectCloud -> pvpPlayer(entity.source as? Entity, depth + 1)
        is TNTPrimed -> pvpPlayer(entity.source, depth + 1)
        is EvokerFangs -> pvpPlayer(entity.owner, depth + 1)
        is Tameable -> entity.owner as? Player
        else -> null
    }
}
