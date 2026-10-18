package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.onboarding.OnboardingService

/** Player journal plus explicit self-only administrator test controls. */
object OnboardingSubCommand : SubCommand {
    override val configKey = "onboarding"
    override val defaultName = "onboarding"
    override val defaultPlayerOnly = true
    override val defaultDescription = "Начальный квест и его повторный запуск для себя"
    override val defaultUsage = "/arc onboarding [test|restart|stop]"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true
        when {
            args.isEmpty() -> OnboardingService.open(player)
            args.size != 1 -> sendUsage(sender)
            args[0].equals("test", true) -> OnboardingService.enrollSelf(player)
            args[0].equals("restart", true) -> OnboardingService.restartSelf(player)
            args[0].equals("stop", true) -> OnboardingService.stopSelf(player)
            else -> sendUsage(sender)
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> =
        if (args.size == 1 && (sender.isOp || sender.hasPermission("arc.admin")))
            listOf("test", "restart", "stop").tabComplete(args[0]) else emptyList()
}
