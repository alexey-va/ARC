package arc.arc.commands;

import arc.arc.configs.StockConfig;
import arc.arc.hooks.HookRegistry;
import arc.arc.stock.*;
import arc.arc.stock.gui.PositionMenu;
import arc.arc.stock.gui.SymbolSelector;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class InvestCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        ParsedCommand parsedCommand = parseArgs(strings);
        String type = parsedCommand.pars.get("t");

        if ("update".equals(type)) {
            if(!commandSender.hasPermission("arc.stocks.update-images")) return true;
            if (HookRegistry.yamipaHook == null || StockConfig.stockMarketLocation == null) return true;
            StockConfig.stockMarketLocation.getNearbyPlayers(StockConfig.updateImagesRadius)
                    .forEach(p -> HookRegistry.yamipaHook.updateImages(p.getLocation(), p));
            return true;
        }

        Player player = (Player) commandSender;

        if (strings.length == 0) {
            new SymbolSelector(player).show(player);
            return true;
        }

        StockPlayer stockPlayer = StockPlayerManager.getOrCreate(player);

        switch (type) {
            case "add-money" -> {
                double amount = Double.parseDouble(parsedCommand.pars.getOrDefault("amount", "1000"));
                StockPlayerManager.addToTradingBalanceFromVault(stockPlayer, amount);
                return true;
            }
            case "withdraw-money" -> {
                double amount = Double.parseDouble(parsedCommand.pars.getOrDefault("amount", "1000"));
                StockPlayerManager.addToTradingBalanceFromVault(stockPlayer, -amount);
                return true;
            }
            case "balance" -> {
                double balance = stockPlayer.getBalance();
                player.sendMessage(balance + " | total: " + stockPlayer.totalBalance());
                return true;
            }
            case "auto" -> {
                StockPlayerManager.switchAuto(stockPlayer);
                return true;
            }
        }

        String symbol = parsedCommand.pars.get("s").toUpperCase();
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) {
            System.out.println("Could not find stock " + symbol);
            return true;
        }

        if (type.equals("prune-history")) {
            if (!player.hasPermission("arc.stocks.prunehistory")) return true;
            StockMarket.pruneHistory(symbol);
            return true;
        }


        double amount = Double.parseDouble(parsedCommand.pars.getOrDefault("amount", "1.0"));
        int leverage = Integer.parseInt(parsedCommand.pars.getOrDefault("leverage", "1"));
        double up = Double.parseDouble(parsedCommand.pars.getOrDefault("up", "1000"));
        double down = Double.parseDouble(parsedCommand.pars.getOrDefault("down", "1000"));


        switch (type) {
            case "buy" -> {
                StockPlayerManager.buyStock(stockPlayer, stock, amount, leverage, up, down);
                return true;
            }
            case "short" -> {
                StockPlayerManager.shortStock(stockPlayer, stock, amount, leverage, up, down);
                return true;
            }
            case "close" -> {
                UUID uuid = UUID.fromString(parsedCommand.pars.get("uuid"));
                int reason = Integer.parseInt(parsedCommand.pars.getOrDefault("reason", "1"));
                StockPlayerManager.closePosition(stockPlayer, symbol, uuid, reason);
            }
        }

        return true;
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
