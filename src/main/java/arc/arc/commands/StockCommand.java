package arc.arc.commands;

import arc.arc.ARC;
import arc.arc.configs.StockConfig;
import arc.arc.hooks.HookRegistry;
import arc.arc.stock.*;
import arc.arc.stock.gui.SymbolSelector;
import arc.arc.util.GuiUtils;
import arc.arc.util.TextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class StockCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

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
                log.trace("Loaded STOCK data for {}", sp.getPlayerName());
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
