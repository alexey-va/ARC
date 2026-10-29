package ru.arc.backwings

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

internal enum class BackWing(val itemId: String, val configKey: String) {
    DREADKNIGHT("dreadknight:dreadknight_wings", "dreadknight"),
    GALAXY("galaxy_set:galaxy_wings", "galaxy"),
    DECEASE("deceaseset:decease_wings", "decease");

    companion object {
        fun fromItemId(id: String?): BackWing? = entries.firstOrNull { it.itemId == id }
    }
}

internal data class WingVector(val x: Float, val y: Float, val z: Float) {
    fun finite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}

internal data class BackWingStyle(
    val scale: Float,
    val rotation: WingVector,
    /** Feet-relative model origin, in body axes: +X left, +Y up, +Z forward at yaw zero. */
    val anchor: WingVector,
) {
    init {
        require(scale.isFinite() && scale > 0f && scale <= 4f) { "scale must be in (0, 4]" }
        require(rotation.finite() && anchor.finite()) { "rotation and local-anchor must be finite" }
    }
}

internal data class BackWingsSettings(
    val styles: Map<BackWing, BackWingStyle> = DEFAULT_STYLES,
    val crouchOffset: WingVector = WingVector(0f, -0.20f, 0.12f),
    val crouchPitchDegrees: Float = 28.64789f,
    val equippedText: Component = message("<#BCE6A4>Крылья надеты"),
    val removedText: Component = message("<#E8DFD2>Крылья сняты"),
    val missingText: Component = message("<#F4BD6A>Крылья сняты: предмета нет в инвентаре"),
) {
    init {
        require(styles.keys == BackWing.entries.toSet()) { "Every supported wing needs a style" }
        require(crouchOffset.finite() && crouchPitchDegrees.isFinite()) { "Crouch pose must be finite" }
    }

    companion object {
        // NONE context uses the original centered meshes; hand/head display presets are not applied.
        val DEFAULT_STYLES = mapOf(
            BackWing.DREADKNIGHT to BackWingStyle(0.70f, WingVector(0f, 90f, 0f), WingVector(0f, 1.02f, -0.31f)),
            BackWing.GALAXY to BackWingStyle(0.90f, WingVector(0f, 90f, 0f), WingVector(0f, 1.02f, -0.25f)),
            BackWing.DECEASE to BackWingStyle(0.95f, WingVector(0f, 0f, 0f), WingVector(0f, 1.10f, -0.22f)),
        )

        private fun message(text: String): Component = MiniMessage.miniMessage().deserialize(text)
    }
}

internal class BackWingsConfig(private val source: Config) {
    fun snapshot(warn: (String) -> Unit): BackWingsSettings {
        val defaults = BackWingsSettings()
        val styles = BackWing.entries.associateWith { wing ->
            val fallback = defaults.styles.getValue(wing)
            val prefix = "models.${wing.configKey}"
            runCatching {
                BackWingStyle(
                    source.double("$prefix.scale", fallback.scale.toDouble()).toFloat(),
                    vector("$prefix.rotation", fallback.rotation),
                    vector("$prefix.local-anchor", fallback.anchor),
                )
            }.getOrElse {
                warn("Back wings: invalid $prefix (${it.message}); using the bundled pose")
                fallback
            }
        }
        val crouch = runCatching {
            val offset = vector("crouch.offset", defaults.crouchOffset)
            val pitch = source.double("crouch.pitch-degrees", defaults.crouchPitchDegrees.toDouble()).toFloat()
            require(offset.finite() && pitch.isFinite()) { "non-finite crouch pose" }
            offset to pitch
        }.getOrElse {
            warn("Back wings: invalid crouch pose; using the bundled pose")
            defaults.crouchOffset to defaults.crouchPitchDegrees
        }
        return BackWingsSettings(
            styles, crouch.first, crouch.second,
            text("equipped", "<#BCE6A4>Крылья надеты"),
            text("removed", "<#E8DFD2>Крылья сняты"),
            text("missing", "<#F4BD6A>Крылья сняты: предмета нет в инвентаре"),
        )
    }

    private fun vector(path: String, fallback: WingVector) = WingVector(
        source.double("$path.x", fallback.x.toDouble()).toFloat(),
        source.double("$path.y", fallback.y.toDouble()).toFloat(),
        source.double("$path.z", fallback.z.toDouble()).toFloat(),
    )

    private fun text(key: String, fallback: String): Component =
        MiniMessage.miniMessage().deserialize(source.string("text.$key", fallback))

    companion object {
        const val RESOURCE = "modules/back-wings.yml"
        fun load(dataPath: Path): BackWingsConfig = BackWingsConfig(
            ConfigManager.of(dataPath, RESOURCE).also { it.mergeMissingFromBundled(RESOURCE) },
        )
    }
}
