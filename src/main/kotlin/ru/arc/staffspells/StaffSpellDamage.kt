package ru.arc.staffspells

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatEnemyAuthorization
import com.magmaguy.elitemobs.advancedcombat.damage.AdvancedDamageScaling
import com.magmaguy.elitemobs.api.EliteMobDamagedByPlayerEventFilter
import com.magmaguy.elitemobs.combatsystem.CombatDamageContext
import com.magmaguy.elitemobs.config.SkillsConfig
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.playerdata.ElitePlayerInventory
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.thirdparty.custommodels.CustomModel
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import ru.arc.util.Logging
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Server-thread-only prototype damage. Captured Staves level is virtual item level; BLAZE_ROD
 * supplies boss modifier lookup. Configured power is the attack multiplier; no native EM/FMM enchantments. */
internal class StaffSpellDamage {
    private val eliteMobsFailureLogged = AtomicBoolean()

    fun eligible(player: Player, target: LivingEntity): Boolean {
        if (!isReady(player, target)) return false
        if (!isEliteMobsEnabled()) return true
        return withEliteMobs(false) { EliteMobsRuntime.canTarget(player, target) }
    }

    /** Null means the player is invalid or the enabled optional bridge failed. */
    fun capture(player: Player): StaffSpellCast? {
        if (!player.isOnline || !player.isValid || player.isDead) return null
        val attackId = UUID.randomUUID()
        if (!isEliteMobsEnabled()) return StaffSpellCast(player.uniqueId, attackId, 1, null, null, false)
        return withEliteMobs(null) { EliteMobsRuntime.capture(player, attackId) }
    }

    /** Rechecks at impact; true only if health plus absorption decreased. */
    fun hit(player: Player, target: LivingEntity, cast: StaffSpellCast, power: Double, vanillaDamage: Double): Boolean {
        if (cast.casterId != player.uniqueId || cast.eliteMobsEnabled != isEliteMobsEnabled()) return false
        if (!power.isFinite() || power <= 0.0 || !vanillaDamage.isFinite() || vanillaDamage <= 0.0) return false
        if (!eligible(player, target)) return false

        val before = target.health + target.absorptionAmount
        val attempted = if (cast.eliteMobsEnabled) {
            withEliteMobs(false) {
                EliteMobsRuntime.hit(player, target, cast, power, vanillaDamage)
                true
            }
        } else {
            target.damage(vanillaDamage, player)
            true
        }
        val after = target.health + target.absorptionAmount
        return attempted && before - after > HEALTH_EPSILON
    }

    private fun isReady(player: Player, target: LivingEntity): Boolean =
        player.isOnline && player.isValid && !player.isDead && target.isValid && !target.isDead &&
            !target.isInvulnerable && target !is Player && target !is ArmorStand && player.world == target.world &&
            !target.hasMetadata("NPC") && (target !is Tameable || !target.isTamed)

    private fun isEliteMobsEnabled() = Bukkit.getPluginManager().isPluginEnabled(ELITE_MOBS_PLUGIN)

    private inline fun <T> withEliteMobs(fallback: T, operation: () -> T): T = try {
        operation()
    } catch (failure: RuntimeException) {
        logEliteMobsFailure(failure)
        fallback
    } catch (failure: LinkageError) {
        logEliteMobsFailure(failure)
        fallback
    }

    private fun logEliteMobsFailure(failure: Throwable) = if (eliteMobsFailureLogged.compareAndSet(false, true))
        Logging.error("Staff spell damage rejected: EliteMobs integration failed; verify the pinned EliteMobs API", failure)
    else Unit

    private companion object {
        const val ELITE_MOBS_PLUGIN = "EliteMobs"
        const val HEALTH_EPSILON = 1.0e-6
    }
}

/** Immutable cast snapshot; contains no mutable item reference. */
internal data class StaffSpellCast(val casterId: UUID, val attackId: UUID, val skillLevel: Int,
    val criticalHit: Boolean?, val loudStrikesBonus: Double?, val eliteMobsEnabled: Boolean)

/** Resolved only on the guarded branch where EliteMobs is enabled. */
private object EliteMobsRuntime {
    fun canTarget(player: Player, target: LivingEntity): Boolean =
        AdvancedCombatEnemyAuthorization.canTargetWithMagicWeapon(player, target)

    fun capture(player: Player, attackId: UUID): StaffSpellCast {
        val skillLevel = if (!SkillsConfig.isWorldExcludedFromSkills(player) && PlayerData.isDataLoaded(player.uniqueId)) {
            maxOf(1, PlayerData.getSkillLevel(player.uniqueId, SkillType.STAVES))
        } else {
            1
        }
        val inventory = ElitePlayerInventory.getPlayer(player)
        return StaffSpellCast(
            player.uniqueId,
            attackId,
            skillLevel,
            EliteMobDamagedByPlayerEventFilter.captureCriticalHit(player),
            inventory?.getLoudStrikesBonusMultiplier(true) ?: 0.0,
            eliteMobsEnabled = true,
        )
    }

    fun hit(player: Player, target: LivingEntity, cast: StaffSpellCast, power: Double, vanillaDamage: Double) {
        val elite = EntityTracker.getEliteMobEntity(target)
        if (elite == null) {
            target.damage(vanillaDamage, player)
            return
        }

        val damage = AdvancedDamageScaling.magicWeapon(
            elite, cast.skillLevel, cast.skillLevel.toDouble(), power, Material.BLAZE_ROD,
        )
        if (!damage.isFinite() || damage <= 0.0) return

        val source = CombatDamageContext.PlayerDamageSource(cast.attackId, SkillType.STAVES, cast.criticalHit, cast.loudStrikesBonus)
        val damageCall = Runnable {
            CombatDamageContext.runPlayerToEliteBypass(source) {
                target.damage(damage, player)
            }
        }
        val customModel = (elite as? CustomBossEntity)?.customModel
        if (customModel == null) damageCall.run()
        else CustomModel.runProjectileDamageBypass(damageCall)
    }
}
