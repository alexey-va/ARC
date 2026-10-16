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
import ru.arc.staffspells.api.StaffHudPlacement

/** Exports the production HUD text, source-owned layout settings, and two camera poses as offline JSON. */
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
                "kind" to "center-billboard TextDisplay",
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
            "evidenceLimit" to "Source-derived component text/layout and moving-camera keyframes. Linear one-tick transport/interpolation model only; native-client depth, lighting, latency and rendering remain unverified.",
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
        val eye = Location(null, 0.0, 65.62, 0.0)
        var motion = StaffSpellHudMotion()
        var previous: Vector? = null
        var previousDirection = eye.direction
        val frames = (0..125).map { tick ->
            val phase = when (tick) {
                in 0..9 -> "rest"
                in 10..19 -> "accelerate"
                in 20..39 -> "forward"
                in 40..49 -> "ascend"
                in 50..59 -> "descend"
                in 60..69 -> "strafe"
                in 70..89 -> "turn"
                in 90..99 -> "pitch-down"
                else -> "settle"
            }
            eye.yaw = when {
                tick < 70 -> 0f
                tick < 80 -> (tick - 69) * 12f
                else -> 120f
            }
            eye.pitch = when {
                tick < 80 -> 0f
                tick < 90 -> -(tick - 79) * 6f
                tick < 100 -> -60f + (tick - 89) * 12f
                else -> 60f
            }
            val forward = eye.direction.normalize()
            val yaw = Math.toRadians(eye.yaw.toDouble())
            val right = Vector(kotlin.math.cos(yaw), 0.0, kotlin.math.sin(yaw))
            val down = right.clone().crossProduct(forward).normalize()
            val velocity = when (tick) {
                in 10..19 -> Vector(0.0, 0.0, (tick - 9) * 0.15)
                in 20..39 -> Vector(0.0, 0.0, 1.5)
                in 40..49 -> Vector(0.0, 1.2, 1.0)
                in 50..59 -> Vector(0.0, -1.2, 1.0)
                in 60..69 -> Vector(1.4, 0.0, 0.5)
                in 70..99 -> forward.clone().multiply(1.5)
                else -> Vector()
            }
            eye.add(velocity)
            val turn = kotlin.math.acos(forward.dot(previousDirection).coerceIn(-1.0, 1.0))
            motion = staffSpellHudMotion(motion, velocity, 1.0, flying = true, config.hudMotion, turn)
            previousDirection = forward.clone()
            val moving = staffHudMovingPosition(config.hudPosition, config.hudMotion, motion,
                forward, down, right, preference, bounds)
            fun at(position: StaffSpellHudPositionSettings) = eye.toVector()
                .add(forward.clone().multiply(position.forwardBlocks))
                .add(down.clone().multiply(position.verticalBlocks))
                .add(right.clone().multiply(position.horizontalBlocks))
            val target = at(moving)
            val smooth = previous?.let { staffHudInterpolationClear(eye, it, target,
                moving.scale.toFloat(), moving.scale.toFloat(), velocity, bounds, preference) } ?: true
            previous = target
            val legacy = config.hudPosition.copy(verticalBlocks = config.hudPosition.verticalBlocks *
                if (preference == StaffHudPlacement.BOTTOM) 1 else -1)
            mapOf(
                "tick" to tick, "phase" to phase,
                "eye" to vectorMap(eye.toVector()), "yaw" to eye.yaw, "pitch" to eye.pitch,
                "velocity" to vectorMap(velocity), "retreat" to motion.retreat,
                "target" to vectorMap(target), "scale" to moving.scale,
                "interpolationTicks" to if (smooth) 1 else 0,
                "beforeTarget" to vectorMap(at(legacy)), "beforeSend" to (tick % 2 == 0),
            )
        }
        return mapOf("placement" to preference.name.lowercase(), "stateId" to state.id,
            "bounds" to mapOf("halfWidth" to bounds.halfWidth, "verticalRadius" to bounds.verticalRadius),
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
