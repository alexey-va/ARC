package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.rtp.FirstRtpResult
import ru.arc.rtp.FirstRtpService
import ru.arc.rtp.RtpPlayerRegistry
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import java.util.UUID

/**
 * Internal compatibility bridge for the Denizen cross-server flow.
 *
 * /arc firstrtp <player> <world>
 * /arc firstrtp reset <player|uuid> [world|all]
 *
 * The optional third argument is a temporary compatibility bridge for the old
 * Denizen script, which used to own persistence and the first-RTP decision.
 */
object FirstRtpSubCommand : SubCommand {
    override val configKey = "firstrtp"
    override val defaultName = "firstrtp"
    override val defaultPermission = "arc.rtp-respawn"
    override val defaultDescription = "Перевести игрока в мир, при первом посещении запустить RTP"
    override val defaultUsage =
        "/arc firstrtp <player> <world> [set-respawn:true|false] | reset <player|uuid> [world|all]"

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        if (args.firstOrNull()?.equals("reset", ignoreCase = true) == true) {
            return reset(sender, args)
        }
        if (!requireArgs(sender, args, 2)) return true

        val player = getOnlinePlayer(sender, args[0]) ?: return true
        val world =
            Bukkit.getWorld(args[1])
                ?: run {
                    sender.sendMessage(TextUtil.mm("<red>Мир <white>${args[1]}<red> не загружен", true))
                    return true
                }
        if (args.size >= 3) {
            val setRespawn =
                args[2].toBooleanStrictOrNull()
                    ?: run {
                        sendUsage(sender)
                        return true
                    }
            startRtp(sender, player, world, setRespawn, persist = false)
            return true
        }

        val state = RtpPlayerRegistry.state(player.uniqueId, world.name)
        if (state.hasTeleportedToWorld) {
            if (player.world.uid != world.uid) {
                player.teleport(world.spawnLocation)
            }
            sender.sendMessage(
                TextUtil.mm(
                    "<green>Игрок <white>${player.name}<green> возвращён в мир <white>${world.name}",
                    true,
                ),
            )
            return true
        }

        startRtp(sender, player, world, setRespawn = !state.hasTeleported, persist = true)
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

    private fun startRtp(
        sender: CommandSender,
        player: org.bukkit.entity.Player,
        world: org.bukkit.World,
        setRespawn: Boolean,
        persist: Boolean,
    ) {
        when (val result = FirstRtpService.start(player, world, setRespawn, persist)) {
            is FirstRtpResult.Started ->
                sender.sendMessage(
                    TextUtil.mm(
                        "<green>RTP запущен для <white>${player.name}<green> через " +
                            "<white>${result.provider.name.lowercase()}",
                        true,
                    ),
                )

            is FirstRtpResult.Rejected ->
                sender.sendMessage(TextUtil.mm("<red>Не удалось запустить RTP: <white>${result.reason}", true))
        }
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
                3 -> listOf("true", "false").tabComplete(args[2])
                else -> null
            }
        }
}
