package ru.arc.listeners

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
import ru.arc.config.ConfigManager
import ru.arc.core.Tasks
import ru.arc.core.modules.EconomyModule
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.mm
import ru.arc.xserver.playerlist.PlayerManager
import java.util.UUID

class CommandListener : Listener {

    private val commandConfig = ConfigManager.of(ARC.instance.dataPath, "misc.yml")
    private var suppressCanonicalSetAudit = false

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerPlaceBlock(ev: BlockPlaceEvent) {
        if (Portal.isOccupied(ev.blockPlaced)) ev.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerCommand(ev: PlayerCommandPreprocessEvent) {
        val args = ev.message.split(" ")
        warpCommand(ev, args)
        moneyCommand(ev.player, ev, args)
        scheduleCanonicalSetAudit(args, ev.player.name)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onServerCommand(serverCommandEvent: ServerCommandEvent) {
        val args = serverCommandEvent.command.split(" ")
        moneyCommandServer(serverCommandEvent, args)
        scheduleCanonicalSetAudit(args, "Server")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun trackPlayerAdminEconomyCommand(ev: PlayerCommandPreprocessEvent) {
        AdminEconomyCommandTracker.track(ev.message.split(" "), ev.player.name)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun trackServerAdminEconomyCommand(ev: ServerCommandEvent) {
        val origin = EconomyCommandOriginResolver.resolve()
        AdminEconomyCommandTracker.track(
            ev.command.split(" "),
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
        if (!event.buffer.startsWith("/money ")) return
        val args = event.buffer.split(" ")
        var len = args.size
        if (event.buffer.endsWith(" ")) len++
        when (len) {
            2 -> event.completions = mutableListOf("give", "set", "take")
            3 -> event.completions = PlayerManager.getPlayerNames().toMutableList()
            4 -> event.completions = mutableListOf("100")
        }
    }

    private fun moneyCommand(player: Player, ev: Cancellable, args: List<String>) {
        val sub = setOf("give", "set", "take")
        if (args.size > 2 && args[0].equals("/money", ignoreCase = true) && sub.contains(args[1].lowercase())) {
            if (!player.hasPermission("rediseconomy.admin")) {
                player.sendMessage(TextUtil.noPermissions())
                return
            }
            if (args.size == 4) {
                ev.isCancelled = true
                try {
                    val money = args[3].toDouble()
                    val before = balanceBeforeSet(args[1], args[2])
                    val command = "money ${args[2]} vault ${args[1]} $money"
                    rerouteMoneyCommand { player.performCommand(command) }
                    scheduleSetDelta(args[1], args[2], player.name, before)
                    info("Rerouted {} to {}", args.joinToString(" "), command)
                } catch (e: Exception) {
                    error("Failed to reroute /money give command to /money <player> vault give <amount>", e)
                }
            } else {
                player.sendMessage(mm("<red>Правильное использование: <gray>/money give/set/take <игрок> <сумма>"))
            }
        }
    }

    private fun moneyCommandServer(ev: Cancellable, args: List<String>) {
        val sub = setOf("give", "set", "take")
        if (args.size > 2 && args[0].equals("money", ignoreCase = true) && sub.contains(args[1].lowercase())) {
            if (args.size == 4) {
                ev.isCancelled = true
                try {
                    val money = args[3].toDouble()
                    val before = balanceBeforeSet(args[1], args[2])
                    val command = "money ${args[2]} vault ${args[1]} $money"
                    rerouteMoneyCommand { ARC.trySeverCommand(command) }
                    scheduleSetDelta(args[1], args[2], "Server", before)
                    info("Rerouted {} to {}", args.joinToString(" "), command)
                } catch (e: Exception) {
                    error("Failed to reroute /money give command to /money <player> vault give <amount>", e)
                }
            }
        }
    }

    private fun balanceBeforeSet(action: String, target: String): Double? {
        if (!action.equals("set", ignoreCase = true)) return null
        val economy = EconomyModule.getEconomy() ?: return null
        return economy.getBalance(org.bukkit.Bukkit.getOfflinePlayer(target))
    }

    private fun auditSetDelta(action: String, target: String, actor: String, before: Double?) {
        if (!action.equals("set", ignoreCase = true) || before == null) return
        val economy = EconomyModule.getEconomy() ?: return
        val offlinePlayer = org.bukkit.Bukkit.getOfflinePlayer(target)
        val after = economy.getBalance(offlinePlayer)
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
                command == "money" && args.size == 5 && args[2].equals("vault", ignoreCase = true) && args[3].equals("set", ignoreCase = true) -> args[1]
                command == "cmi" && args.size == 5 && args[1].equals("money", ignoreCase = true) && args[2].equals("set", ignoreCase = true) -> args[3]
                else -> return
            }
        // Legacy /money set <player> <amount> is cancelled and audited through its rerouted command above.
        if (command == "money" && args.getOrNull(1)?.lowercase() in setOf("give", "set", "take")) return
        val before = balanceBeforeSet("set", target) ?: return
        scheduleSetDelta("set", target, actor, before)
    }

    private fun scheduleSetDelta(action: String, target: String, actor: String, before: Double?) {
        if (!action.equals("set", ignoreCase = true) || before == null) return
        Tasks.scheduler.runLater(1) { auditSetDelta(action, target, actor, before) }
    }

    private inline fun rerouteMoneyCommand(block: () -> Unit) {
        suppressCanonicalSetAudit = true
        try {
            block()
        } finally {
            suppressCanonicalSetAudit = false
        }
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
