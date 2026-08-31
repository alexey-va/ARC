package ru.arc.commands.arc.subcommands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commandhide.CommandHideAdminController
import ru.arc.commandhide.CommandHideAdminResult
import ru.arc.commandhide.CommandHideManager
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.util.Logging.info
import java.util.Locale

internal class CommandHideSubCommand(
    private val controller: CommandHideAdminController = CommandHideManager,
    private val messages: CommandHideCommandMessages = ConfiguredCommandHideMessages,
    private val findOnlinePlayer: (String) -> Player? = ::findExactOnlinePlayer,
    private val onlinePlayerNames: () -> List<String> = {
        Bukkit.getOnlinePlayers().map(Player::getName).sortedWith(String.CASE_INSENSITIVE_ORDER)
    },
) : SubCommand {
    override val configKey: String = "commandhide"
    override val defaultName: String = "commandhide"
    override val defaultPermission: String = ADMIN_PERMISSION
    override val defaultDescription: String = "Управление полным доступом к командам"
    override val defaultUsage: String = "/arc commandhide [allow|restrict|status] [player]"

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        val action = args.getOrNull(0)?.lowercase(Locale.ROOT)
        val playerName = args.getOrNull(1)
        if (args.size != 2 || action !in ACTIONS || playerName == null || !SAFE_PLAYER_NAME.matches(playerName)) {
            sender.sendMessage(messages.usage())
            return true
        }

        val target = findOnlinePlayer(playerName)
        if (target == null) {
            sender.sendMessage(messages.playerOffline(playerName))
            return true
        }

        if (action == STATUS) {
            sendStatus(sender, target, controller.status(target))
            return true
        }

        val enabled = action == ALLOW
        logMutationRequested(sender, target, enabled)
        controller.setBypass(target, enabled) { result ->
            sendMutationResult(sender, target, enabled, result)
        }
        return true
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? =
        when (args.size) {
            1 -> ACTIONS.tabComplete(args[0])
            2 -> onlinePlayerNames().tabComplete(args[1])
            else -> emptyList()
        }

    private fun sendMutationResult(
        sender: CommandSender,
        target: Player,
        enabled: Boolean,
        result: CommandHideAdminResult,
    ) {
        val message =
            when (result) {
                is CommandHideAdminResult.State -> {
                    when {
                        enabled && result.value.managedGrant && result.value.effectiveBypass -> messages.allowed(target.name)
                        enabled -> messages.savedButNotEffective(target.name)
                        result.value.managedGrant -> messages.failed(target.name)
                        result.value.effectiveBypass -> messages.stillAllowed(target.name)
                        else -> messages.restricted(target.name)
                    }
                }

                CommandHideAdminResult.Busy -> messages.busy(target.name)
                CommandHideAdminResult.ModuleDisabled -> messages.moduleDisabled()
                CommandHideAdminResult.BypassDisabled -> messages.bypassDisabled()
                CommandHideAdminResult.ProviderUnavailable -> messages.providerUnavailable()
                CommandHideAdminResult.ConflictingDeny -> messages.conflictingDeny(target.name)
                CommandHideAdminResult.UnmanagedGrant -> messages.unmanagedGrant(target.name)
                CommandHideAdminResult.TargetOffline -> messages.savedWhileOffline(target.name)
                CommandHideAdminResult.Failed -> messages.failed(target.name)
            }
        sender.sendMessage(message)

        val treeUpdated = (result as? CommandHideAdminResult.State)?.commandTreeUpdated ?: true
        if (!treeUpdated) {
            sender.sendMessage(messages.commandTreeRefreshFailed(target.name))
        }
        logMutation(sender, target, enabled, result, treeUpdated)
    }

    private fun sendStatus(
        sender: CommandSender,
        target: Player,
        result: CommandHideAdminResult,
    ) {
        sender.sendMessage(
            when (result) {
                is CommandHideAdminResult.State ->
                    when {
                        result.value.managedGrant -> messages.statusManaged(target.name)
                        result.value.unmanagedDirectGrant -> messages.statusExternal(target.name)
                        result.value.effectiveBypass -> messages.statusInherited(target.name)
                        else -> messages.statusRestricted(target.name)
                    }

                CommandHideAdminResult.Busy -> messages.busy(target.name)
                CommandHideAdminResult.ModuleDisabled -> messages.moduleDisabled()
                CommandHideAdminResult.BypassDisabled -> messages.bypassDisabled()
                CommandHideAdminResult.ProviderUnavailable -> messages.providerUnavailable()
                CommandHideAdminResult.ConflictingDeny -> messages.conflictingDeny(target.name)
                CommandHideAdminResult.UnmanagedGrant -> messages.unmanagedGrant(target.name)
                CommandHideAdminResult.TargetOffline -> messages.playerOffline(target.name)
                CommandHideAdminResult.Failed -> messages.statusFailed(target.name)
            },
        )
    }

    private fun logMutation(
        sender: CommandSender,
        target: Player,
        enabled: Boolean,
        result: CommandHideAdminResult,
        treeUpdated: Boolean,
    ) {
        val actor = (sender as? Player)?.uniqueId?.toString() ?: "console"
        info(
            "CommandHide admin mutation: actor={}, target={}, action={}, outcome={}, command-tree-updated={}",
            actor,
            target.uniqueId,
            if (enabled) ALLOW else RESTRICT,
            result::class.java.simpleName,
            treeUpdated,
        )
    }

    private fun logMutationRequested(
        sender: CommandSender,
        target: Player,
        enabled: Boolean,
    ) {
        val actor = (sender as? Player)?.uniqueId?.toString() ?: "console"
        info(
            "CommandHide admin mutation requested: actor={}, target={}, action={}",
            actor,
            target.uniqueId,
            if (enabled) ALLOW else RESTRICT,
        )
    }

    companion object {
        const val ADMIN_PERMISSION = "arc.command.hide.admin"
        private const val ALLOW = "allow"
        private const val RESTRICT = "restrict"
        private const val STATUS = "status"
        private val ACTIONS = listOf(ALLOW, RESTRICT, STATUS)
        private val SAFE_PLAYER_NAME = Regex("[A-Za-z0-9_.-]{1,32}")
    }
}

internal interface CommandHideCommandMessages {
    fun usage(): Component

    fun playerOffline(player: String): Component

    fun allowed(player: String): Component

    fun restricted(player: String): Component

    fun stillAllowed(player: String): Component

    fun savedButNotEffective(player: String): Component

    fun statusManaged(player: String): Component

    fun statusInherited(player: String): Component

    fun statusExternal(player: String): Component

    fun statusRestricted(player: String): Component

    fun statusFailed(player: String): Component

    fun busy(player: String): Component

    fun failed(player: String): Component

    fun commandTreeRefreshFailed(player: String): Component

    fun conflictingDeny(player: String): Component

    fun unmanagedGrant(player: String): Component

    fun savedWhileOffline(player: String): Component

    fun moduleDisabled(): Component

    fun bypassDisabled(): Component

    fun providerUnavailable(): Component
}

private object ConfiguredCommandHideMessages : CommandHideCommandMessages {
    override fun usage(): Component = message(
        "usage",
        "<#92bed8>Команды <#666666>· <#92bed8>/arc commandhide [режим] [игрок]",
    )

    override fun playerOffline(player: String): Component = playerMessage(
        "player-offline",
        "<#92bed8>Команды <#666666>· <#92bed8><player> <#c42323>не на этом сервере.",
        player,
    )

    override fun allowed(player: String): Component = playerMessage(
        "allowed",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#e6fff3>: <#2bba43>все команды открыты<#e6fff3>.",
        player,
    )

    override fun restricted(player: String): Component = playerMessage(
        "restricted",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#e6fff3>: <#ff9f0f>ограничения включены<#e6fff3>.",
        player,
    )

    override fun stillAllowed(player: String): Component = playerMessage(
        "still-allowed",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#e6fff3>: <#ff9f0f>обход ещё активен <#666666>— <#e6fff3>группа/OP.",
        player,
    )

    override fun savedButNotEffective(player: String): Component = playerMessage(
        "saved-but-not-effective",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#e6fff3>: право есть, но обход не активен.",
        player,
    )

    override fun statusManaged(player: String): Component = playerMessage(
        "status-managed",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#e6fff3>: прямой полный доступ.",
        player,
    )

    override fun statusInherited(player: String): Component = playerMessage(
        "status-inherited",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#e6fff3>: доступ через группу/OP.",
        player,
    )

    override fun statusExternal(player: String): Component = playerMessage(
        "status-external",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#e6fff3>: внешняя прямая выдача.",
        player,
    )

    override fun statusRestricted(player: String): Component = playerMessage(
        "status-restricted",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#e6fff3>: ограничения активны.",
        player,
    )

    override fun statusFailed(player: String): Component = playerMessage(
        "status-failed",
        "<#92bed8>Команды <#666666>· <#c42323>Не удалось проверить <#92bed8><player><#c42323>.",
        player,
    )

    override fun busy(player: String): Component = playerMessage(
        "busy",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#e6fff3>: доступ уже изменяется.",
        player,
    )

    override fun failed(player: String): Component = playerMessage(
        "failed",
        "<#92bed8>Команды <#666666>· <#c42323>Не удалось изменить доступ <#92bed8><player><#c42323>.",
        player,
    )

    override fun commandTreeRefreshFailed(player: String): Component = playerMessage(
        "command-tree-refresh-failed",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#e6fff3>: дерево обновится при входе.",
        player,
    )

    override fun conflictingDeny(player: String): Component = playerMessage(
        "conflicting-deny",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#c42323>: мешает прямой запрет LuckPerms.",
        player,
    )

    override fun unmanagedGrant(player: String): Component = playerMessage(
        "unmanaged-grant",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#ff9f0f>: чужая выдача не изменена.",
        player,
    )

    override fun savedWhileOffline(player: String): Component = playerMessage(
        "saved-while-offline",
        "<#92bed8>Команды <#666666>· <#92bed8><player><#e6fff3>: сохранено, игрок вышел.",
        player,
    )

    override fun moduleDisabled(): Component = message(
        "module-disabled",
        "<#92bed8>Команды <#666666>· <#ff9f0f>Фильтр команд выключен в конфигурации<#e6fff3>.",
    )

    override fun bypassDisabled(): Component = message(
        "bypass-disabled",
        "<#92bed8>Команды <#666666>· <#c42323>Право обхода не настроено<#e6fff3>.",
    )

    override fun providerUnavailable(): Component = message(
        "provider-unavailable",
        "<#92bed8>Команды <#666666>· <#c42323>LuckPerms сейчас недоступен<#e6fff3>.",
    )

    private fun message(
        key: String,
        fallback: String,
    ): Component = CommandConfig.get("commandhide.$key", fallback)

    private fun playerMessage(
        key: String,
        fallback: String,
        player: String,
    ): Component =
        CommandConfig.get(
            "commandhide.$key",
            fallback,
            TagResolver.resolver("player", Tag.inserting(Component.text(player))),
        )
}

private fun findExactOnlinePlayer(input: String): Player? =
    Bukkit.getOnlinePlayers().singleOrNull { it.name.equals(input, ignoreCase = true) }
