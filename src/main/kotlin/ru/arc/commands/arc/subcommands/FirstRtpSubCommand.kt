package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.rtp.FirstRtpCoordinator
import ru.arc.rtp.FirstRtpRouteResult
import ru.arc.rtp.RtpPlayerRegistry
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import java.util.UUID

/**
 * Administrative entry point for the native first-RTP flow.
 *
 * /arc firstrtp <player> <world>
 * /arc firstrtp reset <player|uuid> [world|all]
 */
object FirstRtpSubCommand : SubCommand {
    override val configKey = "firstrtp"
    override val defaultName = "firstrtp"
    override val defaultPermission = "arc.rtp-respawn"
    override val defaultDescription = "Перевести игрока в мир, при первом посещении запустить RTP"
    override val defaultUsage =
        "/arc firstrtp <player> <world> | reset <player|uuid> [world|all]"

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        if (args.firstOrNull()?.equals("reset", ignoreCase = true) == true) {
            return reset(sender, args)
        }
        if (!requireArgs(sender, args, 2)) return true
        if (args.size != 2) {
            sendUsage(sender)
            return true
        }

        val player = getOnlinePlayer(sender, args[0]) ?: return true
        val world =
            Bukkit.getWorld(args[1])
                ?: run {
                    sender.sendMessage(TextUtil.mm("<red>Мир <white>${args[1]}<red> не загружен", true))
                    return true
                }
        when (val result = FirstRtpCoordinator.route(player, world)) {
            FirstRtpRouteResult.ReturnedToWorldSpawn ->
                sender.sendMessage(
                    TextUtil.mm(
                        "<green>Игрок <white>${player.name}<green> возвращён в мир <white>${world.name}",
                        true,
                    ),
                )

            is FirstRtpRouteResult.Started ->
                sender.sendMessage(
                    TextUtil.mm(
                        "<green>RTP запущен для <white>${player.name}<green> через " +
                            "<white>${result.result.provider.name.lowercase()}",
                        true,
                    ),
                )

            is FirstRtpRouteResult.Rejected ->
                sender.sendMessage(TextUtil.mm("<red>Не удалось запустить RTP: <white>${result.reason}", true))
        }
        return true
    }

    private fun reset(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        if (!sender.hasPermission("arc.admin")) {
            sender.sendMessage(TextUtil.noPermissions())
            return true
        }
        if (args.size !in 2..3) {
            sendUsage(sender)
            return true
        }
        val (playerId, displayName) = resolvePlayer(sender, args[1]) ?: return true
        val world =
            args.getOrNull(2)
                ?.takeUnless { it.equals("all", ignoreCase = true) }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        return try {
            val result = RtpPlayerRegistry.reset(playerId, world)
            if (!result.changed) {
                sender.sendMessage(
                    TextUtil.mm(
                        "<yellow>У <white>$displayName<yellow> нет first-RTP отметки" +
                            (world?.let { " для мира <white>$it" } ?: ""),
                        true,
                    ),
                )
            } else {
                val scope =
                    if (world == null) {
                        "глобально и в мирах: ${result.worldsRemoved.joinToString().ifEmpty { "нет" }}"
                    } else {
                        "в мире $world"
                    }
                sender.sendMessage(
                    TextUtil.mm(
                        "<green>First-RTP отметка <white>$displayName<green> сброшена: <white>$scope",
                        true,
                    ),
                )
            }
            true
        } catch (failure: Exception) {
            error("Could not reset first-RTP state for {}", displayName, failure)
            sender.sendMessage(
                TextUtil.mm(
                    "<red>Не удалось сохранить сброс first-RTP. Проверьте лог ARC.",
                    true,
                ),
            )
            true
        }
    }

    private fun resolvePlayer(
        sender: CommandSender,
        raw: String,
    ): Pair<UUID, String>? {
        runCatching { UUID.fromString(raw) }.getOrNull()?.let { return it to raw }
        Bukkit.getPlayerExact(raw)?.let { return it.uniqueId to it.name }
        Bukkit.getOfflinePlayerIfCached(raw)?.let {
            return it.uniqueId to (it.name ?: raw)
        }
        sender.sendMessage(
            TextUtil.mm(
                "<red>Игрок <white>$raw<red> не найден в кэше сервера. Укажите UUID.",
                true,
            ),
        )
        return null
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? =
        if (args.firstOrNull()?.equals("reset", ignoreCase = true) == true) {
            when (args.size) {
                2 -> tabCompletePlayers(args[1])
                3 -> (listOf("all") + Bukkit.getWorlds().map { it.name }).tabComplete(args[2])
                else -> null
            }
        } else {
            when (args.size) {
                1 -> (listOf("reset") + Bukkit.getOnlinePlayers().map { it.name }).tabComplete(args[0])
                2 -> Bukkit.getWorlds().map { it.name }.tabComplete(args[1])
                else -> null
            }
        }
}
