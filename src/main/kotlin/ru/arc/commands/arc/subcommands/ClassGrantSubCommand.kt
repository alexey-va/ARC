package ru.arc.commands.arc.subcommands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.onlinePlayerNames
import ru.arc.commands.arc.tabComplete

internal class ClassGrantSubCommand : SubCommand {
    override val configKey = "classgrant"
    override val defaultName = "classgrant"
    override val defaultPermission = "arc.dungeon.admin.classgrant"
    override val defaultDescription = "Выдать игроку доступ к классу EliteMobs"
    override val defaultUsage = "/arc classgrant [игрок] <id класса>"

    override fun isAvailable(): Boolean = ru.arc.dungeons.dungeonService()?.nativeReady() == true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (!requireArgs(sender, args, 1)) return true
        val (player, formId) = if (args.size == 1) {
            (sender as? Player ?: run { sendUsage(sender); return true }) to args[0]
        } else {
            (getOnlinePlayer(sender, args[0]) ?: return true) to args[1]
        }
        val result = ru.arc.dungeons.dungeonService()?.grantClass(player, formId)
            ?: ru.arc.dungeons.api.ArcDungeonsService.ClassGrant(ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.NOT_READY, formId, formId, emptyList())
        val placeholders = TagResolver.builder()
            .resolver(Placeholder.component("player", Component.text(player.name)))
            .resolver(Placeholder.component("class", Component.text(result.formName())))
            .resolver(Placeholder.component("id", Component.text(result.formId())))
            .resolver(Placeholder.component("skills", Component.text(skillSummary(result))))
            .build()
        val key = when (result.status()) {
            ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.APPLIED -> "classgrant.applied"
            ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.ALREADY_GRANTED -> "classgrant.already"
            ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.DISABLED -> "classgrant.disabled"
            ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.NOT_READY -> "classgrant.loading"
            ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.UNKNOWN_FORM -> "classgrant.unknown"
            ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.RUN_LOCKED -> "classgrant.run-locked"
            ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.FAILED -> "classgrant.failed"
        }
        sender.sendMessage(CommandConfig.get(key, defaultMessage(result.status()), placeholders))
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? = when (args.size) {
        1 -> (onlinePlayerNames() + if (sender is Player) (ru.arc.dungeons.dungeonService()?.classFormIds().orEmpty()) else emptyList()).tabComplete(args[0])
        2 -> (ru.arc.dungeons.dungeonService()?.classFormIds().orEmpty()).tabComplete(args[1])
        else -> null
    }

    private fun skillSummary(result: ru.arc.dungeons.api.ArcDungeonsService.ClassGrant): String =
        result.skillIncreases().joinToString(", ") { "${it.name()} ${it.previousLevel()} → ${it.newLevel()}" }
            .ifEmpty { "не требовалось" }

    private fun defaultMessage(status: ru.arc.dungeons.api.ArcDungeonsService.GrantStatus): String = when (status) {
        ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.APPLIED ->
            "<#9bd48d>✔ Игроку <player> выдан класс <class> <#aaa49a>(<id>). <#e8dfd2>Повышены навыки: <skills>."
        ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.ALREADY_GRANTED ->
            "<#e8dfd2>Игроку <player> уже доступен класс <class> <#aaa49a>(<id>)."
        ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.DISABLED -> "<#d7b486>Система классов EliteMobs сейчас выключена."
        ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.NOT_READY -> "<#d7b486>Профиль игрока <player> ещё загружается."
        ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.UNKNOWN_FORM -> "<#d7b486>Класс с id <id> не найден."
        ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.RUN_LOCKED ->
            "<#d7b486>Класс игрока <player> зафиксирован текущим походом. Повторите после выхода из данжа."
        ru.arc.dungeons.api.ArcDungeonsService.GrantStatus.FAILED -> "<#d7b486>Не удалось выдать класс <class> игроку <player>. Изменения отменены."
    }
}
