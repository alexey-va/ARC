package ru.arc.listeners

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.event.server.TabCompleteEvent
import ru.arc.ARC
import ru.arc.Portal
import ru.arc.PortalData
import ru.arc.audit.AuditManager
import ru.arc.audit.AdminEconomyCommandTracker
import ru.arc.audit.AuditMetadata
import ru.arc.audit.EconomyBalanceObservation
import ru.arc.audit.EconomyCommandOriginResolver
import ru.arc.audit.EconomyEventStatus
import ru.arc.audit.EconomyFlow
import ru.arc.audit.EconomyLedgerContext
import ru.arc.audit.EconomyLedgerParty
import ru.arc.audit.EconomyRecordKind
import ru.arc.audit.EconomySource
import ru.arc.audit.Type
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.Tasks
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.mm
import ru.arc.xserver.playerlist.PlayerManager
import java.util.UUID

class CommandListener internal constructor(
    private val commandConfig: Config = ConfigManager.of(ARC.instance.dataPath, "misc.yml"),
    private val networkPlayerNames: () -> Collection<String> = PlayerManager::getPlayerNames,
    private val moneyCurrencyNames: () -> Collection<String> = ::activeRedisEconomyCurrencyNames,
) : Listener {

    private var suppressCanonicalSetAudit = false

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerPlaceBlock(ev: BlockPlaceEvent) {
        if (Portal.isOccupied(ev.blockPlaced)) ev.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerCommand(ev: PlayerCommandPreprocessEvent) {
        val args = tokenizeCommand(ev.message)
        warpCommand(ev, args)
        moneyCommand(ev.player, ev, ev.message)
        scheduleCanonicalSetAudit(args, ev.player.name)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onServerCommand(serverCommandEvent: ServerCommandEvent) {
        val args = tokenizeCommand(serverCommandEvent.command)
        moneyCommandServer(serverCommandEvent, serverCommandEvent.command)
        scheduleCanonicalSetAudit(args, "Server")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun trackPlayerAdminEconomyCommand(ev: PlayerCommandPreprocessEvent) {
        AdminEconomyCommandTracker.track(tokenizeCommand(ev.message), ev.player.name)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun trackServerAdminEconomyCommand(ev: ServerCommandEvent) {
        val origin = EconomyCommandOriginResolver.resolve()
        AdminEconomyCommandTracker.track(
            tokenizeCommand(ev.command),
            "Server",
            source = origin.source,
            origin = origin.origin,
        )
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onTabComplete(tabCompleteEvent: TabCompleteEvent) {
        moneyTabComplete(tabCompleteEvent)
    }

    private fun moneyTabComplete(event: TabCompleteEvent) {
        if (!legacyMoneyAliasEnabled() || !event.isCommand) return
        val player = event.sender as? Player ?: return
        if (!player.hasPermission(LEGACY_MONEY_ADMIN_PERMISSION)) return

        val completions =
            legacyMoneyCompletions(
                buffer = event.buffer,
                nativeCompletions = event.completions,
                playerNames = networkPlayerNames(),
                currencyNames = moneyCurrencyNames(),
                allowGiveAll = player.hasPermission("rediseconomy.admin.giveall"),
            ) ?: return
        event.completions = completions
    }

    private fun moneyCommand(player: Player, ev: Cancellable, commandLine: String) {
        if (!legacyMoneyAliasEnabled()) return
        val result = parseLegacyMoneyCommand(commandLine, moneyCurrencyNames())
        if (result == LegacyMoneyCommandResult.NotLegacy) return

        ev.isCancelled = true
        if (!player.hasPermission(LEGACY_MONEY_ADMIN_PERMISSION)) {
            player.sendMessage(TextUtil.noPermissions())
            return
        }
        if (result is LegacyMoneyCommandResult.Invalid) {
            player.sendMessage(mm(result.error.playerMessage()))
            return
        }

        val command = (result as LegacyMoneyCommandResult.Valid).command
        if (command.target == "*" && !player.hasPermission("rediseconomy.admin.giveall")) {
            player.sendMessage(TextUtil.noPermissions())
            return
        }
        try {
            val before = balanceBeforeSet(command.action.token, command.target, command.currency)
            val dispatched = rerouteMoneyCommand { player.performCommand(command.canonical) }
            if (!dispatched) {
                error("RedisEconomy rejected the rerouted legacy money command")
                player.sendMessage(mm("<red>Не удалось выполнить команду экономики."))
                return
            }
            scheduleSetDelta(command.action.token, command.target, command.currency, player.name, before)
            info(
                "Rerouted legacy money command action={} target={} currency={} amount={}",
                command.action.token,
                command.target,
                command.currency,
                command.amount,
            )
        } catch (e: Exception) {
            error("Failed to reroute legacy money command to RedisEconomy", e)
            player.sendMessage(mm("<red>Не удалось выполнить команду экономики."))
        }
    }

    private fun moneyCommandServer(ev: ServerCommandEvent, commandLine: String) {
        if (!legacyMoneyAliasEnabled()) return
        val result = parseLegacyMoneyCommand(commandLine, moneyCurrencyNames())
        if (result == LegacyMoneyCommandResult.NotLegacy) return

        ev.isCancelled = true
        if (ev.sender !is ConsoleCommandSender || !ev.sender.hasPermission(LEGACY_MONEY_ADMIN_PERMISSION)) {
            warn("Rejected unauthorized legacy money command from {}", ev.sender.name)
            return
        }
        if (result is LegacyMoneyCommandResult.Invalid) {
            warn("Rejected invalid legacy money command: {}", result.error.name)
            return
        }

        val command = (result as LegacyMoneyCommandResult.Valid).command
        if (command.target == "*" && !ev.sender.hasPermission("rediseconomy.admin.giveall")) {
            warn("Rejected legacy give-all command without rediseconomy.admin.giveall")
            return
        }
        try {
            val before = balanceBeforeSet(command.action.token, command.target, command.currency)
            rerouteMoneyCommand { ARC.trySeverCommand(command.canonical) }
            scheduleSetDelta(command.action.token, command.target, command.currency, "Server", before)
            info(
                "Rerouted legacy server money command action={} target={} currency={} amount={}",
                command.action.token,
                command.target,
                command.currency,
                command.amount,
            )
        } catch (e: Exception) {
            error("Failed to reroute legacy server money command to RedisEconomy", e)
        }
    }

    private fun legacyMoneyAliasEnabled(): Boolean = commandConfig.bool(LEGACY_MONEY_ALIAS_ENABLED_PATH, false)

    private fun balanceBeforeSet(action: String, target: String, currency: String): Double? {
        if (!action.equals("set", ignoreCase = true)) return null
        val playerId = org.bukkit.Bukkit.getOfflinePlayer(target).uniqueId
        return HookRegistry.redisEcoHook?.getCachedBalance(playerId, currency)
    }

    private fun auditSetDelta(action: String, target: String, currency: String, actor: String, before: Double?) {
        if (!action.equals("set", ignoreCase = true) || before == null) return
        val offlinePlayer = org.bukkit.Bukkit.getOfflinePlayer(target)
        val after = HookRegistry.redisEcoHook?.getCachedBalance(offlinePlayer.uniqueId, currency) ?: return
        val delta = after - before
        if (delta == 0.0) return
        val targetId = offlinePlayer.uniqueId
        val onlinePlayer = org.bukkit.Bukkit.getPlayer(targetId)
        val session = AuditManager.session(targetId, onlinePlayer?.world?.name)
        val balance = EconomyBalanceObservation.exact(before, after)
        val actorParty =
            if (actor.equals("Server", ignoreCase = true)) {
                EconomyLedgerParty(id = "server", name = "Server", kind = "server")
            } else {
                val actorPlayer = org.bukkit.Bukkit.getOfflinePlayer(actor)
                EconomyLedgerParty(id = actorPlayer.uniqueId.toString(), name = actor.take(80), kind = "player")
            }
        AuditManager.economyOperation(
            target,
            delta,
            Type.BALANCE_SET,
            "Balance set by $actor",
            AuditMetadata(
                source = EconomySource.BALANCE_SET,
                flow = EconomyFlow.ADJUSTMENT,
                currency = currency,
                server = ARC.serverName ?: "unknown",
                origin = actor,
            ),
            EconomyLedgerContext(
                recordKind = EconomyRecordKind.TRANSACTION,
                status = EconomyEventStatus.SUCCEEDED,
                accountId = targetId.toString(),
                correlationId = UUID.randomUUID().toString(),
                counterparty = actorParty,
                world = session?.world,
                sessionId = session?.sessionId,
                sessionStartedAt = session?.startedAt,
                balanceBefore = balance?.before,
                balanceAfter = balance?.after,
                balanceEvidence = balance?.evidence,
                requestedAmount = after,
                action = "balance_set",
                capturedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun scheduleCanonicalSetAudit(args: List<String>, actor: String) {
        if (suppressCanonicalSetAudit) return
        val command = args.firstOrNull()?.removePrefix("/")?.lowercase() ?: return
        val target =
            when {
                command == "money" && args.size == 5 && args[3].equals("set", ignoreCase = true) -> args[1] to args[2]
                command == "cmi" && args.size == 5 && args[1].equals("money", ignoreCase = true) && args[2].equals("set", ignoreCase = true) -> args[3] to LEGACY_MONEY_DEFAULT_CURRENCY
                else -> return
            }
        val before = balanceBeforeSet("set", target.first, target.second) ?: return
        scheduleSetDelta("set", target.first, target.second, actor, before)
    }

    private fun scheduleSetDelta(action: String, target: String, currency: String, actor: String, before: Double?) {
        if (!action.equals("set", ignoreCase = true) || before == null) return
        Tasks.scheduler.runLater(1) { auditSetDelta(action, target, currency, actor, before) }
    }

    private inline fun <T> rerouteMoneyCommand(block: () -> T): T {
        suppressCanonicalSetAudit = true
        try {
            return block()
        } finally {
            suppressCanonicalSetAudit = false
        }
    }

    private fun LegacyMoneyCommandError.playerMessage(): String =
        when (this) {
            LegacyMoneyCommandError.USAGE ->
                "<red>Использование: <gray>/money <give|take|set> <игрок> [валюта] <сумма>"
            LegacyMoneyCommandError.INVALID_TARGET ->
                "<red>Укажите корректное имя игрока."
            LegacyMoneyCommandError.INVALID_AMOUNT ->
                "<red>Сумма должна быть конечным числом."
            LegacyMoneyCommandError.INVALID_CURRENCY ->
                "<red>Укажите существующую валюту RedisEconomy."
            LegacyMoneyCommandError.NEGATIVE_AMOUNT ->
                "<red>Сумма выдачи или списания не может быть отрицательной."
            LegacyMoneyCommandError.GIVE_ALL_REQUIRES_GIVE ->
                "<red>Цель <gray>* <red>доступна только для действия <gray>give<red>."
        }

    private fun warpCommand(ev: PlayerCommandPreprocessEvent, args: List<String>) {
        if (ev.player.hasPermission("arc.bypass-portal")) return
        if (!commandConfig.bool("portal.command-portals", true)) return
        if (args.size < 2) return

        val excludedSubCommands = commandConfig.stringList("portal.excluded-sub-commands").toHashSet()
        val aliases = commandConfig.stringList("portal.aliases").toHashSet()
        val mainCommand = args[0].substring(1)
        val isCmiWarp = "/cmi" == args[0] && "warp" == args[1]

        if (!aliases.contains(mainCommand) && !isCmiWarp) return
        if (excludedSubCommands.contains(args[1])) return

        var warpExists = false
        val ifCheck = commandConfig.bool("portal.check-player-warps", true) ||
            commandConfig.bool("portal.check-cmi-warps", true)
        if (commandConfig.bool("portal.check-player-warps", true) && HookRegistry.playerWarpsHook != null) {
            warpExists = warpExists || HookRegistry.playerWarpsHook!!.warpExists(args[1], ev.player)
        }
        if (commandConfig.bool("portal.check-cmi-warps", true) && HookRegistry.cmiHook != null) {
            warpExists = warpExists || HookRegistry.cmiHook!!.warpExists(args[1])
        }
        if (ifCheck && !warpExists) return
        Portal(ev.player.uniqueId, PortalData(PortalData.ActionType.COMMAND, null, null, ev.message.substring(1)))
        ev.isCancelled = true
    }
}

private fun activeRedisEconomyCurrencyNames(): Collection<String> =
    runCatching { RedisEconomyAPI.getAPI()?.currenciesWithNames?.keys.orEmpty() }.getOrDefault(emptyList())
