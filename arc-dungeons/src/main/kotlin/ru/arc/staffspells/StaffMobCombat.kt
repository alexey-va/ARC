package ru.arc.staffspells

import com.magmaguy.elitemobs.entitytracker.EntityTracker
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import ru.arc.logging.ArcLogging
import java.util.concurrent.atomic.AtomicBoolean

private val mobStaffBridgeFailure = AtomicBoolean()

/** Never take over another plugin's actor or a player-customized mob. */
internal fun staffMobOwnerAllowed(mob: Mob): Boolean {
    if (!mob.isValid || mob.isDead || !mob.hasAI() || mob.customName() != null ||
        mob.hasMetadata("NPC") || mob.hasMetadata("Elitemob") || mob.hasMetadata("Elitemobs_NPC") ||
        mob.scoreboardTags.isNotEmpty() || mob.persistentDataContainer.keys.any {
            it.namespace != "minecraft" && it.toString() != "arc:cleanup_source"
        }) return false
    if (!Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) return true
    return try {
        !EntityTracker.isEliteMob(mob)
    } catch (failure: RuntimeException) {
        logMobStaffBridgeFailure(failure)
        false
    } catch (failure: LinkageError) {
        logMobStaffBridgeFailure(failure)
        false
    }
}

private fun logMobStaffBridgeFailure(failure: Throwable) {
    if (mobStaffBridgeFailure.compareAndSet(false, true))
        ArcLogging.error("Staff mob rejected: EliteMobs actor lookup failed; verify the pinned EliteMobs API", failure)
}

/** Revalidated during windup, flight and at the native damage boundary. */
internal fun staffMobTargetAllowed(mob: Mob, target: Player, range: Double): Boolean {
    if (!range.isFinite() || range <= 0.0 || !staffMobOwnerAllowed(mob) ||
        mob.target?.uniqueId != target.uniqueId || !target.isOnline || !target.isValid ||
        target.isDead || target.isInvulnerable || target.hasMetadata("NPC") ||
        target.gameMode !in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE) ||
        mob.world != target.world || StaffSpellsModule.mobTargetProtected(target)) return false
    return mob.location.distanceSquared(target.location) <= range * range && visible(mob.eyeLocation, target)
}

/** Ordinary native damage events retain region/other plugin cancellation and mob attribution. */
internal fun staffMobHit(mob: Mob, target: Player, damage: Double): Boolean {
    if (!damage.isFinite() || damage <= 0.0 || !staffMobTargetAllowed(mob, target, 32.0)) return false
    val before = target.health + target.absorptionAmount
    target.damage(damage, DamageSource.builder(DamageType.MAGIC)
        .withDirectEntity(mob).withCausingEntity(mob).build())
    return before - target.health - target.absorptionAmount > 1.0e-6
}
