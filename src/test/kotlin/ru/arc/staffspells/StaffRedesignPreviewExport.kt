package ru.arc.staffspells

import com.google.gson.GsonBuilder
import org.bukkit.Particle
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
    private const val FIXED_GALE_TIDE_REACH_BLOCKS = 36.0
    private const val FIXED_OSSUARY_REACH_BLOCKS = 24.0
    private const val GALE_TIDE_PATH_BLOCKS = FIXED_GALE_TIDE_REACH_BLOCKS - FIXED_LAUNCH_OFFSET_BLOCKS
    private const val OSSUARY_PATH_BLOCKS = FIXED_OSSUARY_REACH_BLOCKS - FIXED_LAUNCH_OFFSET_BLOCKS
    private const val OSSUARY_NODE_SPACING_BLOCKS = 2.0
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

    private fun part(part: StaffDisplayPart, keyPrefix: String, index: Int, origin: Vector): Map<String, Any> {
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
        spell == StaffSpell.TIDE && input.secondary -> 2.8 + charge * .7
        spell == StaffSpell.TIDE -> 1.0 + charge * .3
        spell == StaffSpell.GALE && input.secondary -> 2.0 + charge * .35
        spell == StaffSpell.OSSUARY -> if (input.secondary) 2.1 + charge * .4 else 1.0 + charge * .2
        else -> 2.2 + charge
    }

    private fun arsenalFrame(
        spell: StaffSpell,
        input: StaffCastInput,
        charge: Double,
        duration: Int,
        age: Int,
    ): Map<String, Any> {
        val radius = arsenalRadius(spell, input, charge)
        // Live TIDE/GALE Display scenes inherit Attack.radius; their particle calls may override it.
        val displayRadius = if (spell == StaffSpell.TIDE || spell == StaffSpell.GALE) 2.2 + charge else radius
        val nodeCount = if (spell == StaffSpell.OSSUARY && !input.secondary)
            OSSUARY_PREVIEW_NODE_COUNT else 1
        val parts = mutableListOf<Map<String, Any>>()
        val emissions = mutableListOf<Map<String, Any>>()

        fun addParts(localAge: Int, localDuration: Int, index: Int, anchor: Vector,
            sceneLength: Double = 1.0, impact: Boolean = false, localRadius: Double = displayRadius) {
            // Display scenes expire before rendering at age == duration in StaffSpellDisplayEffects.
            if (localAge !in 0 until localDuration) return
            staffArsenalParts(spell, localAge, localDuration, sceneLength, localRadius,
                impact = impact, secondary = input.secondary, input = input, charge = charge)
                .forEachIndexed { partIndex, display ->
                    parts += part(display, "${spell.id}:node:$index", partIndex, anchor)
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
            admitted.forEach { emissions += sample(it, anchor) }
        }

        val pathLength = when {
            spell == StaffSpell.TIDE && !input.secondary || spell == StaffSpell.GALE && input.secondary ->
                GALE_TIDE_PATH_BLOCKS
            spell == StaffSpell.OSSUARY && !input.secondary -> OSSUARY_PATH_BLOCKS
            else -> PREVIEW_REACH_BLOCKS
        }
        val endpoint = Vector(0.0, 0.0, pathLength)
        when {
            spell == StaffSpell.TIDE && !input.secondary -> {
                val localAge = age - STAFF_TIDE_PRIMARY_ARRIVAL_TICK
                val progress = ((age - STAFF_TIDE_PRIMARY_LAUNCH_TICK).toDouble() / STAFF_TIDE_PRIMARY_TRAVEL_TICKS)
                    .coerceIn(0.0, 1.0)
                val anchor = Vector(0.0, 1.2, GALE_TIDE_PATH_BLOCKS * progress)
                if (age < STAFF_TIDE_PRIMARY_ARRIVAL_TICK) addParts(age, duration, 0, anchor, GALE_TIDE_PATH_BLOCKS)
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
                val anchor = Vector(0.0, 0.0, PREVIEW_REACH_BLOCKS * ((age - 4.0) / 30.0).coerceIn(0.0, 1.0))
                addParts(age, duration, 0, anchor, PREVIEW_REACH_BLOCKS)
                addParticles(age, duration, radius, anchor)
            }
            spell == StaffSpell.GALE && input.secondary -> {
                val particleAnchor = Vector(0.0, 0.0, GALE_TIDE_PATH_BLOCKS * smooth((age - 4.0) / 20.0))
                addParts(age, duration, 0, particleAnchor.clone().add(Vector(0.0, 1.1, 0.0)),
                    GALE_TIDE_PATH_BLOCKS)
                addParticles(age, duration, radius, particleAnchor)
            }
            spell == StaffSpell.GALE -> {
                addParts(age, duration, 0, endpoint)
                addParticles(age, duration, radius, endpoint)
            }
            spell == StaffSpell.NOVA && !input.secondary -> {
                addParts(age, duration, 0, Vector(), PREVIEW_REACH_BLOCKS)
                addParticles(age, duration, radius, Vector(), PREVIEW_REACH_BLOCKS)
            }
            spell == StaffSpell.OSSUARY && !input.secondary -> repeat(nodeCount) { node ->
                val localAge = age - 4 - node * 2
                val anchor = Vector(0.0, 0.0, OSSUARY_PATH_BLOCKS * (node + 1) / nodeCount)
                addParts(localAge, 22, node, anchor)
                addParticles(localAge, 22, radius, anchor)
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
                    val start = Vector(lane * 1.7, 2.2, -PREVIEW_REACH_BLOCKS)
                    val delta = Vector(-lane * 1.7, 0.4, PREVIEW_REACH_BLOCKS)
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

    private fun arsenalClip(spell: StaffSpell, input: StaffCastInput, charge: Double): Map<String, Any> {
        val duration = staffArsenalLifetime(spell, input.secondary)
        val frames = ticks(duration).map { age -> arsenalFrame(spell, input, charge, duration, age) }
        val previewReach = when {
            spell == StaffSpell.TIDE && !input.secondary || spell == StaffSpell.GALE && input.secondary ->
                FIXED_GALE_TIDE_REACH_BLOCKS
            spell == StaffSpell.OSSUARY && !input.secondary -> FIXED_OSSUARY_REACH_BLOCKS
            else -> PREVIEW_REACH_BLOCKS
        }
        val previewPath = when {
            spell == StaffSpell.TIDE && !input.secondary || spell == StaffSpell.GALE && input.secondary ->
                GALE_TIDE_PATH_BLOCKS
            spell == StaffSpell.OSSUARY && !input.secondary -> OSSUARY_PATH_BLOCKS
            else -> PREVIEW_REACH_BLOCKS
        }
        val notes = when {
            spell == StaffSpell.TIDE && !input.secondary ->
                "One water orb launches at tick $STAFF_TIDE_PRIMARY_LAUNCH_TICK and flies for $STAFF_TIDE_PRIMARY_TRAVEL_TICKS ticks from a point 2.5 blocks ahead of the caster to the fixed 36-block caster reach (33.5 blocks of scene travel). In this flat, unobstructed preview it reaches the endpoint and starts the source water-splash burst at tick $STAFF_TIDE_PRIMARY_ARRIVAL_TICK; the $STAFF_TIDE_PRIMARY_IMPACT_TICKS-tick impact stage gives a ${STAFF_TIDE_PRIMARY_ATTACK_TICKS}-tick total clip. Its display uses the source 24-link crown and 8 droplets; particles emit 24 SPLASH and 8 FALLING_WATER samples over the burst. Runtime radius is 2.2–2.45 blocks and contact with a victim or wall can start the burst earlier."
            spell == StaffSpell.NOVA && !input.secondary ->
                "Two crawling root runners grow from the caster toward the target, then six thorn fingers close around the endpoint at tick 30. Display geometry and native particles use a fixed illustrative 12-block lane."
            spell == StaffSpell.GALE ->
                if (input.secondary) "Two interwoven display bands and a hollow ram head follow the source smoothstep controller from a launch point 2.5 blocks ahead to a fixed 36-block caster reach (33.5 blocks of scene travel), from tick 4 over 20 ticks; display origin is raised 1.1 blocks while particles stay on the ground path. The lane is unobstructed and flat."
                else "Three translucent display ribbons spiral around an open tornado core at the attack point; the native particle accents use the same authored funnel frame."
            spell == StaffSpell.TIDE && input.secondary ->
                "A three-row curled water wall and foam crest follow the source linear path from tick 4 over 30 ticks; this offline clip uses an unobstructed illustrative 12-block lane, while runtime targeting and cover clipping determine the endpoint."
            spell == StaffSpell.OSSUARY && !input.secondary ->
                "Eleven bone spikes rise along a 21.5-block ground path from the launch point 2.5 blocks ahead to a fixed 24-block caster reach. Their spacing follows ceil(path/2), they start 2 ticks apart, and each attempts contact at local tick 6. This unobstructed preview places no blocks."
            spell == StaffSpell.OSSUARY && input.secondary ->
                "Ivory ribs form a warning ring, then close around a dark skull centerpiece at tick 12. The cast uses a bounded visible target point and never places blocks."
            spell == StaffSpell.PRISM && input.secondary ->
                "Beam uses the source staffPrismBeam frame and three source-shaped emitter lanes. Target selection is represented by a fixed 12-block endpoint."
            spell == StaffSpell.PRISM ->
                "Beam uses the source staffPrismBeam frame and the runtime empty-target three-ray fallback. Target-selected rays are not simulated."
            else -> "Source-authored local particle and display geometry at a fixed anchor."
        }
        return mapOf(
            "id" to "${spell.id}-${input.name.lowercase(Locale.ROOT)}-${charge.toInt()}",
            "spell" to spell.id, "input" to input.name, "quick" to false,
            "charge" to charge, "durationTicks" to duration,
            "tailTicks" to 40, "frameStepTicks" to FRAME_STEP_TICKS,
            "particleOnly" to (frames.all { (it["parts"] as List<*>).isEmpty() }),
            "anchor" to when {
                spell == StaffSpell.TIDE && !input.secondary || spell == StaffSpell.GALE && input.secondary ->
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
                    listOf("staffTideGaleParts", "staffTideGaleParticleSamples") else emptyList()),
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
                "Production staffDefenseParts: LMB keeps a frontal kite guard for ${duration / 20.0}s. Authored hit scenario: the first frontal hit lands at tick 8 and does not deplete the guard; particles emit only at contact. Combat and native expiry are not simulated.",
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

    /** Icicle's eleven real charge scenes are kept as separate keyed eight-part bodies. */
    private fun icicleChargeClip(holdTicks: Int): Map<String, Any> {
        val charge = staffChargeProgress(holdTicks)
        val eye = Vector(0.0, 1.62, 0.0)
        val frames = ticks(holdTicks).map { age ->
            val updateTick = (age / CHARGE_UPDATE_TICKS) * CHARGE_UPDATE_TICKS
            val progress = staffChargeProgress(minOf(updateTick, holdTicks))
            val parts = (0 until 11).flatMap { slot ->
                val offset = staffIcicleChargeAnchorOffset(slot)
                val anchor = eye.clone().add(Vector(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()))
                staffIcicleChargeParts(progress, slot).mapIndexed { index, display ->
                    part(display, "icicle-charge:$slot", index, anchor)
                }
            }
            mapOf("tick" to age, "parts" to parts, "emissions" to emptyList<Any>())
        }
        return mapOf(
            "id" to "icicle-charge-${chargeToken(charge)}", "spell" to StaffSpell.ICICLE.id, "input" to "CHARGE",
            "quick" to false, "charge" to charge, "durationTicks" to holdTicks + 1, "tailTicks" to 0,
            "frameStepTicks" to FRAME_STEP_TICKS, "particleOnly" to false,
            "anchor" to "caster eye and eleven source shoulder anchors; neutral yaw with local +Z forward",
            "previewReachBlocks" to PREVIEW_REACH_BLOCKS,
            "timelineNote" to "Source charge formation only: eleven eight-part BlockDisplay bodies update on the production four-tick charge clock. At ${holdTicks} ticks, staffIcicleCount is ${staffIcicleCount(charge)}; release/bolt flight is outside this clip.",
            "sourceFunctions" to listOf("staffChargeProgress", "staffIcicleChargeAnchorOffset", "staffIcicleChargeParts", "staffIcicleCount"),
            "frames" to frames,
        )
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
        val duration = 10
        val origin = Vector(0.0, 1.37, 0.0)
        return mapOf(
            "id" to "${spell.id}-primary-0", "spell" to spell.id,
            "input" to "PRIMARY", "quick" to false, "charge" to 0.0,
            "durationTicks" to duration, "tailTicks" to 16, "frameStepTicks" to FRAME_STEP_TICKS,
            "particleOnly" to false, "anchor" to "caster eye minus 0.25 blocks; horizontal +Z",
            "previewReachBlocks" to reach,
            "timelineNote" to "Ближняя основная атака. Контакт на четвёртом тике; Display-кадры каждые два тика с интерполяцией. Реальные столкновения, звук и клиентская физика частиц здесь не моделируются.",
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
        val duration = STAFF_STORM_NOVA_BUILDUP_TICKS + STAFF_STORM_NOVA_TRAVEL_TICKS
        val unobstructedRayLimits = List(STAFF_STORM_NOVA_RAY_COUNT) { STAFF_STORM_NOVA_RADIUS }
        val frames = ticks(duration).map { age ->
            val emissions = staffStormNovaFrame(age, STAFF_STORM_NOVA_RADIUS, unobstructedRayLimits)
                .map { sample(it) }
            mapOf("tick" to age, "parts" to emptyList<Any>(), "emissions" to emissions)
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
            "particleOnly" to true,
            "anchor" to "caster feet; 24 horizontal radial limits, local X/Z in the world plane",
            "previewReachBlocks" to STAFF_STORM_NOVA_RADIUS,
            "previewPathLengthBlocks" to STAFF_STORM_NOVA_RADIUS,
            "timelineNote" to "Source CHAIN Shift+ПКМ nova: a 360-degree front with 24 horizontal rays at the unobstructed 8-block source radius. Cast age 0 starts the buildup, ages 2–4 finish its core, then the front expands from age 4 to 14. The preview supplies full-length ray limits; runtime cover can shorten individual sectors. Combat, native particle lifetime, and client rendering are not simulated.",
            "sourceFunctions" to listOf("staffStormNovaFrame"),
            "frames" to frames,
        )
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
            else listOf(STAFF_REQUIEM_PRIMARY_CLANG_TICK)
        val note = if (secondary) {
            "One copper-and-gold bell hangs 4 blocks above the caster. It clangs at ticks 16, 36, and 56; each clang expands a ground-level soul ring from radius 0 to 7 over 10 ticks (16–26, 36–46, 56–66). The bell remains through the 72-tick clip, with its source aura between pulses."
        } else {
            "One copper-and-gold bell hangs 4 blocks above the aimed point on an illustrative 12-block lane. It clangs at tick 16; a ground-level soul ring expands from radius 0 to 5 by tick 30, then fades through the 36-tick clip. The target and lane are fixed for preview."
        }
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
        val (clips, chargeClips, preparationClips) = MockBukkitTestRuntime.open().use {
            val clips = buildList {
                redesignArsenal.forEach { spell ->
                    listOf(StaffCastInput.PRIMARY, StaffCastInput.SECONDARY).forEach { input ->
                        if (!input.secondary && staffMeleeReach(spell) != null) add(meleeClip(spell))
                        else listOf(0.0, 1.0).forEach { charge -> add(arsenalClip(spell, input, charge)) }
                    }
                }
                add(meleeClip(StaffSpell.MARK))
                add(meleeClip(StaffSpell.FROST))
                listOf(StaffCastInput.PRIMARY, StaffCastInput.SECONDARY).forEach { input ->
                    listOf(0.0, 1.0).forEach { charge -> add(gravityClip(input, charge)) }
                }
                listOf(StaffCastInput.PRIMARY, StaffCastInput.SECONDARY).forEach { input ->
                    listOf(0.0, 1.0).forEach { charge -> add(requiemBellClip(input, charge)) }
                }
                add(chainStormNovaClip())
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
        val fullExport = mapOf(
            "title" to "ARC · Атаки и защита посохов",
            "coordinates" to mapOf(
                "space" to "spell-local", "origin" to "clip-specific launch/cast/target anchor (see clip anchor)",
                "forward" to "+Z", "unit" to "block",
            ),
            "frameStepTicks" to FRAME_STEP_TICKS,
            "particleTailTicks" to 40,
            "previewReachBlocks" to PREVIEW_REACH_BLOCKS,
            "evidence" to "Кадры вызывают production-геометрию и частицы. Новые ближние ЛКМ используют staffMeleeDisplayParts/staffMeleeParticles; Shift-атаки сохраняют свои геометрии. Интерполяция Display моделируется по экспортированным ключевым кадрам. Это офлайн-превью: урон, столкновения, звук и нативный клиент не проверяются.",
            "clips" to clips,
        )
        output.parent?.let { Files.createDirectories(it) }
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(fullExport).plus("\n"))
        if (focusedOutput != null) {
            val focusedClips = clips.filter { clip ->
                (clip["sourceFunctions"] as? List<*>)?.contains("staffMeleeDisplayParts") == true ||
                clip["quick"] == true || ((clip["spell"] == "tide" || clip["spell"] == "gale") &&
                    (clip["input"] == "PRIMARY" || clip["input"] == "SECONDARY")) ||
                    ((clip["spell"] == StaffSpell.CHAIN.id && clip["input"] == StaffCastInput.SECONDARY.name) ||
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
            "evidence" to "Кадры вызывают production-геометрию и частицы. Новые ближние ЛКМ используют staffMeleeDisplayParts/staffMeleeParticles; Shift-атаки сохраняют свои геометрии. Интерполяция Display моделируется по экспортированным ключевым кадрам. Это офлайн-превью: урон, столкновения, звук и нативный клиент не проверяются.",
                "clips" to focusedClips,
            )).plus("\n"))
            println("STAFF_REDESIGN_FOCUSED_PREVIEW clips=${focusedClips.size} output=$focusedOutput")
        }
        println("STAFF_REDESIGN_PREVIEW clips=${clips.size} output=$output")
    }
}
