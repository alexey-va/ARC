package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.misc.StoreGuiFactory
import ru.arc.store.StoreManager
import ru.arc.util.GuiUtils
import ru.arc.util.Logging
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
    override val defaultDescription = "Открыть GUI хранилища предметов (своё или другого игрока)"
    override val defaultUsage = "/arc store [<player>|<uuid>|dump]"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true

        // /arc store dump
        if (args.size == 1 && args[0].equals("dump", ignoreCase = true)) {
            StoreManager.getStoreAsync(player.uniqueId).thenAccept { store ->
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
                } catch (_: Exception) {
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
        StoreManager.getStoreAsync(uuid)
            .thenAccept { store ->
                GuiUtils.constructAndShowAsync({ StoreGuiFactory.create(player, store) }, player, 0)
            }
            .exceptionally { e ->
                Logging.error("[Store] Failed to load store for {}: {}", uuid, e.cause ?: e)
                sender.sendMessage(
                    CommandConfig.get("store.error", "<red>Failed to load store. Check console for details.")
                )
                null
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


