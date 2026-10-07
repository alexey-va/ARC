package ru.arc.staffspells

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import org.joml.Vector3f

/** Offline snapshots copied from the production staff display geometry helper. */
object StaffSpellPreviewExport {
    private const val FRAME_TICKS = StaffSpellDisplayEffects.FRAME_TICKS

    private data class Inputs(
        val length: Double,
        val radius: Double,
        val durationTicks: Int,
        val impact: Boolean,
    )

    private data class Event(val id: String, val inputs: Inputs)
    private data class Frame(val id: String, val ageTicks: Int)
    private data class ExportedState(val metadata: Map<String, Any>, val materials: List<String>)

    // Mirrors StaffSpellController display calls using its current default tuning.
    private val events = mapOf(
        StaffSpell.CHAIN to listOf(Event("segment", Inputs(24.0, 0.65, 12, impact = true))),
        StaffSpell.MARK to listOf(
            Event("charge", Inputs(0.0, 1.3, 18, impact = false)),
            Event("burst", Inputs(0.0, 3.5, 16, impact = true)),
        ),
        StaffSpell.FROST to listOf(Event("fan", Inputs(7.0, 3.2, 16, impact = false))),
        StaffSpell.LANCE to listOf(Event("impact", Inputs(24.0, 0.75, 12, impact = true))),
        StaffSpell.EMBER to listOf(
            Event("flight", Inputs(1.0, 0.85, 24, impact = false)),
            Event("burst", Inputs(0.0, 2.8, 16, impact = true)),
        ),
        StaffSpell.NOVA to listOf(Event("impact", Inputs(4.0, 1.8, 28, impact = true))),
    )

    private fun frames(durationTicks: Int): List<Frame> {
        require(durationTicks > FRAME_TICKS * 2)
        val finalVisible = ((durationTicks - 1) / FRAME_TICKS) * FRAME_TICKS
        val middle = (durationTicks / 2 / FRAME_TICKS) * FRAME_TICKS
        require(middle > 0 && middle < finalVisible)
        return listOf(Frame("start", 0), Frame("middle", middle), Frame("final-visible", finalVisible))
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

    private fun playerCamera(spell: StaffSpell, event: Event, frame: Frame): Map<String, Any> {
        val (position, target) = when {
            spell == StaffSpell.NOVA -> point(0.0, 1.62, -3.5) to point(0.0, 1.62, 4.5)
            spell == StaffSpell.MARK -> point(0.0, 0.7, -6.0) to point(0.0, 0.7, 2.0)
            spell == StaffSpell.EMBER && event.id == "flight" ->
                point(0.0, 0.0, -frame.ageTicks * 1.2) to point(0.0, 0.0, 8.0)
            spell == StaffSpell.EMBER -> point(0.0, 0.0, -24.0) to point(0.0, 0.0, 0.0)
            else -> point(0.0, 0.0, 0.0) to point(0.0, 0.0, 8.0)
        }
        return mapOf(
            "position" to position,
            "target" to target,
            "fovDegrees" to if (spell == StaffSpell.NOVA) 70 else 50,
            "nearClip" to 0.05,
            "farClip" to 150,
        )
    }

    private fun state(spell: StaffSpell, event: Event, frame: Frame): ExportedState {
        val inputs = event.inputs
        val parts = staffDisplayParts(
            spell = spell,
            ageTicks = frame.ageTicks,
            durationTicks = inputs.durationTicks,
            length = inputs.length,
            radius = inputs.radius,
            impact = inputs.impact,
        ).take(StaffSpellDisplayEffects.MAX_PARTS)
        require(parts.isNotEmpty()) { "${spell.id}/${event.id}/${frame.id} produced no display parts" }
        return ExportedState(mapOf(
            "id" to "${spell.id}-${event.id}-${frame.id}",
            "title" to "${spell.id.uppercase()} · ${event.id} · ${frame.id}",
            "spell" to spell.id,
            "scenario" to event.id,
            "frame" to frame.id,
            "ageTicks" to frame.ageTicks,
            "frameTicks" to FRAME_TICKS,
            "durationTicks" to inputs.durationTicks,
            "impact" to inputs.impact,
            "gameplayEvent" to true,
            "length" to inputs.length,
            "radius" to inputs.radius,
            "camera" to playerCamera(spell, event, frame),
            "pieces" to parts.mapIndexed { index, part -> pieceMetadata(spell, event, index, part) },
        ), parts.map { "minecraft:${it.material.name.lowercase()}" })
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: StaffSpellPreviewExport <scene.json>" }
        val output = Path.of(args.single())
        val exported = StaffSpell.entries.associateWith { spell ->
            events.getValue(spell).flatMap { event -> frames(event.inputs.durationTicks).map { frame -> state(spell, event, frame) } }
        }
        val states = StaffSpell.entries.flatMap { exported.getValue(it).map(ExportedState::metadata) }
        val palette = exported.values.asSequence().flatten()
            .flatMap { it.materials.asSequence() }
            .distinct()
            .associateWith { it }

        output.parent?.let { Files.createDirectories(it) }
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "title" to "ARC staff spells",
            "states" to states,
            "palette" to palette,
            "coordinates" to mapOf("space" to "spell-local", "origin" to "cast point; +Z forward", "unit" to "block"),
            "cameraNote" to "Player views use a flat +Z cast-line approximation and do not simulate caster yaw/pitch, target repositioning, or player movement. NOVA's display vortex is previewed 3.5 blocks ahead of the caster; the damage area and particle ring remain centered on the caster.",
            "evidence" to "Each state calls staffDisplayParts and preserves its exact centers and scales; part quaternions are converted to XYZ Euler angles for the preview renderer. Samples follow the runtime's ${FRAME_TICKS}-tick frame step at ages strictly below their removal duration and use only controller-backed events. Player cameras are state-specific: eye-origin for CHAIN/FROST/LANCE, a forward-offset feet-origin for NOVA, a representative six-block offset for MARK, and age-based flight or impact offsets for EMBER. NOVA's vortex camera is an explicit 70-degree preview approximation; its 3.5-block forward display offset is separate from the caster-centered damage/AoE ring. CHAIN impact length is set to the 24-block targeting maximum; target-to-target chain segment lengths vary in live play. The palette map can be baked from the version-matched Minecraft 1.21.11 client JAR. Native Minecraft rendering is not simulated.",
        )).plus("\n"))
        println("STAFF_SPELL_PREVIEW states=${states.size} materials=${palette.size} output=$output")
    }
}
