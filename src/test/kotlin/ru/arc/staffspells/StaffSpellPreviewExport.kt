package ru.arc.staffspells

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import org.joml.Quaternionf
import org.joml.Vector3f

/** Offline snapshots copied from the production staff display geometry helper. */
object StaffSpellPreviewExport {
    private const val FRAME_TICKS = StaffSpellDisplayEffects.FRAME_TICKS
    private val chainFlightTicks = generateSequence(1) { it + 1 }.first { age ->
        (1..age).sumOf(::staffLightningStep) >= 76.8
    }

    private data class Inputs(
        val length: Double,
        val radius: Double,
        val durationTicks: Int,
        val impact: Boolean,
        val secondary: Boolean = false,
        val animationAgeOffsetTicks: Int = 0,
        val transitionFromAgeTicks: Int? = null,
    )

    private data class ChainRoute(
        val yawDegrees: Double,
        val targetX: Double,
        val targetYOffset: Double,
        val targetZ: Double,
    )
    private data class Event(val id: String, val inputs: Inputs, val chainRoute: ChainRoute? = null)
    private data class Frame(val id: String, val ageTicks: Int)
    private data class ExportedState(val metadata: Map<String, Any>, val materials: List<String>)
    private data class ReadabilityPose(
        val id: String,
        val spell: StaffSpell,
        val event: Event,
        val frame: Frame,
        val illustrativeDistanceBlocks: Double,
        val cameraTargetY: Double,
        val cameraTargetZ: Double,
        val effectOffsetY: Double = 0.0,
        val effectOffsetZ: Double = 0.0,
        val trajectoryNote: String,
    )

    // Mirrors StaffSpellController display calls using its current default tuning.
    private val events = mapOf(
        StaffSpell.CHAIN to listOf(
            Event("centerline-empty-flight", Inputs(76.8, 0.8, chainFlightTicks + 1, impact = false)),
            Event("centerline-empty-impact", Inputs(76.8, 0.8, 8, impact = true)),
            Event("secondary-empty-flight", Inputs(76.8, 0.8, chainFlightTicks + 1, impact = false, secondary = true)),
            Event("secondary-empty-impact", Inputs(76.8, 0.8, 8, impact = true, secondary = true)),
        ),
        StaffSpell.MARK to listOf(
            Event("charge", Inputs(0.0, 1.3, 18, impact = false)),
            Event("burst", Inputs(0.0, 3.5, 20, impact = true, animationAgeOffsetTicks = 18,
                transitionFromAgeTicks = 18)),
            Event("gravity-charge", Inputs(0.0, 4.5, 40, impact = false, secondary = true)),
            Event("singularity", Inputs(0.0, 4.5, 20, impact = true, secondary = true,
                animationAgeOffsetTicks = 40, transitionFromAgeTicks = 40)),
        ),
        StaffSpell.FROST to listOf(
            Event("fan", Inputs(10.0, kotlin.math.tan(Math.toRadians(50.0)) * 10.0, 24, impact = false)),
            Event("shoulder-barrage", Inputs(48.0, 0.8, 56, impact = false, secondary = true)),
        ),
        StaffSpell.LANCE to listOf(
            Event("impact", Inputs(48.0, 0.75, staffLanceDuration(48.0), impact = true)),
            Event("triple-spears", Inputs(48.0, 0.9, staffLanceDuration(48.0), impact = true, secondary = true)),
        ),
        StaffSpell.EMBER to listOf(
            Event("flight", Inputs(48.0, 0.85, 44, impact = false)),
            Event("burst", Inputs(0.0, 2.8, 20, impact = true, animationAgeOffsetTicks = 40,
                transitionFromAgeTicks = 40)),
            Event("secondary-flight", Inputs(12.0, 1.2, 8, impact = false, secondary = true)),
            Event("secondary-burst", Inputs(0.0, 3.5, 20, impact = true, secondary = true,
                animationAgeOffsetTicks = 6, transitionFromAgeTicks = 6)),
        ),
        StaffSpell.NOVA to listOf(
            Event("impact", Inputs(8.0, 8.0, 20, impact = true)),
            Event("directed-tidal-crest", Inputs(12.0, 5.5, 20, impact = true, secondary = true)),
        ),
    )

    // First in the exported list: 12 fixed player-eye snapshots at ordinary gameplay scales.
    private val readabilityPoses = buildList {
        listOf(8.0, 16.0, 24.0).forEach { distance ->
            add(ReadabilityPose(
                id = "chain-primary-${distance.toInt()}m",
                spell = StaffSpell.CHAIN,
                event = Event("centerline-primary-flight", Inputs(48.0, 0.8, 40, impact = false),
                    ChainRoute(0.0, 0.0, -0.3, distance)),
                frame = Frame("head-at-${distance.toInt()}m", (distance / 2.0).toInt()),
                illustrativeDistanceBlocks = distance,
                cameraTargetY = 1.32,
                cameraTargetZ = distance,
                trajectoryNote = "Primary starts at the eye with yaw 0 and tracks a centered target at (0, eye - 0.3, ${distance.toInt()}).",
            ))
        }
        add(ReadabilityPose(
            id = "chain-primary-centered-launch-2p5m",
            spell = StaffSpell.CHAIN,
            event = Event("centered-primary-launch", Inputs(2.5, 0.8, 40, impact = false),
                ChainRoute(0.0, 0.0, -0.3, 24.0)),
            frame = Frame("launch", 0),
            illustrativeDistanceBlocks = 2.5,
            cameraTargetY = 1.62,
            cameraTargetZ = 8.0,
            trajectoryNote = "Centered primary launch pose at yaw 0; the first visible route arrives on the first two-tick display update.",
        ))
        listOf(8.0, 16.0, 24.0).forEach { distance ->
            add(ReadabilityPose(
                id = "lance-primary-${distance.toInt()}m",
                spell = StaffSpell.LANCE,
                event = Event("primary-impact", Inputs(distance, 0.75, staffLanceDuration(distance), impact = true)),
                frame = Frame("middle", 10),
                illustrativeDistanceBlocks = distance,
                cameraTargetY = 1.62,
                cameraTargetZ = distance,
                trajectoryNote = "Primary lance at its player-facing ${distance.toInt()}-block aim distance; camera stays at the caster eye with default FOV.",
            ))
        }
        add(ReadabilityPose(
            id = "mark-primary-16m",
            spell = StaffSpell.MARK,
            event = Event("burst", Inputs(0.0, 3.5, 20, impact = true)),
            frame = Frame("middle", 10),
            illustrativeDistanceBlocks = 16.0,
            cameraTargetY = 0.9,
            cameraTargetZ = 16.0,
            effectOffsetZ = 16.0,
            trajectoryNote = "Primary mark burst centered on a target 16 blocks ahead; no camera dolly or zoom.",
        ))
        listOf(8.0, 16.0).forEach { distance ->
            add(ReadabilityPose(
                id = "blackhole-secondary-${distance.toInt()}m",
                spell = StaffSpell.MARK,
                event = Event("gravity-charge", Inputs(0.0, 4.5, 40, impact = false, secondary = true)),
                frame = Frame("charge-middle", 20),
                illustrativeDistanceBlocks = distance,
                cameraTargetY = 1.62,
                cameraTargetZ = distance,
                effectOffsetZ = distance,
                trajectoryNote = "Secondary black hole fixed at its real target point ${distance.toInt()} blocks ahead; camera stays at the caster eye.",
            ))
        }
        add(ReadabilityPose(
            id = "nova-primary-caster-view",
            spell = StaffSpell.NOVA,
            event = Event("impact", Inputs(8.0, 8.0, 20, impact = true)),
            frame = Frame("middle", 14),
            illustrativeDistanceBlocks = 8.0,
            cameraTargetY = 1.62,
            cameraTargetZ = 8.0,
            trajectoryNote = "Primary radial wave stays at the caster origin with its real 8-block radius; this is the player’s first-person cast view.",
        ))
        add(ReadabilityPose(
            id = "nova-secondary-caster-view",
            spell = StaffSpell.NOVA,
            event = Event("directed-tidal-crest", Inputs(12.0, 5.5, 20, impact = true, secondary = true)),
            frame = Frame("middle", 14),
            illustrativeDistanceBlocks = 12.0,
            cameraTargetY = 1.62,
            cameraTargetZ = 12.0,
            trajectoryNote = "Secondary tidal crest stays at the caster origin and advances along the real 12-block lane; no synthetic distance scaling.",
        ))
    }

    private fun frames(durationTicks: Int): List<Frame> {
        require(durationTicks > FRAME_TICKS * 2)
        val finalVisible = ((durationTicks - 1) / FRAME_TICKS) * FRAME_TICKS
        val middle = (durationTicks / 2 / FRAME_TICKS) * FRAME_TICKS
        require(middle > 0 && middle < finalVisible)
        return (0..finalVisible step FRAME_TICKS).map { age ->
            Frame(when (age) {
                0 -> "start"
                middle -> "middle"
                finalVisible -> "final-visible"
                else -> "tick-$age"
            }, age)
        }
    }

    private fun pieceMetadata(spell: StaffSpell, event: Event, partIndex: Int, part: StaffDisplayPart): Map<String, Any> {
        val euler = Vector3f().also { part.rotation.getEulerAnglesXYZ(it) }
        return mapOf(
            "key" to "${spell.id}-${event.id}-$partIndex",
            "material" to part.material.name,
            "x" to part.center.x.toDouble(),
            "y" to part.center.y.toDouble(),
            "z" to part.center.z.toDouble(),
            "width" to part.scale.x.toDouble(),
            "height" to part.scale.y.toDouble(),
            "depth" to part.scale.z.toDouble(),
            "slotIndex" to partIndex,
            "visible" to part.visible,
            "rotationX" to Math.toDegrees(euler.x.toDouble()),
            "rotationY" to Math.toDegrees(euler.y.toDouble()),
            "rotationZ" to Math.toDegrees(euler.z.toDouble()),
        )
    }

    private fun point(x: Double, y: Double, z: Double): Map<String, Double> = mapOf(
        "x" to x,
        "y" to y,
        "z" to z,
    )

    private fun playerCamera(
        spell: StaffSpell,
        event: Event,
        frame: Frame,
        eyeHeight: Double,
        readability: ReadabilityPose? = null,
    ): Map<String, Any> {
        val inputs = event.inputs
        if (readability != null) return mapOf(
            "position" to point(0.0, eyeHeight, 0.0),
            "target" to point(0.0, readability.cameraTargetY, readability.cameraTargetZ),
            "fovDegrees" to 70,
            "nearClip" to 0.05,
            "farClip" to 150,
        )
        val (position, target) = when {
            spell == StaffSpell.MARK && inputs.secondary -> point(0.0, eyeHeight, -6.0) to point(0.0, 0.35, 0.0)
            spell == StaffSpell.NOVA && inputs.secondary -> point(0.0, eyeHeight, 0.0) to point(0.0, eyeHeight, 12.0)
            spell == StaffSpell.FROST && inputs.secondary -> point(0.0, eyeHeight, 0.0) to point(0.0, eyeHeight, 8.0)
            spell in setOf(StaffSpell.NOVA, StaffSpell.FROST) -> point(0.0, eyeHeight, 0.0) to point(0.0, eyeHeight, 8.0)
            spell == StaffSpell.MARK -> point(0.0, eyeHeight, -6.0) to point(0.0, eyeHeight, 2.0)
            spell == StaffSpell.EMBER && inputs.secondary && !inputs.impact ->
                point(0.0, eyeHeight, -6.0) to point(0.0, 6.0, 0.0)
            spell == StaffSpell.EMBER && !inputs.impact ->
                point(0.0, eyeHeight, 0.0) to point(0.0, eyeHeight, 8.0)
            spell == StaffSpell.EMBER -> point(0.0, eyeHeight, -24.0) to point(0.0, eyeHeight, 0.0)
            else -> point(0.0, eyeHeight, 0.0) to point(0.0, eyeHeight, 8.0)
        }
        return mapOf(
            "position" to position,
            "target" to target,
            "fovDegrees" to 70,
            "nearClip" to 0.05,
            "farClip" to 150,
        )
    }

    /** Same steering and two-block tick cadence as live pursuit, aimed at visible fixture targets. */
    private fun homingFlightRoute(
        ticks: Int,
        eyeHeight: Double,
        yawDegrees: Double,
        secondary: Boolean,
        chainRoute: ChainRoute?,
    ): List<Vector3f> {
        val position = org.bukkit.util.Vector(0.0, eyeHeight, 0.0)
        val direction = org.bukkit.util.Vector(0.0, 0.0, 1.0).rotateAroundY(Math.toRadians(yawDegrees))
        val target = when {
            !secondary -> chainRoute?.let { org.bukkit.util.Vector(it.targetX, eyeHeight + it.targetYOffset, it.targetZ) }
                ?: org.bukkit.util.Vector(-10.0, eyeHeight - 0.7, 32.0)
            yawDegrees < -1 -> org.bukkit.util.Vector(0.0, eyeHeight - 0.7, 32.0)
            yawDegrees > 1 -> org.bukkit.util.Vector(10.0, eyeHeight - 0.7, 30.0)
            else -> org.bukkit.util.Vector(-10.0, eyeHeight - 0.7, 30.0)
        }
        fun point() = Vector3f(position.x.toFloat(), position.y.toFloat(), position.z.toFloat())
        val route = mutableListOf(point())
        for (age in 1..ticks) {
            val offset = target.clone().subtract(position)
            if (offset.lengthSquared() <= 4.0) {
                route += Vector3f(target.x.toFloat(), target.y.toFloat(), target.z.toFloat())
                break
            }
            steerStaffBolt(direction, offset, age)
            position.add(direction.clone().multiply(staffLightningStep(age)))
            route += point()
        }
        return route.take(41)
    }

    private fun geometry(
        spell: StaffSpell,
        event: Event,
        frame: Frame,
        eyeHeight: Double,
    ): Pair<List<StaffDisplayPart>, Vector3f> {
        if (spell == StaffSpell.CHAIN) {
            val impact = event.id.endsWith("impact")
            val lastTick = if (impact) chainFlightTicks else frame.ageTicks.coerceIn(0, chainFlightTicks)
            val fade = if (impact) (1.0 - frame.ageTicks / 8.0).coerceIn(0.0, 1.0) else 1.0
            val origin = Vector3f(0f, eyeHeight.toFloat(), 0f)
            val chainRoute = event.chainRoute
            val fanAngles = if (event.inputs.secondary) listOf(-48.0, 0.0, 48.0)
                else listOf(chainRoute?.yawDegrees ?: 0.0)
            val parts = fanAngles.flatMap { angle ->
                val route = if (event.id.contains("empty")) {
                    val direction = org.bukkit.util.Vector(0.0, 0.0, 1.0).rotateAroundY(Math.toRadians(angle))
                    (0..lastTick).map { tick ->
                        val point = direction.clone().multiply(minOf((1..tick).sumOf(::staffLightningStep), 76.8))
                        Vector3f(point.x.toFloat(), (point.y + eyeHeight).toFloat(), point.z.toFloat())
                    }
                } else homingFlightRoute(lastTick, eyeHeight, angle, event.inputs.secondary, chainRoute)
                val localRoute = route.map { Vector3f(it).sub(origin) }
                staffLightningTrailParts(localRoute, fade)
            }
            // A secondary cast owns three separate scene budgets; each trail stays below MAX_PARTS.
            return parts to origin
        }
        if (spell == StaffSpell.FROST && event.inputs.secondary) {
            // Deterministic empty-lane fixture: same anchors, delays, steering and speed as live bolts.
            val pieces = (0..2).flatMap { index ->
                val anchor = staffIcicleAnchorOffset(index)
                val position = org.bukkit.util.Vector(anchor.x.toDouble(), eyeHeight + anchor.y, anchor.z.toDouble())
                val direction = org.bukkit.util.Vector(0.0, 0.0, 1.0)
                val target = org.bukkit.util.Vector(0.0, eyeHeight, 48.0)
                var remaining = 48.0
                var impactAge = -1
                val delay = staffIcicleLaunchDelay(index)
                for (tick in delay..frame.ageTicks) {
                    if (remaining <= 0.001) { impactAge++; continue }
                    val flightAge = tick - delay + 1
                    if (flightAge > 4) steerStaffBolt(direction, target.clone().subtract(position), flightAge)
                    val step = minOf(staffIcicleStep(flightAge), remaining)
                    position.add(direction.clone().multiply(step))
                    remaining -= step
                    if (remaining <= 0.001) impactAge = 0
                }
                val rotation = staffLanceOrientation(Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()))
                val shape = if (impactAge >= 0) staffDisplayParts(StaffSpell.FROST, impactAge, 10, 0.0, 0.9, true, true)
                    else staffDisplayParts(StaffSpell.FROST, frame.ageTicks, 80, 1.0, 0.8, false, true)
                shape.map { part -> part.copy(
                    center = rotation.transform(Vector3f(part.center)).add(position.x.toFloat(), position.y.toFloat(), position.z.toFloat()),
                    rotation = Quaternionf(rotation).mul(part.rotation),
                    visible = part.visible && impactAge < 10,
                ) }
            }
            return pieces to Vector3f()
        }
        val baseParts = staffDisplayParts(
            spell = spell,
            ageTicks = frame.ageTicks,
            durationTicks = event.inputs.durationTicks,
            length = event.inputs.length,
            radius = event.inputs.radius,
            impact = event.inputs.impact,
            secondary = event.inputs.secondary,
            animationAgeTicks = event.inputs.animationAgeOffsetTicks + frame.ageTicks,
        )
        val parts = if (spell == StaffSpell.LANCE && event.id == "triple-spears") {
            listOf(-16.0, 0.0, 16.0).flatMap { angle ->
                val yaw = Quaternionf().rotationY(Math.toRadians(angle).toFloat())
                baseParts.map { part -> part.copy(
                    center = yaw.transform(Vector3f(part.center)),
                    rotation = Quaternionf(yaw).mul(part.rotation),
                ) }
            }
        } else baseParts.take(StaffSpellDisplayEffects.MAX_PARTS)
        val origin = when {
            spell == StaffSpell.EMBER && !event.inputs.impact && event.inputs.secondary ->
                Vector3f(0f, (12.0 - frame.ageTicks * 1.6).coerceAtLeast(0.0).toFloat(), 0f)
            spell == StaffSpell.EMBER && !event.inputs.impact ->
                Vector3f(0f, eyeHeight.toFloat(), (frame.ageTicks.coerceAtMost(40) * 1.2).toFloat())
            spell == StaffSpell.LANCE -> Vector3f(0f, eyeHeight.toFloat(), 0f)
            else -> Vector3f()
        }
        if (spell == StaffSpell.EMBER && event.inputs.secondary && !event.inputs.impact) {
            val down = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(0f, -1f, 0f))
            return parts.map { part -> part.copy(
                center = down.transform(Vector3f(part.center)),
                rotation = Quaternionf(down).mul(part.rotation),
            ) } to origin
        }
        return parts to origin
    }

    private fun eyePosition(camera: Map<String, Any>): Vector3f {
        val point = camera["position"] as Map<*, *>
        return Vector3f((point["x"] as Number).toFloat(), (point["y"] as Number).toFloat(),
            (point["z"] as Number).toFloat())
    }

    /** Static preview uses the production spell/impact clearance plus its fixed runtime padding. */
    private fun playerVisibleParts(
        parts: List<StaffDisplayPart>,
        eye: Vector3f,
        spell: StaffSpell,
        impact: Boolean,
    ) = parts.filter { part ->
        part.visible && Vector3f(part.center).distance(eye) >=
            (staffEyeClearance(spell, impact) + 0.15).toFloat() + part.scale.length() * 0.5f
    }

    private fun state(
        spell: StaffSpell,
        event: Event,
        frame: Frame,
        readability: ReadabilityPose? = null,
    ): List<ExportedState> {
        val inputs = event.inputs
        val views = readability?.let { listOf(1.62 to "player-default") }
            ?: listOf(1.62 to "geometry", 1.62 to "player-standing", 1.27 to "player-sneaking")
        return views.map { (eyeHeight, view) ->
            var (parts, origin) = geometry(spell, event, frame, eyeHeight)
            if (inputs.impact && frame.ageTicks < 4 && spell != StaffSpell.CHAIN) {
                events.getValue(spell).firstOrNull { !it.inputs.impact && it.inputs.secondary == inputs.secondary }
                    ?.let { tracked ->
                        val priorAge = event.inputs.transitionFromAgeTicks
                            ?: ((tracked.inputs.durationTicks - 1) / FRAME_TICKS) * FRAME_TICKS
                        val previous = geometry(spell, tracked, Frame("transition", priorAge), eyeHeight).first
                        parts = blendStaffParts(parts, previous, frame.ageTicks)
                    }
            }
            readability?.let { origin.add(0f, it.effectOffsetY.toFloat(), it.effectOffsetZ.toFloat()) }
            require(parts.isNotEmpty()) { "${spell.id}/${event.id}/${frame.id} produced no display parts" }
            val worldParts = parts.map { part -> part.copy(center = Vector3f(part.center).add(origin)) }
            val camera = playerCamera(spell, event, frame, eyeHeight, readability)
            val indexedParts = worldParts.mapIndexed { index, part -> index to part }
            // Preserve source slot IDs before per-viewer culling. The temporal harness applies
            // the live swept-bounds rule to these source slots for each fixed camera.
            val exportedParts = if (view == "geometry") indexedParts
                else indexedParts.filter { (_, part) ->
                    part.visible && playerVisibleParts(listOf(part), eyePosition(camera), spell, inputs.impact).isNotEmpty()
                }
            ExportedState(mapOf(
                "id" to (readability?.let { "readability-${it.id}" } ?: "${spell.id}-${event.id}-${frame.id}-$view"),
                "title" to (readability?.let { "PLAYER READABILITY · ${spell.id.uppercase()} · ${it.id}" }
                    ?: "${spell.id.uppercase()} · ${event.id} · ${frame.id} · $view"),
                "spell" to spell.id,
                "scenario" to event.id,
                "frame" to frame.id,
                "view" to view,
                "ageTicks" to frame.ageTicks,
                "frameTicks" to FRAME_TICKS,
                "durationTicks" to inputs.durationTicks,
                "animationAgeTicks" to (inputs.animationAgeOffsetTicks + frame.ageTicks),
                "transitionFromAgeTicks" to (inputs.transitionFromAgeTicks ?: -1),
                "impact" to inputs.impact,
                "secondary" to inputs.secondary,
                "gameplayEvent" to true,
                "length" to inputs.length,
                "radius" to inputs.radius,
                "camera" to camera,
                "cameraMode" to if (readability == null) "standing-or-sneaking" else "player-eye-default-fov-no-autozoom",
                "illustrativeDistanceBlocks" to (readability?.illustrativeDistanceBlocks ?: 0.0),
                "partCull" to if (view == "geometry") "source visibility retained; temporal observer culling uses swept bounds" else
                    "distance(center, eye) >= staffEyeClearance(spell, impact) + 0.15 + part-scale-length / 2",
                "projectiles" to when {
                    spell in setOf(StaffSpell.CHAIN, StaffSpell.FROST) && inputs.secondary -> 3
                    spell == StaffSpell.LANCE && event.id == "triple-spears" -> 3
                    else -> 1
                },
                "partBudgetPerProjectile" to StaffSpellDisplayEffects.MAX_PARTS,
                "trajectoryNote" to (readability?.trajectoryNote ?: if (spell != StaffSpell.CHAIN) "Uses production staff display geometry."
                    else if (event.chainRoute != null && event.chainRoute.yawDegrees == 0.0)
                        "Centered zero-yaw route used for the curated readability samples."
                    else if (event.id.startsWith("offset-target-homing-"))
                        "Explicit legacy offset-target homing illustration (target x=${event.chainRoute?.targetX}, z=${event.chainRoute?.targetZ}); it is retained for route inspection, not as the primary cast framing."
                    else if (!inputs.impact && frame.ageTicks == 0)
                        "Runtime begins with an eye-origin head hidden by camera clearance; the first traveled edge becomes visible on the first two-tick update."
                    else "Deterministic traveled-route example; the live controller steers to its selected target."),
                "trailPointCount" to when {
                    spell != StaffSpell.CHAIN -> 0
                    inputs.impact -> 40
                    frame.ageTicks == 0 -> 1
                    else -> frame.ageTicks.coerceIn(1, 40)
                },
                "pieces" to exportedParts.map { (index, part) -> pieceMetadata(spell, event, index, part) },
            ), exportedParts.map { (_, part) -> "minecraft:${part.material.name.lowercase()}" })
        }
    }

    private fun readabilityStates(): List<ExportedState> = readabilityPoses.map { pose ->
        state(pose.spell, pose.event, pose.frame, pose).single()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: StaffSpellPreviewExport <scene.json>" }
        val output = Path.of(args.single())
        val readability = readabilityStates()
        val exported = StaffSpell.entries.associateWith { spell ->
            events.getValue(spell).flatMap { event -> frames(event.inputs.durationTicks).flatMap { frame -> state(spell, event, frame) } }
        }
        val allExported = readability + StaffSpell.entries.flatMap { exported.getValue(it) }
        val states = allExported.map(ExportedState::metadata)
        val palette = allExported.asSequence()
            .flatMap { it.materials.asSequence() }
            .distinct()
            .associateWith { it }

        output.parent?.let { Files.createDirectories(it) }
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "title" to "ARC staff spells",
            "states" to states,
            "palette" to palette,
            "coordinates" to mapOf("space" to "spell-local", "origin" to "cast point; +Z forward", "unit" to "block"),
            "cameraNote" to "The first 12 readability-prefixed states use the standing player eye at local (0, 1.62, 0), default 70-degree FOV and no autozoom. They sample centerline CHAIN travel at 8/16/24 blocks, LANCE at 8/16/24 blocks, primary MARK at 16, black holes at 8/16, and both NOVA caster views. Remaining states retain all production geometry frames and standing/sneaking filters.",
            "evidence" to "Every geometry frame calls the production staffDisplayParts or staffLightningTrailParts function. Player-filtered states use staffEyeClearance(spell, impact) + 0.15 static padding and half the part-scale length. The legacy offset-target CHAIN route remains explicitly labeled; curated primary routes start at yaw 0 and target straight ahead. These are source-driven block-display previews, not native Minecraft lighting, interpolation, particles, sounds or measured client acceptance.",
        )).plus("\n"))
        println("STAFF_SPELL_PREVIEW states=${states.size} materials=${palette.size} output=$output")
    }
}
