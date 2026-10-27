package ru.arc.staffspells

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatEnemyAuthorization
import com.magmaguy.elitemobs.advancedcombat.damage.AdvancedDamageScaling
import com.magmaguy.elitemobs.api.EliteMobDamagedByPlayerEventFilter
import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.combatsystem.CombatDamageContext
import com.magmaguy.elitemobs.config.SkillsConfig
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.items.GearRestrictionHandler
import com.magmaguy.elitemobs.items.ItemDurability
import com.magmaguy.elitemobs.items.customenchantments.SoulbindEnchantment
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.playerdata.ElitePlayerInventory
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.MagicStrike
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.WeaponIdentityResolver
import com.magmaguy.elitemobs.thirdparty.custommodels.CustomModel
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import ru.arc.logging.ArcLogging
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Server-thread-only spell damage. Mapped EliteMobs weapons carry a cast-time snapshot of native weapon stats. */
internal class StaffSpellDamage(
    private val duelAccess: StaffSpellDuelAccess? = null,
    private val canAttackPlayer: (Player, Player) -> Boolean = StaffSpellLandsProtection::canAttack,
) {
    private val eliteMobsFailureLogged = AtomicBoolean()
    // The controller supplies its live actor registry; item tags never authorize a summon target.
    internal var summonCast: (LivingEntity) -> StaffSpellCast? = { null }

    fun inputAllowed(player: Player): Boolean = duelAccess?.inputAllowed(player) ?: true

    fun eligible(player: Player, target: LivingEntity, cast: StaffSpellCast? = null): Boolean {
        if (target is Player && target.uniqueId == player.uniqueId) return false
        val authorization = cast?.duelAuthorization
        if (authorization != null) {
            val opponent = if (target is Player) target else {
                val ownerCast = summonCast(target) ?: return false
                val owner = Bukkit.getPlayer(ownerCast.casterId) ?: return false
                if (!target.isValid || target.isDead || target.isInvulnerable || target.world != player.world ||
                    ownerCast.duelAuthorization == null || !isCurrent(owner, ownerCast)) return false
                owner
            }
            return isReadyDuelTarget(player, opponent) && cast.casterId == player.uniqueId &&
                cast.duelAccess === duelAccess && duelAccess?.isCurrent(authorization, player) == true &&
                duelAccess.canHit(authorization, player, opponent)
        }
        if (summonCast(target) != null) return false
        if (!isReady(player, target)) return false
        if (duelAccess?.context(player) != null) return false
        if (target is Player) return isReadyDuelTarget(player, target) && player.world.pvp &&
            duelAccess?.context(target) == null && canAttackPlayer(player, target)
        if (!isEliteMobsEnabled()) return true
        return withEliteMobs(false) { EliteMobsRuntime.canTarget(player, target) }
    }

    /** Null means the player is invalid or the enabled optional bridge failed. */
    fun capture(player: Player, duelOnly: Boolean = false): StaffSpellCast? {
        if (!player.isOnline || !player.isValid || player.isDead || !inputAllowed(player)) return null
        duelAccess?.context(player)?.let { authorization ->
            if (!duelOnly) return null
            return StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false,
                authorization, duelAccess)
        }
        if (duelOnly) return null
        val attackId = UUID.randomUUID()
        if (!isEliteMobsEnabled()) return StaffSpellCast(player.uniqueId, attackId, 1, null, null, false)
        return withEliteMobs(null) { EliteMobsRuntime.capture(player, attackId) }
    }

    /** Duel casts remain valid only for their captured match, round, owner registration and participant pair. */
    fun isCurrent(player: Player, cast: StaffSpellCast): Boolean {
        if (cast.casterId != player.uniqueId || !player.isOnline || !player.isValid || player.isDead || !inputAllowed(player)) return false
        val authorization = cast.duelAuthorization
        return if (authorization != null) {
            cast.duelAccess === duelAccess && duelAccess?.isCurrent(authorization, player) == true
        } else {
            duelAccess?.context(player) == null
        }
    }

    /** Rechecks at impact; true only if health plus absorption decreased. */
    fun hit(player: Player, target: LivingEntity, cast: StaffSpellCast, power: Double, vanillaDamage: Double): Boolean =
        hit(player, target, cast, power, vanillaDamage, primaryMagicStrike(cast))

    fun hit(
        player: Player,
        target: LivingEntity,
        cast: StaffSpellCast,
        power: Double,
        vanillaDamage: Double,
        magicStrike: MagicStrike?,
    ): Boolean {
        if (cast.casterId != player.uniqueId || !isCurrent(player, cast)) return false
        if (!power.isFinite() || power <= 0.0 || !vanillaDamage.isFinite() || vanillaDamage <= 0.0) return false
        // Last mutation boundary: a target moving behind cover before contact must be safe for every spell owner.
        if (player.world != target.world || !visible(player.eyeLocation, target)) return false
        val authorization = cast.duelAuthorization
        if (authorization != null) {
            if (cast.eliteMobsEnabled ||
                !eligible(player, target, cast)) return false
            val amount = vanillaDamage * authorization.context.damageMultiplier()
            if (!amount.isFinite() || amount <= 0.0 ||
                !eligible(player, target, cast)) return false
            val before = target.health + target.absorptionAmount
            target.damage(amount, player)
            return before - target.health - target.absorptionAmount > HEALTH_EPSILON
        }
        if (cast.eliteMobsEnabled != isEliteMobsEnabled() || !eligible(player, target, cast)) return false

        val before = target.health + target.absorptionAmount
        // PvP uses configured ordinary damage, attributed to the caster. Native damage events
        // retain Lands/region/team cancellation; EliteMobs scaling belongs to PvE only.
        if (target is Player) {
            target.damage(vanillaDamage, player)
            return before - target.health - target.absorptionAmount > HEALTH_EPSILON
        }
        val attempted = if (cast.eliteMobsEnabled) {
            withEliteMobs(false) {
                EliteMobsRuntime.hit(player, target, cast, power, vanillaDamage, magicStrike)
                true
            }
        } else {
            target.damage(vanillaDamage, magicDamageSource(player))
            true
        }
        val after = target.health + target.absorptionAmount
        val hit = attempted && before - after > HEALTH_EPSILON
        if (hit && cast.nativeEliteWeapon && cast.eliteMobsEnabled) {
            withEliteMobs(Unit) { cast.enchantments.ignite(player, target) }
        }
        return hit
    }

    private fun isReady(player: Player, target: LivingEntity): Boolean =
        player.isOnline && player.isValid && !player.isDead && target.isValid && !target.isDead &&
            !target.isInvulnerable && target !is ArmorStand && player.world == target.world &&
            !target.hasMetadata("NPC") && (target !is Tameable || !target.isTamed)

    private fun isReadyDuelTarget(caster: Player, target: Player) =
        caster.isOnline && caster.isValid && !caster.isDead && target.isOnline && target.isValid &&
            !target.isDead && !target.isInvulnerable && caster.uniqueId != target.uniqueId && caster.world == target.world

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
        ArcLogging.error("Staff spell damage rejected: EliteMobs integration failed; verify the pinned EliteMobs API", failure)
    else Unit

    private companion object {
        const val ELITE_MOBS_PLUGIN = "EliteMobs"
        const val HEALTH_EPSILON = 1.0e-6
    }
}

private fun primaryMagicStrike(cast: StaffSpellCast): MagicStrike? {
    if (!cast.nativeEliteWeapon || !cast.eliteMobsEnabled || cast.duelAuthorization != null) return null
    return when (cast.progressionSkill) {
        StaffWeaponFamily.WANDS -> MagicStrike.WAND_MISSILE
        StaffWeaponFamily.STAVES -> MagicStrike.STAFF_FIREBALL
    }
}

private fun magicDamageSource(player: Player): DamageSource = DamageSource.builder(DamageType.MAGIC)
    .withDirectEntity(player)
    .withCausingEntity(player)
    .build()

/** ARC-owned identity keeps duel casts loadable when EliteMobs is absent. */
internal enum class StaffWeaponFamily { WANDS, STAVES }

/** Immutable cast snapshot; contains no mutable item reference. */
internal data class StaffSpellCast(
    val casterId: UUID,
    val attackId: UUID,
    val skillLevel: Int,
    val criticalHit: Boolean?,
    val loudStrikesBonus: Double?,
    val eliteMobsEnabled: Boolean,
    val duelAuthorization: DuelSpellAuthorization? = null,
    val duelAccess: StaffSpellDuelAccess? = null,
    val visualCharge: Double = 0.0,
    /** Actual FMM family for migrated EliteMobs weapons; prototype casts keep the legacy defaults. */
    val progressionSkill: StaffWeaponFamily = StaffWeaponFamily.STAVES,
    /** Actual EliteMobs item level and carrier for mapped weapons, virtual legacy values otherwise. */
    val itemLevel: Double = skillLevel.toDouble(),
    val carrierMaterial: Material = Material.BLAZE_ROD,
    val eliteDamageEnchantmentMultiplier: Double = 1.0,
    val enchantments: StaffEliteEnchantments = StaffEliteEnchantments.NONE,
    val nativeEliteWeapon: Boolean = false,
)

/** Resolved only on the guarded branch where EliteMobs is enabled. */
private object EliteMobsRuntime {
    fun canTarget(player: Player, target: LivingEntity): Boolean =
        AdvancedCombatEnemyAuthorization.canTargetWithMagicWeapon(player, target)

    fun capture(player: Player, attackId: UUID): StaffSpellCast? {
        val weapon = player.inventory.itemInMainHand
        val spell = StaffEliteItems.resolve(weapon)
        if (spell == null) return capturePrototype(player, attackId)

        // Match FMM canAttack: prepare modeled durability first, then reject its last usable point.
        ItemDurability.prepareMagicWeapon(weapon)
        if (EliteItemManager.isOnLastDamage(weapon) || !GearRestrictionHandler.canEquip(player, weapon) ||
            !SoulbindEnchantment.isValidSoulbindUser(weapon.itemMeta, player)) {
            return null
        }
        val progressionSkill = WeaponIdentityResolver.progressionSkill(weapon)
            ?.takeIf { it == SkillType.WANDS || it == SkillType.STAVES }
            ?: return null
        val skillLevel = skillLevel(player, progressionSkill)
        val inventory = ElitePlayerInventory.getPlayer(player)
        val itemLevel = EliteItemManager.getItemLevel(weapon).let { level ->
            if (level.isFinite() && level >= 0.0) level else 0.0
        }
        val damageEnchantmentMultiplier = EliteItemManager.getEliteDamageEnchantmentMultiplier(weapon)
        if (!damageEnchantmentMultiplier.isFinite() || damageEnchantmentMultiplier < 0.0) {
            return null
        }
        return StaffSpellCast(
            casterId = player.uniqueId,
            attackId = attackId,
            skillLevel = skillLevel,
            criticalHit = EliteMobDamagedByPlayerEventFilter.captureCriticalHit(player),
            loudStrikesBonus = inventory?.getLoudStrikesBonusMultiplier(true) ?: 0.0,
            eliteMobsEnabled = true,
            progressionSkill = StaffWeaponFamily.valueOf(progressionSkill.name),
            itemLevel = itemLevel,
            carrierMaterial = weapon.type,
            eliteDamageEnchantmentMultiplier = damageEnchantmentMultiplier,
            enchantments = StaffEliteEnchantments.capture(player, weapon, progressionSkill),
            nativeEliteWeapon = true,
        )
    }

    /** Preserves the original ARC prototype scaling for non-EliteMobs staff spell items. */
    private fun capturePrototype(player: Player, attackId: UUID): StaffSpellCast {
        val skillLevel = skillLevel(player, SkillType.STAVES)
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

    private fun skillLevel(player: Player, skill: SkillType): Int =
        if (!SkillsConfig.isWorldExcludedFromSkills(player) && PlayerData.isDataLoaded(player.uniqueId)) {
            maxOf(1, PlayerData.getSkillLevel(player.uniqueId, skill))
        } else {
            1
        }

    fun hit(
        player: Player,
        target: LivingEntity,
        cast: StaffSpellCast,
        power: Double,
        vanillaDamage: Double,
        magicStrike: MagicStrike?,
    ) {
        val elite = EntityTracker.getEliteMobEntity(target)
        val weaponPower = power * cast.enchantments.damageMultiplier
        val fallbackDamage = vanillaDamage * cast.eliteDamageEnchantmentMultiplier * cast.enchantments.damageMultiplier
        if (!weaponPower.isFinite() || weaponPower <= 0.0 || !fallbackDamage.isFinite() || fallbackDamage <= 0.0) return
        if (elite == null) {
            target.damage(fallbackDamage, magicDamageSource(player))
            return
        }

        val damage = AdvancedDamageScaling.magicWeapon(
            elite, cast.skillLevel,
            if (cast.nativeEliteWeapon) cast.itemLevel else cast.skillLevel.toDouble(),
            weaponPower,
            if (cast.nativeEliteWeapon) cast.carrierMaterial else Material.BLAZE_ROD,
        ) * cast.eliteDamageEnchantmentMultiplier
        if (!damage.isFinite() || damage <= 0.0) return

        val source = CombatDamageContext.PlayerDamageSource(
            cast.attackId, SkillType.valueOf(cast.progressionSkill.name), cast.criticalHit, cast.loudStrikesBonus,
        )
        val nativeStrike = magicStrike?.takeIf {
            cast.nativeEliteWeapon && cast.eliteMobsEnabled && cast.duelAuthorization == null
        }
        val targetDamage = Runnable { target.damage(damage, magicDamageSource(player)) }
        val damageCall = Runnable {
            if (nativeStrike == null) {
                CombatDamageContext.runPlayerToEliteBypass(source, targetDamage)
            } else {
                CombatDamageContext.runMagicWeaponHit(source, nativeStrike, targetDamage)
            }
        }
        val customModel = (elite as? CustomBossEntity)?.customModel
        if (customModel == null) damageCall.run()
        else CustomModel.runProjectileDamageBypass(damageCall)
    }
}
