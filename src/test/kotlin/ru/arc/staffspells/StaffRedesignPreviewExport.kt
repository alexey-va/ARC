package ru.arc.staffspells

import com.google.gson.GsonBuilder
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.bukkit.util.Vector
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Exports the authored new-arsenal and LMB source frames for an offline particle-sprite preview. */
object StaffRedesignPreviewExport {
    private const val FRAME_STEP_TICKS = 2
    private const val PREVIEW_REACH_BLOCKS = 12.0

    private val redesignArsenal = listOf(
        StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE,
        StaffSpell.REQUIEM, StaffSpell.PRISM, StaffSpell.NOVA,
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

    private fun arsenalRadius(spell: StaffSpell, input: StaffCastInput, charge: Double) = when {
        spell == StaffSpell.REQUIEM -> 3.2 + charge
        spell == StaffSpell.TIDE && input.secondary -> 3.2 + charge * 1.3
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
        val nodeCount = if (spell in setOf(StaffSpell.NOVA, StaffSpell.TIDE) && !input.secondary)
            4 + (charge * 2.0).toInt() else 1
        val parts = mutableListOf<Map<String, Any>>()
        val emissions = mutableListOf<Map<String, Any>>()

        fun addParts(localAge: Int, localDuration: Int, index: Int, anchor: Vector) {
            if (localAge !in 0..localDuration) return
            staffArsenalParts(spell, localAge, localDuration, PREVIEW_REACH_BLOCKS, radius,
                impact = false, secondary = input.secondary, input = input, charge = charge)
                .forEachIndexed { partIndex, display ->
                    parts += part(display, "${spell.id}:node:$index", partIndex, anchor)
                }
        }

        fun addParticles(localAge: Int, localDuration: Int, localRadius: Double, anchor: Vector) {
            if (localAge !in 0 until localDuration) return
            val authored = staffArsenalParticles(spell, localAge, localDuration, localRadius,
                input.secondary, charge)
            val limit = if (!input.secondary && spell in setOf(StaffSpell.NOVA, StaffSpell.TIDE))
                240 / nodeCount.coerceAtLeast(1) else 160
            val admitted = if (authored.size <= limit) authored else List(limit) { index ->
                authored[index * (authored.size - 1) / (limit - 1)]
            }
            admitted.forEach { emissions += sample(it, anchor) }
        }

        when {
            spell == StaffSpell.NOVA && !input.secondary -> repeat(nodeCount) { node ->
                val localAge = age - node * 4
                val sceneDuration = (duration - node * 4).coerceAtLeast(1)
                val anchor = Vector(if (node % 2 == 1) 1.15 else 0.0, 0.0,
                    PREVIEW_REACH_BLOCKS * (node + 1) / nodeCount)
                addParts(localAge, sceneDuration, node, anchor)
                addParticles(localAge, 32, radius, anchor)
            }
            spell == StaffSpell.TIDE && !input.secondary -> repeat(nodeCount) { node ->
                val localAge = age - 4 - node * 4
                val anchor = Vector(0.0, 0.0, PREVIEW_REACH_BLOCKS * (node + 1) / nodeCount)
                if (localAge in 0..22) addParticles(localAge, 24, radius, anchor)
            }
            spell == StaffSpell.GALE || spell == StaffSpell.TIDE && input.secondary -> {
                val travelTicks = when (spell) {
                    StaffSpell.GALE -> if (input.secondary) 28.0 else 40.0
                    else -> 30.0
                }
                val startTick = 4.0
                val linearProgress = ((age - startTick) / travelTicks).coerceIn(0.0, 1.0)
                val progress = linearProgress * linearProgress * (3.0 - 2.0 * linearProgress)
                val anchor = Vector(0.0, 0.0, PREVIEW_REACH_BLOCKS * progress)
                val localRadius = if (spell == StaffSpell.TIDE) radius else 2.2 + charge
                addParticles(age, duration, localRadius, anchor)
            }
            else -> {
                addParts(age, duration, 0, Vector())
                addParticles(age, duration, radius, Vector())
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
                    Vector(0.0, 2.6, 0.0) to Vector(0.0, 0.0, 9.0).rotateAroundY(lane * 0.55)
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
                "Cascading ground nodes: source uses 4 nodes at zero charge or 6 at full charge, starting 4 ticks apart; each emits through local tick 22. Preview uses a flat 12-block lane."
            spell == StaffSpell.NOVA && !input.secondary ->
                "Staggered ground blooms: source uses 4 nodes at zero charge or 6 at full charge, 4 ticks apart; odd nodes shift +1.15 blocks sideways. Preview lane is 12 blocks."
            spell == StaffSpell.GALE ->
                "Moving particle anchor follows source smoothstep progress from tick 4 over ${if (input.secondary) 28 else 40} ticks; the preview follows an illustrative fixed 12-block lane."
            spell == StaffSpell.TIDE && input.secondary ->
                "Moving water sheet follows source smoothstep progress from tick 4 over 30 ticks; the preview follows an illustrative fixed 12-block lane."
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
            "timelineNote" to "$notes Base arsenal lists follow the runtime 160-sample admission cap (240 divided across NOVA/TIDE primary nodes); PRISM beam samples are authored separately.",
            "sourceFunctions" to listOf("staffArsenalParts", "staffArsenalParticles", "staffPrismBeam"),
            "frames" to frames,
        )
    }

    private fun quickClip(spell: StaffSpell, input: StaffCastInput): Map<String, Any> {
        val profile = staffGestureProfile(spell, input)
        val frames = ticks(profile.durationTicks).map { age ->
            // Runtime gesture tasks start their first particle frame at tick 2; tick 0 is just the cast cue.
            val emissions = if (age == 0) emptyList() else
                staffGestureFrame(spell, input, age, profile.reach).map(::sample)
            mapOf("tick" to age, "parts" to emptyList<Any>(), "emissions" to emissions)
        }
        return mapOf(
            "id" to "${spell.id}-${input.name.lowercase(Locale.ROOT)}-0",
            "spell" to spell.id, "input" to input.name, "quick" to true,
            "charge" to 0.0, "durationTicks" to profile.durationTicks,
            "tailTicks" to 40, "frameStepTicks" to FRAME_STEP_TICKS,
            "particleOnly" to true,
            "anchor" to profile.anchor.name,
            "profile" to mapOf(
                "form" to profile.form.name, "contact" to profile.contact.name,
                "control" to profile.control.name, "reachBlocks" to profile.reach,
                "widthBlocks" to profile.width, "radiusBlocks" to profile.radius,
                "anticipationTicks" to profile.anticipationTicks,
            ),
            "previewReachBlocks" to profile.reach,
            "timelineNote" to "Production StaffGestureProfile and staffGestureFrame samples; runtime particle frames begin at tick 2. The point/eye anchor is fixed at local origin for geometry inspection.",
            "sourceFunctions" to listOf("staffGestureProfile", "staffGestureFrame"),
            "frames" to frames,
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: StaffRedesignPreviewExport <redesign-motion-clips.json>" }
        val output = Path.of(args.single())
        val clips = MockBukkitTestRuntime.open().use {
            buildList {
                redesignArsenal.forEach { spell ->
                    listOf(StaffCastInput.PRIMARY, StaffCastInput.SECONDARY).forEach { input ->
                        listOf(0.0, 1.0).forEach { charge -> add(arsenalClip(spell, input, charge)) }
                    }
                }
                StaffSpell.entries.forEach { spell ->
                    add(quickClip(spell, StaffCastInput.QUICK))
                    add(quickClip(spell, StaffCastInput.QUICK_SECONDARY))
                }
            }
        }
        output.parent?.let { Files.createDirectories(it) }
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "title" to "ARC staff redesign · source-driven particle preview",
            "coordinates" to mapOf(
                "space" to "spell-local", "origin" to "fixed cast/target anchor", "forward" to "+Z", "unit" to "block",
            ),
            "frameStepTicks" to FRAME_STEP_TICKS,
            "particleTailTicks" to 40,
            "previewReachBlocks" to PREVIEW_REACH_BLOCKS,
            "evidence" to "Frames call production staffArsenalParts/staffArsenalParticles, staffPrismBeam, and staffGestureProfile/staffGestureFrame. Arsenal and gesture frames use the production two-tick cadence; base arsenal samples follow the runtime 160-list limit (240 divided across NOVA/TIDE primary nodes), while PRISM beam samples are separate. Particle sprites and persistence use the offline viewer profile; this geometry preview does not simulate damage, target selection, collision, sounds, client lighting, or exact vanilla particle physics. Moving/ground-node attacks show source call-site timing on a fixed illustrative 12-block lane.",
            "clips" to clips,
        )).plus("\n"))
        println("STAFF_REDESIGN_PREVIEW clips=${clips.size} output=$output")
    }
}
