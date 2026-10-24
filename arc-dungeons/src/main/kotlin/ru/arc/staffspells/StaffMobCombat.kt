package ru.arc.staffspells

import org.bukkit.GameMode
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import ru.arc.logging.ArcLogging
import java.util.concurrent.atomic.AtomicBoolean

/** A captured native dungeon actor; the optional integration owns membership, AI and damage. */
internal interface StaffDungeonMob {
    val body: Mob
    fun isCurrent(): Boolean
    fun currentTarget(): Player?
    fun permitsTarget(player: Player): Boolean
    fun damage(player: Player, multiplier: Double): Boolean
    fun claimMageBehavior(preferredDistance: Double = 8.0, distanceTolerance: Double = 2.0): StaffMobMageBehavior? = null
}

/** A reversible movement lease; native target selection and scripted powers remain authoritative. */
internal interface StaffMobMageBehavior : AutoCloseable {
    fun tick(target: Player?, casting: Boolean)
}

private val mobStaffBridgeFailure = AtomicBoolean()

internal fun <T> staffDungeonBoundary(fallback: T, action: () -> T): T = try {
    action()
} catch (failure: RuntimeException) {
    logMobStaffBridgeFailure(failure)
    fallback
} catch (failure: ReflectiveOperationException) {
    logMobStaffBridgeFailure(failure)
    fallback
} catch (failure: LinkageError) {
    logMobStaffBridgeFailure(failure)
    fallback
}

private fun logMobStaffBridgeFailure(failure: Throwable) {
    if (mobStaffBridgeFailure.compareAndSet(false, true))
        ArcLogging.error("Dungeon staff actor rejected: native EliteMobs integration failed", failure)
}

internal fun staffMobOwnerAllowed(actor: StaffDungeonMob): Boolean = staffDungeonBoundary(false) {
    val mob = actor.body
    mob.isValid && !mob.isDead && !mob.hasMetadata("NPC") && !mob.hasMetadata("Elitemobs_NPC") && actor.isCurrent()
}

/** Revalidated during windup, flight and at the native damage boundary. */
internal fun staffMobTargetAllowed(actor: StaffDungeonMob, target: Player, range: Double): Boolean = staffDungeonBoundary(false) {
    val mob = actor.body
    range.isFinite() && range > 0.0 && staffMobOwnerAllowed(actor) &&
        actor.currentTarget()?.uniqueId == target.uniqueId && target.isOnline && target.isValid &&
        !target.isDead && !target.isInvulnerable && !target.hasMetadata("NPC") &&
        target.gameMode in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE) &&
        mob.world == target.world && !StaffSpellsModule.mobTargetProtected(target) &&
        actor.permitsTarget(target) && mob.location.distanceSquared(target.location) <= range * range && visible(mob.eyeLocation, target)
}

/** EliteMobs owns scaling, defenses and the cancellable native damage pipeline. */
internal fun staffMobHit(actor: StaffDungeonMob, target: Player, multiplier: Double): Boolean = staffDungeonBoundary(false) {
    multiplier.isFinite() && multiplier > 0.0 && staffMobTargetAllowed(actor, target, 32.0) && actor.damage(target, multiplier)
}
