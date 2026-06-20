package ru.arc.misc

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.gui.gui
import ru.arc.hooks.HookRegistry
import ru.arc.util.GuiUtils
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.TextUtil

/**
 * GUI for selecting join/leave messages.
 */
object JoinMessageGuiFactory {
    private val config: Config by lazy {
        ConfigManager.of(ARC.instance.dataFolder.toPath(), "misc.yml")
    }

    /**
     * Parsed message item ready for display.
     */
    private data class MessageItem(
        val displayName: String,
        val message: String,
        val permission: String?,
        val rank: String,
        val material: Material,
        val isCurrent: Boolean,
        val parsedMessage: String,
        val lore: List<String>,
    )

    /**
     * Configuration for message parsing.
     */
    private data class MessageConfig(
        val defaultLore: List<String>,
        val forbiddenLore: List<String>,
        val currentLore: List<String>,
        val selectedMaterial: Material,
        val showAll: Boolean,
        val maxLen: Int,
        val spacesPadding: Int,
        val messagePrefix: String,
        val defaultDisplayName: String,
        val commonRank: String,
    )

    /**
     * Create the join/leave message selection GUI.
     */
    fun create(
        player: Player,
        isJoin: Boolean,
        startPage: Int = 0,
    ): ChestGui {
        val cfg = config
        val prefix = if (isJoin) "join-message-gui." else "leave-message-gui."
        val rows = cfg.int("join-message-gui.rows", 6)
        val title = cfg.string("${prefix}title", if (isJoin) "&8Сообщения при входе" else "&8Сообщения при выходе")

        val messageItems = parseMessageItems(cfg, prefix, player, isJoin)

        return gui(title, rows, player, cfg) {
            // Background for nav bar
            navBackground()

            // Message items
            pagination(0 until (rows - 1)) {
                items(messageItems) { item ->
                    material(item.material)
                    display(item.displayName)
                    lore(item.lore)
                    flags(
                        org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                        org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS,
                    )

                    onClick { event ->
                        val clicker = event.whoClicked as? Player ?: return@onClick

                        // Check permission
                        if (item.permission != null && !clicker.hasPermission(item.permission)) {
                            clicker.sendMessage(
                                cfg.component(
                                    "${prefix}forbidden-temp-display",
                                    "<dark_red>Вы не можете использовать это сообщение!",
                                ),
                            )
                            return@onClick
                        }

                        if (HookRegistry.luckPermsHook == null) {
                            error("LuckPerms hook is not available")
                            return@onClick
                        }

                        val currentPage = 0 // Will need pane reference for real page

                        if (item.isCurrent) {
                            // Remove message
                            if (isJoin) {
                                JoinMessagesManager.removeJoinMessageBlocking(clicker.name, item.message)
                            } else {
                                JoinMessagesManager.removeLeaveMessageBlocking(clicker.name, item.message)
                            }
                        } else {
                            // Add message
                            if (isJoin) {
                                JoinMessagesManager.addJoinMessageBlocking(clicker.name, item.message)
                            } else {
                                JoinMessagesManager.addLeaveMessageBlocking(clicker.name, item.message)
                            }
                        }

                        GuiUtils.constructAndShowAsync({ create(clicker, isJoin, currentPage) }, clicker)
                    }
                }
            }

            // Navigation bar
            navBar {
                // Back button
                back(
                    slot = 0,
                    configKey = "join-message-gui.back-button",
                    material = cfg.material("join-message-gui.back-button", Material.BLUE_STAINED_GLASS_PANE),
                    modelData = cfg.int("join-message-gui.back-button-model-data", 11013),
                )

                // Previous page
                button(3) {
                    material(cfg.material("join-message-gui.prev-button", Material.BLUE_STAINED_GLASS_PANE))
                    modelData(cfg.int("join-message-gui.prev-button-model-data", 11013))
                    display(cfg.string("join-message-gui.prev-button-display", "<gold>Назад"))
                    lore(cfg.list("join-message-gui.prev-button-lore", listOf("<gray>Перейти к предыдущей странице")))
                    onClick { /* Handled by pagination */ }
                }

                // Switch join/leave mode
                button(4) {
                    material(cfg.material("${prefix}switch-button", Material.LIME_STAINED_GLASS_PANE))
                    modelData(cfg.int("${prefix}switch-button-model-data", 0))
                    display(cfg.string("${prefix}switch-button-display", "<gold>Сменить режим"))
                    lore(cfg.list("${prefix}switch-button-lore"))

                    onClick { event ->
                        val clicker = event.whoClicked as? Player ?: return@onClick
                        GuiUtils.constructAndShowAsync({ create(clicker, !isJoin, 0) }, clicker)
                    }
                }

                // Next page
                button(5) {
                    material(cfg.material("join-message-gui.next-button", Material.BLUE_STAINED_GLASS_PANE))
                    modelData(cfg.int("join-message-gui.next-button-model-data", 11013))
                    display(cfg.string("join-message-gui.next-button-display", "<gold>Далее"))
                    lore(cfg.list("join-message-gui.next-button-lore", listOf("<gray>Перейти к следующей странице")))
                    onClick { /* Handled by pagination */ }
                }
            }
        }.also { gui ->
            // Set initial page if needed
            // Note: This requires access to the pagination pane which our DSL doesn't expose yet
        }
    }

    /**
     * Show the GUI to a player.
     */
    fun show(
        player: Player,
        isJoin: Boolean = true,
        startPage: Int = 0,
    ) {
        GuiUtils.constructAndShowAsync({ create(player, isJoin, startPage) }, player)
    }

    // ==================== Message Parsing ====================

    /**
     * Parse all message items from config.
     */
    private fun parseMessageItems(
        cfg: Config,
        prefix: String,
        player: Player,
        isJoin: Boolean,
    ): List<MessageItem> {
        val msgConfig = loadMessageConfig(cfg, prefix)
        val currentMessages = getCurrentMessages(player, isJoin)
        val unseenMessages = currentMessages.toMutableSet()
        val messages = cfg.list<Map<String, Any>>("${prefix}messages")

        val items = mutableListOf<MessageItem>()
        var id = 1

        for (map in messages) {
            parseMessageItem(map, id, player, msgConfig, cfg, prefix, currentMessages)?.let { item ->
                unseenMessages.remove(item.message)
                items.add(item)
            }
            id++
        }

        cleanupUnseenMessages(player, isJoin, unseenMessages)
        return items
    }

    /**
     * Load message display configuration.
     */
    private fun loadMessageConfig(
        cfg: Config,
        prefix: String,
    ): MessageConfig =
        MessageConfig(
            defaultLore =
                cfg.list(
                    "${prefix}default-lore",
                    listOf(
                        "<dark_gray> > <gray>Привелегия: <gold>%rank%",
                        "<white>%prefix%%message%",
                    ),
                ),
            forbiddenLore =
                cfg.list(
                    "${prefix}forbidden-lore",
                    listOf(
                        "<red>Это вам пока недоступно!",
                        "",
                    ),
                ),
            currentLore =
                cfg.list(
                    "${prefix}current-lore",
                    listOf(
                        "<green>Это ваше текущее сообщение",
                        "",
                    ),
                ),
            selectedMaterial = cfg.material("${prefix}selected-material", Material.ENDER_PEARL),
            showAll = cfg.bool("${prefix}show-all", true),
            maxLen = cfg.int("${prefix}max-len", 60),
            spacesPadding = cfg.int("${prefix}spaces-padding", 3),
            messagePrefix = cfg.string("${prefix}prefix", "<dark_green>❖ "),
            defaultDisplayName = cfg.string("${prefix}default-display-name", "<gold>Сообщение %id%"),
            commonRank = cfg.string("${prefix}common-rank", "<green>Для всех"),
        )

    /**
     * Get current messages for player.
     */
    private fun getCurrentMessages(
        player: Player,
        isJoin: Boolean,
    ): Set<String> {
        val joinData = JoinMessagesManager.getOrCreateBlocking(player.name)
        return if (isJoin) joinData.joinMessages else joinData.leaveMessages
    }

    /**
     * Parse a single message item from config map.
     */
    private fun parseMessageItem(
        map: Map<String, Any>,
        id: Int,
        player: Player,
        msgConfig: MessageConfig,
        cfg: Config,
        prefix: String,
        currentMessages: Set<String>,
    ): MessageItem? {
        return try {
            val displayName =
                (map["display-name"] as? String ?: msgConfig.defaultDisplayName)
                    .replace("%id%", id.toString())
            val message = map["message"] as? String ?: return null
            val permission = map["permission"] as? String
            val rank = map["rank"] as? String ?: msgConfig.commonRank
            val material = Material.valueOf((map["material"] as? String ?: "PAPER").uppercase())

            // Skip if no permission and not showing all
            if (!msgConfig.showAll && permission != null && !player.hasPermission(permission)) {
                return null
            }

            val isCurrent = message in currentMessages
            val parsedMessage = HookRegistry.papiHook?.parse(message, player) ?: message
            val lore = buildMessageLore(permission, player, isCurrent, msgConfig, parsedMessage, rank)

            MessageItem(
                displayName = displayName,
                message = message,
                permission = permission,
                rank = rank,
                material = if (isCurrent) msgConfig.selectedMaterial else material,
                isCurrent = isCurrent,
                parsedMessage = parsedMessage,
                lore = lore,
            )
        } catch (e: Exception) {
            error("Error while parsing message: {}", map, e)
            null
        }
    }

    /**
     * Build lore for message item.
     */
    private fun buildMessageLore(
        permission: String?,
        player: Player,
        isCurrent: Boolean,
        msgConfig: MessageConfig,
        parsedMessage: String,
        rank: String,
    ): List<String> {
        val loreLines =
            buildList {
                if (permission != null && !player.hasPermission(permission)) {
                    addAll(msgConfig.forbiddenLore)
                } else if (isCurrent) {
                    addAll(msgConfig.currentLore)
                }
                addAll(msgConfig.defaultLore)
            }.map { line ->
                line
                    .replace("%message%", parsedMessage)
                    .replace("%rank%", rank)
                    .replace("%prefix%", msgConfig.messagePrefix)
            }

        return if (loreLines.isNotEmpty()) {
            val last = loreLines.last()
            loreLines.dropLast(1) + TextUtil.splitLoreString(last, msgConfig.maxLen, msgConfig.spacesPadding)
        } else {
            loreLines
        }
    }

    /**
     * Clean up messages that no longer exist in config.
     */
    private fun cleanupUnseenMessages(
        player: Player,
        isJoin: Boolean,
        unseenMessages: Set<String>,
    ) {
        if (unseenMessages.isEmpty()) return

        info("Player {} has unseen messages: {}", player.name, unseenMessages)
        unseenMessages.forEach { msg ->
            if (isJoin) {
                JoinMessagesManager.removeJoinMessageBlocking(player.name, msg)
            } else {
                JoinMessagesManager.removeLeaveMessageBlocking(player.name, msg)
            }
        }
    }
}
