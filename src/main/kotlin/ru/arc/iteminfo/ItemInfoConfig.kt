package ru.arc.iteminfo

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

internal data class ItemInfoSettings(
    val enabled: Boolean,
    val targetDistance: Double,
    val hologramTemplate: String,
    val bossbarTemplate: String,
) {
    private val miniMessage = MiniMessage.miniMessage()

    fun hologramText(target: ItemInfoTarget): Component = render(hologramTemplate, target)

    fun bossbarText(target: ItemInfoTarget): Component = render(bossbarTemplate, target)

    private fun render(template: String, target: ItemInfoTarget): Component = miniMessage.deserialize(
        template,
        Placeholder.component("name", target.name),
        Placeholder.unparsed("id", target.namespacedId),
    )
}

internal class ItemInfoConfig(private val source: Config) {
    fun snapshot(): ItemInfoSettings {
        val distance = source.double("target-distance", 5.0)
        require(distance in 1.0..8.0) { "Item info target-distance must be in 1.0..8.0" }
        return ItemInfoSettings(
            enabled = source.bool("enabled", true),
            targetDistance = distance,
            hologramTemplate = required("text.hologram", "<white><name><newline><gray><id>"),
            bossbarTemplate = required("text.bossbar", "<white><name> <dark_gray>· <gray><id>"),
        )
    }

    private fun required(path: String, fallback: String): String = source.string(path, fallback).also {
        require(it.isNotBlank()) { "Item info value '$path' cannot be blank" }
        require(it.length <= 500) { "Item info value '$path' is too long" }
    }

    companion object {
        private const val RESOURCE = "item-info.yml"

        fun load(dataPath: Path): ItemInfoConfig {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            source.mergeMissingFromBundled("modules/$RESOURCE")
            return ItemInfoConfig(source)
        }
    }
}
