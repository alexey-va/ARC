package ru.arc.mounts

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import ru.arc.core.TaskScheduler
import ru.arc.util.TextUtil
import java.util.Locale

class MountsCommand(private val gui: MountGuiController) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(TextUtil.playerOnly())
            return true
        }
        gui.openList(player)
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = emptyList()
}

class UnlockMountCommand(
    private val catalog: () -> MountCatalog,
    private val ownership: MountOwnership,
    private val scheduler: TaskScheduler,
) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.size < 3) {
            sender.sendMessage(TextUtil.mm("<red>Использование: /$label <маунт> <уровень> <игрок>", true))
            return true
        }
        val mount = catalog()[args[0].lowercase(Locale.ROOT)]
        if (mount == null) {
            sender.sendMessage(TextUtil.mm("<red>Неизвестный маунт: <white>${args[0]}", true))
            return true
        }
        val level = args[1].toIntOrNull()
        if (level == null || level !in 1..mount.maxLevel) {
            sender.sendMessage(TextUtil.mm("<red>Уровень должен быть от 1 до ${mount.maxLevel}.", true))
            return true
        }
        val playerName = args[2]
        val onlineId = sender.server.getPlayerExact(playerName)?.uniqueId
        val resolved = onlineId?.let { java.util.concurrent.CompletableFuture.completedFuture(it) }
            ?: ownership.resolveUniqueId(playerName)
        resolved.whenComplete { playerId, lookupFailure ->
            if (lookupFailure != null || playerId == null) {
                scheduler.runSync(Runnable {
                    sender.sendMessage(TextUtil.mm("<red>Игрок <white>$playerName <red>не найден.", true))
                })
                return@whenComplete
            }
            ownership.grantLevel(playerId, mount, level).whenComplete { _, saveFailure ->
                scheduler.runSync(Runnable {
                    if (saveFailure == null) {
                        sender.sendMessage(
                            TextUtil.mm(
                                "<green>Маунт <white>${mount.displayName} <green>уровня <white>$level <green>разблокирован для <white>$playerName<green>.",
                                true,
                            ),
                        )
                    } else {
                        sender.sendMessage(TextUtil.mm("<red>Не удалось сохранить разблокировку маунта.", true))
                    }
                })
            }
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> =
        when (args.size) {
            1 -> catalog().all.map(MountDefinition::id).matching(args[0])
            2 -> {
                val mount = catalog()[args[0].lowercase(Locale.ROOT)]
                (1..(mount?.maxLevel ?: 3)).map(Int::toString).matching(args[1])
            }
            3 -> sender.server.onlinePlayers.map(Player::getName).matching(args[2])
            else -> emptyList()
        }
}

class RideMobCommand(
    private val config: () -> MountModuleConfig,
    private val catalog: () -> MountCatalog,
    private val sessions: MountSessionController,
) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(TextUtil.playerOnly())
            return true
        }
        val mountId = args.getOrNull(0)?.lowercase(Locale.ROOT) ?: "bee"
        val mount = catalog()[mountId]
        if (mount == null) {
            player.sendMessage(TextUtil.mm("<red>Неизвестный маунт: <white>$mountId", true))
            return true
        }
        val speed = args.getOrNull(1)?.toDoubleOrNull() ?: 0.5
        if (!speed.isFinite() || speed <= 0.0) {
            player.sendMessage(TextUtil.mm("<red>Скорость должна быть положительным числом.", true))
            return true
        }
        val result =
            sessions.spawn(
                player,
                mount,
                speed,
                config().adminSessionDuration.toMillis(),
                glow = false,
            )
        if (result != MountSpawnResult.SUCCESS) {
            player.sendMessage(TextUtil.mm("<red>Не удалось призвать маунта: <white>${result.name.lowercase()}<red>.", true))
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> =
        when (args.size) {
            1 -> catalog().all.map(MountDefinition::id).matching(args[0])
            2 -> listOf("0.3", "0.5", "1.0").matching(args[1])
            else -> emptyList()
        }
}

private fun Collection<String>.matching(prefix: String): List<String> =
    filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
