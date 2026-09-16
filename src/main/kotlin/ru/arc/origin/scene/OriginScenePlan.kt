package ru.arc.origin.scene

import org.bukkit.Location
import org.bukkit.World
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.npc.NpcRouteBounds
import ru.arc.npc.NpcRouteProfile
import java.nio.file.Path

internal data class OriginScenePoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) {
    fun inWorld(world: World): Location = Location(world, x, y, z, yaw, pitch)
}

internal data class OriginSceneActor(
    val id: Int,
    val home: OriginScenePoint,
    val deniedDenizenFlags: Set<String>,
)

internal sealed interface OriginSceneStep {
    val actorId: Int?

    data class Move(
        override val actorId: Int,
        val anchor: String,
        val routeProfile: String,
        val timeoutTicks: Long,
    ) : OriginSceneStep

    data class Wait(val ticks: Long) : OriginSceneStep {
        override val actorId: Int? = null
    }

    data class LookAtAnchor(override val actorId: Int, val anchor: String) : OriginSceneStep

    data class LookAtActor(override val actorId: Int, val targetActorId: Int) : OriginSceneStep

    data class Equip(override val actorId: Int, val material: String) : OriginSceneStep

    data class Swing(override val actorId: Int, val repetitions: Int, val periodTicks: Long) : OriginSceneStep

    data class Sound(
        override val actorId: Int?,
        val anchor: String?,
        val sound: String,
        val volume: Float,
        val pitch: Float,
    ) : OriginSceneStep

    data class Particle(
        override val actorId: Int?,
        val anchor: String?,
        val particle: String,
        val count: Int,
    ) : OriginSceneStep

    data class Speech(override val actorId: Int, val text: String) : OriginSceneStep

    data class Mount(override val actorId: Int, val vehicleActorId: Int) : OriginSceneStep
}

internal data class OriginSceneCycle(
    val id: String,
    val actorIds: Set<Int>,
    val cooldownMillis: LongRange,
    val initialDelayMillis: Long,
    val steps: List<OriginSceneStep>,
)

internal data class OriginSceneDefinition(
    val id: String,
    val anchor: OriginScenePoint,
    val audienceRange: Double,
    val retryMillis: Long,
    val maxConcurrentCycles: Int,
    val actors: Map<Int, OriginSceneActor>,
    val anchors: Map<String, OriginScenePoint>,
    val routeProfiles: Map<String, NpcRouteProfile>,
    val cycles: List<OriginSceneCycle>,
) {
    fun validate() {
        require(id.isNotBlank())
        require(audienceRange > 0.0)
        require(maxConcurrentCycles > 0)
        require(actors.isNotEmpty() && cycles.isNotEmpty())
        cycles.forEach { cycle ->
            require(cycle.actorIds.isNotEmpty() && cycle.actorIds.all(actors::containsKey)) {
                "scene $id cycle ${cycle.id} references an undeclared actor"
            }
            require(cycle.steps.isNotEmpty()) { "scene $id cycle ${cycle.id} has no steps" }
            cycle.steps.forEach { step ->
                step.actorId?.let { require(it in cycle.actorIds) { "scene $id cycle ${cycle.id} step actor $it is not leased" } }
                when (step) {
                    is OriginSceneStep.Move -> {
                        require(step.anchor in anchors) { "scene $id cycle ${cycle.id} references anchor ${step.anchor}" }
                        require(step.routeProfile in routeProfiles) { "scene $id cycle ${cycle.id} references route ${step.routeProfile}" }
                    }
                    is OriginSceneStep.LookAtAnchor -> require(step.anchor in anchors)
                    is OriginSceneStep.LookAtActor -> require(step.targetActorId in actors)
                    is OriginSceneStep.Sound -> require(step.anchor == null || step.anchor in anchors)
                    is OriginSceneStep.Particle -> require(step.anchor == null || step.anchor in anchors)
                    is OriginSceneStep.Mount -> require(step.vehicleActorId in cycle.actorIds)
                    else -> Unit
                }
            }
        }
    }
}

internal data class OriginScenePlan(
    val world: String,
    val tickTicks: Long,
    val speechDurationTicks: Long,
    val speechHeight: Double,
    val speechViewRange: Float,
    val speechScale: Float,
    val scenes: List<OriginSceneDefinition>,
) {
    private val scenesById = scenes.associateBy(OriginSceneDefinition::id)

    fun scene(id: String): OriginSceneDefinition = requireNotNull(scenesById[id]) { "Unknown Origin scene: $id" }

    companion object {
        fun load(dataPath: Path): OriginScenePlan {
            val source = ConfigManager.ofModule(dataPath, "origin-scenes.yml")
            source.mergeMissingFromBundled("modules/origin-scenes.yml")
            val plan = OriginScenePlan(
                world = source.string("world", "rc_origin_spawn"),
                tickTicks = source.integer("tick-ticks", 20).toLong().coerceIn(5L, 100L),
                speechDurationTicks = source.integer("speech.duration-ticks", 100).toLong().coerceIn(20L, 300L),
                speechHeight = source.real("speech.height", 2.65).coerceIn(1.8, 4.0),
                speechViewRange = source.real("speech.view-range", 1.0).toFloat().coerceIn(0.5f, 4f),
                speechScale = source.real("speech.scale", 0.95).toFloat().coerceIn(0.5f, 2f),
                scenes = source.stringList("scene-ids").map { parseScene(source, it) },
            )
            require(plan.scenes.map(OriginSceneDefinition::id).distinct().size == plan.scenes.size)
            plan.scenes.forEach(OriginSceneDefinition::validate)
            return plan
        }

        private fun parseScene(source: Config, id: String): OriginSceneDefinition {
            val root = "scenes.$id"
            val actorIds = source.stringList("$root.actor-ids").map(String::toInt)
            val actors = actorIds.associateWith { actorId ->
                OriginSceneActor(
                    id = actorId,
                    home = point(source.string("$root.actors.$actorId.home"), "$root.actors.$actorId.home"),
                    deniedDenizenFlags = source.stringList("$root.actors.$actorId.denied-denizen-flags").toSet(),
                )
            }
            val anchors = source.stringList("$root.anchor-ids").associateWith { anchorId ->
                point(source.string("$root.anchors.$anchorId"), "$root.anchors.$anchorId")
            }
            val routeProfiles = source.stringList("$root.route-profile-ids").associateWith { profileId ->
                routeProfile(source, "$root.route-profiles.$profileId", "$id-$profileId")
            }
            return OriginSceneDefinition(
                id = id,
                anchor = point(source.string("$root.anchor"), "$root.anchor"),
                audienceRange = source.real("$root.audience-range", 48.0).coerceIn(4.0, 128.0),
                retryMillis = source.integer("$root.retry-seconds", 8).toLong().coerceIn(1L, 120L) * 1_000L,
                maxConcurrentCycles = source.integer("$root.max-concurrent-cycles", 1).coerceIn(1, 8),
                actors = actors,
                anchors = anchors,
                routeProfiles = routeProfiles,
                cycles = source.stringList("$root.cycle-ids").map { cycleId -> parseCycle(source, root, cycleId) },
            )
        }

        private fun parseCycle(source: Config, sceneRoot: String, id: String): OriginSceneCycle {
            val root = "$sceneRoot.cycles.$id"
            val minimum = source.integer("$root.cooldown-min-seconds", 30).toLong().coerceIn(1L, 3_600L) * 1_000L
            val maximum = source.integer("$root.cooldown-max-seconds", 60).toLong().coerceIn(1L, 3_600L) * 1_000L
            return OriginSceneCycle(
                id = id,
                actorIds = source.stringList("$root.actor-ids").map(String::toInt).toSet(),
                cooldownMillis = minOf(minimum, maximum)..maxOf(minimum, maximum),
                initialDelayMillis = source.integer("$root.initial-delay-seconds", 10).toLong().coerceIn(0L, 600L) * 1_000L,
                steps = source.stringList("$root.step-ids").map { stepId -> parseStep(source, "$root.steps.$stepId") },
            )
        }

        private fun parseStep(source: Config, root: String): OriginSceneStep = when (source.string("$root.type").uppercase()) {
            "MOVE" -> OriginSceneStep.Move(
                actorId = source.integer("$root.actor-id"),
                anchor = source.string("$root.anchor"),
                routeProfile = source.string("$root.route-profile"),
                timeoutTicks = source.integer("$root.timeout-ticks", 240).toLong().coerceIn(20L, 1_200L),
            )
            "WAIT" -> OriginSceneStep.Wait(source.integer("$root.ticks", 20).toLong().coerceIn(1L, 1_200L))
            "LOOK_AT_ANCHOR" -> OriginSceneStep.LookAtAnchor(source.integer("$root.actor-id"), source.string("$root.anchor"))
            "LOOK_AT_ACTOR" -> OriginSceneStep.LookAtActor(source.integer("$root.actor-id"), source.integer("$root.target-actor-id"))
            "EQUIP" -> OriginSceneStep.Equip(source.integer("$root.actor-id"), source.string("$root.material"))
            "SWING" -> OriginSceneStep.Swing(
                source.integer("$root.actor-id"),
                source.integer("$root.repetitions", 1).coerceIn(1, 20),
                source.integer("$root.period-ticks", 10).toLong().coerceIn(1L, 100L),
            )
            "SOUND" -> OriginSceneStep.Sound(
                actorId = source.integer("$root.actor-id", -1).takeIf { it >= 0 },
                anchor = source.string("$root.anchor", "").takeIf(String::isNotBlank),
                sound = source.string("$root.sound"),
                volume = source.real("$root.volume", 0.5).toFloat().coerceIn(0f, 4f),
                pitch = source.real("$root.pitch", 1.0).toFloat().coerceIn(0.5f, 2f),
            )
            "PARTICLE" -> OriginSceneStep.Particle(
                actorId = source.integer("$root.actor-id", -1).takeIf { it >= 0 },
                anchor = source.string("$root.anchor", "").takeIf(String::isNotBlank),
                particle = source.string("$root.particle"),
                count = source.integer("$root.count", 3).coerceIn(1, 50),
            )
            "SPEECH" -> OriginSceneStep.Speech(source.integer("$root.actor-id"), source.string("$root.text"))
            "MOUNT" -> OriginSceneStep.Mount(source.integer("$root.rider-id"), source.integer("$root.vehicle-id"))
            else -> error("Unknown Origin scene step type at $root")
        }

        private fun routeProfile(source: Config, root: String, id: String): NpcRouteProfile {
            val minimum = block(source.string("$root.min-block"), "$root.min-block")
            val maximum = block(source.string("$root.max-block"), "$root.max-block")
            return NpcRouteProfile(
                id = id,
                floorY = source.integer("$root.floor-y"),
                bounds = NpcRouteBounds(minOf(minimum.first, maximum.first), maxOf(minimum.first, maximum.first), minOf(minimum.second, maximum.second), maxOf(minimum.second, maximum.second)),
                forbidden = source.stringList("$root.forbidden-areas").mapIndexed { index, raw -> bounds(raw, "$root.forbidden-areas[$index]") },
                preferred = source.stringList("$root.preferred-areas").mapIndexed { index, raw -> bounds(raw, "$root.preferred-areas[$index]") },
                maxVisited = source.integer("$root.max-visited", 1_024).coerceIn(64, 4_096),
                snapRadius = source.integer("$root.snap-radius", 3).coerceIn(1, 6),
                pollTicks = source.integer("$root.poll-ticks", 2).toLong().coerceIn(1L, 10L),
                stallPolls = source.integer("$root.stall-polls", 24).coerceIn(5, 100),
                offFloorTolerance = source.real("$root.off-floor-tolerance", 0.45).coerceIn(0.1, 1.0),
                distanceMargin = source.real("$root.distance-margin", 0.35).coerceIn(0.1, 2.0),
                pathDistanceMargin = source.real("$root.path-distance-margin", 0.35).coerceIn(0.1, 2.0),
                speedModifier = source.real("$root.speed-modifier", 0.68).toFloat().coerceIn(0.1f, 2f),
                entityObstaclePadding = source.real("$root.entity-obstacle-padding", 0.25).coerceIn(0.0, 1.0),
                obstacleRefreshPolls = source.integer("$root.obstacle-refresh-polls", 10).coerceIn(1, 100),
                headingLookAheadCells = source.integer("$root.heading-look-ahead-cells", 2).coerceIn(1, 8),
                headingUpdateTicks = source.integer("$root.heading-update-ticks", 1).toLong().coerceIn(1L, 10L),
                headingMaxTurnDegreesPerTick = source.real("$root.heading-max-turn-degrees-per-tick", 18.0).toFloat().coerceIn(1f, 90f),
                cornerSmoothingDistance = source.real("$root.corner-smoothing-distance", 0.75).coerceIn(0.0, 1.5),
                cornerSmoothingLead = source.real("$root.corner-smoothing-lead", 0.30).coerceIn(0.0, 0.75),
                maximumStepHeight = source.real("$root.maximum-step-height", 0.125).coerceIn(0.0, 0.5),
            )
        }

        private fun point(raw: String, path: String): OriginScenePoint {
            val values = raw.split(',').map(String::trim)
            require(values.size in 3..5) { "$path must be x,y,z[,yaw[,pitch]]" }
            return OriginScenePoint(values[0].toDouble(), values[1].toDouble(), values[2].toDouble(), values.getOrNull(3)?.toFloat() ?: 0f, values.getOrNull(4)?.toFloat() ?: 0f)
        }

        private fun block(raw: String, path: String): Pair<Int, Int> {
            val values = raw.split(',').map(String::trim)
            require(values.size == 2) { "$path must be x,z" }
            return values[0].toInt() to values[1].toInt()
        }

        private fun bounds(raw: String, path: String): NpcRouteBounds {
            val values = raw.split(',').map(String::trim)
            require(values.size == 4) { "$path must be min-x,min-z,max-x,max-z" }
            return NpcRouteBounds(minOf(values[0].toInt(), values[2].toInt()), maxOf(values[0].toInt(), values[2].toInt()), minOf(values[1].toInt(), values[3].toInt()), maxOf(values[1].toInt(), values[3].toInt()))
        }
    }
}
