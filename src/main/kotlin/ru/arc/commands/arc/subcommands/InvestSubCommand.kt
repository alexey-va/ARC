package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.onlinePlayerNames
import ru.arc.commands.arc.tabComplete
import ru.arc.configs.ConfigManager
import ru.arc.configs.StockConfig
import ru.arc.hooks.HookRegistry
import ru.arc.stock.HistoryManager
import ru.arc.stock.StockMarket
import ru.arc.stock.StockPlayerManager
import ru.arc.stock.gui.SymbolSelector
import ru.arc.util.GuiUtils
import ru.arc.util.Logging.info
import java.util.UUID

/**
 * /arc invest - торговля акциями.
 *
 * Использование с параметрами вида -key:value:
 * - /arc invest -t:menu [-player:name] - открыть меню
 * - /arc invest -t:buy -s:SYMBOL -amount:N [-leverage:N] [-up:N] [-down:N]
 * - /arc invest -t:short -s:SYMBOL -amount:N [-leverage:N] [-up:N] [-down:N]
 * - /arc invest -t:close -s:SYMBOL -uuid:UUID [-reason:N]
 * - /arc invest -t:add-money -amount:N
 * - /arc invest -t:withdraw-money -amount:N
 * - /arc invest -t:auto
 * - /arc invest -t:update (admin)
 * - /arc invest -t:give-dividend -s:SYMBOL (admin)
 * - /arc invest -t:prune-history -s:SYMBOL (admin)
 */
object InvestSubCommand : SubCommand {

    override val configKey = "invest"
    override val defaultName = "invest"
    override val defaultPermission = "arc.stocks.buy"
    override val defaultDescription = "Торговля акциями"
    override val defaultUsage = "/arc invest [-t:action] [-s:symbol] [-amount:N] ..."
    override val defaultPlayerOnly = false // Console can do admin actions

    private val config get() = ConfigManager.of(ARC.plugin.dataPath, "stocks/stock.yml")

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (!config.bool("enabled", false)) {
            info("Stocks are disabled")
            sender.sendMessage(config.componentDef("messages.disabled", "<red>Здесь эта команда недоступна."))
            return true
        }

        val params = parseParams(args)
        val type = params["t"]

        // Admin commands
        when (type) {
            "update" -> {
                if (!sender.hasPermission("arc.stocks.update-images")) return true
                if (HookRegistry.yamipaHook == null || StockConfig.stockMarketLocation == null) return true
                val list = StockConfig.stockMarketLocation.getNearbyPlayers(StockConfig.updateImagesRadius)
                    .filter { !it.hasMetadata("NPC") }
                if (list.isEmpty()) return true
                HookRegistry.yamipaHook.updateImages(list[0].location, list)
                return true
            }

            "give-dividend" -> {
                if (!sender.hasPermission("arc.admin")) return true
                val symbol = params["s"]?.uppercase() ?: return true
                StockPlayerManager.giveDividend(symbol)
                return true
            }

            "prune-history" -> {
                if (!sender.hasPermission("arc.stocks.prunehistory")) return true
                HistoryManager.pruneHistory(params["s"])
                return true
            }
        }

        // Player commands
        val player = sender as? Player ?: run {
            sender.sendMessage(CommandConfig.playerOnly())
            return true
        }

        // Default: open menu
        if (args.isEmpty() || type == "menu") {
            val targetName = params["player"]
            val stockPlayerFuture = if (targetName == null) {
                StockPlayerManager.getOrCreate(player)
            } else {
                if (!sender.hasPermission("arc.stocks.menu.other")) {
                    sender.sendMessage(CommandConfig.noPermission())
                    return true
                }
                val offlinePlayer = Bukkit.getOfflinePlayer(targetName)
                StockPlayerManager.getOrNull(offlinePlayer.uniqueId)
            }
            stockPlayerFuture.thenAccept { sp ->
                if (sp != null) {
                    GuiUtils.constructAndShowAsync({ SymbolSelector(sp) }, player)
                }
            }
            return true
        }

        val stockPlayerFuture = StockPlayerManager.getOrCreate(player)

        when (type) {
            "add-money" -> {
                val amount = params["amount"]?.toDoubleOrNull() ?: 1000.0
                stockPlayerFuture.thenAccept { sp ->
                    runInMainThread { StockPlayerManager.addToTradingBalanceFromVault(sp, amount) }
                }
                return true
            }

            "withdraw-money" -> {
                val amount = params["amount"]?.toDoubleOrNull() ?: 1000.0
                stockPlayerFuture.thenAccept { sp ->
                    runInMainThread { StockPlayerManager.addToTradingBalanceFromVault(sp, -amount) }
                }
                return true
            }

            "auto" -> {
                stockPlayerFuture.thenAccept { StockPlayerManager.switchAuto(it) }
                return true
            }
        }

        val symbol = params["s"]?.uppercase() ?: run {
            sender.sendMessage(CommandConfig.get("invest.no-symbol", "<red>Укажите символ акции: -s:SYMBOL"))
            return true
        }
        val stock = StockMarket.stock(symbol) ?: run {
            sender.sendMessage(
                CommandConfig.get(
                    "invest.stock-not-found",
                    "<red>Акция <white>%symbol%<red> не найдена!",
                    "%symbol%",
                    symbol
                )
            )
            return true
        }

        val amount = params["amount"]?.toDoubleOrNull() ?: 1.0
        val leverage = params["leverage"]?.toIntOrNull() ?: 1
        val up = params["up"]?.toDoubleOrNull() ?: 1000.0
        val down = params["down"]?.toDoubleOrNull() ?: 1000.0

        when (type) {
            "buy" -> stockPlayerFuture.thenAccept { sp ->
                runInMainThread { StockPlayerManager.buyStock(sp, stock, amount, leverage, up, down) }
            }

            "short" -> stockPlayerFuture.thenAccept { sp ->
                runInMainThread { StockPlayerManager.shortStock(sp, stock, amount, leverage, up, down) }
            }

            "close" -> {
                val uuid = params["uuid"]?.let { UUID.fromString(it) } ?: run {
                    sender.sendMessage(CommandConfig.get("invest.no-uuid", "<red>Укажите UUID позиции: -uuid:UUID"))
                    return true
                }
                val reason = params["reason"]?.toIntOrNull() ?: 1
                stockPlayerFuture.thenAccept { sp ->
                    runInMainThread { StockPlayerManager.closePosition(sp, symbol, uuid, reason) }
                }
            }
        }

        return true
    }

    private fun parseParams(args: Array<String>): Map<String, String> {
        val params = mutableMapOf<String, String>()
        for (arg in args) {
            if (arg.startsWith("-")) {
                val parts = arg.substring(1).split(":", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = parts[1]
                }
            }
        }
        return params
    }

    private fun runInMainThread(action: Runnable) {
        if (Bukkit.isPrimaryThread()) action.run()
        else Bukkit.getScheduler().runTask(ARC.plugin, action)
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        val last = args.lastOrNull() ?: ""

        val suggestions = mutableListOf<String>()

        when {
            last.isEmpty() -> suggestions.addAll(
                listOf(
                    "-t",
                    "-s",
                    "-amount",
                    "-leverage",
                    "-up",
                    "-down",
                    "-uuid",
                    "-player"
                )
            )

            last.startsWith("-t") -> suggestions.addAll(
                listOf(
                    "-t:buy",
                    "-t:short",
                    "-t:close",
                    "-t:add-money",
                    "-t:withdraw-money",
                    "-t:auto",
                    "-t:menu",
                    "-t:update",
                    "-t:give-dividend",
                    "-t:prune-history"
                )
            )

            last.startsWith("-s") -> suggestions.add("-s:SYMBOL")
            last.startsWith("-player") -> {
                onlinePlayerNames().forEach { name ->
                    suggestions.add("-player:$name")
                }
            }

            last.startsWith("-a") -> suggestions.add("-amount:1000")
            last.startsWith("-l") -> suggestions.add("-leverage:1")
            last.startsWith("-uu") -> suggestions.add("-uuid:UUID")
            last.startsWith("-u") -> suggestions.add("-up:1000")
            last.startsWith("-d") -> suggestions.add("-down:1000")
        }

        return suggestions.tabComplete(last)
    }
}

