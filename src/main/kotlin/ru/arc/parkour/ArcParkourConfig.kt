package ru.arc.parkour

import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.util.ConfigItemSpec
import java.nio.file.Path
import java.util.Locale

data class ArcParkourSettings(
    val enabled: Boolean,
    val interceptJoinAllCommand: Boolean,
    val interceptLegacyMenu: Boolean,
    val joinAllPermission: String,
    val legacyMenuTitle: String,
    val hudEnabled: Boolean,
    val fadeInTicks: Int,
    val stayTicks: Int,
    val fadeOutTicks: Int,
    val unavailableMessage: String,
    val noReadyCoursesMessage: String,
    val gui: Config,
    val categories: List<ParkourCategoryDefinition>,
    val background: ConfigItemSpec,
    val close: ConfigItemSpec,
    val back: ConfigItemSpec,
    val guide: ConfigItemSpec,
)

data class ParkourCategoryDefinition(
    val id: String,
    val order: Int,
    val prefixes: List<String>,
    val name: String,
    val courseName: String,
    val courseDisplay: String,
    val icon: Material,
    val courseIcon: Material,
    val display: String,
    val description: List<String>,
)

class ArcParkourConfig(
    private val module: Config,
    private val gui: Config,
) {
    fun snapshot(): ArcParkourSettings {
        val categories =
            gui.keys("categories").map { rawId ->
                val id = rawId.trim().lowercase(Locale.ROOT)
                require(id == rawId && validId(id)) { "Parkour category id '$rawId' must be normalized" }
                val root = "categories.$id"
                val prefixes = gui.stringList("$root.prefixes").map { it.trim().lowercase(Locale.ROOT) }
                require(prefixes.isNotEmpty() && prefixes.size <= 8) {
                    "Parkour category '$id' must declare 1..8 prefixes"
                }
                require(prefixes.all(::validId)) { "Parkour category '$id' has an invalid prefix" }
                ParkourCategoryDefinition(
                    id = id,
                    order = gui.integer("$root.order", 100),
                    prefixes = prefixes,
                    name = bounded(gui.string("$root.name", id), 32, "category '$id' name"),
                    courseName = bounded(gui.string("$root.course-name", "Трасса <number>"), 64, "category '$id' course name"),
                    courseDisplay = bounded(gui.string("$root.course-display", "<#92bed8><bold><course>"), 96, "category '$id' course display"),
                    icon = material("$root.material", Material.LEATHER_BOOTS),
                    courseIcon = material("$root.course-material", Material.STONE_PRESSURE_PLATE),
                    display = bounded(gui.string("$root.display", id), 96, "category '$id' display"),
                    description = boundedLines(gui.stringList("$root.description"), 4, "category '$id' description"),
                )
            }.sortedWith(compareBy<ParkourCategoryDefinition> { it.order }.thenBy { it.id })
        require(categories.size in 1..7) { "Parkour GUI must declare 1..7 categories" }
        val prefixes = categories.flatMap(ParkourCategoryDefinition::prefixes)
        require(prefixes.distinct().size == prefixes.size) { "Parkour category prefixes must be unique" }

        return ArcParkourSettings(
            enabled = module.bool("enabled", false),
            interceptJoinAllCommand = module.bool("integration.intercept-joinall-command", true),
            interceptLegacyMenu = module.bool("integration.intercept-legacy-menu", true),
            joinAllPermission = permission(module.string("integration.joinall-permission", "parkour.basic.joinall")),
            legacyMenuTitle = bounded(module.string("integration.legacy-menu-title", "Трассы паркура"), 64, "legacy menu title"),
            hudEnabled = module.bool("hud.enabled", true),
            fadeInTicks = ticks("hud.fade-in-ticks", 5),
            stayTicks = ticks("hud.stay-ticks", 35),
            fadeOutTicks = ticks("hud.fade-out-ticks", 10),
            unavailableMessage = bounded(module.string("messages.unavailable", "<#c42323>Каталог трасс сейчас недоступен."), 180, "unavailable message"),
            noReadyCoursesMessage = bounded(module.string("messages.no-ready-courses", "<#ff9f0f>Сейчас нет готовых трасс в этой категории."), 180, "empty category message"),
            gui = gui,
            categories = categories,
            background = item("background", Material.GRAY_STAINED_GLASS_PANE),
            close = item("close", Material.BARRIER),
            back = item("back", Material.BLUE_STAINED_GLASS_PANE),
            guide = item("guide", Material.BOOK),
        )
    }

    private fun item(path: String, fallback: Material): ConfigItemSpec {
        val spec = ConfigItemSpec.readFromConfig(gui, path) ?: ConfigItemSpec(material = fallback)
        require((spec.modelData ?: 0) >= 0) { "Parkour GUI item '$path' custom model data cannot be negative" }
        return spec.copy(material = spec.material ?: fallback)
    }

    private fun material(path: String, fallback: Material): Material {
        val raw = gui.string(path, fallback.name).trim()
        return Material.matchMaterial(raw) ?: throw IllegalArgumentException("Parkour material '$path' is invalid: '$raw'")
    }

    private fun ticks(path: String, fallback: Int): Int =
        module.integer(path, fallback).also { require(it in 0..200) { "Parkour '$path' must be between 0 and 200 ticks" } }

    private fun permission(value: String): String =
        value.trim().also {
            require(it.length in 1..160 && it.all { character -> character.isLetterOrDigit() || character in "._-*" }) {
                "Parkour joinall permission is invalid"
            }
        }

    private fun bounded(value: String, limit: Int, field: String): String =
        value.trim().also {
            require(it.isNotEmpty()) { "Parkour $field cannot be blank" }
            require(it.codePointCount(0, it.length) <= limit) { "Parkour $field is too long" }
        }

    private fun boundedLines(lines: List<String>, limit: Int, field: String): List<String> {
        require(lines.size in 1..limit) { "Parkour $field must contain 1..$limit lines" }
        return lines.map { bounded(it, 120, field) }
    }

    private fun validId(value: String): Boolean =
        value.length in 1..32 && value.all { it.isLetterOrDigit() || it in "_-" }

    companion object {
        fun load(dataPath: Path): ArcParkourConfig =
            ArcParkourConfig(
                ConfigManager.ofModule(dataPath, "parkour.yml"),
                ConfigManager.of(dataPath, "guis/parkour.yml"),
            )
    }
}
