package ru.arc.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.configs.StockConfig;
import ru.arc.hooks.HookRegistry;
import ru.arc.stock.HistoryManager;
import ru.arc.stock.Stock;
import ru.arc.stock.StockMarket;
import ru.arc.stock.StockPlayer;
import ru.arc.stock.StockPlayerManager;
import ru.arc.stock.gui.SymbolSelector;
import ru.arc.util.GuiUtils;
import ru.arc.util.TextUtil;

import static ru.arc.util.Logging.info;

public class StockCommand implements CommandExecutor {

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "stocks/stock.yml");

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(!config.bool("enabled", false)) {
            info("Stocks are disabled");
            commandSender.sendMessage(config.componentDef("messages.disabled","<red>Здесь эта команда недоступна."));
            return true;
        }

        ParsedCommand parsedCommand = parseArgs(strings);
        String type = parsedCommand.pars.get("t");

        if ("update".equals(type)) {
            if (!commandSender.hasPermission("arc.stocks.update-images")) return true;
            if (HookRegistry.yamipaHook == null || StockConfig.stockMarketLocation == null) return true;
            var list = StockConfig.stockMarketLocation.getNearbyPlayers(StockConfig.updateImagesRadius)
                    .stream().filter(p -> !p.hasMetadata("NPC"))
                    .toList();
            if (list.isEmpty()) return true;
            Player player = list.get(0);
            HookRegistry.yamipaHook.updateImages(player.getLocation(), list);
            return true;
        } else if ("give-dividend".equals(type)) {
            StockPlayerManager.giveDividend(parsedCommand.pars.get("s").toUpperCase());
            return true;
        } else if ("prune-history".equals(type)) {
            if (!commandSender.hasPermission("arc.stocks.prunehistory")) return true;
            HistoryManager.pruneHistory(parsedCommand.pars.get("s"));
            return true;
        }

        Player player = (Player) commandSender;

        if (strings.length == 0 || "menu".equals(type)) {
            if(!commandSender.hasPermission("arc.stocks.buy")) {
                commandSender.sendMessage(TextUtil.noPermissions());
                return true;
            }
            String name = parsedCommand.pars.get("player");
            CompletableFuture<StockPlayer> stockPlayer;
            if (name == null) {
                stockPlayer = StockPlayerManager.getOrCreate(player);
            } else {
                if (!commandSender.hasPermission("arc.stocks.menu.other")) {
                    commandSender.sendMessage(TextUtil.noPermissions());
                    return true;
                }

                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(name);
                if (offlinePlayer == null || offlinePlayer.getUniqueId() == null) {
                    commandSender.sendMessage("Could not find player: " + name);
                    return true;
                }
                stockPlayer = StockPlayerManager.getOrNull(offlinePlayer.getUniqueId());
            }
            stockPlayer.thenAccept(sp -> {
                GuiUtils.constructAndShowAsync(() -> new SymbolSelector(sp), player);
            });
            return true;
        }

        CompletableFuture<StockPlayer> stockPlayer = StockPlayerManager.getOrCreate(player);

        switch (type) {
            case "add-money" -> {
                double amount = Double.parseDouble(parsedCommand.pars.getOrDefault("amount", "1000"));
                stockPlayer.thenAccept(sp -> runInMainThread(() -> StockPlayerManager.addToTradingBalanceFromVault(sp, amount)));
                return true;
            }
            case "withdraw-money" -> {
                double amount = Double.parseDouble(parsedCommand.pars.getOrDefault("amount", "1000"));
                stockPlayer.thenAccept(sp -> runInMainThread(() -> StockPlayerManager.addToTradingBalanceFromVault(sp, -amount)));
                return true;
            }
            case "auto" -> {
                stockPlayer.thenAccept(StockPlayerManager::switchAuto);
                return true;
            }
        }

        String symbol = parsedCommand.pars.get("s").toUpperCase();
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) {
            System.out.println("Could not find stock " + symbol);
            return true;
        }


        double amount = Double.parseDouble(parsedCommand.pars.getOrDefault("amount", "1.0"));
        int leverage = Integer.parseInt(parsedCommand.pars.getOrDefault("leverage", "1"));
        double up = Double.parseDouble(parsedCommand.pars.getOrDefault("up", "1000"));
        double down = Double.parseDouble(parsedCommand.pars.getOrDefault("down", "1000"));


        switch (type) {
            case "buy" -> {
                stockPlayer.thenAccept(sp -> runInMainThread(() -> StockPlayerManager.buyStock(sp, stock, amount, leverage, up, down)));
                return true;
            }
            case "short" -> {
                stockPlayer.thenAccept(sp -> runInMainThread(() -> StockPlayerManager.shortStock(sp, stock, amount, leverage, up, down)));
                return true;
            }
            case "close" -> {
                UUID uuid = UUID.fromString(parsedCommand.pars.get("uuid"));
                int reason = Integer.parseInt(parsedCommand.pars.getOrDefault("reason", "1"));
                stockPlayer.thenAccept(sp -> runInMainThread(() -> StockPlayerManager.closePosition(sp, symbol, uuid, reason)));
            }
        }

        return true;
    }

    private void runInMainThread(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) runnable.run();
        else Bukkit.getScheduler().runTask(ARC.plugin, runnable);
    }

    // /arc-invest -t:buy/short/add-money/withdraw-money/close -s:AAPL -amount:1 -leverage:3 -up:1000 -down:1000 -uuid:UUID

    public record ParsedCommand(Map<String, String> pars, List<String> args) {
    }

    private static ParsedCommand parseArgs(String[] strings) {
        List<String> args = new ArrayList<>();
        Map<String, String> pars = new HashMap<>();
        for (String s : strings) {
            if (!s.startsWith("-")) args.add(s);
            else {
                s = s.substring(1);
                String[] temp = s.split(":");
                pars.put(temp[0], temp.length == 2 ? temp[1] : null);
            }
        }
        return new ParsedCommand(pars, args);
    }
}
