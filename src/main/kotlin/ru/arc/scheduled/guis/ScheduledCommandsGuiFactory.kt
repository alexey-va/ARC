package ru.arc.scheduled.guis

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.gui
import ru.arc.scheduled.ScheduledCommandEntry
import ru.arc.scheduled.ScheduledCommandsManager
import ru.arc.util.GuiUtils
import ru.arc.util.fromConfig
import ru.arc.util.guiItem

object ScheduledCommandsGuiFactory {
    private fun guiConfig(): Config = ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/scheduled-commands.yml")

    fun openList(player: Player) {
        GuiUtils.constructAndShowAsync({ buildListGui(player) }, player)
    }

    fun openEditor(
        player: Player,
        entryId: String,
    ) {
        val entry = ScheduledCommandsManager.settings().entry(entryId) ?: return
        GuiUtils.constructAndShowAsync({ EditScheduledCommandGui(player, entry) }, player)
    }

    fun buildListGui(player: Player): ChestGui {
        val cfg = guiConfig()
        val settings = ScheduledCommandsManager.settings()
        val entries = settings.entries()
        val title = settings.guiTitle

        return gui(title, 6, player, cfg) {
            contentBackground(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
            navBackground()

            pagination(0 until 5) {
                items(entries) { entry ->
                    stack(entryListItem(cfg, entry))
                    onClick {
                        it.isCancelled = true
                        openEditor(player, entry.id)
                    }
                }
            }

            navBar {
                button(4) {
                    material(Material.CLOCK)
                    display("<yellow>Обновить")
                    lore(listOf("<gray>Нажмите для обновления списка"))
                    fromConfig(cfg, "list-menu.refresh")
                    onClick {
                        it.isCancelled = true
                        openList(player)
                    }
                }
            }
        }
    }

    private fun entryListItem(
        cfg: Config,
        entry: ScheduledCommandEntry,
    ): org.bukkit.inventory.ItemStack {
        val material =
            if (entry.enabled) {
                Material.LIME_CANDLE
            } else {
                Material.GRAY_CANDLE
            }
        val resolver =
            TagResolver
                .builder()
                .resolver(
                    TagResolver.resolver(
                        "id",
                        Tag.inserting(
                            net.kyori.adventure.text.Component
                                .text(entry.id),
                        ),
                    ),
                ).resolver(
                    TagResolver.resolver(
                        "schedule",
                        Tag.inserting(
                            net.kyori.adventure.text.Component
                                .text(entry.schedule.describe()),
                        ),
                    ),
                ).resolver(
                    TagResolver.resolver(
                        "command",
                        Tag.inserting(
                            net.kyori.adventure.text.Component
                                .text(entry.command),
                        ),
                    ),
                ).resolver(
                    TagResolver.resolver(
                        "servers",
                        Tag.inserting(
                            net.kyori.adventure.text.Component
                                .text(entry.serversLabel()),
                        ),
                    ),
                ).build()

        return guiItem(material) {
            flags(ItemFlag.HIDE_ATTRIBUTES)
            tagResolver(resolver)
            display(
                if (entry.enabled) {
                    "<green><id>"
                } else {
                    "<red><id> <gray>(выкл.)"
                },
            )
            lore(
                listOf(
                    "<gray><schedule>",
                    "<dark_gray><command>",
                    "<gray>Серверы: <white><servers>",
                    "",
                    "<yellow>Нажмите для редактирования",
                ),
            )
            fromConfig(cfg, "list-menu.entry")
        }.item
    }
}
