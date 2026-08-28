package ru.arc.autobuild.gui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.util.TextUtil
import ru.arc.util.itemComponents

/** Player-facing item states used by the active construction GUI. */
internal data class BuildingGuiItemState(
    val displayName: Component?,
    val lore: List<Component>,
) {
    fun applyTo(item: ItemStack) {
        item.editMeta { meta ->
            displayName?.let(meta::displayName)
            meta.lore(lore)
        }
    }
}

/**
 * Resolves complete GUI states from config so temporary feedback never loses lore or styling.
 */
internal object BuildingGuiPresentation {
    fun item(
        config: Config,
        path: String,
        resolver: TagResolver? = null,
    ): BuildingGuiItemState {
        val (displayName, lore) = config.itemComponents(path, resolver)
        return BuildingGuiItemState(
            displayName = TextUtil.strip(displayName),
            lore = lore.mapNotNull(TextUtil::strip),
        )
    }

    fun progress(config: Config, percentage: Int): BuildingGuiItemState =
        item(
            config,
            "building-gui.progress",
            TagResolver.resolver(
                "progress",
                Tag.inserting(Component.text(percentage.coerceIn(0, 100))),
            ),
        )
}
