package ru.arc.graves

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.SoundCategory
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.paper.display.PacketDisplay
import kotlin.math.cos
import kotlin.math.sin

/** Main-thread feedback for accepted hits; never owns collision, hit counting or removal. */
internal class GraveImpact(scheduler: TaskScheduler = Tasks.scheduler) : AutoCloseable {
    private data class Rest(val part: PacketDisplay, val at: Location, val teleportDuration: Int)
    private class Motion(val parts: List<Rest>)
    private val tasks = LifecycleTaskScope(scheduler)
    private val active = mutableMapOf<PacketDisplay, Motion>()
    private var closed = false

    /** Once per accepted hit, outside the per-viewer rendering loop. */
    fun hit(at: Location, appearance: GraveAppearance, hitNumber: Int) {
        require(hitNumber in 1..2)
        if (closed) return
        val sound = material(appearance.model).createBlockData().soundGroup
        at.world?.playSound(at, sound.hitSound, SoundCategory.BLOCKS,
            sound.volume * if (hitNumber == 1) .45f else .65f,
            sound.pitch * if (hitNumber == 1) 1.1f else .95f)
    }

    /** Only after durable removal succeeds; Creative uses this directly without the hit cues. */
    fun broken(at: Location, appearance: GraveAppearance) {
        if (closed) return
        val sound = material(appearance.model).createBlockData().soundGroup
        at.world?.playSound(at, sound.breakSound, SoundCategory.BLOCKS, sound.volume * .9f, sound.pitch * .9f)
    }

    /** [parts] has GravesHud's stable order: model parts, then the inscription's text displays. */
    fun shake(parts: List<PacketDisplay>, appearance: GraveAppearance, hitNumber: Int) {
        val offsets = offsets(hitNumber, appearance.model.engraved)
        if (closed) return
        val key = parts.firstOrNull() ?: return
        val previous = active[key]
        val moving = parts.mapIndexedNotNull { index, part ->
            if (!part.isValid || !moves(appearance.model, index)) null
            else previous?.parts?.firstOrNull { it.part === part } ?: Rest(part, part.location, part.teleportDuration)
        }
        if (moving.isEmpty()) return
        val motion = Motion(moving)
        active[key] = motion // A second strike retains the rest pose and invalidates the first tail.
        val yaw = Math.toRadians(appearance.yaw.toDouble())
        fun pose(offset: Double) {
            moving.forEach { rest ->
                if (rest.part.isValid) {
                    rest.part.teleportDuration = FRAME_TICKS.toInt()
                    // World translation preserves the text's distinct entity yaw and its backing plane.
                    rest.part.teleport(rest.at.clone().add(cos(yaw) * offset, 0.0, -sin(yaw) * offset))
                }
            }
        }
        try {
            offsets.forEachIndexed { index, offset ->
                if (index == 0) pose(offset)
                else tasks.runLater(index * FRAME_TICKS) { if (active[key] === motion) pose(offset) }
            }
            // Let the final zero-offset keyframe finish before restoring interpolation settings.
            tasks.runLater(offsets.size * FRAME_TICKS) {
                if (active.remove(key, motion)) restore(motion)
            }
        } catch (failure: RuntimeException) {
            if (active.remove(key, motion)) restore(motion)
            throw failure
        }
    }

    /** Retire this marker's animation before removing or replacing its packet displays. */
    fun cancel(parts: List<PacketDisplay>) {
        parts.firstOrNull()?.let { active.remove(it) }?.let(::restore)
    }

    private fun restore(motion: Motion) {
        motion.parts.forEach { rest ->
            if (rest.part.isValid) {
                rest.part.teleport(rest.at)
                rest.part.teleportDuration = rest.teleportDuration
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        active.values.forEach(::restore)
        active.clear()
    }

    companion object {
        internal const val FRAME_TICKS = 2L

        /** The tallest structural part selects wood/stone/deepslate without duplicating sound IDs. */
        internal fun material(model: GraveModel): Material = model.body.maxBy { it.height }.material

        internal fun offsets(hitNumber: Int, engraved: Boolean): List<Double> {
            require(hitNumber in 1..2)
            val strength = if (engraved) .02 else .045
            return if (hitNumber == 1) listOf(.6, -.25, .10, 0.0).map { it * strength }
            else listOf(-1.0, .6, -.28, .10, 0.0).map { it * strength }
        }

        internal fun moves(model: GraveModel, index: Int): Boolean {
            if (!model.engraved || index >= model.parts.size) return true
            val part = model.parts[index]
            // Yamipa .9 maps are block-anchored. Keep their slab/frame/ribbon fixed; shake only
            // the independent plinth and plaque with its text, within the narrowest frame's gap.
            return part.rotationX == 0f && part.rotationY == 0f && part.rotationZ == 0f && part.y + part.height <= .55
        }
    }
}
