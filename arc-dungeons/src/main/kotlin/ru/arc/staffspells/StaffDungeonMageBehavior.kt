package ru.arc.staffspells

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import com.destroystokyo.paper.entity.ai.MobGoals
import com.magmaguy.elitemobs.api.mind.EliteMindBodyLocomotion
import com.magmaguy.elitemobs.easyminecraftgoals.NMSAdapter
import com.magmaguy.elitemobs.easyminecraftgoals.NMSManager
import com.magmaguy.elitemobs.easyminecraftgoals.TransientMovementOverride
import com.magmaguy.elitemobs.easyminecraftgoals.events.WanderBackToPointEndEvent
import com.magmaguy.elitemobs.easyminecraftgoals.events.WanderBackToPointStartEvent
import com.magmaguy.elitemobs.easyminecraftgoals.internal.AbstractWanderBackToPoint
import com.magmaguy.elitemobs.mobconstructor.mobdata.aggressivemobs.EliteMobProperties
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import ru.arc.dungeons.ArcDungeonsPlugin
import java.util.Collections
import java.util.EnumSet
import java.util.IdentityHashMap
import kotlin.math.cos
import kotlin.math.sin

/** Only ARC's speed modifier, flee lease and optional Paper goal are acquired or released here. */
internal class StaffDungeonMageBehavior(
    private val actor: StaffDungeonMob,
    private val preferredDistance: Double,
    distanceTolerance: Double,
    private val adapter: NMSAdapter,
    private val nativeMind: Boolean,
    private val goals: MobGoals?,
) : StaffMobMageBehavior, Listener {
    private val body = actor.body
    private val bodyId = body.uniqueId
    private val minimumDistance = preferredDistance - distanceTolerance
    private val maximumDistance = preferredDistance + distanceTolerance
    private val speed = requireNotNull(body.getAttribute(Attribute.MOVEMENT_SPEED))
    private val holdModifier = AttributeModifier(HOLD_KEY, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1)
    private val returns = Collections.newSetFromMap(IdentityHashMap<AbstractWanderBackToPoint, Boolean>())
    private var target: Player? = null
    private var mode = Mode.IDLE
    private var flee: TransientMovementOverride? = null
    private var holding = false
    private var clock = 0L
    private var retryAt = 0L
    private var progressAt = 0L
    private var progressLocation: Location? = null
    private var installedGoal = false
    private var closed = false
    val isReturning get() = returns.isNotEmpty()

    private val goal = object : Goal<Mob> {
        override fun getKey(): GoalKey<Mob> = GoalKey.of(Mob::class.java, GOAL_KEY)
        override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK)
        override fun shouldActivate() = !closed && !isReturning && target != null && mode in setOf(Mode.HOLD, Mode.RETREAT)
        override fun start() { body.pathfinder.stopPathfinding() }
        override fun stop() { body.pathfinder.stopPathfinding() }
        override fun tick() {
            val current = target?.takeIf(::canControl) ?: return
            body.lookAt(current.eyeLocation)
            if (mode == Mode.RETREAT && clock >= retryAt) retreatVanilla(current)
        }
    }

    internal fun start(plugin: Plugin) {
        try {
            if (!nativeMind) {
                // EliteMobs' hard leash has priority -1 and can preempt this goal.
                installedGoal = true
                requireNotNull(goals).addGoal(body, 0, goal)
            }
            Bukkit.getPluginManager().registerEvents(this, plugin)
        } catch (failure: RuntimeException) {
            close()
            throw failure
        } catch (failure: LinkageError) {
            close()
            throw failure
        }
    }

    override fun tick(target: Player?, casting: Boolean) {
        if (closed) return
        try {
            clock++
            if (target == null || !canControl(target)) {
                releaseMovement()
                return
            }
            this.target = target
            val distanceSquared = body.location.distanceSquared(target.location)
            mode = when {
                casting -> Mode.HOLD
                distanceSquared < minimumDistance * minimumDistance ||
                    mode == Mode.RETREAT && distanceSquared < preferredDistance * preferredDistance -> Mode.RETREAT
                distanceSquared <= maximumDistance * maximumDistance -> Mode.HOLD
                else -> Mode.IDLE
            }
            when (mode) {
                Mode.HOLD -> { closeFlee(); hold(true) }
                Mode.IDLE -> releaseMovement()
                Mode.RETREAT -> if (nativeMind) retreatNative(target) else if (clock >= retryAt) hold(false)
            }
        } catch (failure: RuntimeException) {
            close()
            throw failure
        } catch (failure: LinkageError) {
            close()
            throw failure
        }
    }

    private fun canControl(player: Player): Boolean = !closed && !isReturning && body.uniqueId == bodyId &&
        staffMobOwnerAllowed(actor) && actor.currentTarget()?.uniqueId == player.uniqueId && actor.permitsTarget(player) &&
        player.isOnline && player.isValid && !player.isDead && !player.isInvulnerable &&
        player.gameMode in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE) && player.world == body.world

    private fun retreatNative(player: Player) {
        if (flee?.isActive == false) closeFlee()
        if (flee == null) {
            if (clock < retryAt) { hold(true); return }
            hold(false)
            flee = adapter.beginFlee(body, player.location, RETREAT_SPEED).orElse(null)
            retryAt = clock + RETRY_TICKS
            progressAt = clock
            progressLocation = body.location.clone()
            if (flee == null) { hold(true); return }
        }
        hold(false)
        if (clock % REPATH_TICKS == 0L && flee?.retarget(player.location) != true) {
            closeFlee()
            hold(true)
            return
        }
        if (clock - progressAt >= RETRY_TICKS) {
            val previous = progressLocation
            // Native flee remains active when a later path search fails. Bound that otherwise permanent lease.
            if (previous == null || previous.world != body.world || previous.distanceSquared(body.location) < .04) {
                closeFlee()
                retryAt = clock + RETRY_TICKS
                hold(true)
            } else {
                progressAt = clock
                progressLocation = body.location.clone()
            }
        }
    }

    private fun retreatVanilla(player: Player) {
        retryAt = clock + RETRY_TICKS
        val origin = body.location
        val away = origin.toVector().subtract(player.location.toVector()).setY(0.0)
        if (away.lengthSquared() < .0001) away.setZ(1.0)
        away.normalize()
        val distance = (preferredDistance - origin.distance(player.location)).coerceAtLeast(3.0)
        for (degrees in RETREAT_ANGLES) {
            val angle = Math.toRadians(degrees)
            val destination = origin.clone().add(
                (away.x * cos(angle) - away.z * sin(angle)) * distance, 0.0,
                (away.x * sin(angle) + away.z * cos(angle)) * distance,
            )
            if (!body.world.isChunkLoaded(destination.blockX shr 4, destination.blockZ shr 4)) continue
            val path = body.pathfinder.findPath(destination) ?: continue
            val end = path.finalPoint ?: continue
            if (!path.canReachFinalPoint() || end.world != player.world ||
                end.distanceSquared(player.location) <= origin.distanceSquared(player.location) + 1.0) continue
            hold(false)
            if (body.pathfinder.moveTo(path, RETREAT_SPEED)) return
        }
        // A cornered mage keeps casting and retries; it does not switch back to charging into melee.
        hold(true)
    }

    private fun hold(enabled: Boolean) {
        if (holding == enabled) return
        if (enabled) {
            holding = true
            if (speed.getModifier(HOLD_KEY) == null) speed.addTransientModifier(holdModifier)
            body.pathfinder.stopPathfinding()
        } else speed.removeModifier(HOLD_KEY)
        holding = enabled
    }

    private fun closeFlee() {
        val previous = flee ?: return
        flee = null
        try { previous.close() } finally { body.pathfinder.stopPathfinding() }
    }

    private fun releaseMovement() {
        target = null
        mode = Mode.IDLE
        try { closeFlee() } finally { hold(false) }
        retryAt = 0L
        progressLocation = null
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeashStart(event: WanderBackToPointStartEvent) {
        if (closed || event.livingEntity?.uniqueId != bodyId) return
        returns.add(event.abstractWanderBackToPoint)
        releaseMovement()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onLeashEnd(event: WanderBackToPointEndEvent) {
        if (event.livingEntity?.uniqueId == bodyId) returns.remove(event.abstractWanderBackToPoint)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            releaseMovement()
        } finally {
            try {
                if (installedGoal) goals?.removeGoal(body, goal)
            } finally {
                installedGoal = false
                returns.clear()
                HandlerList.unregisterAll(this)
            }
        }
    }

    private enum class Mode { IDLE, HOLD, RETREAT }

    companion object {
        private val HOLD_KEY = NamespacedKey("arcdungeons", "staff_mage_hold")
        private val GOAL_KEY = NamespacedKey("arcdungeons", "staff_mage_range")
        private const val RETREAT_SPEED = 1.15
        private const val REPATH_TICKS = 5
        private const val RETRY_TICKS = 20
        private val RETREAT_ANGLES = doubleArrayOf(0.0, 45.0, -45.0, 90.0, -90.0)

        fun claim(actor: StaffDungeonMob, preferredDistance: Double, distanceTolerance: Double): StaffDungeonMageBehavior? {
            require(preferredDistance.isFinite() && distanceTolerance.isFinite() && distanceTolerance >= 0.0 &&
                preferredDistance > distanceTolerance) { "Invalid staff mage distance band" }
            if (!staffMobOwnerAllowed(actor) || actor.body.getAttribute(Attribute.MOVEMENT_SPEED) == null) return null
            val locomotion = EliteMobProperties.getPluginData(actor.body.type)?.locomotion
            // These native profiles use movement-speed-based pathfinding; other profiles retain spellcasting only.
            if (locomotion !in setOf(EliteMindBodyLocomotion.GROUNDED, EliteMindBodyLocomotion.AMPHIBIOUS)) return null
            val adapter = NMSManager.getAdapter() ?: return null
            val nativeMind = adapter.isMindBody(actor.body)
            return StaffDungeonMageBehavior(actor, preferredDistance, distanceTolerance, adapter, nativeMind,
                if (nativeMind) null else Bukkit.getMobGoals()).also { it.start(ArcDungeonsPlugin.instance) }
        }
    }
}
