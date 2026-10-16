package ru.arc.staffspells

import com.google.gson.GsonBuilder
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import org.bukkit.Particle
import org.bukkit.Material
import org.joml.Vector3f
import org.joml.Quaternionf
import org.bukkit.block.data.BlockData
import org.bukkit.util.Vector
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.ceil

/** Exports authored spell and defense frames for an offline particle and BlockDisplay preview. */
object StaffRedesignPreviewExport {
    private const val FRAME_STEP_TICKS = 2
    private const val PREVIEW_REACH_BLOCKS = 12.0
    private const val FIXED_LAUNCH_OFFSET_BLOCKS = 2.5
    private const val FIXED_GALE_TIDE_REACH_BLOCKS = 56.0
    private const val FIXED_OSSUARY_REACH_BLOCKS = 24.0
    private const val GALE_TIDE_PATH_BLOCKS = FIXED_GALE_TIDE_REACH_BLOCKS - FIXED_LAUNCH_OFFSET_BLOCKS
    private const val OSSUARY_PATH_BLOCKS = FIXED_OSSUARY_REACH_BLOCKS - FIXED_LAUNCH_OFFSET_BLOCKS
    private const val OSSUARY_NODE_SPACING_BLOCKS = 2.0
    private const val GALE_RAM_LAUNCH_TICK = 4
    private const val GALE_RAM_TRAVEL_TICKS = 20
    private const val GALE_RAM_RELEASE_TICK = GALE_RAM_LAUNCH_TICK + GALE_RAM_TRAVEL_TICKS
    private const val GALE_RAM_RELEASE_TICKS = 12
    private const val GALE_RAM_BASE_BURST_RADIUS = 2.3
    private const val GALE_RAM_CHARGE_BURST_RADIUS = .45
    private const val TIDE_WAVE_LAUNCH_TICK = 4
    private const val TIDE_WAVE_TRAVEL_TICKS = 30
    private const val TIDE_WAVE_DAMAGE_START_TICK = 8
    private const val TIDE_WAVE_DAMAGE_END_TICK = 34
    private const val LUNAR_SECONDARY_DAMAGE_START_TICK = 16
    private const val LUNAR_SECONDARY_DAMAGE_END_TICK = 24
    private const val LUNAR_SECONDARY_RADIUS_BONUS = .7
    private const val OSSUARY_PRIMARY_BASE_RADIUS = 3.4
    private const val OSSUARY_PRIMARY_CHARGE_RADIUS = .35
    private const val OSSUARY_SECONDARY_BASE_RADIUS = 5.5
    private const val OSSUARY_SECONDARY_CHARGE_RADIUS = .5
    private val OSSUARY_PREVIEW_NODE_COUNT = ceil(OSSUARY_PATH_BLOCKS / OSSUARY_NODE_SPACING_BLOCKS)
        .toInt().coerceIn(1, 12)
    private const val GRAVITY_PREVIEW_HOLD_TICKS = 60
    private const val GRAVITY_PREVIEW_HOLD_DISTANCE = 4.0
    private const val GRAVITY_PREVIEW_REACH_BLOCKS = 32.0
    private const val GRAVITY_PREVIEW_PATH_BLOCKS =
        GRAVITY_PREVIEW_REACH_BLOCKS - GRAVITY_PREVIEW_HOLD_DISTANCE
    private const val GRAVITY_PREVIEW_INITIAL_SPEED = 3.2
    private const val GRAVITY_PREVIEW_ACCELERATION = 0.16
    private const val GRAVITY_PREVIEW_MAX_SPEED = 4.4
    private const val GRAVITY_PREVIEW_FLIGHT_TICKS = 36
    private const val GRAVITY_PREVIEW_IMPACT_TICKS = 16
    private const val CHARGE_UPDATE_TICKS = 4
    private const val PREPARATION_DURATION_TICKS = 10
    private const val EMBER_PREVIEW_SPEED = 1.2
    private const val WARD_PREVIEW_DURATION_TICKS = 400
    private const val WARD_PREVIEW_DEPLETION_TICK = 388
    private const val WARD_PREVIEW_BREAK_TICKS = 6

    private val redesignArsenal = listOf(
        StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE,
        StaffSpell.PRISM, StaffSpell.NOVA, StaffSpell.OSSUARY,
    )

    private fun point(x: Double, y: Double, z: Double) = mapOf("x" to x, "y" to y, "z" to z)

    private fun particleData(data: Any?): Map<String, Any>? = when (data) {
        is Particle.DustTransition -> mapOf(
            "kind" to "dust-transition",
            "fromRgb" to data.color.asRGB(), "toRgb" to data.toColor.asRGB(),
            "size" to data.size.toDouble(),
        )
        is Particle.DustOptions -> mapOf(
            "kind" to "dust", "rgb" to data.color.asRGB(), "size" to data.size.toDouble(),
        )
        is BlockData -> mapOf("kind" to "block", "material" to data.material.name.lowercase(Locale.ROOT))
        else -> null
    }

    private fun sample(sample: StaffParticleSample, origin: Vector = Vector()): Map<String, Any> = buildMap {
        put("particle", sample.particle.name.lowercase(Locale.ROOT))
        put("offset", point(sample.offset.x + origin.x, sample.offset.y + origin.y, sample.offset.z + origin.z))
        put("count", sample.count)
        put("spread", point(sample.spread.x, sample.spread.y, sample.spread.z))
        put("speed", sample.speed)
        particleData(sample.data)?.let { put("data", it) }
    }

    internal fun part(part: StaffDisplayPart, keyPrefix: String, index: Int, origin: Vector): Map<String, Any> {
        val euler = part.rotation.getEulerAnglesXYZ(org.joml.Vector3f())
        return mapOf(
            "key" to "$keyPrefix:${part.identity ?: index}",
            "material" to "minecraft:${part.material.name.lowercase(Locale.ROOT)}",
            "x" to (part.center.x + origin.x).toDouble(),
            "y" to (part.center.y + origin.y).toDouble(),
            "z" to (part.center.z + origin.z).toDouble(),
            "width" to part.scale.x.toDouble(), "height" to part.scale.y.toDouble(),
            "depth" to part.scale.z.toDouble(), "visible" to part.visible,
            "rotationX" to Math.toDegrees(euler.x.toDouble()),
            "rotationY" to Math.toDegrees(euler.y.toDouble()),
            "rotationZ" to Math.toDegrees(euler.z.toDouble()),
        )
    }

    private fun ticks(duration: Int) = (0..duration step FRAME_STEP_TICKS).toList() +
        if (duration % FRAME_STEP_TICKS == 0) emptyList() else listOf(duration)

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    private fun chargeToken(charge: Double) = charge.toString().removeSuffix(".0")

    private fun arsenalRadius(spell: StaffSpell, input: StaffCastInput, charge: Double) = when {
        spell == StaffSpell.TIDE && input.secondary -> staffTideWaveHalfWidth(charge)
        spell == StaffSpell.TIDE -> 1.0 + charge * .3
        spell == StaffSpell.GALE && input.secondary -> GALE_RAM_BASE_BURST_RADIUS + charge * GALE_RAM_CHARGE_BURST_RADIUS
        spell == StaffSpell.OSSUARY -> if (input.secondary)
            OSSUARY_SECONDARY_BASE_RADIUS + charge * OSSUARY_SECONDARY_CHARGE_RADIUS
        else OSSUARY_PRIMARY_BASE_RADIUS + charge * OSSUARY_PRIMARY_CHARGE_RADIUS
        else -> 2.2 + charge
    }

    private fun arsenalFrame(
        spell: StaffSpell,
        input: StaffCastInput,
        charge: Double,
        duration: Int,
        age: Int,
        galePitchDegrees: Double = 0.0,
        boneShapes: List<StaffOssuaryShape> = emptyList(),
    ): Map<String, Any> {
        val galeRotation = if (spell == StaffSpell.GALE && input.secondary || spell == StaffSpell.TIDE && !input.secondary)
            Quaternionf().rotationX(-Math.toRadians(galePitchDegrees).toFloat()) else null
        val galeDirection = galeRotation?.transform(Vector3f(0f, 0f, 1f)) ?: Vector3f(0f, 0f, 1f)
        fun airPoint(distance: Double) = Vector(galeDirection.x * distance,
            1.62 + galeDirection.y * distance, galeDirection.z * distance)
        val radius = arsenalRadius(spell, input, charge)
        // Live TIDE/GALE Display scenes inherit Attack.radius; their particle calls may override it.
        val displayRadius = when {
            spell == StaffSpell.TIDE && input.secondary -> staffTideWaveHalfWidth(charge)
            spell == StaffSpell.GALE && input.secondary && age >= GALE_RAM_RELEASE_TICK -> radius
            spell == StaffSpell.GALE && input.secondary -> 2.2 + charge
            spell == StaffSpell.TIDE || spell == StaffSpell.GALE -> 2.2 + charge
            else -> radius
        }
        val nodeCount = if (spell == StaffSpell.OSSUARY && !input.secondary)
            OSSUARY_PREVIEW_NODE_COUNT else 1
        val parts = mutableListOf<Map<String, Any>>()
        val emissions = mutableListOf<Map<String, Any>>()

        fun addParts(localAge: Int, localDuration: Int, index: Int, anchor: Vector,
            sceneLength: Double = 1.0, impact: Boolean = false, localRadius: Double = displayRadius) {
            // Display scenes expire before rendering at age == duration in StaffSpellDisplayEffects.
            if (localAge !in 0 until localDuration) return
            val authored = if (spell == StaffSpell.OSSUARY) staffOssuaryRibParts(localAge, localDuration,
                localRadius, input.secondary, charge, boneShapes[index]) else
                staffArsenalParts(spell, localAge, localDuration, sceneLength, localRadius,
                    impact = impact, secondary = input.secondary, input = input, charge = charge)
            authored.forEachIndexed { partIndex, display ->
                    val oriented = if (galeRotation == null) display else display.copy(
                        center = galeRotation.transform(Vector3f(display.center)),
                        rotation = Quaternionf(galeRotation).mul(display.rotation))
                    parts += part(oriented, "${spell.id}:node:$index", partIndex, anchor)
                }
        }

        fun addParticles(localAge: Int, localDuration: Int, localRadius: Double, anchor: Vector,
            localReach: Double = localRadius, impact: Boolean = false) {
            if (localAge !in 0 until localDuration) return
            val authored = staffArsenalParticles(spell, localAge, localDuration, localRadius,
                input.secondary, charge, localReach, impact)
            val limit = if (!input.secondary && spell == StaffSpell.OSSUARY)
                240 / nodeCount.coerceAtLeast(1) else 160
            val admitted = if (authored.size <= limit) authored else List(limit) { index ->
                authored[index * (authored.size - 1) / (limit - 1)]
            }
            admitted.forEach {
                val oriented = if (galeRotation == null) it else galeRotation.transform(
                    Vector3f(it.offset.x.toFloat(), it.offset.y.toFloat(), it.offset.z.toFloat())).let { offset ->
                    it.copy(offset = Vector(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()))
                }
                emissions += sample(oriented, anchor)
            }
        }

        val pathLength = when {
            spell == StaffSpell.TIDE && !input.secondary -> STAFF_TIDE_PRIMARY_RANGE - FIXED_LAUNCH_OFFSET_BLOCKS
            spell == StaffSpell.TIDE || spell == StaffSpell.GALE && input.secondary ->
                GALE_TIDE_PATH_BLOCKS
            spell == StaffSpell.OSSUARY && !input.secondary -> OSSUARY_PATH_BLOCKS
            else -> PREVIEW_REACH_BLOCKS
        }
        val launchOffset = if (spell == StaffSpell.TIDE || spell == StaffSpell.GALE && input.secondary ||
            spell == StaffSpell.OSSUARY && !input.secondary) FIXED_LAUNCH_OFFSET_BLOCKS else 0.0
        val endpoint = if (spell == StaffSpell.GALE && input.secondary || spell == StaffSpell.TIDE && !input.secondary) airPoint(launchOffset + pathLength)
            else Vector(0.0, 0.0, launchOffset + pathLength)
        when {
            spell == StaffSpell.TIDE && !input.secondary -> {
                val localAge = age - STAFF_TIDE_PRIMARY_ARRIVAL_TICK
                val progress = ((age - STAFF_TIDE_PRIMARY_LAUNCH_TICK).toDouble() / STAFF_TIDE_PRIMARY_TRAVEL_TICKS)
                    .coerceIn(0.0, 1.0)
                val anchor = airPoint(FIXED_LAUNCH_OFFSET_BLOCKS + pathLength * progress)
                if (age < STAFF_TIDE_PRIMARY_ARRIVAL_TICK) addParts(age, duration, 0, anchor, pathLength)
                else {
                    val impactRadius = 2.2 + charge * .25
                    addParts(localAge, STAFF_TIDE_PRIMARY_IMPACT_TICKS, 0, anchor, 1.0, impact = true,
                        localRadius = impactRadius)
                    addParticles(localAge, STAFF_TIDE_PRIMARY_IMPACT_TICKS, impactRadius, anchor,
                        localReach = impactRadius, impact = true)
                }
                if (age < STAFF_TIDE_PRIMARY_ARRIVAL_TICK) addParticles(age, duration, radius, anchor)
            }
            spell == StaffSpell.TIDE && input.secondary -> {
                val progress = ((age - TIDE_WAVE_LAUNCH_TICK).toDouble() / TIDE_WAVE_TRAVEL_TICKS)
                    .coerceIn(0.0, 1.0)
                val anchor = Vector(0.0, 0.0, FIXED_LAUNCH_OFFSET_BLOCKS + GALE_TIDE_PATH_BLOCKS * smooth(progress))
                addParts(age, duration, 0, anchor, GALE_TIDE_PATH_BLOCKS, localRadius = displayRadius)
                addParticles(age, duration, radius, anchor, localReach = GALE_TIDE_PATH_BLOCKS)
            }
            spell == StaffSpell.GALE && input.secondary -> {
                val particleAnchor = airPoint(FIXED_LAUNCH_OFFSET_BLOCKS +
                    GALE_TIDE_PATH_BLOCKS * smooth((age - GALE_RAM_LAUNCH_TICK).toDouble() / GALE_RAM_TRAVEL_TICKS))
                if (age < GALE_RAM_RELEASE_TICK) {
                    addParts(age, duration, 0, particleAnchor,
                        GALE_TIDE_PATH_BLOCKS, localRadius = 2.2 + charge)
                    addParticles(age, duration, radius, particleAnchor)
                } else {
                    val releaseAge = age - GALE_RAM_RELEASE_TICK
                    val burstRadius = GALE_RAM_BASE_BURST_RADIUS + charge * GALE_RAM_CHARGE_BURST_RADIUS
                    addParts(releaseAge, GALE_RAM_RELEASE_TICKS, 0, endpoint,
                        GALE_TIDE_PATH_BLOCKS, impact = true, localRadius = burstRadius)
                    addParticles(releaseAge, GALE_RAM_RELEASE_TICKS, burstRadius, endpoint,
                        localReach = burstRadius, impact = true)
                }
            }
            spell == StaffSpell.GALE -> {
                var offset = Vector3f()
                for (tick in 0..age step FRAME_STEP_TICKS) offset = staffGaleWanderStep(offset, tick, charge)
                val anchor = endpoint.clone().add(Vector(offset.x.toDouble(), 0.0, offset.z.toDouble()))
                addParts(age, duration, 0, anchor)
                addParticles(age, duration, radius, anchor)
            }
            spell == StaffSpell.NOVA && !input.secondary -> {
                val anchor = Vector(0.0, 0.0, FIXED_LAUNCH_OFFSET_BLOCKS)
                val rootLength = PREVIEW_REACH_BLOCKS - FIXED_LAUNCH_OFFSET_BLOCKS
                addParts(age, duration, 0, anchor, rootLength)
                addParticles(age, duration, radius, anchor, rootLength)
            }
            spell == StaffSpell.OSSUARY && !input.secondary -> {
                val alive = (0 until nodeCount).filter { age - 4 - it * 2 in 0 until 22 }
                var total = 0
                val visibleNodes = alive.asReversed().takeWhile { node ->
                    total += staffOssuaryRibParts(age - 4 - node * 2, 22, displayRadius, false, charge, boneShapes[node]).size
                    total <= StaffSpellDisplayEffects.MAX_PARTS_PER_CASTER
                }.take(StaffSpellDisplayEffects.MAX_PER_CASTER).toSet()
                repeat(nodeCount) { node ->
                    val localAge = age - 4 - node * 2
                    val anchor = Vector(0.0, 0.0,
                        FIXED_LAUNCH_OFFSET_BLOCKS + OSSUARY_PATH_BLOCKS * (node + 1) / nodeCount)
                    if (node in visibleNodes) addParts(localAge, 22, node, anchor)
                    addParticles(localAge, 22, radius, anchor)
                }
            }
            else -> {
                val anchor = if (spell == StaffSpell.LUNAR ||
                    spell == StaffSpell.NOVA || spell == StaffSpell.OSSUARY || spell == StaffSpell.PRISM) endpoint else Vector()
                addParts(age, duration, 0, anchor)
                addParticles(age, duration, radius, anchor)
            }
        }

        if (spell == StaffSpell.PRISM && age in 10..24) {
            val front = ((age - 10) / 6.0).coerceIn(0.0, 1.0)
            val rays = if (input.secondary) {
                (-1..1).map { lane ->
                    val start = Vector(lane * 1.7, 2.2, FIXED_LAUNCH_OFFSET_BLOCKS)
                    val delta = Vector(-lane * 1.7, 0.4, PREVIEW_REACH_BLOCKS - FIXED_LAUNCH_OFFSET_BLOCKS)
                    start to delta
                }
            } else {
                (-1..1).map { lane ->
                    Vector(0.0, 2.6, PREVIEW_REACH_BLOCKS) to Vector(0.0, 0.0, 9.0).rotateAroundY(lane * 0.55)
                }
            }
            rays.forEach { (start, delta) ->
                staffPrismBeam(delta, front, charge, age).forEach { emissions += sample(it, start) }
            }
        }

        return mapOf("tick" to age, "parts" to parts, "emissions" to emissions)
    }

    private fun arsenalClip(spell: StaffSpell, input: StaffCastInput, charge: Double,
        galePitchDegrees: Double = 0.0, castVariant: Int = 0): Map<String, Any> {
        val boneShapes = if (spell == StaffSpell.OSSUARY) List(OSSUARY_PREVIEW_NODE_COUNT) {
            staffOssuaryShape(UUID(0L, if (castVariant == 0) 871L else 1251L), it)
        } else emptyList()
        val duration = staffArsenalLifetime(spell, input.secondary)
        val frames = ticks(duration).map { age -> arsenalFrame(spell, input, charge, duration, age, galePitchDegrees, boneShapes) }
        val previewReach = when {
            spell == StaffSpell.TIDE && !input.secondary -> STAFF_TIDE_PRIMARY_RANGE
            spell == StaffSpell.TIDE || spell == StaffSpell.GALE && input.secondary ->
                FIXED_GALE_TIDE_REACH_BLOCKS
            spell == StaffSpell.OSSUARY && !input.secondary -> FIXED_OSSUARY_REACH_BLOCKS
            else -> PREVIEW_REACH_BLOCKS
        }
        val previewPath = when {
            spell == StaffSpell.TIDE && !input.secondary -> STAFF_TIDE_PRIMARY_RANGE - FIXED_LAUNCH_OFFSET_BLOCKS
            spell == StaffSpell.TIDE || spell == StaffSpell.GALE && input.secondary ->
                GALE_TIDE_PATH_BLOCKS
            spell == StaffSpell.OSSUARY && !input.secondary -> OSSUARY_PATH_BLOCKS
            spell == StaffSpell.NOVA && !input.secondary -> PREVIEW_REACH_BLOCKS - FIXED_LAUNCH_OFFSET_BLOCKS
            else -> PREVIEW_REACH_BLOCKS
        }
        val notes = when {
            spell == StaffSpell.TIDE && !input.secondary ->
                "Three broad rotating water ribbons carry a compressed droplet along full-pitch aim ${galePitchDegrees} degrees upward. Launch tick $STAFF_TIDE_PRIMARY_LAUNCH_TICK, flight $STAFF_TIDE_PRIMARY_TRAVEL_TICKS ticks, caster reach $STAFF_TIDE_PRIMARY_RANGE blocks. At contact the mass opens into a directional splash cup with detached droplets for $STAFF_TIDE_PRIMARY_IMPACT_TICKS ticks. The unobstructed export reaches the endpoint; live swept collision releases earlier at a target or wall, with one accepted hit per victim and radius 2.2–2.45 blocks."
            spell == StaffSpell.NOVA && !input.secondary ->
                "Two crawling root runners grow from a launch point 2.5 blocks ahead toward the target, then six thorn fingers close around the endpoint at tick 30. Display geometry and native particles use an illustrative 9.5-block path ending 12 blocks from the caster."
            spell == StaffSpell.LUNAR && input.secondary ->
                "The paired crescents converge at the selected ground point, strike at tick $LUNAR_SECONDARY_DAMAGE_START_TICK, then crumble downward over six ticks into the existing bounded shard field. Its visible ${String.format(Locale.ROOT, "%.1f", arsenalRadius(spell, input, charge) + LUNAR_SECONDARY_RADIUS_BONUS)}-block damage radius remains active through tick $LUNAR_SECONDARY_DAMAGE_END_TICK, with line-of-sight checks preserving cover and one hit per target."
            spell == StaffSpell.GALE ->
                if (input.secondary) "Two interwoven display bands and a hollow ram head follow the source smoothstep controller from a launch point $FIXED_LAUNCH_OFFSET_BLOCKS blocks ahead to a fixed $FIXED_GALE_TIDE_REACH_BLOCKS-block caster reach ($GALE_TIDE_PATH_BLOCKS blocks of scene travel), from tick $GALE_RAM_LAUNCH_TICK over $GALE_RAM_TRAVEL_TICKS ticks. In this unobstructed flat preview, the ram releases a spherical pressure shell of 36 tangential fragments at the endpoint at tick $GALE_RAM_RELEASE_TICK; its $GALE_RAM_RELEASE_TICKS-tick terminal stage matches the runtime ${GALE_RAM_BASE_BURST_RADIUS}–${GALE_RAM_BASE_BURST_RADIUS + GALE_RAM_CHARGE_BURST_RADIUS}-block damage radius. The center follows full pitch ${galePitchDegrees} degrees upward from the caster eye, with the same orientation for displays and particles. Runtime releases early at a wall, eligible victim, or endpoint; it does not project the ram onto ground."
                else "Three translucent ribbons twist around an open tornado core whose ground anchor wanders irregularly around the aimed point. The exported movement calls staffGaleWanderStep at the runtime two-tick cadence with its 1.05-block step cap; display and native particles share the same moving center. Flat ground and no cover are assumed; runtime clips movement and sweeps damage along this center."
            spell == StaffSpell.TIDE && input.secondary ->
                "A five-row curled water wall and foam crest follow a yaw-only, floor-sampled path from a launch point $FIXED_LAUNCH_OFFSET_BLOCKS blocks ahead to a fixed $FIXED_GALE_TIDE_REACH_BLOCKS-block caster reach ($GALE_TIDE_PATH_BLOCKS blocks of scene travel), starting at tick $TIDE_WAVE_LAUNCH_TICK and travelling for $TIDE_WAVE_TRAVEL_TICKS ticks. Its ${staffTideWaveHalfWidth(0.0) * 2}–${staffTideWaveHalfWidth(1.0) * 2}-block width matches the runtime damage radius from tick $TIDE_WAVE_DAMAGE_START_TICK through tick $TIDE_WAVE_DAMAGE_END_TICK; walls and raised ground stop the wave. The offline preview shows the flat, unobstructed path."
            spell == StaffSpell.OSSUARY && !input.secondary ->
                "Eleven branching bone clusters rise along a 21.5-block ground path from the launch point 2.5 blocks ahead to a fixed 24-block caster reach. Their spacing follows ceil(path/2), they start 2 ticks apart, and each uses a matching ${OSSUARY_PRIMARY_BASE_RADIUS}–${OSSUARY_PRIMARY_BASE_RADIUS + OSSUARY_PRIMARY_CHARGE_RADIUS}-block visual and damage radius at local tick 6. Each cast/node owns a seeded 20–30-part skeleton with different bends, ribs and forks; the preview sums actual shape sizes against the production 192-part caster budget while their particles continue. This unobstructed preview places no blocks."
            spell == StaffSpell.OSSUARY && input.secondary ->
                "Branching bone clusters erupt at varied angles, with tall main spikes and smaller side forks. The broad 5.5–6.0-block display footprint matches its runtime damage radius around the selected ground point; visibility checks preserve cover and the cast never places blocks."
            spell == StaffSpell.PRISM && input.secondary ->
                "Beam uses the source staffPrismBeam frame and three converging emitter lanes, starting 2.5 blocks ahead of the caster and ending at the prism 12 blocks ahead. Target selection is represented by that fixed endpoint."
            spell == StaffSpell.PRISM ->
                "Beam uses the source staffPrismBeam frame and the runtime empty-target three-ray fallback. Target-selected rays are not simulated."
            else -> "Source-authored local particle and display geometry at a fixed anchor."
        }
        return mapOf(
            "id" to "${spell.id}-${input.name.lowercase(Locale.ROOT)}-${charge.toInt()}${if (galePitchDegrees == 0.0) "" else "-upward"}${if (castVariant == 0) "" else "-cast-b"}",
            "spell" to spell.id, "input" to input.name, "quick" to false,
            "charge" to charge, "durationTicks" to duration,
            "tailTicks" to 40, "frameStepTicks" to FRAME_STEP_TICKS,
            "particleOnly" to (frames.all { (it["parts"] as List<*>).isEmpty() }),
            "anchor" to when {
                spell == StaffSpell.GALE && input.secondary || spell == StaffSpell.TIDE && !input.secondary ->
                    "caster eye at y=1.62, launch 2.5 blocks along full-pitch aim; air path and impact share the same orientation"
                spell == StaffSpell.TIDE ->
                    "cast-ground launch point 2.5 blocks ahead of caster; local +Z points to the fixed endpoint"
                spell == StaffSpell.OSSUARY && !input.secondary ->
                    "cast-ground launch point 2.5 blocks ahead of caster; local +Z follows the fixed spike lane"
                else -> "cast ground/target anchor, depending on the live spell; preview geometry uses +Z as forward"
            },
            "previewReachBlocks" to previewReach,
            "launchOffsetBlocks" to if (previewReach != PREVIEW_REACH_BLOCKS) FIXED_LAUNCH_OFFSET_BLOCKS else 0.0,
            "previewPathLengthBlocks" to previewPath,
            "timelineNote" to "$notes Most single-source particle frames use a 160-sample admission limit. OSSUARY divides a 240-sample cast-frame budget across its spikes; REQUIEM source bell frames emit at most 56 samples, while PRISM beam lanes are sampled separately.",
            "sourceFunctions" to (listOf("staffArsenalParts", "staffArsenalParticles", "staffPrismBeam") +
                if (spell == StaffSpell.GALE || spell == StaffSpell.TIDE)
                    listOf("staffTideGaleParts", "staffTideGaleParticleSamples", "staffGaleWanderStep") else emptyList()),
            "frames" to frames,
        ).let { clip ->
            if (galePitchDegrees == 0.0) clip else clip + mapOf(
                "casterEye" to point(0.0, 1.62, 0.0),
                "casterLook" to point(0.0, kotlin.math.sin(Math.toRadians(galePitchDegrees)),
                    kotlin.math.cos(Math.toRadians(galePitchDegrees))),
            )
        }
    }

    /** Cuboid actors expose the live pull/scale poses; they are not exported Minecraft mob models. */
    private fun blackHoleClip(): Map<String, Any> {
        val core = Vector(0.0, 1.15, 8.0)
        val positions = listOf(Vector(-3.1, 0.0, 7.4), Vector(2.8, 0.0, 8.3), Vector(.8, 0.0, 11.1))
        val outward = positions.map { it.clone().subtract(core).apply { y = 0.0 }.normalize() }
        val scales = DoubleArray(positions.size) { 1.0 }
        val releaseScales = DoubleArray(positions.size) { 1.0 }
        val fed = mutableSetOf<Int>()
        var growth = 1.0
        val duration = 70
        val frames = ticks(duration).map { age ->
            val releasing = age >= STAFF_BLACK_HOLE_TICKS
            if (age == STAFF_BLACK_HOLE_TICKS) scales.copyInto(releaseScales)
            positions.forEachIndexed { i, feet ->
                if (age in 2 until STAFF_BLACK_HOLE_TICKS) {
                    val offset = core.clone().subtract(feet.clone().add(Vector(0.0, .9 * scales[i], 0.0)))
                    scales[i] = staffBlackHoleScale(offset.length(), scales[i])
                    if (offset.lengthSquared() <= STAFF_BLACK_HOLE_CORE_RADIUS * STAFF_BLACK_HOLE_CORE_RADIUS &&
                        scales[i] <= .35) fed += i
                    // Idealized two-tick integration removes the source's native-gravity compensation.
                    val motion = staffBlackHoleVelocity(offset).apply { y -= .12 }.multiply(2.0)
                    feet.add(motion)
                } else if (age in STAFF_BLACK_HOLE_TICKS..STAFF_BLACK_HOLE_TICKS + STAFF_BLACK_HOLE_RELEASE_TICKS) {
                    scales[i] = staffBlackHoleReleaseScale(releaseScales[i], age - STAFF_BLACK_HOLE_TICKS)
                    feet.add(outward[i].clone().multiply(.44))
                } else if (age > STAFF_BLACK_HOLE_TICKS + STAFF_BLACK_HOLE_RELEASE_TICKS) {
                    feet.y = (feet.y - .12 * (age - STAFF_BLACK_HOLE_TICKS - STAFF_BLACK_HOLE_RELEASE_TICKS)).coerceAtLeast(0.0)
                }
            }
            growth = minOf(staffBlackHoleGrowth(fed.size), growth + .04)
            val parts = mutableListOf<Map<String, Any>>()
            if (age < 60) {
                val holeAge = if (releasing) age - STAFF_BLACK_HOLE_TICKS else age
                staffDisplayParts(StaffSpell.MARK, holeAge, if (releasing) 20 else STAFF_BLACK_HOLE_TICKS,
                    1.0, STAFF_BLACK_HOLE_RADIUS, impact = releasing, secondary = true,
                    animationAgeTicks = age, blackHoleGrowth = growth).forEachIndexed { index, display ->
                    parts += part(display, "black-hole", index, core)
                }
            }
            positions.forEachIndexed { i, feet ->
                fun actorPart(material: Material, x: Double, y: Double, z: Double,
                    width: Double, height: Double, depth: Double): StaffDisplayPart {
                    val scale = scales[i].toFloat()
                    return StaffDisplayPart(material, Vector3f(x.toFloat(), y.toFloat(), z.toFloat()).mul(scale),
                        Vector3f(width.toFloat(), height.toFloat(), depth.toFloat()).mul(scale), Quaternionf())
                }
                listOf(
                    actorPart(Material.GREEN_CONCRETE, 0.0, 1.58, 0.0, .45, .44, .45),
                    actorPart(Material.CYAN_WOOL, 0.0, .99, 0.0, .55, .72, .32),
                    actorPart(Material.BLUE_CONCRETE, -.14, .36, 0.0, .24, .72, .28),
                    actorPart(Material.BLUE_CONCRETE, .14, .36, 0.0, .24, .72, .28),
                    actorPart(Material.GREEN_CONCRETE, -.37, 1.22, -.20, .18, .19, .60),
                    actorPart(Material.GREEN_CONCRETE, .37, 1.22, -.20, .18, .19, .60),
                ).forEachIndexed { index, display -> parts += part(display, "illustrative-mob-$i", index, feet) }
            }
            mapOf("tick" to age, "parts" to parts, "emissions" to emptyList<Any>(),
                "actorScales" to scales.toList(), "fedMobs" to fed.size, "holeGrowth" to growth)
        }
        return mapOf(
            "id" to "mark-secondary-0-absorption", "spell" to StaffSpell.MARK.id,
            "input" to StaffCastInput.SECONDARY.name, "quick" to false, "charge" to 0.0,
            "durationTicks" to duration, "tailTicks" to 0, "frameStepTicks" to FRAME_STEP_TICKS,
            "particleOnly" to false, "casterFeet" to point(0.0, 0.0, 0.0),
            "casterEye" to point(0.0, 1.62, 0.0), "casterLook" to point(0.0, 0.0, 1.0),
            "anchor" to "shared visual/gameplay core 8 blocks forward, 1.15 blocks above floor",
            "sourceFunctions" to listOf("staffDisplayParts", "staffBlackHoleScale", "staffBlackHoleVelocity",
                "staffBlackHoleGrowth", "staffBlackHoleReleaseScale"),
            "timelineNote" to "Три цветных кубоидных манекена — условные мобы для проверки масштаба, не нативные модели. Production-функции задают уменьшение до 18%, тягу и рост ядра на 8% за поглощённого моба (предел 48% при шести). 40 тиков поглощения, затем 10 тиков возврата размера без дополнительного урона; сфера схлопывается до тика 60. Движение манекенов — иллюстративная интеграция без AI, стен и точной физики Minecraft; урон, admission и cleanup проверяются отдельно. В runtime игроки никогда не уменьшаются.",
            "frames" to frames,
        )
    }

    private fun quickClip(spell: StaffSpell, input: StaffCastInput): Map<String, Any> {
        val heavy = spell in setOf(StaffSpell.FAULT, StaffSpell.GRAVITY, StaffSpell.OSSUARY,
            StaffSpell.NOVA, StaffSpell.NECROMANCER)
        val allAround = input.secondary
        val duration = if (allAround) WARD_PREVIEW_DURATION_TICKS else if (heavy) 32 else 24
        val hitTick = if (allAround) WARD_PREVIEW_DEPLETION_TICK else 8
        val endTick = if (allAround) hitTick + WARD_PREVIEW_BREAK_TICKS else duration
        val postHitStrength = if (heavy) 2.0 / 3.0 else .5
        val frames = ticks(duration).map { age ->
            val active = age < duration && age < endTick
            val frameDuration = if (allAround && age >= hitTick) endTick else duration
            val endingAge = if (allAround && age >= hitTick) age - hitTick else null
            val impactAge = (age - hitTick).takeIf { it in 0..6 } ?: 100
            val strength = when {
                allAround && age >= hitTick -> 0.0
                age >= hitTick -> postHitStrength
                else -> 1.0
            }
            mapOf(
                "tick" to age,
                "parts" to if (active) staffDefenseParts(
                    spell, age, frameDuration, allAround, strength, impactAge, endingAge,
                )
                    .mapIndexed { index, display ->
                        part(display, "${spell.id}:${input.name.lowercase(Locale.ROOT)}", index, Vector())
                    } else emptyList<Any>(),
                "emissions" to if (age == hitTick)
                    staffDefenseParticles(spell, age, allAround, impact = true, depleted = allAround).map(::sample)
                    else emptyList(),
            )
        }
        return mapOf(
            "id" to "${spell.id}-${input.name.lowercase(Locale.ROOT)}-0",
            "spell" to spell.id, "input" to input.name, "quick" to true,
            "charge" to 0.0, "durationTicks" to duration, "tailTicks" to 40,
            "frameStepTicks" to FRAME_STEP_TICKS,
            "particleOnly" to frames.all { (it["parts"] as List<*>).isEmpty() },
            "anchor" to "caster feet", "previewReachBlocks" to 2.0,
            "timelineNote" to if (allAround)
                "Production staffDefenseParts: Shift+LMB keeps four transparent kite panels for ${duration / 20.0}s. Authored hit scenario: the shield depletes at tick $hitTick and its source break animation ends at tick $endTick; particles emit only at that contact."
            else
                "Production staffDefenseParts: RMB keeps a frontal kite guard for ${duration / 20.0}s. Authored hit scenario: the first frontal hit lands at tick 8 and does not deplete the guard; particles emit only at contact. Combat and native expiry are not simulated.",
            "sourceFunctions" to listOf("staffDefenseParts", "staffDefenseParticles"), "frames" to frames,
        )
    }

    /** Ember's persistent body is sampled at the real charge clock, then carried without respawning. */
    private fun emberChargeClip(holdTicks: Int): Map<String, Any> {
        val charge = staffChargeProgress(holdTicks)
        var totalTravel = 0.0
        var travelTicks = 0
        while (totalTravel < PREVIEW_REACH_BLOCKS && travelTicks < 80) {
            travelTicks++
            totalTravel = (totalTravel + staffEmberStep(travelTicks, EMBER_PREVIEW_SPEED))
                .coerceAtMost(PREVIEW_REACH_BLOCKS)
        }
        val duration = holdTicks + travelTicks + 1
        val eye = Vector(0.0, 1.62, 0.0)
        val offset = staffEmberChargeOffset()
        val bodyAtRelease = staffEmberBodyParts(charge, offset).map { display ->
            display.copy(center = org.joml.Vector3f(display.center).sub(offset))
        }
        val frames = ticks(duration - 1).map { age ->
            val flying = age >= holdTicks
            val parts = if (!flying) {
                val updateTick = (age / CHARGE_UPDATE_TICKS) * CHARGE_UPDATE_TICKS
                val progress = staffChargeProgress(minOf(updateTick, holdTicks))
                staffChargeParts(StaffSpell.EMBER, progress, age).mapIndexed { index, display ->
                    part(display, "ember-body", index, eye)
                }
            } else {
                val flightAge = age - holdTicks
                val distance = (1..flightAge).sumOf { staffEmberStep(it, EMBER_PREVIEW_SPEED) }
                    .coerceAtMost(PREVIEW_REACH_BLOCKS)
                val origin = eye.clone().add(Vector(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()))
                    .add(Vector(0.0, 0.0, distance))
                bodyAtRelease.mapIndexed { index, display -> part(display, "ember-body", index, origin) }
            }
            mapOf("tick" to age, "parts" to parts, "emissions" to emptyList<Any>())
        }
        return mapOf(
            "id" to "ember-charge-${chargeToken(charge)}", "spell" to StaffSpell.EMBER.id, "input" to "CHARGE",
            "quick" to false, "charge" to charge, "durationTicks" to duration, "tailTicks" to 0,
            "frameStepTicks" to FRAME_STEP_TICKS, "particleOnly" to false,
            "anchor" to "caster eye; neutral yaw/pitch with local +Z forward",
            "previewReachBlocks" to PREVIEW_REACH_BLOCKS,
            "timelineNote" to "Source geometry: ${holdTicks}-tick charge, then the same 21 body parts and identities move along +Z using staffEmberStep at the default 1.2 speed. The release origin includes staffEmberChargeOffset; release age 0 preserves the charge pose. Flight is shown through the fixed 12-block preview lane.",
            "sourceFunctions" to listOf("staffChargeProgress", "staffChargeParts", "staffEmberBodyParts", "staffEmberChargeOffset", "staffEmberStep"),
            "frames" to frames,
        )
    }

    /** Icicle's eleven charge scenes release their active keyed eight-part bodies. */
    private fun icicleChargeClip(holdTicks: Int): Map<String, Any> {
        data class ChargeTween(val from: StaffDisplayPart, val target: StaffDisplayPart, val startedAt: Int)
        fun pose(tween: ChargeTween, tick: Int, duration: Int = StaffSpellDisplayEffects.CHARGE_INTERPOLATION_TICKS): StaffDisplayPart {
            val fraction = ((tick - tween.startedAt).toFloat() / duration).coerceIn(0f, 1f)
            val size = Vector3f(tween.from.scale).lerp(tween.target.scale, fraction)
            return tween.target.copy(
                center = Vector3f(tween.from.center).lerp(tween.target.center, fraction),
                scale = size,
                rotation = Quaternionf(tween.from.rotation).slerp(tween.target.rotation, fraction),
                visible = tween.target.visible && size.lengthSquared() > 0f,
            )
        }
        val charge = staffChargeProgress(holdTicks)
        val activeCount = staffIcicleCount(charge)
        val eye = Vector(0.0, 1.62, 0.0)
        val target = Vector(0.0, eye.y, staffIcicleChargeAnchorOffset(0).z + PREVIEW_REACH_BLOCKS)
        var flightTicks = 0
        var flightDistance = 0.0
        while (flightDistance < PREVIEW_REACH_BLOCKS) {
            flightTicks++
            flightDistance += staffIcicleStep(flightTicks)
        }
        val lastLaunchDelay = if (activeCount == 0) 0 else staffIcicleLaunchDelay(activeCount - 1)
        val duration = holdTicks + lastLaunchDelay + flightTicks
        val chargeTweens = Array(11) { linkedMapOf<String, ChargeTween>() }
        var releasedBodies: List<List<StaffDisplayPart>> = List(11) { emptyList() }
        val frames = (0 until duration).map { age ->
            val charging = age <= holdTicks
            val parts = if (charging) {
                if (age % CHARGE_UPDATE_TICKS == 0) {
                    val progress = staffChargeProgress(age)
                    (0 until 11).forEach { slot ->
                        staffIcicleChargeParts(progress, slot).forEach { display ->
                            val key = requireNotNull(display.identity)
                            val previous = chargeTweens[slot][key]
                            val from = previous?.let { pose(it, age) } ?: display.copy(scale = Vector3f())
                            chargeTweens[slot][key] = ChargeTween(from, display,
                                if (previous == null) age + 1 else age)
                        }
                    }
                }
                val bodies = chargeTweens.map { slot -> slot.values.map { pose(it, age) } }
                if (age == holdTicks) releasedBodies = bodies
                (0 until 11).flatMap { slot ->
                    val offset = staffIcicleChargeAnchorOffset(slot)
                    val anchor = eye.clone().add(Vector(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()))
                    bodies[slot].mapIndexed { index, display ->
                        part(display, "icicle-charge:$slot", index, anchor)
                    }
                }
            } else {
                val releaseAge = age - holdTicks
                (0 until activeCount).flatMap { slot ->
                    val offset = staffIcicleChargeAnchorOffset(slot)
                    val anchor = eye.clone().add(Vector(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()))
                    val position = anchor.clone()
                    val direction = Vector(0.0, 0.0, 1.0)
                    var rotation = org.joml.Quaternionf()
                    var remaining = PREVIEW_REACH_BLOCKS
                    val launchDelay = staffIcicleLaunchDelay(slot)
                    for (tick in 1..releaseAge) {
                        if (tick < launchDelay) continue
                        if (remaining <= 0.001) break
                        val flightAge = tick - launchDelay + 1
                        if (flightAge > 4)
                            steerStaffBolt(direction, target.clone().subtract(position), flightAge)
                        val step = minOf(staffIcicleStep(flightAge), remaining)
                        position.add(direction.clone().multiply(step))
                        rotation = staffIcicleFlightOrientation(rotation, org.joml.Vector3f(
                            direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat(),
                        ))
                        remaining -= step
                    }
                    val targetBodies = chargeTweens[slot].values.map { it.target }
                    releasedBodies[slot].mapIndexed { index, held ->
                        val display = pose(ChargeTween(held, targetBodies[index], holdTicks), age,
                            StaffSpellDisplayEffects.FRAME_TICKS)
                        val oriented = display.copy(
                            center = rotation.transform(org.joml.Vector3f(display.center)),
                            rotation = org.joml.Quaternionf(rotation).mul(display.rotation),
                        )
                        part(oriented, "icicle-charge:$slot", index, position)
                    }
                }
            }
            mapOf("tick" to age, "parts" to parts, "emissions" to emptyList<Any>())
        }
        return mapOf(
            "id" to "icicle-charge-${chargeToken(charge)}", "spell" to StaffSpell.ICICLE.id, "input" to "CHARGE",
            "quick" to false, "charge" to charge, "durationTicks" to duration, "tailTicks" to 0,
            "frameStepTicks" to 1, "particleOnly" to false,
            "anchor" to "neutral caster eye; eleven camera-space crown anchors; local +Z forward",
            "previewReachBlocks" to PREVIEW_REACH_BLOCKS,
            "illustrativeTarget" to point(target.x, target.y, target.z),
            "previewFlightDistanceBlocks" to PREVIEW_REACH_BLOCKS,
            "timelineNote" to "Production geometry sampled every tick: ${holdTicks}-tick charge updates every four ticks. Newly born keyed parts begin at zero scale, receive their first growth target one tick later, and interpolate over four ticks; existing parts interpolate from their current pose. Release keeps the same ${activeCount} eight-part bodies and completes their current growth over the normal two-tick flight transform, without a visual reset. Launch delays use staffIcicleLaunchDelay, flight uses staffIcicleStep and the illustrative target. This models the interpolation contract, not the native packet engine, collision or damage; travel is capped at ${PREVIEW_REACH_BLOCKS} blocks.",
            "sourceFunctions" to listOf("staffChargeProgress", "staffIcicleChargeAnchorOffset", "staffIcicleChargeParts", "staffIcicleCount", "StaffSpellDisplayEffects.CHARGE_INTERPOLATION_TICKS", "StaffSpellDisplayEffects.FRAME_TICKS", "staffIcicleLaunchDelay", "staffIcicleStep", "steerStaffBolt"),
            "frames" to frames,
        )
    }

    private fun rootNetworkClip(input: StaffCastInput, charge: Double, seed: Long = 871L,
        raised: Boolean = false, airborne: Boolean = false): Map<String, Any> {
        var result: Map<String, Any> = emptyMap()
        withStaffHarness { h ->
            val settings = staffSettings(range = 56.0)
            val world = h.paper.addSimpleWorld("root-preview-$seed-${input.name}-$raised-$airborne")
            for (x in -14..14) for (z in -4..32) {
                world.getBlockAt(x, 63, z).type = Material.STONE
                if (raised && z >= 8) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            val player = h.player("root-preview", world, StaffSpell.NOVA)
            if (airborne) player.teleport(player.location.add(0.0, 20.0, 0.0))
            val targets = listOf(.5 to 12.5, -2.2 to 11.0, 2.8 to 13.0, .3 to 16.0).map { (x, z) ->
                h.zombie(world, x, z).apply { if (raised) teleport(location.add(0.0, 1.0, 0.0)) }
            }
            player.teleport(player.location.setDirection(center(targets.first()).toVector().subtract(player.eyeLocation.toVector())))
            var age = 0
            data class ExportScene(val id: UUID, val origin: Location, val born: Int, val duration: Int,
                val geometry: (Int) -> List<StaffDisplayPart>)
            val scenes = mutableListOf<ExportScene>()
            val expiry = mutableMapOf<UUID, Int>()
            val contacts = linkedMapOf<UUID, Int>()
            val emissions = mutableListOf<Map<String, Any>>()
            every { h.effects.custom(any(), any(), any(), any(), any()) } answers {
                val id = UUID.randomUUID()
                scenes += ExportScene(id, arg<Location>(2).clone(), age, arg(3), arg(4))
                id
            }
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            every { damage.eligible(any(), any(), any()) } answers { secondArg<org.bukkit.entity.LivingEntity>() !is Player }
            every { damage.hit(any(), any(), any(), any(), any()) } answers {
                contacts[secondArg<org.bukkit.entity.LivingEntity>().uniqueId] = age
                true
            }
            val visuals = mockk<StaffSpellVisuals>(relaxed = true)
            every { visuals.withOwner(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
            every { visuals.particles(any(), any(), any()) } answers {
                val at = firstArg<Location>()
                secondArg<List<StaffParticleSample>>().forEach { emissions += sample(it, Vector(at.x, at.y - 64.0, at.z)) }
            }
            val arsenal = StaffSpellArsenal(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage),
                rootTarget = { _, target, _, duration -> expiry[target.uniqueId] = age + duration; true },
                rootActive = { target, _ -> (expiry[target] ?: 0) > age }, clearRoots = { expiry.clear() })
            val frames = mutableListOf<Map<String, Any>>()
            try {
                arsenal.start()
                check(arsenal.cast(player, StaffSpell.NOVA,
                    StaffSpellCast(player.uniqueId, UUID(0L, seed), 1, null, null, false),
                    settings.tuning.getValue(StaffSpell.NOVA), input, charge) == StaffArsenalCastResult.ACCEPTED)
                ticks(STAFF_NOVA_ATTACK_TICKS).forEach { tick ->
                    age = tick
                    if (tick > 0) h.scheduler.tick(FRAME_STEP_TICKS.toLong())
                    val parts = scenes.filter { it.id !in h.removedEffects && age - it.born in 0 until it.duration }
                        .flatMapIndexed { sceneIndex, scene -> scene.geometry(age - scene.born).mapIndexed { index, display ->
                            part(display, "nova:$sceneIndex", index, Vector(scene.origin.x, scene.origin.y - 64.0, scene.origin.z))
                        } }
                    frames += mapOf("tick" to tick, "parts" to parts, "emissions" to emissions.toList())
                    emissions.clear()
                }
            } finally { arsenal.close() }
            val suffix = when { airborne -> "-air-terrace"; raised -> "-terrace"; seed != 871L -> "-cast-b"; else -> "" }
            result = mapOf("id" to "nova-${input.name.lowercase(Locale.ROOT)}-${charge.toInt()}$suffix",
                "spell" to "nova", "input" to input.name, "charge" to charge, "quick" to false,
                "durationTicks" to STAFF_NOVA_ATTACK_TICKS, "tailTicks" to 0, "frameStepTicks" to FRAME_STEP_TICKS,
                "particleOnly" to false, "previewReachBlocks" to STAFF_NOVA_FAN_REACH,
                "casterFeet" to point(player.location.x, player.location.y - 64.0, player.location.z),
                "casterEye" to point(player.eyeLocation.x, player.eyeLocation.y - 64.0, player.eyeLocation.z),
                "casterLook" to point(player.eyeLocation.direction.x, player.eyeLocation.direction.y, player.eyeLocation.direction.z),
                "illustrativeTargets" to targets.map { point(it.location.x, it.location.y - 64.0, it.location.z) },
                "syntheticTerrain" to if (raised) (-9..9).flatMap { x -> (8..23).map { z -> point(x + .5, .5, z + .5) } } else emptyList(),
                "acceptedContacts" to targets.mapIndexedNotNull { index, target -> contacts[target.uniqueId]?.let { mapOf("target" to index, "tick" to it) } },
                "timelineNote" to "Actual StaffSpellArsenal frames and sampled block collision in the test harness. Cast seed $seed stays fixed through growth; other casts change bends, widths and side branches. " +
                    (if (raised) "Targets stand one block above the approach floor. " else "Flat connected floor. ") +
                    (if (airborne) "The caster flies 20 blocks above the floor; roots seed the aimed ledge and follow its connected surface. " else "The caster stands at the approach floor. ") +
                    "Two field pools stay within 96 parts. Only harness-accepted hits create independently timed 70-tick wraps. Combat bridge and server packet/client rendering are mocked; this is source/runtime geometry evidence, not native-client acceptance.",
                "sourceFunctions" to listOf("StaffSpellArsenal.cast", "staffRootSurfacePath", "staffNovaFanPaths", "staffNovaSnarePaths",
                    "staffNovaFanParts", "staffNovaSnareParts", "staffNovaWrapParts"), "frames" to frames)
        }
        return result
    }

    private fun prismRefractionClip(charge: Double): Map<String, Any> {
        val duration = staffArsenalLifetime(StaffSpell.PRISM, true)
        val aimAnchor = Vector(0.0, 0.0, 12.2)
        val radius = arsenalRadius(StaffSpell.PRISM, StaffCastInput.SECONDARY, charge)
        val points = listOf(Vector3f(0f, 1.65f, 2.5f), Vector3f(1.8f, 1.5f, 10f),
            Vector3f(-2.0f, 1.1f, 13f), Vector3f(3.6f, 1.4f, 15f), Vector3f(-3f, 1.2f, 17f))
        val frames = ticks(duration).map { age ->
            val parts = mutableListOf<Map<String, Any>>()
            val emissions = mutableListOf<Map<String, Any>>()
            if (age < duration) staffArsenalParts(StaffSpell.PRISM, age, duration, 1.0, radius,
                impact = false, secondary = true, input = StaffCastInput.SECONDARY, charge = charge)
                .forEachIndexed { index, display -> parts += part(display, "prism:assembly", index, aimAnchor) }
            if (age in 10 until duration) staffPrismRefractionParts(age - 10, duration - 10, points)
                .forEachIndexed { index, display -> parts += part(display, "prism:route", index, Vector()) }
            if (age < 10) {
                staffArsenalParticles(StaffSpell.PRISM, age, duration, radius, true, charge)
                    .forEach { emissions += sample(it, aimAnchor) }
            } else if (age <= 32) {
                val travel = staffPrismTravelDistance(age - 10, points)
                var passed = 0.0
                points.zipWithNext().forEach { (from, to) ->
                    val ray = Vector((to.x - from.x).toDouble(), (to.y - from.y).toDouble(), (to.z - from.z).toDouble())
                    val length = ray.length()
                    val front = ((travel - passed) / length).coerceIn(0.0, 1.0)
                    passed += length
                    if (front > 0.0) staffPrismBeam(ray, front, charge, age)
                        .filterIndexed { index, _ -> index % 4 == 0 }
                        .forEach { emissions += sample(it, Vector(from.x.toDouble(), from.y.toDouble(), from.z.toDouble())) }
                }
            }
            mapOf("tick" to age, "parts" to parts, "emissions" to emissions)
        }
        return mapOf("id" to "prism-secondary-${charge.toInt()}", "spell" to "prism",
            "input" to "SECONDARY", "charge" to charge, "quick" to false,
            "durationTicks" to duration, "tailTicks" to 0, "frameStepTicks" to FRAME_STEP_TICKS,
            "particleOnly" to false, "previewReachBlocks" to 18.0,
            "timelineNote" to "Both production scenes are shown: the aimed core/satellites rise from tick0 and remain for40ticks; placed mirrors appear at tick10 and the beam advances through the route over14ticks. Native anticipation and bounded beam particles follow runtime emission timing. The flat-ground aim and target route are illustrative; actual target selection, cover and damage are not simulated.",
            "sourceFunctions" to listOf("staffArsenalParts", "staffArsenalParticles", "staffPrismRefractionParts", "staffPrismTravelDistance", "staffPrismBeam"),
            "frames" to frames)
    }

    private fun frostRingClip(): Map<String, Any> {
        val duration = 30
        return mapOf("id" to "frost-secondary-0", "spell" to "frost", "input" to "SECONDARY",
            "charge" to 0.0, "quick" to false, "durationTicks" to duration, "tailTicks" to 0,
            "frameStepTicks" to FRAME_STEP_TICKS, "particleOnly" to false, "previewReachBlocks" to 8.0,
            "timelineNote" to "Production radial ice shelf and teeth at8blocks, matching30-tick scene. Native wave particles/audio and collision are not represented in this clip.",
            "sourceFunctions" to listOf("staffDisplayParts"),
            "frames" to ticks(duration).map { age -> mapOf("tick" to age,
                "parts" to (if (age < duration) staffDisplayParts(StaffSpell.FROST, age, duration,
                    1.0, 8.0, impact = true, secondary = true) else emptyList())
                    .mapIndexed { index, display -> part(display, "frost-nova", index, Vector()) },
                "emissions" to emptyList<Any>()) })
    }

    private fun preparationClip(spell: StaffSpell, secondary: Boolean): Map<String, Any>? {
        if (!secondary && staffMeleeReach(spell) != null) return null
        val duration = PREPARATION_DURATION_TICKS
        val input = if (secondary) "PREP_SECONDARY" else "PREP_PRIMARY"
        val eye = Vector(0.0, 1.62, 0.0)
        val frames = ticks(duration).map { age ->
            val parts = staffPreparationParts(spell, secondary, age, duration).mapIndexed { index, display ->
                part(display, "preparation:${spell.id}:${if (secondary) "secondary" else "primary"}", index, eye)
            }
            mapOf("tick" to age, "parts" to parts, "emissions" to emptyList<Any>())
        }
        if (frames.none { (it["parts"] as List<*>).isNotEmpty() }) return null
        val suffix = if (secondary) "secondary" else "primary"
        return mapOf(
            "id" to "${spell.id}-prep_${suffix}-0", "spell" to spell.id, "input" to input,
            "quick" to false, "charge" to 0.0, "durationTicks" to duration, "tailTicks" to 0,
            "frameStepTicks" to FRAME_STEP_TICKS, "particleOnly" to false,
            "anchor" to "caster eye; yaw-only scene frame with local +Z forward",
            "previewReachBlocks" to PREVIEW_REACH_BLOCKS,
            "timelineNote" to "Production staffPreparationParts display cue at eye origin: 10 ticks, sampled every two ticks. Age 0 opens; ages 2–4 show the full pose; ages 6–8 show its fade. This is the authored pre-cast cue, not an attack animation.",
            "sourceFunctions" to listOf("staffPreparationParts"), "frames" to frames,
        )
    }

    private fun meleeClip(spell: StaffSpell): Map<String, Any> {
        val reach = requireNotNull(staffMeleeReach(spell))
        val duration = staffMeleeDuration(spell)
        val origin = Vector(0.0, 1.37, 0.0)
        return mapOf(
            "id" to "${spell.id}-primary-0", "spell" to spell.id,
            "input" to "PRIMARY", "quick" to false, "charge" to 0.0,
            "durationTicks" to duration, "tailTicks" to 16, "frameStepTicks" to FRAME_STEP_TICKS,
            "particleOnly" to false, "anchor" to "caster eye minus 0.25 blocks; horizontal +Z",
            "previewReachBlocks" to reach,
            "timelineNote" to "Большая фронтальная атака. Контакт на шестом тике; Display-кадры каждые два тика с интерполяцией. Реальные столкновения, звук и клиентская физика частиц здесь не моделируются.",
            "sourceFunctions" to listOf("staffMeleeDisplayParts", "staffMeleeParticles"),
            "frames" to ticks(duration).map { age -> mapOf(
                "tick" to age,
                "parts" to staffMeleeDisplayParts(spell, age, duration, reach).mapIndexed { index, display ->
                    part(display, "melee:${spell.id}", index, origin)
                },
                "emissions" to staffMeleeParticles(spell, age, duration, reach).map { sample(it, origin) },
            ) },
        )
    }

    private fun gravityPreviewImpactTick(): Int {
        var traveled = 0.0
        var speed = GRAVITY_PREVIEW_INITIAL_SPEED
        var flightTicks = 0
        while (traveled < GRAVITY_PREVIEW_PATH_BLOCKS && flightTicks < GRAVITY_PREVIEW_FLIGHT_TICKS) {
            flightTicks += FRAME_STEP_TICKS
            speed = (speed + GRAVITY_PREVIEW_ACCELERATION * FRAME_STEP_TICKS)
                .coerceAtMost(GRAVITY_PREVIEW_MAX_SPEED)
            traveled = (traveled + minOf(speed, GRAVITY_PREVIEW_PATH_BLOCKS - traveled))
                .coerceAtMost(GRAVITY_PREVIEW_PATH_BLOCKS)
        }
        return GRAVITY_PREVIEW_HOLD_TICKS + flightTicks
    }

    private fun chainStormNovaClip(): Map<String, Any> {
        val duration = STAFF_STORM_NOVA_BUILDUP_TICKS + STAFF_STORM_NOVA_TRAVEL_TICKS + 2
        val unobstructedRayLimits = List(STAFF_STORM_NOVA_RAY_COUNT) { STAFF_STORM_NOVA_RADIUS }
        val frames = ticks(duration).map { age ->
            val emissions = staffStormNovaFrame(age, STAFF_STORM_NOVA_RADIUS, unobstructedRayLimits)
                .map { sample(it) }
            mapOf("tick" to age, "parts" to stormStrikePreviewParts(age, unobstructedRayLimits)
                .mapIndexed { index, display -> part(display, "native-lightning-proxy", index, Vector()) },
                "emissions" to emissions)
        }
        return mapOf(
            "id" to "chain-secondary-storm-nova",
            "spell" to StaffSpell.CHAIN.id,
            "input" to StaffCastInput.SECONDARY.name,
            "quick" to false,
            "charge" to 0.0,
            "durationTicks" to duration,
            "tailTicks" to 40,
            "frameStepTicks" to FRAME_STEP_TICKS,
            "particleOnly" to false,
            "anchor" to "caster feet; 24 horizontal radial limits, local X/Z in the world plane",
            "previewReachBlocks" to STAFF_STORM_NOVA_RADIUS,
            "previewPathLengthBlocks" to STAFF_STORM_NOVA_RADIUS,
            "timelineNote" to "Source CHAIN Shift+ПКМ nova: a 360-degree front with 24 horizontal rays at the unobstructed 8-block source radius. Cast age 0 starts the buildup, ages 2–4 finish its core, then the front expands from age 4 to 14. Three native effect-only sky bolts strike at ages 6, 10 and 14 along that front. White zigzags here are labeled illustrative proxies for the native lightning renderer, not production BlockDisplays; their positions and timing use the production strike schedule. Runtime cover clips sectors. Native lightning appearance and audio require the Minecraft client.",
            "sourceFunctions" to listOf("staffStormNovaFrame", "staffStormNovaStrikes"),
            "frames" to frames,
        )
    }

    /** Illustrative native-lightning proxy only; production uses strikeLightningEffect, not these displays. */
    private fun stormStrikePreviewParts(age: Int, limits: List<Double>): List<StaffDisplayPart> =
        staffStormNovaStrikes(age, STAFF_STORM_NOVA_RADIUS, limits).flatMapIndexed { strike, at ->
            (0 until 5).map { segment ->
                fun point(step: Int) = Vector3f((at.x + if (step == 0) 0.0 else
                    (if ((step + strike) % 2 == 0) .38 else -.38)).toFloat(),
                    (step * 2.0).toFloat(), (at.z + if (step % 2 == 0) 0.0 else .22).toFloat())
                val from = point(segment)
                val to = point(segment + 1)
                val delta = Vector3f(to).sub(from)
                StaffDisplayPart(Material.WHITE_CONCRETE, Vector3f(from).add(to).mul(.5f),
                    Vector3f(.11f, .11f, delta.length()),
                    Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), delta.normalize()),
                    identity = "illustrative-lightning:$age:$strike:$segment")
            }
        }

    private fun requiemBellClip(input: StaffCastInput, charge: Double): Map<String, Any> {
        val secondary = input.secondary
        val duration = staffArsenalLifetime(StaffSpell.REQUIEM, secondary)
        val radius = if (secondary) STAFF_REQUIEM_SECONDARY_RADIUS else STAFF_REQUIEM_PRIMARY_RADIUS
        val anchor = if (secondary) Vector() else Vector(0.0, 0.0, PREVIEW_REACH_BLOCKS)
        val unobstructedRayLimits = List(STAFF_REQUIEM_COLLISION_RAY_COUNT) { radius }
        val mode = if (secondary) "secondary" else "primary"
        val frames = ticks(duration).map { age ->
            val parts = staffRequiemBellParts(age, duration, secondary, charge).mapIndexed { index, display ->
                part(display, "requiem:bell:$mode", index, anchor)
            }
            val emissions = staffRequiemParticles(age, duration, secondary, radius, charge, unobstructedRayLimits)
                .map { sample(it, anchor) }
            mapOf("tick" to age, "parts" to parts, "emissions" to emissions)
        }
        val clangTicks = if (secondary) STAFF_REQUIEM_SECONDARY_CLANG_TICKS
            else STAFF_REQUIEM_PRIMARY_CLANG_TICKS
        val note = "Production bell and pulses: clangs at $clangTicks, 14-tick expanding fronts, ${duration}-tick lifetime. Primary uses a fixed aimed point; secondary is centered on the caster."
        return mapOf(
            "id" to "${StaffSpell.REQUIEM.id}-${input.name.lowercase(Locale.ROOT)}-${charge.toInt()}",
            "spell" to StaffSpell.REQUIEM.id,
            "input" to input.name,
            "quick" to false,
            "charge" to charge,
            "durationTicks" to duration,
            "tailTicks" to 40,
            "frameStepTicks" to FRAME_STEP_TICKS,
            "particleOnly" to false,
            "anchor" to if (secondary) "caster ground anchor; bell center is +4y" else "fixed aimed ground point at +Z; bell center is +4y",
            "previewReachBlocks" to if (secondary) radius else PREVIEW_REACH_BLOCKS,
            "previewPathLengthBlocks" to if (secondary) radius else PREVIEW_REACH_BLOCKS,
            "pulseRadiusBlocks" to radius,
            "clangTicks" to clangTicks,
            "timelineNote" to "$note Geometry and particles call the production REQUIEM helpers; the preview does not simulate sound playback, target selection, cover clipping, or combat.",
            "sourceFunctions" to listOf("staffArsenalLifetime", "staffRequiemPulse", "staffRequiemBellParts", "staffRequiemParticles"),
            "frames" to frames,
        )
    }

    private fun gravityClip(input: StaffCastInput, charge: Double): Map<String, Any> {
        val spell = StaffSpell.GRAVITY
        if (input.secondary) {
            val duration = 36
            val radius = 6.5
            return mapOf(
                "id" to "gravity-secondary-${charge.toInt()}", "spell" to spell.id,
                "input" to input.name, "quick" to false, "charge" to charge,
                "durationTicks" to duration, "tailTicks" to 16, "frameStepTicks" to FRAME_STEP_TICKS,
                "particleOnly" to false, "anchor" to "caster feet; annular floor compression at the slam",
                "previewReachBlocks" to radius,
                "timelineNote" to "A low pressure collar converges; at tick 8 up to 32 native-textured floor cells in a donut sink 0.8 block, hold, then return. The world and collision blocks never change. This preview uses a labeled synthetic flat grass floor with a hole cut only beneath the animated ring; live source blocks are terrain-sampled in allowed worlds. Existing target lift/slam and accepted landing damage are unchanged.",
                "syntheticTerrain" to (-5..5).flatMap { x -> (-5..5).map { z -> x to z } }
                    .filterNot { it in staffSceneryDepressionFootprint(radius) }
                    .map { (x, z) -> mapOf("x" to x, "z" to z) },
                "sourceFunctions" to listOf("staffGravityPulseParts", "staffGravityPulseFrame", "staffSceneryDepressionFootprint", "staffSceneryDepressionY"),
                "frames" to ticks(duration).map { age -> mapOf<String, Any>(
                    "tick" to age,
                    "parts" to (staffGravityPulseParts(age, duration, radius) +
                        staffSceneryDepressionFootprint(radius).map { (x, z) ->
                            StaffDisplayPart(Material.GRASS_BLOCK,
                                Vector3f(x.toFloat(), (-.5 + staffSceneryDepressionY((age - 8).coerceAtLeast(0))).toFloat(), z.toFloat()),
                                Vector3f(1f, 1f, 1f), Quaternionf(), identity = "synthetic-terrain:$x:$z")
                        }).mapIndexed { index, display -> part(display, "gravity-pulse", index, Vector()) },
                    "emissions" to staffGravityPulseFrame(age, radius).map { sample(it) },
                ) },
            )
        }
        val impactAt = gravityPreviewImpactTick()
        val duration = impactAt + GRAVITY_PREVIEW_IMPACT_TICKS
        var throwZ = GRAVITY_PREVIEW_HOLD_DISTANCE
        var speed = GRAVITY_PREVIEW_INITIAL_SPEED
        var remaining = GRAVITY_PREVIEW_PATH_BLOCKS
        var flightTicks = 0
        var impactAge: Int? = null
        val frames = ticks(duration).map { age ->
            val emissions = mutableListOf<Map<String, Any>>()
            val parts = mutableListOf<Map<String, Any>>()
            if (age > GRAVITY_PREVIEW_HOLD_TICKS && impactAge == null) {
                flightTicks += FRAME_STEP_TICKS
                speed = (speed + GRAVITY_PREVIEW_ACCELERATION * FRAME_STEP_TICKS)
                    .coerceAtMost(GRAVITY_PREVIEW_MAX_SPEED)
                val step = minOf(speed, remaining)
                throwZ += step
                remaining = (remaining - step).coerceAtLeast(0.0)
                if (remaining <= 0.0 || flightTicks >= GRAVITY_PREVIEW_FLIGHT_TICKS) impactAge = age
            }
            val impact = impactAge != null
            val localAge = impactAge?.let { age - it } ?: age
            if (!impact || localAge < GRAVITY_PREVIEW_IMPACT_TICKS) {
                staffChannelParts(spell, localAge, if (impact) GRAVITY_PREVIEW_IMPACT_TICKS else 196, 1.0,
                    if (impact) 2.5 else 1.0, impact, input.secondary).forEachIndexed { i, display ->
                        parts += part(display, "gravity", i, Vector(0.0, 1.62, throwZ))
                    }
            }
            if (impactAge == age) staffGravityImpactFrame().forEach {
                emissions += sample(it, Vector(0.0, 1.62, throwZ))
            }
            mapOf("tick" to age, "parts" to parts, "emissions" to emissions)
        }
        return mapOf(
            "id" to "${spell.id}-${input.name.lowercase(Locale.ROOT)}-${charge.toInt()}",
            "spell" to spell.id, "input" to input.name, "quick" to false,
            "charge" to charge, "durationTicks" to duration, "tailTicks" to 40,
            "frameStepTicks" to FRAME_STEP_TICKS, "particleOnly" to false,
            "anchor" to "caster feet; held stone begins ${GRAVITY_PREVIEW_HOLD_DISTANCE}-block eye offset on +Z",
            "previewReachBlocks" to GRAVITY_PREVIEW_REACH_BLOCKS,
            "previewPathLengthBlocks" to GRAVITY_PREVIEW_PATH_BLOCKS,
            "timelineNote" to "Production boulder geometry with stable gravity-stone identities: this illustrative clip holds for 60 ticks at a four-block eye offset, then flies 28 unobstructed blocks to the 32-block caster reach. Release speed is 3.2; every 2-tick movement adds 0.32 (0.16 per tick) before moving, capped at 4.4. The calculated impact begins at tick ${impactAt} and its source geometry lasts 16 ticks. Runtime hold follows the look for up to 160 ticks and cover or a target may shorten flight. No real blocks are changed.",
            "sourceFunctions" to listOf("staffChannelParts", "staffGravityImpactFrame"),
            "frames" to frames,
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2) {
            "Usage: StaffRedesignPreviewExport <redesign-motion-clips.json> [defense-charge-preparation-clips.json]"
        }
        val output = Path.of(args[0])
        val focusedOutput = args.getOrNull(1)?.let { Path.of(it) }
            ?: output.resolveSibling("defense-charge-preparation-clips.json")
        require(focusedOutput.toAbsolutePath().normalize() != output.toAbsolutePath().normalize()) {
            "The focused preview must use a separate file from the full export."
        }
        val (otherClips, chargeClips, preparationClips) = MockBukkitTestRuntime.open().use {
            val clips = buildList {
                redesignArsenal.filter { it != StaffSpell.NOVA }.forEach { spell ->
                    listOf(StaffCastInput.PRIMARY, StaffCastInput.SECONDARY).forEach { input ->
                        if (!input.secondary && staffMeleeReach(spell) != null) add(meleeClip(spell))
                        else listOf(0.0, 1.0).forEach { charge -> add(when {
                            spell == StaffSpell.PRISM && input.secondary -> prismRefractionClip(charge)
                            else -> arsenalClip(spell, input, charge)
                        }) }
                    }
                }
                add(arsenalClip(StaffSpell.GALE, StaffCastInput.SECONDARY, 0.0, galePitchDegrees = 25.0))
                add(arsenalClip(StaffSpell.TIDE, StaffCastInput.PRIMARY, 0.0, galePitchDegrees = 25.0))
                add(arsenalClip(StaffSpell.OSSUARY, StaffCastInput.PRIMARY, 0.0, castVariant = 1))
                add(arsenalClip(StaffSpell.OSSUARY, StaffCastInput.SECONDARY, 0.0, castVariant = 1))
                add(meleeClip(StaffSpell.FROST))
                add(frostRingClip())
                add(meleeClip(StaffSpell.FAULT))
                listOf(StaffCastInput.PRIMARY, StaffCastInput.SECONDARY).forEach { input ->
                    listOf(0.0, 1.0).forEach { charge -> add(gravityClip(input, charge)) }
                }
                listOf(StaffCastInput.PRIMARY, StaffCastInput.SECONDARY).forEach { input ->
                    listOf(0.0, 1.0).forEach { charge -> add(requiemBellClip(input, charge)) }
                }
                add(chainStormNovaClip())
                add(blackHoleClip())
                StaffSpell.available.forEach { spell ->
                    add(quickClip(spell, StaffCastInput.QUICK))
                    add(quickClip(spell, StaffCastInput.QUICK_SECONDARY))
                }
            }
            Triple(
                clips,
                listOf(4, 20, 36).flatMap { holdTicks ->
                    listOf(emberChargeClip(holdTicks), icicleChargeClip(holdTicks))
                },
                StaffSpell.available.flatMap { spell ->
                    listOf(false, true).mapNotNull { secondary -> preparationClip(spell, secondary) }
                },
            )
        }
        val clips = otherClips + buildList {
            listOf(StaffCastInput.PRIMARY, StaffCastInput.SECONDARY).forEach { input ->
                listOf(0.0, 1.0).forEach { charge -> add(rootNetworkClip(input, charge)) }
                add(rootNetworkClip(input, 0.0, seed = 1251L))
                add(rootNetworkClip(input, 0.0, raised = true))
                add(rootNetworkClip(input, 0.0, raised = true, airborne = true))
            }
        }
        val fullExport = mapOf(
            "title" to "ARC · Атаки и защита посохов",
            "coordinates" to mapOf(
                "space" to "spell-local", "origin" to "clip-specific launch/cast/target anchor (see clip anchor)",
                "forward" to "+Z", "unit" to "block",
            ),
            "frameStepTicks" to FRAME_STEP_TICKS,
            "particleTailTicks" to 40,
            "previewReachBlocks" to PREVIEW_REACH_BLOCKS,
            "evidence" to "Кадры вызывают production-геометрию и частицы. Три фронтальные ПКМ используют staffMeleeDisplayParts/staffMeleeParticles; Shift-атаки сохраняют свои геометрии. Интерполяция Display моделируется по экспортированным ключевым кадрам. Это офлайн-превью: урон, столкновения, звук и нативный клиент не проверяются.",
            "clips" to clips,
        )
        output.parent?.let { Files.createDirectories(it) }
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(fullExport).plus("\n"))
        StaffHudPreviewExport.main(arrayOf(output.resolveSibling("hud.json").toString()))
        if (focusedOutput != null) {
            val focusedClips = clips.filter { clip ->
                (clip["sourceFunctions"] as? List<*>)?.contains("staffMeleeDisplayParts") == true ||
                clip["quick"] == true || ((clip["spell"] == "tide" || clip["spell"] == "gale") &&
                    (clip["input"] == "PRIMARY" || clip["input"] == "SECONDARY")) ||
                    ((clip["spell"] in setOf(StaffSpell.CHAIN.id, StaffSpell.MARK.id) && clip["input"] == StaffCastInput.SECONDARY.name) ||
                        clip["spell"] == StaffSpell.REQUIEM.id)
            } + chargeClips + preparationClips
            focusedOutput.parent?.let { Files.createDirectories(it) }
            Files.writeString(focusedOutput, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "title" to "ARC · Щиты, атаки и подготовка посохов",
                "coordinates" to mapOf(
                    "space" to "spell-local", "origin" to "caster eye or source cast anchor", "forward" to "+Z", "unit" to "block",
                ),
                "frameStepTicks" to FRAME_STEP_TICKS,
                "particleTailTicks" to 40,
                "previewReachBlocks" to PREVIEW_REACH_BLOCKS,
            "evidence" to "Кадры вызывают production-геометрию и частицы. Три фронтальные ПКМ используют staffMeleeDisplayParts/staffMeleeParticles; Shift-атаки сохраняют свои геометрии. Интерполяция Display моделируется по экспортированным ключевым кадрам. Это офлайн-превью: урон, столкновения, звук и нативный клиент не проверяются.",
                "clips" to focusedClips,
            )).plus("\n"))
            println("STAFF_REDESIGN_FOCUSED_PREVIEW clips=${focusedClips.size} output=$focusedOutput")
        }
        println("STAFF_REDESIGN_PREVIEW clips=${clips.size} output=$output")
    }
}
