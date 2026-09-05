package ru.arc.misc

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.menu.MenuElementId
import ru.arc.paper.menu.PaperCloudStorage
import ru.arc.paper.menu.PaperCloudStorageButton
import ru.arc.paper.menu.PaperCloudStorageContent
import ru.arc.paper.menu.PaperCloudStorageFailure
import ru.arc.store.StoreData
import ru.arc.store.StoreManager
import kotlin.math.ceil

/** Store rules and persistence adapter for the shared cloud-chest UI. */
object StoreGuiFactory {
    fun open(player: Player, store: StoreData) {
        val config = ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/store.yml")
        val menu = storeMenuForSize(store.size)
        ArcMenus.openStorage(
            player, menu, ArcMenuSchema.STORE_ITEMS,
            object : PaperCloudStorage {
                override fun snapshot(): List<ItemStack?> = store.getSlots()
                override fun compareAndSet(expected: List<ItemStack?>, replacement: List<ItemStack?>): Boolean {
                    if (!store.compareAndSetSlots(expected, replacement)) return false
                    StoreManager.saveLater(store)
                    return true
                }
            },
            PaperCloudStorageContent(
                title = config.component("store.title", "<dark_gray>Хранилище"),
                background = ArcMenus.background(menu),
                buttons = mapOf(MenuElementId.of("back") to PaperCloudStorageButton(ArcMenus.item(menu, "back")) {
                    it.closeInventory()
                    it.performCommand(config.string("store.back-command"))
                }),
                onFailure = { viewer, reason ->
                    val message = when (reason) {
                        PaperCloudStorageFailure.FULL -> config.component("store.no-space.display", "<red>Нет места!")
                        PaperCloudStorageFailure.STALE -> config.component("store.item-is-gone.display", "<red>Предмет исчез!")
                        PaperCloudStorageFailure.REJECTED -> config.component("store.transfer-rejected.display", "<red>Этот предмет нельзя поместить в хранилище.")
                    }
                    viewer.sendMessage(message)
                },
            ),
        )
    }
}

internal fun storeMenuForSize(storeSize: Int) =
    checkNotNull(ArcMenuSchema.STORE[(ceil(storeSize.coerceAtLeast(0).toDouble() / 9).toInt() + 1).coerceIn(2, 6)])
