package ru.arc.staffspells

import com.google.gson.GsonBuilder
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration

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
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: StaffHudPreviewExport <output.json>" }
        val output = Path.of(args.single()).toAbsolutePath().normalize()
        val defaults = loadDefaults()
        val config = StaffSpellConfig(StaffTestConfig(configValues(defaults)))
        val states = listOf(
            state("instant-ready", "ready", StaffSpell.LANCE, false,
                config.staffHudReady(StaffSpell.LANCE, secondary = false), defaults, "ready-instant"),
            state("charge-ready", "ready", StaffSpell.EMBER, false,
                config.staffHudReady(StaffSpell.EMBER, secondary = false), defaults, "ready-charge"),
            state("charging", "charging", StaffSpell.EMBER, false,
                config.staffHudCharging(StaffSpell.EMBER, secondary = false, 0.55,
                    staffSpellHudBar(0.55)), defaults, "charging"),
            state("gravity-channel", "channel", StaffSpell.GRAVITY, true,
                config.staffHudChannel(StaffSpell.GRAVITY, secondary = true), defaults, "channel"),
            state("cooldown", "cooldown", StaffSpell.FROST, true,
                config.staffHudCooldown(StaffSpell.FROST, secondary = true, remainingTicks = 18,
                    bar = staffSpellHudBar(staffCooldownProgress(18, 40).toDouble())), defaults, "cooldown"),
        )
        val visual = staffSpellHudVisualSpec()
        val basePose = staffSpellHudPose()
        val nearWallDistance = staffHudSafeDistance(basePose.distance, obstructionDistance = 1.6)
        val nearWallPose = staffSpellHudPose(nearWallDistance)
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
            "evidenceLimit" to "Source-derived component text/layout; does not simulate native-client depth, FOV, lighting, or interpolation.",
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
        defaults: YamlConfiguration,
        templateKey: String,
    ): State {
        val plain = PlainTextComponentSerializer.plainText().serialize(component)
        return State(
            id = id,
            phase = phase,
            spellId = spell.id,
            ability = plain.lineSequence().firstOrNull().orEmpty(),
            input = if (secondary) "Shift + ЛКМ" else "ЛКМ",
            miniMessageTemplate = requireNotNull(defaults.getString("messages.staff-hud.$templateKey")),
            plainText = plain,
            lines = plain.lines(),
        )
    }

    private fun poseMap(pose: StaffSpellHudPose) = mapOf(
        "forwardBlocks" to pose.forwardOffset,
        "cameraDownBlocks" to pose.cameraDownOffset,
        "eyeDistanceBlocks" to pose.distance,
        "textScale" to pose.textScale,
        "screenAngleBelowReticleDegrees" to Math.toDegrees(kotlin.math.atan2(
            pose.cameraDownOffset, pose.forwardOffset,
        )),
    )

    private fun configValues(defaults: YamlConfiguration): Map<String, String> = buildMap {
        listOf(
            "lance.name",
            "ember.name", "ember.secondary-name",
            "frost.name", "frost.secondary-name",
            "gravity.name", "gravity.secondary-name",
        ).forEach { key -> put(key, requireNotNull(defaults.getString(key)) { "Missing staff name: $key" }) }
        listOf("ready-instant", "ready-charge", "ready-channel", "charging", "cooldown", "channel")
            .forEach { key ->
                val sourceKey = "messages.staff-hud.$key"
                put(sourceKey, requireNotNull(defaults.getString(sourceKey)) { "Missing HUD text: $sourceKey" })
            }
    }

    private fun loadDefaults(): YamlConfiguration {
        val resource = requireNotNull(StaffHudPreviewExport::class.java.getResourceAsStream("/modules/staff-spells.yml")) {
            "Missing modules/staff-spells.yml on test runtime classpath"
        }
        return InputStreamReader(resource, StandardCharsets.UTF_8).use(YamlConfiguration::loadConfiguration)
    }
}
