package ru.arc.npc

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.ai.PathfinderType
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

internal data class NpcRouteCell(val x: Int, val z: Int)

internal data class NpcRouteBounds(
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
) {
    init {
        require(minX <= maxX && minZ <= maxZ)
    }

    operator fun contains(cell: NpcRouteCell): Boolean = cell.x in minX..maxX && cell.z in minZ..maxZ
}

/**
 * Reusable routing policy for one flat NPC movement area.
 *
 * Forbidden areas are hard walls. Preferred areas reduce the A* step cost, so
 * callers can describe corridors without turning every point into a waypoint.
 */
internal data class NpcRouteProfile(
    val id: String,
    val floorY: Int,
    val bounds: NpcRouteBounds,
    val forbidden: List<NpcRouteBounds> = emptyList(),
    val preferred: List<NpcRouteBounds> = emptyList(),
    val maxVisited: Int = 1_024,
    val snapRadius: Int = 3,
    val pollTicks: Long = 2L,
    val stallPolls: Int = 20,
    val offFloorTolerance: Double = 0.45,
    val distanceMargin: Double = 0.35,
    val pathDistanceMargin: Double = 0.35,
    val speedModifier: Float = 0.72f,
    val entityObstaclePadding: Double = 0.25,
    val obstacleRefreshPolls: Int = 10,
) {
    fun allows(cell: NpcRouteCell): Boolean = cell in bounds && forbidden.none { cell in it }

    fun stepCost(cell: NpcRouteCell): Int = if (preferred.any { cell in it }) 1 else 10
}

/** Supplies scene-specific occupied cells without coupling the router to a furniture plugin. */
internal fun interface NpcRouteObstacleSource {
    fun blockedCells(world: World, profile: NpcRouteProfile): Set<NpcRouteCell>
}

internal data class NpcRouteEvent(
    val phase: String,
    val profileId: String,
    val npcId: Int,
    val actual: Location,
    val target: Location,
    val cells: Int = 0,
    val reason: String? = null,
)

/** Bounded four-way A*: no diagonal corner cutting and no implicit height changes. */
internal fun findNpcGridPath(
    start: NpcRouteCell,
    goals: List<NpcRouteCell>,
    profile: NpcRouteProfile,
    isWalkable: (NpcRouteCell) -> Boolean,
): List<NpcRouteCell>? {
    if (!profile.allows(start) || !isWalkable(start)) return null
    val allowedGoals = goals.filter { profile.allows(it) && isWalkable(it) }.toSet()
    if (allowedGoals.isEmpty()) return null
    if (start in allowedGoals) return listOf(start)
    fun heuristic(cell: NpcRouteCell): Int = allowedGoals.minOf { abs(cell.x - it.x) + abs(cell.z - it.z) }
    data class Candidate(val cell: NpcRouteCell, val cost: Int, val estimate: Int)
    val open = PriorityQueue(compareBy<Candidate>({ it.estimate }, { heuristic(it.cell) }, { it.cell.x }, { it.cell.z }))
    val costs = mutableMapOf(start to 0)
    val previous = mutableMapOf<NpcRouteCell, NpcRouteCell>()
    val closed = mutableSetOf<NpcRouteCell>()
    open += Candidate(start, 0, heuristic(start))
    while (open.isNotEmpty() && closed.size < profile.maxVisited) {
        val current = open.remove()
        if (current.cost != costs[current.cell] || !closed.add(current.cell)) continue
        if (current.cell in allowedGoals) {
            val path = mutableListOf(current.cell)
            while (path.last() != start) path += previous.getValue(path.last())
            return path.asReversed()
        }
        val neighbors = arrayOf(
            NpcRouteCell(current.cell.x + 1, current.cell.z),
            NpcRouteCell(current.cell.x - 1, current.cell.z),
            NpcRouteCell(current.cell.x, current.cell.z + 1),
            NpcRouteCell(current.cell.x, current.cell.z - 1),
        )
        for (next in neighbors) {
            if (!profile.allows(next) || next in closed || !isWalkable(next)) continue
            val nextCost = current.cost + profile.stepCost(next)
            if (nextCost >= costs.getOrDefault(next, Int.MAX_VALUE)) continue
            costs[next] = nextCost
            previous[next] = current.cell
            open += Candidate(next, nextCost, nextCost + heuristic(next))
        }
    }
    return null
}

private data class ActiveNpcRoute(
    val token: UUID,
    val profile: NpcRouteProfile,
    val destination: Location,
    val via: List<Location>,
    val extraBlocked: Set<NpcRouteCell>,
    var blocked: Set<NpcRouteCell>,
    val cells: List<NpcRouteCell>,
    var obstaclePolls: Int = 0,
    var stalledPolls: Int = 0,
    var previousX: Double,
    var previousZ: Double,
    val recoveries: Int = 0,
)

/**
 * Citizens adapter for precomputed ARC paths. Citizens animates and collides;
 * it never chooses the route because setTarget(Iterable<Vector>) receives the
 * complete path calculated above.
 */
internal class CitizensNpcRouteController(
    private val onEvent: (NpcRouteEvent) -> Unit = {},
    private val obstacleSource: NpcRouteObstacleSource = NpcRouteObstacleSource { _, _ -> emptySet() },
) : AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val active = mutableMapOf<Int, ActiveNpcRoute>()

    fun navigate(
        npc: NPC,
        destination: Location,
        profile: NpcRouteProfile,
        via: List<Location> = emptyList(),
        extraBlocked: Set<NpcRouteCell> = emptySet(),
    ): Boolean = navigate(npc, destination, profile, via, extraBlocked, 0)

    fun isNavigating(npc: NPC): Boolean = npc.id in active || npc.navigator.isNavigating

    fun stop(npc: NPC) {
        active.remove(npc.id)
        if (npc.navigator.isNavigating) npc.navigator.cancelNavigation()
    }

    private fun navigate(
        npc: NPC,
        destination: Location,
        profile: NpcRouteProfile,
        via: List<Location>,
        extraBlocked: Set<NpcRouteCell>,
        recoveries: Int,
    ): Boolean {
        stop(npc)
        if (
            !npc.isSpawned ||
            npc.entity.world != destination.world ||
            destination.blockY != profile.floorY ||
            via.any { it.world != destination.world || it.blockY != profile.floorY }
        ) return false
        val world = destination.world
        val blocked = obstacleSource.blockedCells(world, profile) + extraBlocked
        val actual = npc.entity.location
        val starts = candidates(world, actual.blockX, actual.blockZ, profile, blocked)
        val start = starts.firstOrNull()
        if (start == null) {
            event("PATH_UNAVAILABLE", profile, npc, destination, reason = "no-safe-endpoint")
            return false
        }
        val path = mutableListOf(start)
        for (anchor in via + destination) {
            val goals = candidates(world, anchor.blockX, anchor.blockZ, profile, blocked)
            val exact = NpcRouteCell(anchor.blockX, anchor.blockZ)
            val walkable: (NpcRouteCell) -> Boolean = { it !in blocked && isWalkable(world, profile.floorY, it) }
            val segment =
                exact.takeIf { it in goals }?.let { findNpcGridPath(path.last(), listOf(it), profile, walkable) }
                    ?: findNpcGridPath(path.last(), goals, profile, walkable)
            if (segment == null) {
                event("PATH_UNAVAILABLE", profile, npc, destination, reason = "no-level-route")
                return false
            }
            path += segment.drop(1)
        }
        val currentCell = NpcRouteCell(actual.blockX, actual.blockZ)
        if (
            abs(actual.y - profile.floorY) > 0.25 ||
            currentCell != start ||
            !isWalkable(world, profile.floorY, currentCell)
        ) {
            val recovered = Location(world, start.x + 0.5, profile.floorY.toDouble(), start.z + 0.5, actual.yaw, 0f)
            npc.entity.teleport(recovered)
            event("RECOVERED", profile, npc, destination, path.size, "off-floor-start", actual)
        }
        configure(npc, destination, profile)
        val vectors = path.map { Vector(it.x + 0.5, profile.floorY.toDouble(), it.z + 0.5) }
        npc.navigator.setTarget(vectors)
        val route = ActiveNpcRoute(
            UUID.randomUUID(),
            profile,
            destination.clone(),
            via.map(Location::clone),
            extraBlocked.toSet(),
            blocked,
            path,
            previousX = npc.entity.location.x,
            previousZ = npc.entity.location.z,
            recoveries = recoveries,
        )
        active[npc.id] = route
        event("STARTED", profile, npc, destination, path.size)
        monitor(npc.id, route.token)
        return true
    }

    private fun candidates(
        world: World,
        centerX: Int,
        centerZ: Int,
        profile: NpcRouteProfile,
        blocked: Set<NpcRouteCell>,
    ): List<NpcRouteCell> =
        (-profile.snapRadius..profile.snapRadius)
            .flatMap { dx -> (-profile.snapRadius..profile.snapRadius).map { dz -> NpcRouteCell(centerX + dx, centerZ + dz) } }
            .filter { it !in blocked && profile.allows(it) && isWalkable(world, profile.floorY, it) }
            .sortedWith(compareBy({ (it.x - centerX) * (it.x - centerX) + (it.z - centerZ) * (it.z - centerZ) }, { it.x }, { it.z }))

    private fun isWalkable(world: World, floorY: Int, cell: NpcRouteCell): Boolean {
        val feet = world.getBlockAt(cell.x, floorY, cell.z)
        val head = world.getBlockAt(cell.x, floorY + 1, cell.z)
        val support = world.getBlockAt(cell.x, floorY - 1, cell.z)
        if (!feet.isPassable || !head.isPassable || feet.isLiquid || head.isLiquid) return false
        val supportBoxes = support.collisionShape.boundingBoxes
        return supportBoxes.any { box ->
            box.maxY >= 0.999 && box.minX <= 0.3 && box.maxX >= 0.7 && box.minZ <= 0.3 && box.maxZ >= 0.7
        }
    }

    private fun configure(npc: NPC, destination: Location, profile: NpcRouteProfile) {
        npc.navigator.localParameters
            .distanceMargin(profile.distanceMargin)
            .pathDistanceMargin(profile.pathDistanceMargin)
            .speedModifier(profile.speedModifier)
            .pathfinderType(PathfinderType.CITIZENS)
            .stationaryTicks(Int.MAX_VALUE)
            .stuckAction(null)
            .lookAtFunction { current ->
                val entity = current.npc.entity
                val eye = (entity as? LivingEntity)?.eyeLocation ?: entity.location.clone().add(0.0, 1.6, 0.0)
                val horizontalVelocity = entity.velocity.clone().setY(0)
                if (horizontalVelocity.lengthSquared() > 0.0025) {
                    eye.clone().add(horizontalVelocity.normalize().multiply(3.0))
                } else {
                    Location(entity.world, destination.x, eye.y, destination.z)
                }
            }
    }

    private fun monitor(npcId: Int, token: UUID) {
        val route = active[npcId]?.takeIf { it.token == token } ?: return
        val npc = runCatching { CitizensAPI.getNPCRegistry().getById(npcId) }.getOrNull()?.takeIf { it.isSpawned } ?: run {
            active.remove(npcId, route)
            return
        }
        val actual = npc.entity.location
        if (actual.world != route.destination.world) {
            active.remove(npcId, route)
            npc.navigator.cancelNavigation()
            event("ABORTED", route.profile, npc, route.destination, route.cells.size, "world-changed", actual)
            return
        }
        val currentCell = NpcRouteCell(actual.blockX, actual.blockZ)
        if (
            abs(actual.y - route.profile.floorY) > route.profile.offFloorTolerance ||
            !route.profile.allows(currentCell) ||
            !isWalkable(actual.world, route.profile.floorY, currentCell)
        ) {
            npc.navigator.cancelNavigation()
            event("DEVIATED", route.profile, npc, route.destination, route.cells.size, "left-level-floor", actual)
            if (route.recoveries < 2) {
                tasks.runLater(1L) {
                    if (active[npcId] === route) {
                        navigate(npc, route.destination, route.profile, route.via, route.extraBlocked, route.recoveries + 1)
                    }
                }
            } else {
                active.remove(npcId, route)
            }
            return
        }
        route.obstaclePolls++
        if (route.obstaclePolls >= route.profile.obstacleRefreshPolls) {
            route.blocked = obstacleSource.blockedCells(actual.world, route.profile) + route.extraBlocked
            route.obstaclePolls = 0
        }
        if (route.cells.any { it in route.blocked || !route.profile.allows(it) || !isWalkable(actual.world, route.profile.floorY, it) }) {
            npc.navigator.cancelNavigation()
            event("REPLANNING", route.profile, npc, route.destination, route.cells.size, "terrain-changed", actual)
            tasks.runLater(1L) {
                if (active[npcId] === route) {
                    navigate(npc, route.destination, route.profile, route.via, route.extraBlocked, route.recoveries)
                }
            }
            return
        }
        if (!npc.navigator.isNavigating) {
            active.remove(npcId, route)
            event("FINISHED", route.profile, npc, route.destination, route.cells.size)
            return
        }
        val movement = hypot(actual.x - route.previousX, actual.z - route.previousZ)
        if (movement > 0.025) route.stalledPolls = 0 else route.stalledPolls++
        route.previousX = actual.x
        route.previousZ = actual.z
        if (route.stalledPolls >= route.profile.stallPolls) {
            active.remove(npcId, route)
            npc.navigator.cancelNavigation()
            event("STALLED", route.profile, npc, route.destination, route.cells.size, "no-progress", actual)
            return
        }
        tasks.runLater(route.profile.pollTicks) { monitor(npcId, token) }
    }

    private fun event(
        phase: String,
        profile: NpcRouteProfile,
        npc: NPC,
        target: Location,
        cells: Int = 0,
        reason: String? = null,
        actual: Location = npc.entity.location,
    ) {
        onEvent(NpcRouteEvent(phase, profile.id, npc.id, actual.clone(), target.clone(), cells, reason))
    }

    override fun close() {
        tasks.close()
        active.keys.toList().forEach { id ->
            runCatching { CitizensAPI.getNPCRegistry().getById(id) }.getOrNull()?.takeIf { it.isSpawned }?.let(::stop)
        }
        active.clear()
    }
}
