package ru.arc.homemarkers

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Color
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

internal data class HomeMarkerSettings(
    val refreshTicks: Long = 100,
    val viewDistance: Double = 32.0,
    val scale: Float = 1.6f,
    val labelScale: Float = 0.6f,
    val modelId: String = "",
    val homeColor: Color = Color.fromRGB(0x68D8FF),
    val warpColor: Color = Color.fromRGB(0xFFC14D),
    private val homeLabel: String = "Дом",
    private val warpLabel: String = "Варп",
    private val labelTemplate: String = "<kind> · <white><name>",
    val failureMessage: Component = MiniMessage.miniMessage().deserialize(
        "<#F4BD6A>Не удалось открыть настройки точки. Попробуйте ещё раз.",
    ),
) {
    init {
        require(refreshTicks in 20..1_200) { "Home markers refresh-ticks must be in 20..1200" }
        require(viewDistance.isFinite() && viewDistance in 8.0..64.0) { "Home markers view-distance must be in 8..64" }
        require(scale.isFinite() && scale in 0.75f..2.5f) { "Home markers scale must be in 0.75..2.5" }
        require(labelScale.isFinite() && labelScale in 0.3f..1f) { "Home markers label-scale must be in 0.3..1" }
        require(modelId.length <= 200) { "Home markers model-id is too long" }
        require(listOf(homeLabel, warpLabel, labelTemplate).all { it.isNotBlank() && it.length <= 500 }) {
            "Home marker text must be nonblank and at most 500 characters"
        }
    }

    fun color(kind: HomeMarkerKind): Color = if (kind == HomeMarkerKind.HOME) homeColor else warpColor

    fun label(marker: HomeMarker): Component = MiniMessage.miniMessage().deserialize(
        labelTemplate,
        Placeholder.component("kind", Component.text(
            if (marker.kind == HomeMarkerKind.HOME) homeLabel else warpLabel,
            TextColor.color(color(marker.kind).asRGB()),
        )),
        Placeholder.unparsed("name", marker.name),
    )
}

internal class HomeMarkerConfig(private val source: Config) {
    fun snapshot(): HomeMarkerSettings = HomeMarkerSettings(
        refreshTicks = source.int("refresh-ticks", 100).toLong(),
        viewDistance = source.double("view-distance", 32.0),
        scale = source.double("scale", 1.6).toFloat(),
        labelScale = source.double("label-scale", 0.6).toFloat(),
        modelId = source.string("model-id", "").trim(),
        homeColor = color("colors.home", "#68D8FF"),
        warpColor = color("colors.warp", "#FFC14D"),
        homeLabel = source.string("text.home", "Дом"),
        warpLabel = source.string("text.warp", "Варп"),
        labelTemplate = source.string("text.label", "<kind> · <white><name>"),
        failureMessage = MiniMessage.miniMessage().deserialize(source.string(
            "text.unavailable", "<#F4BD6A>Не удалось открыть настройки точки. Попробуйте ещё раз.",
        )),
    )

    private fun color(path: String, fallback: String): Color {
        val value = source.string(path, fallback)
        val rgb = TextColor.fromHexString(value)?.value()
        requireNotNull(rgb) { "Home markers $path must be a #RRGGBB color" }
        return Color.fromRGB(rgb)
    }

    companion object {
        private const val RESOURCE = "modules/home-markers.yml"

        fun load(dataPath: Path): HomeMarkerConfig {
            val source = ConfigManager.of(dataPath, RESOURCE)
            source.mergeMissingFromBundled(RESOURCE)
            return HomeMarkerConfig(source)
        }
    }
}
