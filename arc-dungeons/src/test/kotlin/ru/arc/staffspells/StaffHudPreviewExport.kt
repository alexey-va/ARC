package ru.arc.staffspells

import com.google.gson.GsonBuilder
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.Location
import org.bukkit.util.Vector
import org.bukkit.util.Transformation
import ru.arc.staffspells.api.StaffHudPlacement

/** Exports production text and native passenger/billboard transforms for delayed-metadata playback. */
object StaffHudPreviewExport {
    private data class State(
        val id: String,
        val phase: String,
        val spellId: String,
        val ability: String,
        val input: String,
        val miniMessageTemplate: String,
        val plainText: String,
        val lines: List<String>,
        val coloredLines: List<String>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: StaffHudPreviewExport <output.json>" }
        val output = Path.of(args.single()).toAbsolutePath().normalize()
        val defaults = loadDefaults()
        val config = StaffSpellConfig(StaffTestConfig(configValues(defaults)))
        val states = listOf(
            state("secondary-ready", "ready", StaffSpell.GRAVITY, true,
                config.staffHudReady(StaffSpell.GRAVITY, secondary = true), config, defaults, "ready-instant"),
            state("charge-ready", "ready", StaffSpell.EMBER, false,
                config.staffHudReady(StaffSpell.EMBER, secondary = false), config, defaults, "ready-charge"),
            state("charging", "charging", StaffSpell.EMBER, false,
                config.staffHudCharging(StaffSpell.EMBER, secondary = false, 0.55,
                    staffSpellHudBar(0.55)), config, defaults, "charging"),
            state("gravity-channel", "channel", StaffSpell.GRAVITY, false,
                config.staffHudChannel(StaffSpell.GRAVITY, secondary = false), config, defaults, "channel"),
            state("cooldown", "cooldown", StaffSpell.FROST, true,
                config.staffHudCooldown(StaffSpell.FROST, secondary = true, remainingTicks = 18,
                    bar = staffSpellHudBar(staffCooldownProgress(18, 40).toDouble())), config, defaults, "cooldown"),
        )
        val visual = staffSpellHudVisualSpec()
        val basePose = staffSpellHudPose(config.hudPosition)
        val nearWallDistance = staffHudSafeDistance(basePose.distance, obstructionDistance = 1.6)
        val nearWallPose = staffSpellHudPose(nearWallDistance, config.hudPosition)
        val document = mapOf(
            "schema" to 1,
            "source" to mapOf(
                "module" to "arc-dungeons",
                "messages" to "src/main/resources/modules/staff-spells.yml:messages.staff-hud",
                "poseHelper" to "staffSpellHudPose",
                "occlusionHelper" to "staffHudSafeDistance",
            ),
            "renderer" to mapOf(
                "kind" to "player-passenger center-billboard TextDisplay",
                "lineWidth" to visual.lineWidth,
                "displayWidthBlocks" to visual.displayWidth,
                "displayHeightBlocks" to visual.displayHeight,
                "viewRange" to visual.viewRange,
                "textOpacityUnsigned" to (visual.textOpacity.toInt() and 0xff),
                "shadowed" to visual.shadowed,
                "seeThrough" to visual.seeThrough,
                "backgroundArgb" to visual.backgroundColor.asARGB(),
                "followPeriodTicks" to visual.followPeriodTicks,
                "interpolationTicks" to visual.interpolationTicks,
                "privatePerViewer" to true,
            ),
            "poses" to mapOf(
                "unobstructed" to poseMap(basePose),
                "wallAt1_6Blocks" to poseMap(nearWallPose),
            ),
            "states" to states,
            "motionClips" to listOf(StaffHudPlacement.TOP, StaffHudPlacement.BOTTOM).map { preference ->
                motionClip(config, states.first { it.id == "charging" }, preference)
            },
            "evidenceLimit" to "Source-derived text and native passenger/billboard transforms. One-tick metadata transport plus interpolation; native pose attachment, F5 camera collision, depth, lighting and network latency remain unverified.",
        )
        Files.createDirectories(requireNotNull(output.parent))
        Files.writeString(output, GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(document) + "\n")
        println("WROTE ${output}")
    }

    private fun state(
        id: String,
        phase: String,
        spell: StaffSpell,
        secondary: Boolean,
        component: Component,
        config: StaffSpellConfig,
        defaults: YamlConfiguration,
        templateKey: String,
    ): State {
        val full = component.append(Component.newline()).append(config.hotkeyHud(
            if (phase == "cooldown") StaffHotkeyHudState(48, 20) else StaffHotkeyHudState(0, 0)))
        val plain = PlainTextComponentSerializer.plainText().serialize(full)
        return State(
            id = id,
            phase = phase,
            spellId = spell.id,
            ability = plain.lineSequence().firstOrNull().orEmpty().substringAfter(" · "),
            input = config.inputLabel(spell, StaffCastInput.of(secondary, quick = false)),
            miniMessageTemplate = requireNotNull(defaults.getString("messages.staff-hud.$templateKey")),
            plainText = plain,
            lines = plain.lines(),
            coloredLines = LegacyComponentSerializer.builder().character('&').hexColors().build()
                .serialize(full).lines(),
        )
    }

    private fun poseMap(pose: StaffSpellHudPose) = mapOf(
        "forwardBlocks" to pose.forwardOffset,
        "cameraDownBlocks" to pose.cameraDownOffset,
        "horizontalBlocks" to pose.horizontalOffset,
        "eyeDistanceBlocks" to pose.distance,
        "textScale" to pose.textScale,
        "screenAngleBelowReticleDegrees" to Math.toDegrees(kotlin.math.atan2(
            pose.cameraDownOffset, pose.forwardOffset,
        )),
    )

    private fun motionClip(config: StaffSpellConfig, state: State, preference: StaffHudPlacement): Map<String, Any> {
        val bounds = staffSpellHudBounds(state.plainText, staffSpellHudVisualSpec().lineWidth)
        val frames = staffHudMotionPreviewFrames(config, bounds, preference).map { frame ->
            mapOf(
                "tick" to frame.tick, "phase" to frame.phase,
                "eye" to vectorMap(frame.eye.toVector()), "yaw" to frame.eye.yaw, "pitch" to frame.eye.pitch,
                "passengerGap" to frame.passengerGap,
                "velocity" to vectorMap(frame.velocity), "retreat" to frame.motion.retreat,
                "translation" to frame.transform.translation.let { listOf(it.x, it.y, it.z) },
                "scale" to frame.pose.textScale, "interpolationTicks" to 1,
                "beforeTarget" to vectorMap(frame.beforeTarget), "beforeSend" to (frame.tick % 2 == 0),
            )
        }
        return mapOf("placement" to preference.name.lowercase(), "stateId" to state.id,
            "anchor" to "native-player-passenger", "billboard" to "center",
            "bounds" to mapOf("halfWidth" to bounds.halfWidth, "verticalRadius" to bounds.verticalRadius,
                "originUp" to bounds.originUp),
            "transportDelayTicks" to 1, "frames" to frames)
    }

    private fun vectorMap(value: Vector) = listOf(value.x, value.y, value.z)

    private fun configValues(defaults: YamlConfiguration): Map<String, Any> = buildMap {
        listOf(
            "lance.name",
            "ember.name", "ember.secondary-name",
            "frost.name", "frost.secondary-name",
            "gravity.name", "gravity.secondary-name",
        ).forEach { key -> put(key, requireNotNull(defaults.getString(key)) { "Missing staff name: $key" }) }
        listOf("ready-instant", "ready-charge", "charging", "cooldown", "channel")
            .forEach { key ->
                val sourceKey = "messages.staff-hud.$key"
                put(sourceKey, requireNotNull(defaults.getString(sourceKey)) { "Missing HUD text: $sourceKey" })
            }
        listOf("left-click", "right-click", "shift-left-click", "shift-right-click").forEach { key ->
            val sourceKey = "messages.controls.$key"
            put(sourceKey, requireNotNull(defaults.getString(sourceKey)) { "Missing control label: $sourceKey" })
        }
        listOf(
            "hud.position.forward-blocks", "hud.position.vertical-blocks",
            "hud.position.horizontal-blocks", "hud.position.scale",
            "hud.motion.prediction-ticks", "hud.motion.max-lead-blocks", "hud.motion.forward-blocks",
            "hud.motion.vertical-blocks", "hud.motion.settle-ticks",
        ).forEach { key ->
            require(defaults.contains(key)) { "Missing HUD position setting: $key" }
            put(key, defaults.getDouble(key))
        }
    }

    private fun loadDefaults(): YamlConfiguration {
        val resource = requireNotNull(StaffHudPreviewExport::class.java.getResourceAsStream("/modules/staff-spells.yml")) {
            "Missing modules/staff-spells.yml on test runtime classpath"
        }
        return InputStreamReader(resource, StandardCharsets.UTF_8).use(YamlConfiguration::loadConfiguration)
    }
}

/** The regression and exported animation share camera input, never a duplicated placement formula. */
internal data class StaffHudMotionPreviewFrame(
    val tick: Int,
    val phase: String,
    val eye: Location,
    val passengerGap: Double,
    val velocity: Vector,
    val motion: StaffSpellHudMotion,
    val pose: StaffSpellHudPose,
    val transform: Transformation,
    val beforeTarget: Vector,
)

internal fun staffHudMotionPreviewFrames(
    config: StaffSpellConfig,
    bounds: StaffSpellHudBounds,
    preference: StaffHudPlacement,
): List<StaffHudMotionPreviewFrame> {
    val feet = Vector(0.0, 64.0, 0.0)
    var motion = StaffSpellHudMotion()
    var previousDirection = Vector(0.0, 0.0, 1.0)
    return (0..165).map { tick ->
        val phase = when (tick) {
            in 0..9 -> "rest"
            in 10..19 -> "accelerate"
            in 20..39 -> "forward"
            in 40..49 -> "ascend"
            in 50..59 -> "descend"
            in 60..69 -> "strafe"
            in 70..89 -> "turn"
            in 90..99 -> "pitch-down"
            in 126..135 -> "sneaking"
            in 136..145 -> "swimming"
            in 146..155 -> "gliding"
            else -> "settle"
        }
        val yaw = when {
            tick < 70 -> 0f
            tick < 80 -> (tick - 69) * 12f
            else -> 120f
        }
        val pitch = when {
            tick < 80 -> 0f
            tick < 90 -> -(tick - 79) * 6f
            tick < 100 -> -60f + (tick - 89) * 12f
            tick < 126 -> 60f
            else -> 0f
        }
        val eyeHeight = when (tick) {
            in 126..135 -> 1.27
            in 136..155 -> 0.4
            else -> 1.62
        }
        val bodyHeight = when (tick) {
            in 126..135 -> 1.5
            in 136..155 -> 0.6
            else -> 1.8
        }
        val eye = Location(null, feet.x, feet.y + eyeHeight, feet.z, yaw, pitch)
        val forward = eye.direction.normalize()
        val right = Vector(-kotlin.math.cos(Math.toRadians(yaw.toDouble())), 0.0,
            -kotlin.math.sin(Math.toRadians(yaw.toDouble())))
        val down = forward.clone().crossProduct(right).normalize()
        val velocity = when (tick) {
            in 10..19 -> Vector(0.0, 0.0, (tick - 9) * 0.15)
            in 20..39 -> Vector(0.0, 0.0, 1.5)
            in 40..49 -> Vector(0.0, 1.2, 1.0)
            in 50..59 -> Vector(0.0, -1.2, 1.0)
            in 60..69 -> Vector(1.4, 0.0, 0.5)
            in 70..99, in 146..155 -> forward.clone().multiply(1.5)
            else -> Vector()
        }
        feet.add(velocity)
        eye.add(velocity)
        val turn = kotlin.math.acos(forward.dot(previousDirection).coerceIn(-1.0, 1.0))
        motion = staffSpellHudMotion(motion, velocity, 1.0, flying = true, config.hudMotion, turn)
        previousDirection = forward.clone()
        val gap = bodyHeight - eyeHeight
        val moving = staffHudMovingPosition(config.hudPosition, config.hudMotion, motion, preference, bounds, gap)
        val pose = staffSpellHudPose(moving)
        val legacy = config.hudPosition.copy(verticalBlocks = config.hudPosition.verticalBlocks *
            if (preference == StaffHudPlacement.BOTTOM) 1 else -1)
        val before = eye.toVector().add(forward.clone().multiply(legacy.forwardBlocks))
            .add(down.clone().multiply(legacy.verticalBlocks))
            .add(right.clone().multiply(legacy.horizontalBlocks))
        StaffHudMotionPreviewFrame(tick, phase, eye, gap, velocity, motion, pose,
            staffHudBillboardTransformation(pose, bounds, eye, gap), before)
    }
}
