package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import ru.arc.ARC
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.configs.ConfigManager
import ru.arc.misc.StoreGui
import ru.arc.store.StoreManager
import ru.arc.util.GuiUtils
import ru.arc.xserver.playerlist.PlayerManager

/**
 * /arc store - просмотр хранилища игрока.
 *
 * Использование:
 * - /arc store - открыть своё хранилище
 * - /arc store <player> - открыть хранилище другого игрока
 * - /arc store dump - вывести содержимое в консоль
 */
object StoreSubCommand : SubCommand {

    override val configKey = "store"
    override val defaultName = "store"
    override val defaultPermission = "arc.store"
    override val defaultDescription = "Просмотр хранилища"
    override val defaultUsage = "/arc store [player|dump]"
    override val defaultPlayerOnly = true

    private val config get() = ConfigManager.of(ARC.plugin.dataFolder.toPath().resolve("store"), "store.yml")

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true

        // /arc store dump
        if (args.size == 1 && args[0].equals("dump", ignoreCase = true)) {
            StoreManager.getStore(player.uniqueId).thenAccept { store ->
                println("Dumping store for ${player.name}")
                store.itemList.forEach { println(it) }
            }
            player.sendMessage(CommandConfig.get("store.dumped", "<gray>Содержимое хранилища выведено в консоль."))
            return true
        }

        // Определяем целевого игрока
        val targetName = args.getOrNull(0) ?: player.name
        val self = targetName.equals(player.name, ignoreCase = true)

        // Проверка прав на просмотр чужого хранилища
        if (!self && !player.hasPermission("arc.store.others")) {
            sender.sendMessage(CommandConfig.noPermission())
            return true
        }

        // Получаем UUID
        val uuid = if (self) {
            player.uniqueId
        } else {
            // Поддержка UUID напрямую
            if (targetName.length > 30) {
                try {
                    java.util.UUID.fromString(targetName)
                } catch (e: Exception) {
                    sender.sendMessage(
                        CommandConfig.get(
                            "store.invalid-uuid",
                            "<red>Неверный UUID: <white>%uuid%",
                            "%uuid%",
                            targetName
                        )
                    )
                    return true
                }
            } else {
                val offlinePlayer = Bukkit.getOfflinePlayer(targetName)
                if (!offlinePlayer.hasPlayedBefore()) {
                    sender.sendMessage(CommandConfig.playerNotFound(targetName))
                    return true
                }
                offlinePlayer.uniqueId
            }
        }

        // Открываем GUI
        StoreManager.getStore(uuid).thenAccept { store ->
            GuiUtils.constructAndShowAsync({ StoreGui(config, player, store) }, player, 0)
        }

        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> {
                val suggestions = mutableListOf("dump")
                if (sender.hasPermission("arc.store.others")) {
                    suggestions.addAll(PlayerManager.getPlayerNames())
                }
                suggestions.tabComplete(args[0])
            }

            else -> null
        }
    }
}


