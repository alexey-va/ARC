package ru.arc.commands

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import ru.arc.xserver.XActionManager
import ru.arc.xserver.playerlist.PlayerManager
import java.util.UUID
import ru.arc.xserver.XCommand as XServerCommand

/**
 * /x - кросс-серверная команда.
 *
 * Выполняет команду на других серверах через Redis.
 *
 * Параметры (формат -key:value):
 * - -servers:server1,server2 или -servers:all
 * - -player:name - имя игрока (для команд с %player%)
 * - -uuid:UUID - UUID игрока
 * - -timeout:100 - таймаут ожидания игрока (тики)
 * - -delay:0 - задержка выполнения (тики)
 * - -sender:console|player - от чьего имени выполнять
 * - -move-to-server:true|false - переместить игрока на сервер
 *
 * Пример: /x -servers:survival -player:Steve give %player% diamond 64
 */
object XCommand : CommandExecutor, TabCompleter {

    private val config get() = ConfigManager.of(ARC.plugin.dataPath, "commands.yml")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("arc.x")) {
            sender.sendMessage(TextUtil.noPermissions())
            return true
        }

        val params = parseParams(args)
        val commandArgs = args.filter { !it.startsWith("-") }
        val commandStr = commandArgs.joinToString(" ")

        if (commandStr.isEmpty()) {
            sender.sendMessage(TextUtil.mm("<red>Использование: <gray>/x [-servers:all] [-player:name] <command>"))
            return true
        }

        // Parse parameters
        val serversStr = params["servers"]
        val serverList: Set<String>? = if (serversStr == null || serversStr.equals("all", ignoreCase = true)) {
            null // null means all servers
        } else {
            serversStr.split(",").toSet()
        }

        val playerName = params["player"]
        val timeout = params["timeout"]?.toIntOrNull() ?: 100
        val uuid = params["uuid"]?.let { UUID.fromString(it) }
        val delay = params["delay"]?.toIntOrNull() ?: 0
        val senderType = params["sender"]?.uppercase()?.let {
            try {
                XServerCommand.Sender.valueOf(it)
            } catch (e: Exception) {
                XServerCommand.Sender.CONSOLE
            }
        } ?: XServerCommand.Sender.CONSOLE

        val xCommand = XServerCommand.create(
            commandStr,
            senderType,
            playerName,
            uuid,
            timeout,
            delay,
            serverList
        )

        XActionManager.publish(xCommand)

        // Handle move-to-server
        val moveToServer = params["move-to-server"]?.toBoolean() ?: false
        if (moveToServer) {
            if (playerName == null) {
                warn("Cannot move player to server without specifying player")
                return true
            }
            if (serverList == null || serverList.isEmpty()) {
                warn("Cannot move player to server without specifying server")
                return true
            }
            if (serverList.size != 1) {
                warn("Cannot move player to server, multiple servers specified: {}", serverList)
                return true
            }

            val player = uuid?.let { Bukkit.getPlayer(it) } ?: Bukkit.getPlayerExact(playerName)
            if (player == null) {
                warn("Cannot move player to server, player not found: {}", playerName)
                return true
            }
            XActionManager.movePlayerToServer(player, serverList.first())
        }

        val message =
            config.string("xcommand.success-message", "<gold>Команда <gray>%command% <gold>успешна отправлена!")
                .replace("%command%", commandStr)
        sender.sendMessage(TextUtil.mm(message))

        return true
    }

    private fun parseParams(args: Array<String>): Map<String, String> {
        val params = mutableMapOf<String, String>()
        for (arg in args) {
            if (arg.startsWith("-")) {
                val parts = arg.substring(1).split(":", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = parts[1]
                }
            }
        }
        return params
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>
    ): List<String>? {
        val last = args.lastOrNull() ?: ""
        val suggestions = mutableListOf<String>()

        when {
            last.isEmpty() -> suggestions.addAll(
                listOf(
                    "-servers",
                    "-player",
                    "-timeout",
                    "-uuid",
                    "-move-to-server",
                    "-delay",
                    "-sender"
                )
            )

            last.startsWith("-servers") -> {
                suggestions.add("-servers:all")
                PlayerManager.getServerNames().forEach { suggestions.add("-servers:$it") }
            }

            last.startsWith("-player") -> {
                PlayerManager.getPlayerNames().forEach { suggestions.add("-player:$it") }
            }

            last.startsWith("-uuid") -> {
                PlayerManager.getPlayerUuids().forEach { suggestions.add("-uuid:$it") }
            }

            last.startsWith("-timeout") -> suggestions.addAll(listOf("-timeout:100", "-timeout:200", "-timeout:500"))
            last.startsWith("-move-to-server") -> suggestions.addAll(
                listOf(
                    "-move-to-server:true",
                    "-move-to-server:false"
                )
            )

            last.startsWith("-delay") -> suggestions.addAll(listOf("-delay:0", "-delay:20", "-delay:60"))
            last.startsWith("-sender") -> suggestions.addAll(listOf("-sender:console", "-sender:player"))
        }

        return suggestions.filter { it.startsWith(last, ignoreCase = true) }.sorted()
    }
}

