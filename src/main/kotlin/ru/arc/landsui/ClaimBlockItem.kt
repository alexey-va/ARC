package ru.arc.landsui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** ARC owns the item; Lands only handles the requested claim through its public API. */
internal object ClaimBlockItem {
    val markerKey = NamespacedKey("arc", "claim_block")
    val radiusKey = NamespacedKey("arc", "claim_block_radius")
    val ownerKey = NamespacedKey("arc", "claim_block_owner")
    private val miniMessage = MiniMessage.miniMessage()

    fun create(owner: UUID, settings: LandsUiSettings): ItemStack = ItemStack(Material.GRASS_BLOCK).also { stack ->
        stack.editMeta { meta ->
            meta.displayName(parsed(settings.text("claim-block-name")))
            meta.lore(listOf(
                parsed(settings.text("claim-block-size").replace("<size>", "1").replace("<chunks>", "1")),
                parsed(settings.text("claim-block-reusable")),
                parsed(settings.text("claim-block-shortcut")),
            ))
            meta.persistentDataContainer.set(markerKey, PersistentDataType.BYTE, 1)
            meta.persistentDataContainer.set(radiusKey, PersistentDataType.INTEGER, 0)
            meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, owner.toString())
        }
    }

    private fun parsed(value: String): Component =
        miniMessage.deserialize(value).decoration(TextDecoration.ITALIC, false)
}
