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
        val input: StaffCastInput = StaffCastInput.of(secondary, quick = false),
        val chargeProgress: Double? = null,
        val projectileCount: Int? = null,
    )

    private data class ChainRoute(
        val yawDegrees: Double,
        val targetX: Double,
        val targetYOffset: Double,
        val targetZ: Double,
    )
    private data class Event(
        val id: String,
        val inputs: Inputs,
        val chainRoute: ChainRoute? = null,
        val firstFrameTick: Int = 0,
        val sceneTimeline: Map<String, Any> = emptyMap(),
        val trajectoryNote: String? = null,
    )
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

    private const val ICICLE_SHATTER_TICKS = 10
    private const val FAULT_PRIMARY_ERUPTION_TICKS = 22
    private const val FAULT_SECONDARY_WARNING_TICKS = 8
    private const val FAULT_SECONDARY_ERUPTION_TICKS = 24
    private val faultPrimaryEruptions = listOf(
        2 to 3.6,
        8 to 7.2,
        14 to 10.8,
        20 to 14.4,
        26 to 18.0,
    )
    private fun icicleHailOffset(index: Int, count: Int): Pair<Double, Double> {
        val angle = index * Math.PI * 2.0 / count
        return kotlin.math.cos(angle) * 2.2 to kotlin.math.sin(angle) * 2.2
    }

    private fun arsenalModes(length: Double, radius: Double, spell: String) = listOf(
        StaffCastInput.PRIMARY,
        StaffCastInput.SECONDARY,
        StaffCastInput.QUICK,
        StaffCastInput.QUICK_SECONDARY,
    ).map { input ->
        val id = when (input) {
            StaffCastInput.PRIMARY -> "primary"
            StaffCastInput.SECONDARY -> "secondary"
            StaffCastInput.QUICK -> "quick"
            StaffCastInput.QUICK_SECONDARY -> "quick-secondary"
        }
        Event("$spell-$id", Inputs(length, radius, 24, impact = false,
            secondary = input.secondary, input = input),
            trajectoryNote = "Production $spell $id display geometry for ${input.name.lowercase()} input.")
    }

    private fun icicleTravelTicks(distance: Double): Int {
        var remaining = distance
        var age = 0
        while (remaining > 0.001 && age < 160) {
            age++
            remaining -= staffIcicleStep(age)
        }
        require(remaining <= 0.001) { "Icicle preview distance exceeds the flight bound" }
        return age
    }

    private fun icicleSceneDuration(distance: Double, count: Int): Int =
        staffIcicleLaunchDelay(count - 1) + icicleTravelTicks(distance) - 1 + ICICLE_SHATTER_TICKS - 1

    private fun iciclePrimaryTimeline(index: Int, count: Int) = mapOf(
        "projectile" to index + 1,
        "anchorOffsetFromEye" to staffIcicleAnchorOffset(index, count).let { mapOf(
            "x" to it.x.toDouble(), "y" to it.y.toDouble(), "z" to it.z.toDouble(),
        ) },
        "launchTick" to staffIcicleLaunchDelay(index),
        "flightDistanceBlocks" to 48.0,
        "flightTicks" to icicleTravelTicks(48.0),
        "impactTick" to staffIcicleLaunchDelay(index) + icicleTravelTicks(48.0) - 1,
        "initialDisplayDurationTicks" to 80,
        "impactDurationTicks" to ICICLE_SHATTER_TICKS,
    )

    private fun icicleHailTimeline(index: Int, count: Int) = mapOf(
        "projectile" to index + 1,
        "offsetFromGround" to icicleHailOffset(index, count).let { mapOf("x" to it.first, "z" to it.second) },
        "spawnHeightAboveGroundBlocks" to 10.15,
        "launchTick" to staffIcicleLaunchDelay(index),
        "flightTicks" to icicleTravelTicks(10.15),
        "impactTick" to staffIcicleLaunchDelay(index) + icicleTravelTicks(10.15) - 1,
        "initialDisplayDurationTicks" to 60,
        "impactDurationTicks" to ICICLE_SHATTER_TICKS,
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
            Event("fan", Inputs(28.0, kotlin.math.tan(Math.toRadians(50.0)) * 28.0, 24, impact = false)),
            Event("radial-ice-nova", Inputs(8.0, 8.0, 30, impact = true, secondary = true),
                sceneTimeline = mapOf("effects" to listOf(mapOf(
                    "kind" to "radial-ice-wave", "startTick" to 0, "durationTicks" to 30,
                ))),
                trajectoryNote = "Production secondary frost radial wave at the caster origin; one display scene, with no projectile route."),
        ),
        StaffSpell.ICICLE to listOf(
            Event("charge-empty", Inputs(0.0, 0.0, 40, impact = false, chargeProgress = 0.0)),
            Event("charge-half", Inputs(0.0, 0.0, 40, impact = false, chargeProgress = 0.5)),
            Event("charge-full", Inputs(0.0, 0.0, 40, impact = false, chargeProgress = 1.0)),
            Event("shoulder-barrage", Inputs(48.0, 0.9, icicleSceneDuration(48.0, 7), impact = false,
                projectileCount = 7),
                sceneTimeline = mapOf(
                    "flightDistanceBlocks" to 48.0,
                    "shatterDurationTicks" to ICICLE_SHATTER_TICKS,
                    "projectilesAtZeroCharge" to 7,
                    "projectilesAtFullCharge" to 11,
                    "projectiles" to (0 until 7).map { iciclePrimaryTimeline(it, 7) },
                ),
                trajectoryNote = "Seven production ICICLE shoulder launches; each uses staffIcicleStep, its live launch delay and the production eight-piece shatter. A fully charged release launches eleven."),
            Event("fully-charged-shoulder-barrage", Inputs(48.0, 0.9,
                icicleSceneDuration(48.0, 11), impact = false, projectileCount = 11),
                sceneTimeline = mapOf(
                    "flightDistanceBlocks" to 48.0,
                    "shatterDurationTicks" to ICICLE_SHATTER_TICKS,
                    "projectilesAtZeroCharge" to 7,
                    "projectilesAtFullCharge" to 11,
                    "projectiles" to (0 until 11).map { iciclePrimaryTimeline(it, 11) },
                ),
                trajectoryNote = "Fully charged production ICICLE release with eleven shoulder launches, each using its eleven-shot anchor spread and live launch delay."),
            Event("downward-hail", Inputs(16.0, 0.9, icicleSceneDuration(10.15, 5), impact = false,
                secondary = true, projectileCount = 5),
                sceneTimeline = mapOf(
                    "groundCenter" to mapOf("x" to 0.0, "z" to 16.0),
                    "shatterDurationTicks" to ICICLE_SHATTER_TICKS,
                    "projectilesAtZeroCharge" to 5,
                    "projectilesAtFullCharge" to 9,
                    "projectiles" to (0 until 5).map { icicleHailTimeline(it, 5) },
                ),
                trajectoryNote = "Five ICICLE secondary crystals spawn around the selected ground point, descend with staffIcicleStep, then use the production eight-piece shatter for 10 ticks. A fully charged release launches nine."),
            Event("fully-charged-downward-hail", Inputs(16.0, 0.9,
                icicleSceneDuration(10.15, 9), impact = false, secondary = true, projectileCount = 9),
                sceneTimeline = mapOf(
                    "groundCenter" to mapOf("x" to 0.0, "z" to 16.0),
                    "shatterDurationTicks" to ICICLE_SHATTER_TICKS,
                    "projectilesAtZeroCharge" to 5,
                    "projectilesAtFullCharge" to 9,
                    "projectiles" to (0 until 9).map { icicleHailTimeline(it, 9) },
                ),
                trajectoryNote = "Fully charged secondary ICICLE release with nine descending crystals, using the production ring spacing and launch delays."),
        ),
        StaffSpell.FAULT to listOf(
            Event("ground-eruption-chain", Inputs(18.0, 2.0, 48, impact = true), firstFrameTick = 2,
                sceneTimeline = mapOf("effects" to faultPrimaryEruptions.mapIndexed { index, (startTick, z) -> mapOf(
                    "eruption" to index + 1, "x" to 0.0, "z" to z,
                    "startTick" to startTick, "durationTicks" to FAULT_PRIMARY_ERUPTION_TICKS,
                    "radiusBlocks" to 2.0,
                ) }),
                trajectoryNote = "Five production FAULT eruptions on the forward ground lane; each scene uses staffDisplayParts for 22 ticks."),
            Event("charged-ground-eruption", Inputs(16.0, 4.5,
                FAULT_SECONDARY_WARNING_TICKS + FAULT_SECONDARY_ERUPTION_TICKS,
                impact = true, secondary = true), firstFrameTick = FAULT_SECONDARY_WARNING_TICKS,
                sceneTimeline = mapOf(
                    "warning" to mapOf("startTick" to 0, "durationTicks" to FAULT_SECONDARY_WARNING_TICKS,
                        "source" to "particle warning", "includedInPreviewGeometry" to false),
                    "effect" to mapOf("x" to 0.0, "z" to 16.0,
                        "startTick" to FAULT_SECONDARY_WARNING_TICKS,
                        "durationTicks" to FAULT_SECONDARY_ERUPTION_TICKS,
                        "radiusBlocks" to 4.5),
                ),
                trajectoryNote = "The eight-tick particle warning is recorded as timing only; the preview starts with the production 24-tick FAULT eruption at ground z=16."),
        ),
        StaffSpell.LANCE to listOf(
            Event("impact", Inputs(48.0, 0.75, staffLanceDuration(48.0), impact = true)),
            Event("triple-spears", Inputs(48.0, 0.9, staffLanceDuration(48.0), impact = true, secondary = true)),
        ),
        StaffSpell.EMBER to listOf(
            Event("charge-empty", Inputs(0.0, 0.0, 40, impact = false, chargeProgress = 0.0)),
            Event("charge-half", Inputs(0.0, 0.0, 40, impact = false, chargeProgress = 0.5)),
            Event("charge-full", Inputs(0.0, 0.0, 40, impact = false, chargeProgress = 1.0)),
            Event("flight", Inputs(48.0, 0.85, 44, impact = false)),
            Event("burst", Inputs(0.0, 2.8, 20, impact = true, animationAgeOffsetTicks = 40,
                transitionFromAgeTicks = 40)),
            Event("secondary-flight", Inputs(12.0, 1.2, 8, impact = false, secondary = true)),
            Event("secondary-burst", Inputs(0.0, 3.5, 20, impact = true, secondary = true,
                animationAgeOffsetTicks = 6, transitionFromAgeTicks = 6)),
        ),
        StaffSpell.NOVA to arsenalModes(24.0, 2.4, "nova"),
        StaffSpell.LUNAR to arsenalModes(24.0, 2.0, "lunar"),
        StaffSpell.GALE to arsenalModes(24.0, 2.4, "gale"),
        StaffSpell.TIDE to arsenalModes(24.0, 2.4, "tide"),
        StaffSpell.REQUIEM to arsenalModes(24.0, 2.8, "requiem"),
        StaffSpell.PRISM to arsenalModes(24.0, 1.8, "prism"),
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
            event = Event("spiraling-vine", Inputs(24.0, 2.4, 20, impact = false,
                input = StaffCastInput.PRIMARY)),
            frame = Frame("middle", 14),
            illustrativeDistanceBlocks = 8.0,
            cameraTargetY = 1.62,
            cameraTargetZ = 8.0,
            trajectoryNote = "Primary spiraling vine starts at the caster eye; this is the player’s first-person cast view.",
        ))
        add(ReadabilityPose(
            id = "nova-secondary-bloom-target-view",
            spell = StaffSpell.NOVA,
            event = Event("rooted-bloom", Inputs(24.0, 4.4, 20, impact = true, secondary = true,
                input = StaffCastInput.SECONDARY)),
            frame = Frame("middle", 14),
            illustrativeDistanceBlocks = 12.0,
            cameraTargetY = 1.62,
            cameraTargetZ = 12.0,
            trajectoryNote = "Secondary emerald bloom is rooted at the aimed point 12 blocks ahead; no synthetic distance scaling.",
        ))
    }

    private fun frames(durationTicks: Int, firstFrameTick: Int = 0): List<Frame> {
        require(durationTicks > FRAME_TICKS * 2)
        val finalVisible = ((durationTicks - 1) / FRAME_TICKS) * FRAME_TICKS
        require(firstFrameTick in 0..finalVisible && firstFrameTick % FRAME_TICKS == 0)
        val middle = (((firstFrameTick + finalVisible) / 2) / FRAME_TICKS) * FRAME_TICKS
        require(middle > firstFrameTick && middle < finalVisible)
        return (firstFrameTick..finalVisible step FRAME_TICKS).map { age ->
            Frame(when (age) {
                firstFrameTick -> "start"
                middle -> "middle"
                finalVisible -> "final-visible"
                else -> "tick-$age"
            }, age)
        }
    }

    private fun frames(event: Event): List<Frame> {
        val chargeProgress = event.inputs.chargeProgress ?: return frames(event.inputs.durationTicks, event.firstFrameTick)
        val age = (36.0 * chargeProgress).toInt()
        return listOf(Frame("charge-${(chargeProgress * 100).toInt()}", age))
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
            inputs.chargeProgress != null -> point(0.0, eyeHeight, 0.0) to point(0.0, eyeHeight, 10.0)
            spell == StaffSpell.FAULT && inputs.secondary ->
                point(0.0, eyeHeight, 0.0) to point(0.0, eyeHeight, 16.0)
            spell == StaffSpell.ICICLE && inputs.secondary ->
                point(0.0, eyeHeight, 0.0) to point(0.0, eyeHeight, 16.0)
            spell == StaffSpell.FAULT -> point(0.0, eyeHeight, 0.0) to point(0.0, eyeHeight, 10.8)
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

    private fun orientIcicleParts(
        parts: List<StaffDisplayPart>,
        position: org.bukkit.util.Vector,
        rotation: Quaternionf,
    ): List<StaffDisplayPart> {
        return parts.map { part -> part.copy(
            center = rotation.transform(Vector3f(part.center)).add(
                position.x.toFloat(), position.y.toFloat(), position.z.toFloat(),
            ),
            rotation = Quaternionf(rotation).mul(part.rotation),
        ) }
    }

    private fun icicleGeometry(
        event: Event,
        frame: Frame,
        eyeHeight: Double,
    ): List<StaffDisplayPart> = (0 until (event.inputs.projectileCount ?: 3)).flatMap { index ->
        val secondary = event.inputs.secondary
        val position: org.bukkit.util.Vector
        val direction: org.bukkit.util.Vector
        val remainingDistance: Double
        val count = event.inputs.projectileCount ?: 3
        val delay = staffIcicleLaunchDelay(index)
        if (secondary) {
            val offset = icicleHailOffset(index, count)
            position = org.bukkit.util.Vector(offset.first, 10.15, 16.0 + offset.second)
            direction = org.bukkit.util.Vector(0.0, -1.0, 0.0)
            remainingDistance = 10.15
        } else {
            val anchor = staffIcicleAnchorOffset(index, count)
            position = org.bukkit.util.Vector(anchor.x.toDouble(), eyeHeight + anchor.y, anchor.z.toDouble())
            direction = org.bukkit.util.Vector(0.0, 0.0, 1.0)
            remainingDistance = event.inputs.length
        }

        val target = org.bukkit.util.Vector(0.0, eyeHeight, 48.0)
        var rotation = staffLanceOrientation(Vector3f(
            direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat(),
        ))
        var remaining = remainingDistance
        var impactTick = -1
        for (tick in delay..frame.ageTicks) {
            if (remaining <= 0.001) continue
            val flightAge = tick - delay + 1
            if (!secondary && flightAge > 4) steerStaffBolt(direction, target.clone().subtract(position), flightAge)
            val step = minOf(staffIcicleStep(flightAge), remaining)
            position.add(direction.clone().multiply(step))
            rotation = staffIcicleFlightOrientation(rotation, Vector3f(
                direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat(),
            ))
            remaining -= step
            if (remaining <= 0.001) impactTick = tick
        }
        // Live impacts reset the display age, then production renders again on its two-tick cadence.
        val impactAge = if (impactTick < 0) -1 else {
            val elapsed = (frame.ageTicks - impactTick).coerceAtLeast(0)
            ((elapsed + FRAME_TICKS - 1) / FRAME_TICKS) * FRAME_TICKS
        }

        val parts = if (impactAge >= 0) {
            staffDisplayParts(
                StaffSpell.ICICLE, impactAge, ICICLE_SHATTER_TICKS,
                event.inputs.length, event.inputs.radius, impact = true, secondary = secondary,
            )
        } else if (!secondary) staffIcicleChargeParts(((count - 7) / 4.0).coerceIn(0.0, 1.0), index)
        else staffDisplayParts(
            StaffSpell.ICICLE, frame.ageTicks, event.inputs.durationTicks,
            event.inputs.length, event.inputs.radius, impact = false, secondary = secondary,
        )
        orientIcicleParts(parts, position, rotation).map { part ->
            part.copy(visible = part.visible && impactAge < ICICLE_SHATTER_TICKS)
        }
    }

    private fun faultGeometry(event: Event, frame: Frame): List<StaffDisplayPart> {
        if (event.inputs.secondary) {
            val eruptionAge = frame.ageTicks - FAULT_SECONDARY_WARNING_TICKS
            if (eruptionAge !in 0 until FAULT_SECONDARY_ERUPTION_TICKS) return emptyList()
            return staffDisplayParts(
                StaffSpell.FAULT, eruptionAge, FAULT_SECONDARY_ERUPTION_TICKS,
                event.inputs.length, event.inputs.radius, impact = true, secondary = true,
            ).map { part -> part.copy(center = Vector3f(part.center).add(0f, 0f, 16f)) }
        }

        return faultPrimaryEruptions.flatMap { (startTick, z) ->
            val eruptionAge = frame.ageTicks - startTick
            if (eruptionAge !in 0 until FAULT_PRIMARY_ERUPTION_TICKS) emptyList()
            else staffDisplayParts(
                StaffSpell.FAULT, eruptionAge, FAULT_PRIMARY_ERUPTION_TICKS,
                event.inputs.length, event.inputs.radius, impact = true, secondary = false,
            ).map { part -> part.copy(center = Vector3f(part.center).add(0f, 0f, z.toFloat())) }
        }
    }

    private fun geometry(
        spell: StaffSpell,
        event: Event,
        frame: Frame,
        eyeHeight: Double,
    ): Pair<List<StaffDisplayPart>, Vector3f> {
        event.inputs.chargeProgress?.let { progress ->
            return staffChargeParts(spell, progress, frame.ageTicks) to Vector3f(0f, eyeHeight.toFloat(), 0f)
        }
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
        if (spell == StaffSpell.ICICLE) return icicleGeometry(event, frame, eyeHeight) to Vector3f()
        if (spell == StaffSpell.FAULT) return faultGeometry(event, frame) to Vector3f()
        val baseParts = staffDisplayParts(
            spell = spell,
            ageTicks = frame.ageTicks,
            durationTicks = event.inputs.durationTicks,
            length = event.inputs.length,
            radius = event.inputs.radius,
            impact = event.inputs.impact,
            secondary = event.inputs.secondary,
            animationAgeTicks = event.inputs.animationAgeOffsetTicks + frame.ageTicks,
            input = event.inputs.input,
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
                events.getValue(spell).firstOrNull { !it.inputs.impact && it.inputs.input == inputs.input }
                    ?.let { tracked ->
                        val priorAge = event.inputs.transitionFromAgeTicks
                            ?: ((tracked.inputs.durationTicks - 1) / FRAME_TICKS) * FRAME_TICKS
                        val previous = geometry(spell, tracked, Frame("transition", priorAge), eyeHeight).first
                        parts = blendStaffParts(parts, previous, frame.ageTicks)
                    }
            }
            readability?.let { origin.add(0f, it.effectOffsetY.toFloat(), it.effectOffsetZ.toFloat()) }
            require(parts.isNotEmpty() || staffParticleOnlyQuick(spell, inputs.input)) { "${spell.id}/${event.id}/${frame.id} produced no display parts" }
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
                "input" to inputs.input.name.lowercase(),
                "quick" to inputs.input.quick,
                "chargeProgress" to (inputs.chargeProgress ?: -1.0),
                "previewMode" to "offline-production-geometry",
                "eventTimeline" to event.sceneTimeline,
                "gameplayEvent" to true,
                "length" to inputs.length,
                "radius" to inputs.radius,
                "camera" to camera,
                "cameraMode" to if (readability == null) "standing-or-sneaking" else "player-eye-default-fov-no-autozoom",
                "illustrativeDistanceBlocks" to (readability?.illustrativeDistanceBlocks ?: 0.0),
                "partCull" to if (view == "geometry") "source visibility retained; temporal observer culling uses swept bounds" else
                    "distance(center, eye) >= staffEyeClearance(spell, impact) + 0.15 + part-scale-length / 2",
                "projectiles" to when {
                    inputs.chargeProgress != null -> 0
                    spell == StaffSpell.CHAIN && inputs.secondary -> 3
                    spell == StaffSpell.ICICLE -> inputs.projectileCount ?: 3
                    spell == StaffSpell.FAULT && !inputs.secondary -> faultPrimaryEruptions.size
                    spell == StaffSpell.LANCE && event.id == "triple-spears" -> 3
                    else -> 1
                },
                "partBudgetPerProjectile" to StaffSpellDisplayEffects.MAX_PARTS,
                "trajectoryNote" to (readability?.trajectoryNote ?: event.trajectoryNote ?: if (spell != StaffSpell.CHAIN) "Uses production staff display geometry."
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
        require(args.size in 1..2) { "Usage: StaffSpellPreviewExport <scene.json> [spell-id]" }
        val output = Path.of(args.first())
        val selected = args.getOrNull(1)?.let { id ->
            setOf(requireNotNull(events.keys.find { it.id == id }) { "Unknown preview spell: $id" })
        } ?: events.keys
        val readability = readabilityPoses.filter { it.spell in selected }.flatMap { pose ->
            state(pose.spell, pose.event, pose.frame, pose)
        }
        val exported = selected.associateWith { spell ->
            events.getValue(spell).flatMap { event ->
                frames(event).flatMap { frame -> state(spell, event, frame) }
            }
        }
        val allExported = readability + exported.values.flatten()
        val states = allExported.map(ExportedState::metadata)
        val palette = allExported.asSequence()
            .flatMap { it.materials.asSequence() }
            .distinct()
            .associateWith { it }

        output.parent?.let { Files.createDirectories(it) }
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "title" to "ARC staff spells · offline source preview",
            "states" to states,
            "palette" to palette,
            "coordinates" to mapOf("space" to "spell-local", "origin" to "cast point; +Z forward", "unit" to "block"),
            "cameraNote" to "The first 12 readability-prefixed states use the standing player eye at local (0, 1.62, 0), default 70-degree FOV and no autozoom. They sample centerline CHAIN travel at 8/16/24 blocks, LANCE at 8/16/24 blocks, primary MARK at 16, black holes at 8/16, and the NOVA vine and aimed bloom. Remaining states retain production geometry frames, all four Arsenal inputs and standing/sneaking filters.",
            "evidence" to "Every geometry frame calls production staffDisplayParts, staffChargeParts or staffLightningTrailParts. Player-filtered states use staffEyeClearance(spell, impact) + 0.15 static padding and half the part-scale length. The legacy offset-target CHAIN route remains explicitly labeled; curated primary routes start at yaw 0 and target straight ahead. These are offline source-driven block-display previews, not native Minecraft lighting, interpolation, particles, sounds or measured client acceptance.",
        )).plus("\n"))
        // A compact continuous shape reel, including weak/full release and every quick gesture.
        // The anchor is fixed for fair silhouette comparisons; it is not a simulated world trajectory.
        val clips = selected.flatMap { spell ->
            StaffCastInput.entries.flatMap { input ->
                (if (input.quick) listOf(0.0) else listOf(0.0, 1.0)).map { charge ->
                    val legacyQuick = input.quick && spell !in setOf(StaffSpell.NOVA, StaffSpell.LUNAR,
                        StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM, StaffSpell.PRISM)
                    val profile = if (legacyQuick) staffQuickProfile(spell, input.secondary) else null
                    val duration = if (legacyQuick) STAFF_QUICK_DURATION_TICKS else if (input.quick) 14 else 20
                    val length = if (profile == null) 12.0 else when (profile.hitShape) {
                        StaffQuickHitShape.POINT, StaffQuickHitShape.CAGE, StaffQuickHitShape.COLUMN ->
                            if (spell == StaffSpell.MARK) .1 else if (spell == StaffSpell.CHAIN) profile.reach else 1.8
                        else -> profile.reach
                    }
                    val radius = profile?.radius ?: if (input.quick) 2.0 else 3.0
                    val impact = !input.quick && spell in setOf(StaffSpell.LANCE, StaffSpell.FAULT)
                    val event = Event("motion-${input.name.lowercase()}-${charge.toInt()}",
                        Inputs(length, radius, duration, impact, input.secondary, input = input))
                    mapOf("id" to "${spell.id}-${input.name.lowercase()}-${charge.toInt()}",
                        "spell" to spell.id, "input" to input.name, "charge" to charge,
                        "particleOnly" to staffParticleOnlyQuick(spell, input),
                        "durationTicks" to duration, "frames" to (0..duration step FRAME_TICKS).map { age ->
                            val parts = staffDisplayParts(spell, age, duration, length,
                                radius, impact, input.secondary, input = input, charge = charge)
                            mapOf("tick" to age, "pieces" to parts.mapIndexed { i, part -> pieceMetadata(spell, event, i, part) })
                        })
                }
            }
        }
        Files.writeString(output.resolveSibling("motion-clips.json"), GsonBuilder().create().toJson(mapOf(
            "evidence" to "Production geometry at 2-tick cadence. Fixed spell anchor; no live trajectories, native particles or audio.",
            "clips" to clips,
        )))
        println("STAFF_SPELL_PREVIEW states=${states.size} materials=${palette.size} output=$output")
    }
}
