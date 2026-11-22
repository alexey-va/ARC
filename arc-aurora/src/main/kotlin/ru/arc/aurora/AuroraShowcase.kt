package ru.arc.aurora

import java.util.UUID
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** An immutable panel in the world, fixed to the player's view at invocation. */
internal class AuroraShowcase(
    val world: UUID,
    private val eye: AuroraOffset,
    yaw: Float,
    val profiles: List<AuroraProfile>,
    val expiresAt: Long,
) {
    private val angle = Math.toRadians(yaw.toDouble())
    val normalX = sin(angle)
    val normalZ = -cos(angle)

    fun pose(index: Int): Pose {
        val slot = slots[index]
        return Pose(
            AuroraOffset(
                eye.x - normalX * DEPTH + normalZ * slot.right,
                eye.y + slot.height,
                eye.z - normalZ * DEPTH - normalX * slot.right,
            ),
            slot.size,
            Math.toRadians(slot.tilt),
        )
    }

    data class Pose(val anchor: AuroraOffset, val size: Double, val pitch: Double)

    private data class Slot(
        val shape: AuroraShape, val right: Double, val height: Double, val size: Double, val tilt: Double = 0.0,
    ) {
        val id = shape.name.lowercase(Locale.ROOT)
        val nightPreset = shape == AuroraShape.SKY_STITCH || shape == AuroraShape.AURORA_CURTAIN
    }

    companion object {
        const val DURATION_TICKS = 1_200L
        private const val DEPTH = 32.0
        private val slots = listOf(
            Slot(AuroraShape.SKY_STITCH, -10.0, 15.0, 6.0),
            Slot(AuroraShape.AURORA_CURTAIN, 10.0, 15.0, 7.0),
            Slot(AuroraShape.INVISIBLE_HARE, -13.0, 7.0, 4.0, 42.0),
            Slot(AuroraShape.PAPER_FLOTILLA, 0.0, 7.0, 4.8, 42.0),
            Slot(AuroraShape.TUMBLEWEED, 13.0, 7.0, 4.0, 12.0),
        )

        fun profiles(settings: AuroraSettings, fallback: AuroraSettings? = null): List<AuroraProfile>? {
            return slots.map { slot ->
                fun AuroraSettings.find(): AuroraProfile? =
                    (if (slot.nightPreset) eventPresets["treasure-hunt"].orEmpty() else profiles)
                        .firstOrNull { it.id == slot.id }

                val profile = settings.find() ?: fallback?.find() ?: return null
                if (!profile.skyScene || profile.shape != slot.shape) return null
                profile
            }
        }
    }
}
