package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.onboarding.OnboardingResetResult
import ru.arc.onboarding.OnboardingService
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import java.util.UUID

/** Administrative reset for one player's persisted product-onboarding state. */
object OnboardingSubCommand : SubCommand {
    override val configKey = "onboarding"
    override val defaultName = "onboarding"
    override val defaultPermission = "arc.admin"
    override val defaultDescription = "Управление состоянием продуктового онбординга"
    override val defaultUsage = "/arc onboarding reset <player|uuid>"

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        if (args.size != 2 || !args[0].equals("reset", ignoreCase = true)) {
            sendUsage(sender)
            return true
        }
        val (playerId, displayName) = resolvePlayer(sender, args[1]) ?: return true
        val result =
            try {
                OnboardingService.reset(playerId)
            } catch (failure: Exception) {
                error("Could not reset onboarding state for {}", displayName, failure)
                sender.sendMessage(TextUtil.mm("<red>Не удалось сохранить сброс онбординга. Проверьте лог ARC.", true))
                return true
            }
        when (result) {
            OnboardingResetResult.DISABLED ->
                sender.sendMessage(TextUtil.mm("<yellow>Продуктовый онбординг отключён на этом сервере", true))

            OnboardingResetResult.NOT_FOUND ->
                sender.sendMessage(TextUtil.mm("<yellow>У <white>$displayName<yellow> нет состояния онбординга", true))

            OnboardingResetResult.RESET ->
                sender.sendMessage(TextUtil.mm("<green>Состояние онбординга <white>$displayName<green> сброшено", true))
        }
        return true
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
        sender.sendMessage(TextUtil.mm("<red>Игрок <white>$raw<red> не найден в кэше сервера. Укажите UUID.", true))
        return null
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? =
        when (args.size) {
            1 -> listOf("reset").tabComplete(args[0])
            2 -> if (args[0].equals("reset", ignoreCase = true)) tabCompletePlayers(args[1]) else null
            else -> null
        }
}
