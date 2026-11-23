package ru.arc.playerseat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val HAND_SIDE_OFFSET = 1.92f

internal class SeatDirectionDisplay(private val displays: PaperPacketDisplays) : SeatDirections {
    constructor(plugin: Plugin) : this(PaperPacketDisplays(plugin, "player-seat-directions"))

    private val signals = mutableMapOf<UUID, Signal>()

    private class Signal(
        val carrierId: UUID,
        val arrow: MutableList<PacketBlockDisplay>,
        val caption: PacketTextDisplay,
        var pose: SeatDirectionPose,
        var yaw: Float,
        var pitch: Float,
    ) {
        fun render(interpolationTicks: Int) {
            val rotation = seatHeadingRotation(yaw, pitch)
            val size = pose.scaleForPitch(pitch)
            // At steep angles the two players share the sightline. Slide the cue beside them
            // while keeping its pointing axis parallel to the rider's gaze.
            val steepness = ((abs(pitch) - 55f) / 30f).coerceIn(0f, 1f)
            val blend = steepness * steepness
            val side = if (pitch < 0f) 3.2f else .95f
            val handOffset = if (pose.style == SeatArrowStyle.POINTING_HAND) HAND_SIDE_OFFSET * size else 0f
            val arrowShift = -(side - handOffset) * blend
            val captionShift = -side * blend
            arrow.forEachIndexed { index, display ->
                display.interpolationDuration = interpolationTicks
                display.transformation = pose.arrow[index].transform.turned(rotation, size, arrowShift)
            }
            caption.interpolationDuration = interpolationTicks
            caption.transformation = pose.caption.turned(rotation, size, captionShift)
        }

        fun remove() {
            arrow.forEach(PacketDisplay::remove)
            caption.remove()
        }
    }

    override fun show(carrier: Player, rider: Player, yaw: Float, pitch: Float, caption: String) {
        val riderId = rider.uniqueId
        if (signals[riderId]?.carrierId != carrier.uniqueId) hide(riderId)
        val signal = signals[riderId]
        val palette = SeatDirectionPalette.pick(signal?.pose?.palette)
        val text = palette.caption(caption)
        val height = carrier.height.toFloat()
        if (signal == null) {
            val pose = SeatDirectionPose(height, SeatArrowStyle.COMPASS, palette)
            signals[riderId] = createSignal(carrier, rider, text, pose, yaw, pitch)
            return
        }
        val old = signal.pose
        val next = SeatDirectionPose(height, old.style.next(), palette)
        try {
            val at = anchor(carrier)
            next.arrow.forEachIndexed { index, part ->
                val display = signal.arrow.getOrNull(index)
                if (display == null) {
                    val added = displays.spawnBlock(at, part.material.createBlockData())
                    signal.arrow.add(added)
                    attach(added, carrier, rider)
                } else if (old.arrow[index].material != part.material) {
                    display.blockData = part.material.createBlockData()
                }
            }
            while (signal.arrow.size > next.arrow.size) {
                signal.arrow.last().remove()
                signal.arrow.removeAt(signal.arrow.lastIndex)
            }
            signal.pose = next
            signal.caption.text(text)
            signal.render(0)
        } catch (failure: Exception) {
            hide(riderId)
            throw failure
        }
    }

    override fun turn(carrier: Player, rider: Player, yaw: Float, pitch: Float) {
        val signal = signals[rider.uniqueId] ?: return
        if (carrier.uniqueId != signal.carrierId) return
        val nextYaw = seatTurnYaw(signal.yaw, yaw)
        val nextPitch = signal.pitch + (pitch - signal.pitch).coerceIn(-30f, 30f)
        val height = carrier.height.toFloat()
        if (nextYaw == signal.yaw && nextPitch == signal.pitch && height == signal.pose.carrierHeight) return
        signal.yaw = nextYaw
        signal.pitch = nextPitch
        if (height != signal.pose.carrierHeight) signal.pose = SeatDirectionPose(height, signal.pose.style, signal.pose.palette)
        signal.render(2)
    }

    // A fixed display applies its entity yaw/pitch before its transform. Keep those axes world-aligned.
    private fun anchor(carrier: Player) = carrier.location.apply {
        yaw = 0f
        pitch = 0f
    }

    private fun attach(part: PacketDisplay, carrier: Player, rider: Player) {
        part.isVisibleByDefault = false
        part.showTo(carrier)
        part.showTo(rider)
        part.brightness = Display.Brightness(15, 15)
        part.pauseWhenNotObserved = false
        part.attachTo(carrier)
    }

    private fun createSignal(
        carrier: Player, rider: Player, text: Component, pose: SeatDirectionPose, yaw: Float, pitch: Float,
    ): Signal {
        val at = anchor(carrier)
        val parts = mutableListOf<PacketDisplay>()
        try {
            val arrow = pose.arrow.mapTo(mutableListOf()) { part ->
                displays.spawnBlock(at, part.material.createBlockData()).also(parts::add)
            }
            val caption = displays.spawnText(at, text).also(parts::add).apply {
                backgroundColor = Color.fromARGB(212, 35, 17, 31)
                isShadowed = true
            }
            for (part in parts) attach(part, carrier, rider)
            return Signal(carrier.uniqueId, arrow, caption, pose, yaw % 720f, pitch).also { it.render(0) }
        } catch (failure: Exception) {
            parts.forEach(PacketDisplay::remove)
            throw failure
        }
    }

    override fun hide(riderId: UUID) {
        signals.remove(riderId)?.remove()
    }

    override fun close() {
        signals.clear()
        displays.close()
    }
}

internal class SeatHandColors(
    val shade: Material,
    val body: Material,
    val highlight: Material,
    val cuff: Material,
    val stripe: Material,
)

internal enum class SeatDirectionPalette(
    val rim: Material,
    val spine: Material,
    val face: Material,
    val shade: Material,
    val hand: SeatHandColors,
    private val leadColor: Int,
    private val accentColor: Int,
) {
    ROSE(Material.NETHER_BRICKS, Material.QUARTZ_BLOCK, Material.PINK_WOOL, Material.PINK_CONCRETE,
        SeatHandColors(Material.MAGENTA_TERRACOTTA, Material.PINK_CONCRETE, Material.PINK_CONCRETE_POWDER,
            Material.MAGENTA_CONCRETE, Material.SMOOTH_QUARTZ), 0xFF679A, 0xFFD1E0),
    SCARLET(Material.RED_NETHER_BRICKS, Material.WHITE_CONCRETE, Material.REDSTONE_BLOCK, Material.RED_WOOL,
        SeatHandColors(Material.PINK_TERRACOTTA, Material.WHITE_TERRACOTTA, Material.SMOOTH_QUARTZ,
            Material.PURPLE_TERRACOTTA, Material.PINK_CONCRETE_POWDER), 0xFF7A70, 0xFFBCB1),
    ORCHID(Material.CRIMSON_PLANKS, Material.CALCITE, Material.PURPUR_BLOCK, Material.MAGENTA_WOOL,
        SeatHandColors(Material.PURPLE_TERRACOTTA, Material.MAGENTA_CONCRETE_POWDER, Material.WHITE_TERRACOTTA,
            Material.PINK_CONCRETE, Material.SMOOTH_QUARTZ), 0xEB93F0, 0xFFD0F3),
    EMERALD(Material.POLISHED_BLACKSTONE, Material.IRON_BLOCK, Material.EMERALD_BLOCK, Material.DEEPSLATE_EMERALD_ORE,
        SeatHandColors(Material.GRAY_CONCRETE, Material.PURPLE_CONCRETE_POWDER, Material.SMOOTH_QUARTZ,
            Material.WEATHERED_COPPER, Material.SEA_LANTERN), 0x6FF992, 0xCEFFD8),
    PEARL(Material.POLISHED_BLACKSTONE, Material.PINK_WOOL, Material.CALCITE, Material.IRON_BLOCK,
        SeatHandColors(Material.WHITE_TERRACOTTA, Material.SMOOTH_QUARTZ, Material.PINK_CONCRETE_POWDER,
            Material.PINK_CONCRETE, Material.MAGENTA_TERRACOTTA), 0xFFF5F8, 0xFF92C3);

    fun caption(phrase: String): Component {
        val first = phrase.substringBefore(' ')
        return Component.text(first, TextColor.color(leadColor)).decorate(TextDecoration.BOLD)
            .append(Component.text(phrase.substring(first.length), TextColor.color(accentColor)))
    }

    companion object {
        fun pick(previous: SeatDirectionPalette?, random: Random = Random.Default): SeatDirectionPalette {
            if (previous == null || entries.size == 1) return entries.random(random)
            return entries[(previous.ordinal + random.nextInt(1, entries.size)) % entries.size]
        }
    }
}

internal class SeatArrowPart(val material: Material, val transform: Transformation)

internal enum class SeatArrowStyle {
    COMPASS, CHEVRONS, FLETCHED, POINTING_HAND;

    fun next(): SeatArrowStyle = entries[(ordinal + 1) % entries.size]
}

/** Small increments keep the display's linearly interpolated offset on the turning arc. */
internal fun seatTurnYaw(current: Float, target: Float): Float {
    val delta = Location.normalizeYaw(target - current)
    // A full quaternion period also preserves its sign across the +/-180 degree seam.
    return (current + delta.coerceIn(-30f, 30f)) % 720f
}

internal fun seatHeadingRotation(yaw: Float, pitch: Float): Quaternionf =
    Quaternionf().rotationY(-Math.toRadians(yaw.toDouble()).toFloat()).rotateX(Math.toRadians(pitch.toDouble()).toFloat())

internal fun Transformation.turned(heading: Quaternionf, size: Float = 1f, side: Float = 0f): Transformation = Transformation(
    heading.transform(Vector3f(translation).mul(size).add(side, 0f, 0f)), Quaternionf(heading).mul(leftRotation),
    Vector3f(scale).mul(size), rightRotation,
)

/** Offsets from the native passenger origin above the carrier, with +Z forward at Bukkit yaw zero. */
internal class SeatDirectionPose(val carrierHeight: Float, val style: SeatArrowStyle, val palette: SeatDirectionPalette) {
    val arrow = when (style) {
        SeatArrowStyle.COMPASS -> compass()
        SeatArrowStyle.CHEVRONS -> chevrons()
        SeatArrowStyle.FLETCHED -> fletched()
        SeatArrowStyle.POINTING_HAND -> pointingHand()
    }

    fun scaleForPitch(pitch: Float): Float {
        if (pitch <= 0f) return 1f
        // Keep downward cues above the carrier's feet on level ground by bringing the whole cue closer.
        val angle = Math.toRadians(pitch.toDouble()).toFloat()
        val drop = if (style == SeatArrowStyle.POINTING_HAND) carrierHeight * .56f + .49f else carrierHeight * .52f + .2f
        val depth = drop * cos(angle) + 7.3f * sin(angle)
        return ((carrierHeight - .2f) / depth).coerceIn(.1f, 1f)
    }

    private fun compass() = listOf(
        shaft(palette.rim, 4.28f, 5.78f, .31f, .16f, 0f),
        shaft(palette.spine, 4.36f, 6.16f, .15f, .06f, .102f),
        wing(palette.rim, -1, 6.46f, 1f, .15f, 0f),
        wing(palette.face, -1, 6.40f, .87f, .060f, .091f),
        wing(palette.rim, 1, 6.46f, 1f, .15f, 0f),
        wing(palette.shade, 1, 6.40f, .87f, .060f, .091f),
        diamond(palette.spine, 6f, .141f, .14f, .05f, .99f),
    )

    private fun chevrons() = listOf(
        wing(palette.rim, -1, 5.28f, .75f, .16f, -.035f),
        wing(palette.rim, 1, 5.28f, .75f, .16f, -.035f),
        wing(palette.spine, -1, 5.22f, .62f, .055f, .066f),
        wing(palette.spine, 1, 5.22f, .62f, .055f, .066f),
        wing(palette.face, -1, 6.65f, 1.02f, .15f, .035f),
        wing(palette.face, 1, 6.65f, 1.02f, .15f, .035f),
        diamond(palette.shade, 6.17f, .116f, .19f, .06f, .80f),
    )

    private fun fletched() = listOf(
        shaft(palette.rim, 4.14f, 6.16f, .15f, .12f, 0f),
        shaft(palette.spine, 4.23f, 6.34f, .075f, .05f, .077f),
        feather(palette.face, -1),
        feather(palette.shade, 1),
        wing(palette.face, -1, 6.50f, .76f, .13f, .025f),
        wing(palette.shade, 1, 6.50f, .76f, .13f, .025f),
        diamond(palette.spine, 6.31f, .105f, .18f, .045f, .48f),
    )

    private fun pointingHand(): List<SeatArrowPart> = buildList {
        val colors = palette.hand
        val degree = (PI / 180).toFloat()
        // Roll around the pointing axis, and put the hand to the right so the extended finger has parallax.
        val frame = Quaternionf().rotationZ(-11f * degree)
        val origin = Vector3f(-HAND_SIDE_OFFSET, -carrierHeight * 5f / 9f, 5.20f)
        val flat = Quaternionf()
        fun box(material: Material, at: Vector3f, size: Vector3f, rotation: Quaternionf = flat) {
            val center = frame.transform(Vector3f(at)).add(origin)
            add(centered(material, center, Quaternionf(frame).mul(rotation), size, flat))
        }
        // Two intersecting blocks cut a step from each corner of a solid pad.
        fun pad(material: Material, at: Vector3f, size: Vector3f, cut: Float, rotation: Quaternionf = flat) {
            box(material, at, Vector3f(size.x, size.y - 2f * cut, size.z), rotation)
            box(material, at, Vector3f(size.x - 2f * cut, size.y, size.z - .015f), rotation)
        }

        pad(colors.cuff, Vector3f(-.24f, -.245f, -.57f), Vector3f(.71f, .335f, .43f), .056f)
        box(Material.BLACK_TERRACOTTA, Vector3f(-.24f, -.408f, -.57f), Vector3f(.57f, .043f, .38f))
        val stripeAngle = Quaternionf().rotationZ(-31f * degree)
        for (x in listOf(-.33f, -.135f)) {
            box(colors.stripe, Vector3f(x, -.225f, -.792f), Vector3f(.074f, .166f, .028f), stripeAngle)
        }
        box(Material.BLACK_TERRACOTTA, Vector3f(-.008f, -.334f, -.013f), Vector3f(1.08f, .073f, .77f))
        pad(colors.shade, Vector3f(-.003f, -.078f, -.016f), Vector3f(1.215f, .478f, .836f), .064f)
        pad(colors.body, Vector3f(-.005f, .007f, -.047f), Vector3f(1.21f, .459f, .884f), .084f)

        // The knuckle and fingertip share a straight, horizontal +Z axis.
        box(colors.body, Vector3f(.395f, .217f, .119f), Vector3f(.308f, .398f, .73f))
        box(colors.shade, Vector3f(.40f, .270f, .957f), Vector3f(.281f, .075f, 1.67f))
        pad(colors.body, Vector3f(.40f, .402f, .973f), Vector3f(.344f, .272f, 1.78f), .048f)
        box(colors.body, Vector3f(.40f, .402f, 1.878f), Vector3f(.249f, .167f, .119f))
        box(colors.highlight, Vector3f(.496f, .528f, .917f), Vector3f(.116f, .028f, 1.285f),
            Quaternionf().rotationZ(-24f * degree))

        fun curledFinger(at: Vector3f, size: Vector3f, tilt: Float) {
            val knuckle = Quaternionf().rotationX(tilt * degree)
            box(colors.shade, Vector3f(at).add(-.014f, -.030f, .009f),
                Vector3f(size).add(.025f, -.014f, -.016f), knuckle)
            pad(colors.body, at, size, .053f, knuckle)
            // The visible pad faces back and up toward the carrier and rider.
            val facet = knuckle.transform(Vector3f(-.012f, .030f, -size.z / 2f - .012f)).add(at)
            box(colors.highlight, facet, Vector3f(size.x * .69f, size.y * .40f, .032f), knuckle)
        }
        curledFinger(Vector3f(.112f, .106f, -.335f), Vector3f(.342f, .344f, .515f), 18f)
        curledFinger(Vector3f(-.223f, .054f, -.306f), Vector3f(.326f, .318f, .485f), 20f)
        curledFinger(Vector3f(-.537f, -.020f, -.261f), Vector3f(.285f, .281f, .431f), 22f)

        val thumbBase = Quaternionf().rotationY(-27f * degree)
        val thumbTip = Quaternionf().rotationZ(-23f * degree)
        box(colors.shade, Vector3f(.594f, -.126f, -.120f), Vector3f(.29f, .242f, .53f), thumbBase)
        pad(colors.body, Vector3f(.679f, -.083f, -.268f), Vector3f(.331f, .278f, .433f), .052f, thumbBase)
        pad(colors.body, Vector3f(.796f, .014f, -.312f), Vector3f(.241f, .286f, .269f), .047f, thumbTip)
        box(colors.highlight, Vector3f(.798f, .044f, -.450f), Vector3f(.144f, .117f, .028f), thumbTip)
    }

    // Both players share the carrier's horizontal position. Fixed text faces them from above the arrow.
    val caption = Transformation(
        Vector3f(0f, -.04f * carrierHeight, 5.84f), Quaternionf().rotateY(PI.toFloat()),
        Vector3f(1.85f), Quaternionf(),
    )

    private fun point(z: Float, lift: Float) =
        Vector3f(0f, -.52f * carrierHeight + lift, z)

    private fun shaft(material: Material, from: Float, to: Float, width: Float, height: Float, lift: Float): SeatArrowPart {
        return centered(material, point((from + to) / 2f, lift), Quaternionf(), Vector3f(width, height, to - from), Quaternionf())
    }

    private fun diamond(material: Material, z: Float, lift: Float, width: Float, height: Float, length: Float): SeatArrowPart =
        centered(material, point(z, lift), Quaternionf(), Vector3f(width / sqrt(2f), height, length / sqrt(2f)),
            Quaternionf().rotationY((PI / 4).toFloat()))

    private fun feather(material: Material, side: Int): SeatArrowPart = centered(
        material, point(4.66f, .06f).add(side * .12f, 0f, 0f),
        Quaternionf().rotationY(-side * Math.toRadians(14.0).toFloat()),
        Vector3f(.21f / sqrt(2f), .11f, 1.10f / sqrt(2f)), Quaternionf().rotationY((PI / 4).toFloat()),
    )

    private fun centered(material: Material, at: Vector3f, left: Quaternionf, scale: Vector3f, right: Quaternionf): SeatArrowPart {
        val center = right.transform(Vector3f(.5f)).mul(scale)
        val corner = at.sub(left.transform(center))
        return SeatArrowPart(material, Transformation(corner, left, scale, right))
    }

    private fun wing(material: Material, side: Int, tipZ: Float, size: Float, height: Float, lift: Float): SeatArrowPart {
        // Turning a block before a nonuniform stretch gives each wing swept, parallel edges.
        val right = Quaternionf().rotationY(side * (PI / 3).toFloat())
        val scale = Vector3f(.50f * size, height, 1.32f * size)
        val seam = right.transform(Vector3f(0f, 0f, 1f)).mul(scale)
        val left = Quaternionf().rotationZ(-side * Math.toRadians(12.0).toFloat()).rotateY(-atan2(seam.x, seam.z))
        // Align the inner edge with the spine. Its front corner is the shared sharp point.
        val innerCorner = Vector3f(if (side < 0) 1f else 0f, .5f, 1f)
        val corner = point(tipZ, lift).sub(left.transform(right.transform(innerCorner).mul(scale)))
        return SeatArrowPart(material, Transformation(corner, left, scale, right))
    }
}
