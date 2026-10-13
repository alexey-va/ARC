package ru.arc.staffspells

import com.google.gson.GsonBuilder
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.bukkit.util.Vector
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Exports authored spell and defense frames for an offline particle and BlockDisplay preview. */
object StaffRedesignPreviewExport {
    private const val FRAME_STEP_TICKS = 2
    private const val PREVIEW_REACH_BLOCKS = 12.0
    private const val CHARGE_UPDATE_TICKS = 4
    private const val PREPARATION_DURATION_TICKS = 10
    private const val EMBER_PREVIEW_SPEED = 1.2

    private val redesignArsenal = listOf(
        StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE,
        StaffSpell.REQUIEM, StaffSpell.PRISM, StaffSpell.NOVA, StaffSpell.OSSUARY,
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
        spell == StaffSpell.REQUIEM && input.secondary -> 4.0 + charge * .75
        spell == StaffSpell.REQUIEM -> .75 + charge * .2
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
            4 + (charge * 2.0).toInt() else 1
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

        val endpoint = Vector(0.0, 0.0, PREVIEW_REACH_BLOCKS)
        when {
            spell == StaffSpell.TIDE && !input.secondary -> {
                val localAge = age - 28
                val progress = smooth((age - 4.0) / 24.0)
                val anchor = Vector(0.0, 1.2, PREVIEW_REACH_BLOCKS * progress)
                if (age < 28) addParts(age, duration, 0, anchor, 1.0)
                else addParts(localAge, 16, 0, anchor, 1.0, impact = true,
                    localRadius = 1.6 + charge * .4)
                if (age < 28) addParticles(age, duration, radius, anchor)
                else addParticles(localAge, 16, 1.6 + charge * .4, anchor,
                    localReach = radius, impact = true)
            }
            spell == StaffSpell.TIDE && input.secondary -> {
                val anchor = Vector(0.0, 0.0, PREVIEW_REACH_BLOCKS * smooth((age - 4.0) / 30.0))
                addParts(age, duration, 0, anchor)
                addParticles(age, duration, radius, anchor)
            }
            spell == StaffSpell.GALE && input.secondary -> {
                val particleAnchor = Vector(0.0, 0.0, PREVIEW_REACH_BLOCKS * smooth((age - 4.0) / 20.0))
                addParts(age, duration, 0, particleAnchor.clone().add(Vector(0.0, 1.1, 0.0)))
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
                val localAge = age - 4 - node * 4
                val anchor = Vector(0.0, 0.0, PREVIEW_REACH_BLOCKS * (node + 1) / nodeCount)
                addParts(localAge, 22, node, anchor)
                addParticles(localAge, 22, radius, anchor)
            }
            spell == StaffSpell.REQUIEM && !input.secondary -> {
                val missileCount = 3 + (charge * 2).toInt()
                repeat(missileCount) { missile ->
                    val launch = 4 + missile * 6
                    val localAge = age - launch
                    if (localAge >= 0) {
                        val lateral = (missile - (missileCount - 1) / 2.0) * .62
                        val from = Vector(lateral, 1.35, 0.0)
                        val to = Vector(0.0, 1.65, PREVIEW_REACH_BLOCKS)
                        val t = smooth(localAge / 14.0)
                        val anchor = from.clone().add(to.clone().subtract(from).multiply(t))
                        if (localAge < 14) {
                            addParts(localAge, 22, missile, anchor)
                            addParticles(localAge, 22, radius, anchor)
                        } else if (localAge < 22) {
                            addParts(localAge - 14, 8, missile,
                                endpoint.clone().add(Vector(0.0, 1.65, 0.0)), impact = true)
                        }
                    }
                }
            }
            else -> {
                val anchor = if (spell == StaffSpell.LUNAR || spell == StaffSpell.REQUIEM ||
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
        val notes = when {
            spell == StaffSpell.TIDE && !input.secondary ->
                "One water orb travels along the clipped lane from tick 4 to tick 28; the moving BlockDisplay core changes to its source impact geometry at arrival and fades for 16 ticks. Preview uses a flat 12-block lane."
            spell == StaffSpell.NOVA && !input.secondary ->
                "Two crawling root runners grow from the caster toward the target, then six thorn fingers close around the endpoint at tick 30. Display geometry and native particles use a fixed illustrative 12-block lane."
            spell == StaffSpell.GALE ->
                if (input.secondary) "Two interwoven display bands and a hollow ram head follow the source controller from tick 4 over 20 ticks; display origin is raised 1.1 blocks while particles stay on the ground path."
                else "Three translucent display ribbons spiral around an open tornado core at the attack point; the native particle accents use the same authored funnel frame."
            spell == StaffSpell.TIDE && input.secondary ->
                "A three-row curled water wall and foam crest follow the controller's smoothstep path from tick 4 over 30 ticks; preview uses a fixed 12-block lane."
            spell == StaffSpell.REQUIEM && !input.secondary ->
                "Three soul missiles launch from offset lanes 6 ticks apart, each taking 14 ticks to reach a clipped target line; charge adds two more. Preview shows a fixed 12-block target lane."
            spell == StaffSpell.REQUIEM && input.secondary ->
                "A soul shockfront expands outward from tick 8 for 20 ticks; each radial segment is clipped by the first blocking surface. Preview uses an unobstructed fixed 12-block disk."
            spell == StaffSpell.OSSUARY && !input.secondary ->
                "Four bone spikes at zero charge or six at full charge rise along the ground lane, 4 ticks apart; each attempts contact when its tip arrives at local tick 8. No blocks are placed."
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
            "anchor" to "cast ground/target anchor, depending on the live spell; preview geometry uses +Z as forward",
            "previewReachBlocks" to PREVIEW_REACH_BLOCKS,
            "timelineNote" to "$notes Most single-source particle frames use a 160-sample admission limit. OSSUARY divides a 240-sample cast-frame budget across its spikes; REQUIEM applies the per-source limit to each active missile, while PRISM beam lanes are sampled separately.",
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
        val duration = if (allAround) 60 else if (heavy) 32 else 24
        val hitTick = if (allAround) 48 else 8
        val endTick = if (allAround) hitTick + 6 else duration
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
                "Production staffDefenseParts: Shift+LMB keeps four transparent kite panels for 3.0s. Authored hit scenario: the shield depletes at tick 48 and its source break animation ends at tick 54; particles emit only at that contact."
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

    private fun channelClip(spell: StaffSpell, input: StaffCastInput, charge: Double): Map<String, Any> {
        val duration = if (spell == StaffSpell.INFERNO) 60 else 90
        var throwZ = 4.0
        var speed = .55
        var impactAge: Int? = null
        val frames = ticks(duration).map { age ->
            val emissions = mutableListOf<Map<String, Any>>()
            val parts = mutableListOf<Map<String, Any>>()
            if (spell == StaffSpell.INFERNO && age < 60) {
                staffInfernoFrame(age, 9.0).forEach { emissions += sample(it, Vector(0.0, 1.34, .48)) }
            } else if (spell == StaffSpell.GRAVITY) {
                if (age > 60 && impactAge == null) {
                    speed = (speed + .085 * 2).coerceAtMost(2.2)
                    throwZ = (throwZ + speed).coerceAtMost(12.0)
                    if (throwZ >= 12.0) impactAge = age
                }
                val impact = impactAge != null
                val localAge = impactAge?.let { age - it } ?: age
                if (!impact || localAge < 16) {
                    staffChannelParts(spell, localAge, if (impact) 16 else 196, 1.0,
                        if (impact) 2.5 else 1.0, impact, input.secondary).forEachIndexed { i, display ->
                            parts += part(display, "gravity", i, Vector(0.0, 1.62, throwZ))
                        }
                }
                if (impactAge == age) staffGravityImpactFrame().forEach {
                    emissions += sample(it, Vector(0.0, 1.62, throwZ))
                }
            }
            mapOf("tick" to age, "parts" to parts, "emissions" to emissions)
        }
        return mapOf(
            "id" to "${spell.id}-${input.name.lowercase(Locale.ROOT)}-${charge.toInt()}",
            "spell" to spell.id, "input" to input.name, "quick" to false,
            "charge" to charge, "durationTicks" to duration, "tailTicks" to 40,
            "frameStepTicks" to FRAME_STEP_TICKS, "particleOnly" to (spell == StaffSpell.INFERNO),
            "anchor" to "caster feet", "previewReachBlocks" to 12.0,
            "timelineNote" to if (spell == StaffSpell.INFERNO)
                "Production flame frames for a steady 3-second hold along +Z. In game the stream follows the current look and is clipped by cover; charge selector does not change a channel."
                else "Production boulder geometry: scripted 60-tick hold at four blocks, release with the controller's accelerating 2-tick movement, then impact at twelve blocks. In game hold follows the look for up to eight seconds. No real blocks are changed.",
            "sourceFunctions" to listOf("staffInfernoFrame", "staffChannelParts", "staffGravityImpactFrame"),
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
                        listOf(0.0, 1.0).forEach { charge -> add(arsenalClip(spell, input, charge)) }
                    }
                }
                listOf(StaffSpell.INFERNO, StaffSpell.GRAVITY).forEach { spell ->
                    listOf(StaffCastInput.PRIMARY, StaffCastInput.SECONDARY).forEach { input ->
                        listOf(0.0, 1.0).forEach { charge -> add(channelClip(spell, input, charge)) }
                    }
                }
                StaffSpell.entries.forEach { spell ->
                    add(quickClip(spell, StaffCastInput.QUICK))
                    add(quickClip(spell, StaffCastInput.QUICK_SECONDARY))
                }
            }
            Triple(
                clips,
                listOf(4, 20, 36).flatMap { holdTicks ->
                    listOf(emberChargeClip(holdTicks), icicleChargeClip(holdTicks))
                },
                StaffSpell.entries.flatMap { spell ->
                    listOf(false, true).mapNotNull { secondary -> preparationClip(spell, secondary) }
                },
            )
        }
        val fullExport = mapOf(
            "title" to "ARC · Атаки и защита посохов",
            "coordinates" to mapOf(
                "space" to "spell-local", "origin" to "fixed cast/target anchor", "forward" to "+Z", "unit" to "block",
            ),
            "frameStepTicks" to FRAME_STEP_TICKS,
            "particleTailTicks" to 40,
            "previewReachBlocks" to PREVIEW_REACH_BLOCKS,
            "evidence" to "Кадры вызывают production-геометрию и частицы staffArsenalParts/staffArsenalParticles, staffPrismBeam, staffDefenseParts/staffDefenseParticles и held-channel geometry с фактическими материалами, размерами и преобразованиями. Движущиеся атаки показаны на иллюстративной линии 12 блоков. Офлайн-превью не моделирует урон, выбор цели, столкновения, звук, освещение клиента и точную физику ванильных частиц; это не подтверждение результата в игровом клиенте.",
            "clips" to clips,
        )
        output.parent?.let { Files.createDirectories(it) }
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(fullExport).plus("\n"))
        if (focusedOutput != null) {
            val focusedClips = clips.filter { clip ->
                clip["quick"] == true || ((clip["spell"] == "tide" || clip["spell"] == "gale") &&
                    (clip["input"] == "PRIMARY" || clip["input"] == "SECONDARY"))
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
                "evidence" to "Набор объединяет защиту, атаки TIDE/GALE, заряд Ember/Icicle и production-подготовки мгновенных заклинаний. Display-части несут исходные материалы, размеры, повороты и стабильные идентификаторы. Офлайн-превью не проверяет игровой клиент, урон, столкновения или точную физику частиц.",
                "clips" to focusedClips,
            )).plus("\n"))
            println("STAFF_REDESIGN_FOCUSED_PREVIEW clips=${focusedClips.size} output=$focusedOutput")
        }
        println("STAFF_REDESIGN_PREVIEW clips=${clips.size} output=$output")
    }
}
