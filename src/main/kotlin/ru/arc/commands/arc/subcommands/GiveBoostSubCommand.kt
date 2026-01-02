package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.jobs.JobsBoost

/**
 * /arc giveboost - выдать буст Jobs игроку.
 *
 * Использование: /arc giveboost <player> <job|all> <boost> <type> <duration> [id]
 *
 * Примеры:
 * - /arc giveboost Steve miner 1.5 EXP 1h boost_id
 * - /arc giveboost Steve all 2.0 MONEY 1d
 */
object GiveBoostSubCommand : SubCommand {

    override val configKey = "giveboost"
    override val defaultName = "giveboost"
    override val defaultPermission = "arc.admin.givejobsboost"
    override val defaultDescription = "Выдать буст Jobs игроку"
    override val defaultUsage = "/arc giveboost <player> <job|all> <boost> <type> <duration> [id]"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 5) {
            sendUsage(sender)
            return true
        }

        val playerName = args[0]
        val player = Bukkit.getPlayer(playerName)
        if (player == null || !player.name.equals(playerName, ignoreCase = true)) {
            sender.sendMessage(CommandConfig.playerNotFound(playerName))
            return true
        }

        var jobName: String? = args[1]
        if (jobName.equals("all", ignoreCase = true)) jobName = null

        val boost = args[2].toDoubleOrNull() ?: run {
            sender.sendMessage(
                CommandConfig.get(
                    "giveboost.invalid-boost",
                    "<red>Неверное значение буста: <white>%value%",
                    "%value%",
                    args[2]
                )
            )
            return true
        }

        val boostType = try {
            JobsBoost.Type.valueOf(args[3].uppercase())
        } catch (e: Exception) {
            sender.sendMessage(
                CommandConfig.get(
                    "giveboost.invalid-type", "<red>Неверный тип буста: <white>%value%<gray>. Доступные: %types%",
                    "%value%", args[3], "%types%", JobsBoost.Type.entries.joinToString(", ")
                )
            )
            return true
        }

        val durationMs = parseDuration(args[4])
        if (durationMs == null) {
            sender.sendMessage(
                CommandConfig.get(
                    "giveboost.invalid-duration",
                    "<red>Неверный формат длительности: <white>%value%<gray>. Используйте: 1s, 1m, 1h, 1d",
                    "%value%",
                    args[4]
                )
            )
            return true
        }

        val id = args.getOrNull(5)?.takeIf { it != "null" }

        if (HookRegistry.jobsHook == null) {
            sender.sendMessage(CommandConfig.hookNotLoaded("Jobs"))
            return true
        }

        val jobNames = listOf(jobName)
        HookRegistry.jobsHook.addBoost(
            player.uniqueId,
            jobNames,
            boost,
            System.currentTimeMillis() + durationMs,
            id,
            listOf(boostType)
        )

        sender.sendMessage(
            CommandConfig.get(
                "giveboost.success", "<green>Буст добавлен игроку <white>%player%<green>!",
                "%player%", player.name
            )
        )

        return true
    }

    private fun parseDuration(duration: String): Long? {
        if (duration.length < 2) return null
        val value = duration.dropLast(1).toLongOrNull() ?: return null
        return when (duration.last()) {
            's' -> value * 1000
            'm' -> value * 1000 * 60
            'h' -> value * 1000 * 60 * 60
            'd' -> value * 1000 * 60 * 60 * 24
            else -> null
        }
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> tabCompletePlayers(args[0])
            2 -> {
                val jobs = HookRegistry.jobsHook?.jobNames ?: emptyList()
                (listOf("all") + jobs).tabComplete(args[1])
            }

            3 -> listOf("1.0", "1.5", "2.0").tabComplete(args[2])
            4 -> JobsBoost.Type.entries.map { it.name }.tabComplete(args[3])
            5 -> listOf("1h", "1d", "7d", "30m").tabComplete(args[4])
            6 -> listOf("[id]")
            else -> null
        }
    }
}


