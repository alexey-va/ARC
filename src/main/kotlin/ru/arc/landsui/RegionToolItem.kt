package ru.arc.landsui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

internal object RegionToolItem {
    private val marker = NamespacedKey("arc", "region_tool")
    private val miniMessage = MiniMessage.miniMessage()

    fun create(settings: LandsUiSettings): ItemStack = ItemStack(Material.DEBUG_STICK).also { stack ->
        stack.editMeta { meta ->
            meta.displayName(parsed(settings.text("region-tool-name")))
            meta.lore(
                listOf(
                    "region-tool-lore-first",
                    "region-tool-lore-second",
                    "region-tool-lore-menu",
                    "region-tool-lore-reset",
                ).map { key -> parsed(settings.text(key)) },
            )
            meta.persistentDataContainer.set(marker, PersistentDataType.BYTE, 1)
        }
    }

    fun matches(stack: ItemStack?): Boolean = stack?.let {
        it.type == Material.DEBUG_STICK &&
            it.itemMeta?.persistentDataContainer?.get(marker, PersistentDataType.BYTE) == 1.toByte()
    } == true

    private fun parsed(value: String): Component =
        miniMessage.deserialize(value).decoration(TextDecoration.ITALIC, false)
}
