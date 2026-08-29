package ru.arc.misc

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.sync
import ru.arc.gui.gui
import ru.arc.hooks.HookRegistry
import ru.arc.util.GuiUtils
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import ru.arc.util.fromConfig

/** GUI for selecting the network-wide join and leave phrases published by ProxyARC. */
object JoinMessageGuiFactory {
    private val config: Config by lazy {
        ConfigManager.of(ARC.instance.dataFolder.toPath(), "modules/misc.yml")
    }

    private data class MessageItem(
        val displayName: String,
        val message: String,
        val permission: String?,
        val material: Material,
        val customModelData: Int,
        val isCurrent: Boolean,
        val lore: List<String>,
    )

    private data class MessageConfig(
        val defaultLore: List<String>,
        val forbiddenLore: List<String>,
        val currentLore: List<String>,
        val showAll: Boolean,
        val maxLen: Int,
        val spacesPadding: Int,
        val messagePrefix: String,
        val commonRank: String,
    )

    private fun create(
        player: Player,
        isJoin: Boolean,
        currentMessages: Set<String>,
        catalog: JoinMessageCatalog,
        startPage: Int,
    ): ChestGui {
        val cfg = config
        val prefix = if (isJoin) "join-message-gui." else "leave-message-gui."
        val rows = cfg.int("join-message-gui.rows", 6).coerceIn(2, 6)
        val title = cfg.string("${prefix}title", if (isJoin) "&8Сообщения при входе" else "&8Сообщения при выходе")
        val messageItems = parseMessageItems(catalog.entries(isJoin), player, isJoin, currentMessages, prefix)
        lateinit var pages: PaginatedPane

        return gui(title, rows, player, cfg) {
            contentBackground()
            navBackground()

            pagination(0 until (rows - 1)) {
                items(messageItems) { item ->
                    material(item.material)
                    if (item.customModelData > 0) modelData(item.customModelData)
                    display(nonItalic(item.displayName))
                    lore(item.lore.map(::nonItalic))
                    flags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)

                    onClick { event ->
                        val clicker = event.whoClicked as? Player ?: return@onClick
                        if (item.permission != null && !clicker.hasPermission(item.permission)) {
                            clicker.sendMessage(
                                cfg.component(
                                    "${prefix}forbidden-temp-display",
                                    "<red>Эта фраза пока недоступна.",
                                ),
                            )
                            return@onClick
                        }

                        val currentPage = pages.page
                        JoinMessagesManager
                            .updateMessageAsync(
                                player = clicker.name,
                                message = item.message,
                                isJoin = isJoin,
                                selected = !item.isCurrent,
                            ).whenComplete { _, failure ->
                                if (failure != null) {
                                    reportFailure(clicker, "update message", failure)
                                } else {
                                    show(clicker, isJoin, currentPage)
                                }
                            }
                    }
                }
            }
            pages = checkNotNull(paginatedContentPane())

            navBar {
                back(slot = 0, configKey = "join-message-gui.back-button")
                prevPage(slot = 3, configKey = "join-message-gui.prev-button")
                button(4) {
                    display("<italic:false><gold>Сменить режим")
                    lore(emptyList())
                    fromConfig(cfg, "${prefix}switch-button")
                    onClick { event ->
                        val clicker = event.whoClicked as? Player ?: return@onClick
                        show(clicker, !isJoin, 0)
                    }
                }
                nextPage(slot = 5, configKey = "join-message-gui.next-button")
            }

            onBuild {
                pages.page = startPage.coerceIn(0, (pages.pages - 1).coerceAtLeast(0))
            }
        }
    }

    fun show(
        player: Player,
        isJoin: Boolean = true,
        startPage: Int = 0,
    ) {
        JoinMessageCatalogManager.currentAsync()
            .thenCombine(JoinMessagesManager.getOrCreateAsync(player.name)) { catalog, messages ->
                catalog to messages
            }.whenComplete { result, failure ->
                if (failure != null || result == null) {
                    reportFailure(player, "load synchronized message catalog", failure ?: IllegalStateException("Missing catalog"))
                    return@whenComplete
                }
                val (catalog, data) = result
                val currentMessages = if (isJoin) data.joinMessages.toSet() else data.leaveMessages.toSet()
                GuiUtils.constructAndShowAsync(
                    { create(player, isJoin, currentMessages, catalog, startPage) },
                    player,
                )
            }
    }

    private fun parseMessageItems(
        entries: List<JoinMessageCatalogEntry>,
        player: Player,
        isJoin: Boolean,
        currentMessages: Set<String>,
        prefix: String,
    ): List<MessageItem> {
        val msgConfig = loadMessageConfig(prefix)
        val unseenMessages = currentMessages.toMutableSet()
        val items =
            entries.mapNotNull { entry ->
                val permission = entry.permission
                if (!msgConfig.showAll && permission != null && !player.hasPermission(permission)) {
                    return@mapNotNull null
                }
                unseenMessages.remove(entry.message)
                val isCurrent = entry.message in currentMessages
                val parsedMessage = HookRegistry.papiHook?.parse(entry.message, player) ?: entry.message
                val material = JoinMessageMaterial.resolve(entry.material)
                if (material == Material.PAPER && entry.material != "PAPER") {
                    warn("Unknown material '{}' for join message '{}'; using PAPER", entry.material, entry.id)
                }
                MessageItem(
                    displayName = entry.displayName,
                    message = entry.message,
                    permission = permission,
                    material = material,
                    customModelData = entry.customModelData,
                    isCurrent = isCurrent,
                    lore = buildMessageLore(permission, player, isCurrent, msgConfig, parsedMessage, entry.rank),
                )
            }

        cleanupUnseenMessages(player, isJoin, unseenMessages)
        return items
    }

    private fun loadMessageConfig(prefix: String): MessageConfig =
        MessageConfig(
            defaultLore = config.list("${prefix}default-lore", listOf("<white>%prefix%%message%")),
            forbiddenLore = config.list("${prefix}forbidden-lore", listOf("<red>Эта фраза пока недоступна.")),
            currentLore = config.list("${prefix}current-lore", listOf("<green>Выбрано")),
            showAll = config.bool("${prefix}show-all", true),
            maxLen = config.int("${prefix}max-len", 80),
            spacesPadding = config.int("${prefix}spaces-padding", 3),
            messagePrefix = config.string("${prefix}prefix", if (prefix.startsWith("join")) "<dark_green>❖ " else "<dark_red>❖ "),
            commonRank = config.string("${prefix}common-rank", "<green>Для всех"),
        )

    private fun buildMessageLore(
        permission: String?,
        player: Player,
        isCurrent: Boolean,
        msgConfig: MessageConfig,
        parsedMessage: String,
        configuredRank: String,
    ): List<String> {
        val rank = configuredRank.ifBlank { msgConfig.commonRank }
        val loreLines =
            buildList {
                if (permission != null && !player.hasPermission(permission)) {
                    addAll(msgConfig.forbiddenLore)
                } else if (isCurrent) {
                    addAll(msgConfig.currentLore)
                }
                addAll(msgConfig.defaultLore)
            }.map { line ->
                line.replace("%message%", parsedMessage)
                    .replace("%rank%", rank)
                    .replace("%prefix%", msgConfig.messagePrefix)
            }

        if (loreLines.isEmpty()) return emptyList()
        val last = loreLines.last()
        return loreLines.dropLast(1) + TextUtil.splitLoreString(last, msgConfig.maxLen, msgConfig.spacesPadding)
    }

    private fun cleanupUnseenMessages(
        player: Player,
        isJoin: Boolean,
        unseenMessages: Set<String>,
    ) {
        if (unseenMessages.isEmpty()) return
        info("Player {} has selected phrases missing from catalog: {}", player.name, unseenMessages)
        JoinMessagesManager.removeMessagesAsync(player.name, unseenMessages, isJoin).whenComplete { _, failure ->
            if (failure != null) reportFailure(player, "remove unavailable messages", failure)
        }
    }

    private fun reportFailure(
        player: Player,
        operation: String,
        failure: Throwable,
    ) {
        error("Failed to {} for player {}", operation, player.name, failure)
        sync {
            player.sendMessage(
                config.component(
                    "join-message-gui.error",
                    "<red>Меню фраз сейчас недоступно. Попробуйте ещё раз.",
                ),
            )
        }
    }

    internal fun nonItalic(value: String): String =
        if (value.startsWith("<italic:")) value else "<italic:false>$value"
}
