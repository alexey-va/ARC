package ru.arc.aurora

import java.util.UUID
import java.util.Locale
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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

    fun contains(x: Double, y: Double, z: Double): Boolean {
        val dx = x - eye.x
        val dy = y - eye.y
        val dz = z - eye.z
        return dx * dx + dy * dy + dz * dz <= AUDIENCE_RADIUS * AUDIENCE_RADIUS
    }

    fun pose(index: Int): Pose {
        if (profiles[index].shape == AuroraShape.FROG_RAIN && profiles.size == RAIN_GROUPS) {
            val inner = index % 2 == 0
            val sector = index / 2
            val variation = sector % 3
            val direction = 0.18 + index * 2.0 * PI / RAIN_GROUPS
            val (radius, height) = when (index) {
                2 -> 2.6 to 13.7
                8 -> 3.2 to 14.6
                else -> if (inner) (9.3 + variation * 0.9) to (5.4 + variation * 0.45)
                    else (16.9 + variation * 1.2) to (10.4 + variation * 0.8)
            }
            val size = if (inner) 3.35 else 4.10
            val phase = (index * 5 + 1) % RAIN_GROUPS
            return pose(AuroraOffset(radius * sin(direction), height, radius * cos(direction)), size, 0.0,
                phase * profiles[index].sceneDurationTicks / RAIN_GROUPS)
        }
        if (profiles.size == COPY_COUNT) {
            val profile = profiles[index]
            val size = when (profile.shape) {
                AuroraShape.CUBE -> 2.1
                AuroraShape.SKY_LANTERNS -> 2.45
                AuroraShape.RING, AuroraShape.CIRCLE, AuroraShape.SPHERE, AuroraShape.ARC,
                AuroraShape.CRYSTAL, AuroraShape.STAR, AuroraShape.JELLYFISH, AuroraShape.MOON_FAN -> 3.45
                AuroraShape.PAPER_FLOTILLA, AuroraShape.SKY_STITCH -> 4.2
                AuroraShape.INVISIBLE_HARE, AuroraShape.TUMBLEWEED -> 3.8
                else -> profile.shapeSize.coerceIn(3.65, 3.95)
            }
            val tilt = when (profile.shape) {
                AuroraShape.INVISIBLE_HARE, AuroraShape.PAPER_FLOTILLA -> 42.0
                AuroraShape.TUMBLEWEED -> 12.0
                else -> 0.0
            }
            // Nine evenly spaced ages; immediate neighbours show different parts of the action.
            val phase = (index * 4 + 6) % COPY_COUNT
            return pose(copies[index], if (index == 4) size * 1.06 else size, tilt,
                phase * profile.sceneDurationTicks / COPY_COUNT)
        }
        val slot = slots[index]
        return pose(AuroraOffset(slot.right, slot.height, 32.0), slot.size, slot.tilt, 0)
    }

    private fun pose(position: AuroraOffset, size: Double, tilt: Double, phaseOffsetTicks: Int): Pose {
        return Pose(
            AuroraOffset(
                eye.x - normalX * position.z + normalZ * position.x,
                eye.y + position.y,
                eye.z - normalZ * position.z - normalX * position.x,
            ),
            size, Math.toRadians(tilt), phaseOffsetTicks, position,
        )
    }

    data class Pose(val anchor: AuroraOffset, val size: Double, val pitch: Double, val phaseOffsetTicks: Int,
        val viewerLocalSlot: AuroraOffset)

    private data class Slot(
        val shape: AuroraShape, val right: Double, val height: Double, val size: Double, val tilt: Double = 0.0,
    ) {
        val id = shape.name.lowercase(Locale.ROOT)
        val nightPreset = shape == AuroraShape.SKY_STITCH || shape == AuroraShape.AURORA_CURTAIN
    }

    companion object {
        const val AUDIENCE_RADIUS = 64.0
        const val DURATION_TICKS = 1_200L
        const val SELECTED_DURATION_TICKS = 3_600L
        const val COPY_COUNT = 9
        private const val RAIN_GROUPS = 14
        fun copyCount(shape: AuroraShape) = if (shape == AuroraShape.FROG_RAIN) RAIN_GROUPS else COPY_COUNT
        const val STORY_COPIES_PER_VISIT = 2

        /** A flat story's local +Z looks from this slot toward its viewer, including any terrain lift. */
        fun storyFacing(toEyeX: Double, toEyeY: Double, toEyeZ: Double): Quaternionf =
            Quaternionf().rotationY(atan2(toEyeX, toEyeZ).toFloat())
                .rotateX((-atan2(toEyeY, hypot(toEyeX, toEyeZ))).toFloat())

        // Viewer-local right, height and forward distance. Gaps accommodate the full moving silhouettes.
        private val copies = listOf(
            AuroraOffset(-13.1, 5.6, 31.5), AuroraOffset(0.0, 5.15, 30.6), AuroraOffset(13.6, 5.9, 31.8),
            AuroraOffset(-16.9, 15.6, 34.5), AuroraOffset(-0.75, 14.9, 32.5), AuroraOffset(15.9, 15.9, 34.8),
            AuroraOffset(-15.8, 28.0, 37.4), AuroraOffset(1.4, 26.6, 36.5), AuroraOffset(17.0, 27.5, 37.5),
        )
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
