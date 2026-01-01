package ru.arc.autobuild

import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM
import ru.arc.ARC
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.worldguard.WGHook
import ru.arc.util.LocationUtils
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.RandomUtils
import java.util.concurrent.ThreadLocalRandom

/**
 * Represents an active construction site with state management.
 * Uses State Pattern for clean lifecycle management.
 *
 * Lifecycle: Created -> DisplayingOutline -> Confirmation -> Building -> Done
 *                                         \-> Cancelled
 *                            \-> Cancelled
 */
class ConstructionSite(
    val building: Building,
    val centerBlock: Location,
    val player: Player,
    val rotation: Int,
    val world: World,
    val subRotation: Int,
    val yOffset: Int
) {
    /** Corner coordinates for the building area */
    data class Corners(val corner1: BlockVector3, val corner2: BlockVector3)

    // ==================== State Management ====================

    var state: ConstructionState = ConstructionState.Created
        private set

    /**
     * Transitions to a new state with validation.
     * @return true if transition was successful
     */
    fun transitionTo(newState: ConstructionState): Boolean {
        if (!state.canTransitionTo(newState)) {
            error("Invalid transition from {} to {}", state, newState)
            return false
        }

        info("ConstructionSite transition: {} -> {}", state::class.simpleName, newState::class.simpleName)
        state.exit(this)
        state = newState
        state.enter(this)
        return true
    }

    // ==================== Configuration ====================

    /** Seconds to display border before timeout */
    val displaySeconds: Int get() = 180

    /** Seconds to wait for confirmation before timeout */
    val confirmSeconds: Int get() = BuildConfig.confirmTimeSeconds

    // ==================== Mutable Properties ====================

    var timestamp: Long = System.currentTimeMillis()
        internal set

    var npcId: Int = -1
        internal set

    private val chunks = mutableSetOf<Chunk>()
    internal var display: Display? = null
    internal var construction: Construction? = null

    // ==================== Computed Properties ====================

    /** Combined rotation from player direction and sub-rotation */
    val fullRotation: Int get() = (rotation + subRotation) % 360

    /** Center location adjusted for Y offset */
    val adjustedCenter: Location get() = centerBlock.clone().add(0.0, yOffset.toDouble(), 0.0)

    /** Bounding box corners of the construction area */
    val corners: Corners
        get() {
            val c1 = building.getCorner1(fullRotation)
            val c2 = building.getCorner2(fullRotation)
            return Corners(
                BlockVector3.at(minOf(c1.x(), c2.x()), minOf(c1.y(), c2.y()) + yOffset, minOf(c1.z(), c2.z())),
                BlockVector3.at(maxOf(c1.x(), c2.x()), maxOf(c1.y(), c2.y()) + yOffset, maxOf(c1.z(), c2.z()))
            )
        }

    /** Building progress (0.0 to 1.0) */
    val progress: Double
        get() {
            if (state != ConstructionState.Building) return 0.0
            val built = construction?.pointer?.get() ?: return 0.0
            return built.toDouble() / building.volume
        }

    // ==================== Convenience Methods ====================

    /** Start displaying the border outline */
    fun startDisplayingBorder() = transitionTo(ConstructionState.DisplayingOutline)

    /** Move to confirmation phase (spawn NPC) */
    fun startConfirmation() = transitionTo(ConstructionState.Confirmation)

    /** Start building */
    fun startBuild() = transitionTo(ConstructionState.Building)

    /** Cancel construction */
    fun cancel() = transitionTo(ConstructionState.Cancelled)

    /** Mark as done */
    fun complete() = transitionTo(ConstructionState.Done)

    /** Finish building instantly (admin command). Returns true if successful. */
    fun finishInstantly(): Boolean {
        if (state != ConstructionState.Building) return false
        construction?.finishInstantly()
        return transitionTo(ConstructionState.Done)
    }

    // ==================== Permission Checking ====================

    fun canBuild(): Boolean {
        calculateChunks()

        // Check Lands plugin (chunk-based, already efficient)
        HookRegistry.landsHook?.let { lands ->
            for (chunk in chunks) {
                if (!lands.canBuild(player, chunk)) {
                    info("Can't build in chunk: {}", chunk)
                    return false
                }
            }
        }

        // Check WorldGuard - optimized to check only strategic points
        HookRegistry.wgHook?.let { wg ->
            if (!canBuildWorldGuard(wg)) return false
        }
        return true
    }

    /**
     * Optimized WorldGuard check - instead of checking every block (O(n³)),
     * we check corners, edges, and a sample of interior points.
     */
    private fun canBuildWorldGuard(wg: WGHook): Boolean {
        val c = corners
        val checkPoints = mutableSetOf<Triple<Int, Int, Int>>()

        // Add all 8 corners
        for (x in listOf(c.corner1.x(), c.corner2.x())) {
            for (y in listOf(c.corner1.y(), c.corner2.y())) {
                for (z in listOf(c.corner1.z(), c.corner2.z())) {
                    checkPoints.add(Triple(x, y, z))
                }
            }
        }

        // Add edge midpoints
        val midX = (c.corner1.x() + c.corner2.x()) / 2
        val midY = (c.corner1.y() + c.corner2.y()) / 2
        val midZ = (c.corner1.z() + c.corner2.z()) / 2

        for (y in listOf(c.corner1.y(), c.corner2.y())) {
            for (z in listOf(c.corner1.z(), c.corner2.z())) {
                checkPoints.add(Triple(midX, y, z))
            }
        }
        for (x in listOf(c.corner1.x(), c.corner2.x())) {
            for (z in listOf(c.corner1.z(), c.corner2.z())) {
                checkPoints.add(Triple(x, midY, z))
            }
        }
        for (x in listOf(c.corner1.x(), c.corner2.x())) {
            for (y in listOf(c.corner1.y(), c.corner2.y())) {
                checkPoints.add(Triple(x, y, midZ))
            }
        }

        // Add face centers and center point
        checkPoints.add(Triple(midX, midY, c.corner1.z()))
        checkPoints.add(Triple(midX, midY, c.corner2.z()))
        checkPoints.add(Triple(midX, c.corner1.y(), midZ))
        checkPoints.add(Triple(midX, c.corner2.y(), midZ))
        checkPoints.add(Triple(c.corner1.x(), midY, midZ))
        checkPoints.add(Triple(c.corner2.x(), midY, midZ))
        checkPoints.add(Triple(midX, midY, midZ))

        for ((x, y, z) in checkPoints) {
            val loc = Location(world, centerBlock.x + x, centerBlock.y + y, centerBlock.z + z)
            if (!wg.canBuild(player, loc)) {
                info("Can't build in worldguard: {} {} {}", loc.blockX, loc.blockY, loc.blockZ)
                return false
            }
        }

        return true
    }

    // ==================== Chunk Management ====================

    private fun calculateChunks() {
        if (chunks.isNotEmpty()) return
        val c = corners
        for (x in c.corner1.x() until c.corner2.x()) {
            for (z in c.corner1.z() until c.corner2.z()) {
                val loc = Location(world, (x + centerBlock.x), 1.0, (z + centerBlock.z))
                chunks.add(loc.chunk)
            }
        }
    }

    internal fun forceloadChunks() {
        calculateChunks()
        info("Forceloading {} chunks", chunks.size)
        chunks.forEach { it.isForceLoaded = true }
    }

    private fun stopForceload() {
        chunks.filter { it.isForceLoaded }.forEach { it.isForceLoaded = false }
    }

    // ==================== Utilities ====================

    /** Checks if this site matches the given parameters (for click confirmation) */
    fun same(player: Player, location: Location, building: Building): Boolean =
        location.toCenterLocation() == centerBlock.toCenterLocation() &&
            building.fileName == this.building.fileName &&
            rotation == BuildingManager.rotationFromYaw(player.yaw)

    /** Gets particle locations for the center block highlight */
    fun getCenterLocations(): List<Location> {
        val center1 = centerBlock.toBlockLocation().clone().add(-0.05, -1.05, -0.05)
        val center2 = center1.clone().add(1.1, 1.1, 1.1)
        return LocationUtils.getBorderLocations(center1, center2, 6)
    }

    /** Gets particle locations for the building border */
    fun getBorderLocations(): List<LocationUtils.LocationData> {
        val c = corners
        val corner1 = Location(
            world,
            c.corner1.x() + adjustedCenter.x,
            c.corner1.y() + adjustedCenter.y,
            c.corner1.z() + adjustedCenter.z
        )
        val corner2 = Location(
            world,
            c.corner2.x() + adjustedCenter.x + 1,
            c.corner2.y() + adjustedCenter.y + 1,
            c.corner2.z() + adjustedCenter.z + 1
        )
        return LocationUtils.getBorderLocationsWithCornerData(corner1, corner2, 2, 3)
    }

    /** Launches celebratory fireworks */
    internal fun launchFireworks() {
        try {
            val maxFireworks = 5
            var count = 0
            lateinit var task: org.bukkit.scheduler.BukkitTask
            task = Bukkit.getScheduler().runTaskTimer(ARC.plugin, Runnable {
                if (++count > maxFireworks) {
                    task.cancel()
                    return@Runnable
                }
                spawnFirework()
            }, 0L, 10L)
        } catch (e: Exception) {
            error("Failed to launch fireworks", e)
        }
    }

    private fun spawnFirework() {
        val velocity = org.bukkit.util.Vector(0.0, ThreadLocalRandom.current().nextDouble(0.1, 0.5), 0.0)
        val firework = world.spawnEntity(centerBlock, EntityType.FIREWORK_ROCKET, CUSTOM) {
            it.velocity = velocity
        } as Firework

        val colors = listOf(
            Color.WHITE, Color.AQUA, Color.PURPLE, Color.YELLOW, Color.MAROON,
            Color.GREEN, Color.TEAL, Color.OLIVE, Color.FUCHSIA, Color.LIME,
            Color.RED, Color.ORANGE
        )

        val effect = FireworkEffect.builder()
            .flicker(ThreadLocalRandom.current().nextBoolean())
            .trail(ThreadLocalRandom.current().nextBoolean())
            .with(RandomUtils.random(FireworkEffect.Type.entries.toTypedArray()))
            .withColor(RandomUtils.random(colors.toTypedArray(), 3).toList())
            .withFade(RandomUtils.random(colors.toTypedArray(), 3).toList())
            .build()

        firework.fireworkMeta = firework.fireworkMeta.apply {
            power = ThreadLocalRandom.current().nextInt(1, 3)
            addEffect(effect)
        }

        Bukkit.getScheduler().runTaskLater(ARC.plugin, Runnable { firework.detonate() }, 20)
    }

    /** Cleans up all resources */
    internal fun cleanup(destroyNpcDelaySeconds: Int) {
        BuildingManager.removeConstruction(this)
        stopForceload()
        display?.stop()
        construction?.cancel(destroyNpcDelaySeconds)
    }

    override fun toString() =
        "ConstructionSite(building=$building, player=${player.name}, state=${state::class.simpleName})"
}
