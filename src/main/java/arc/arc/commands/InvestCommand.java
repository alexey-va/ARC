package arc.arc.commands;

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

        Player player = (Player) commandSender;

        if(strings.length == 0){
            new SymbolSelector(player).show(player);
            return true;
        }

        StockPlayer stockPlayer = StockPlayerManager.getOrCreate(player);

        ParsedCommand parsedCommand = parseArgs(strings);
        String type = parsedCommand.pars.get("t");

        if(type.equals("add-money")){
            double amount = Double.parseDouble(parsedCommand.pars.getOrDefault("amount", "1000"));
            StockPlayerManager.addToTradingBalanceFromVault(stockPlayer, amount);
            return true;
        } else if(type.equals("withdraw-money")){
            double amount = Double.parseDouble(parsedCommand.pars.getOrDefault("amount", "1000"));
            StockPlayerManager.addToTradingBalanceFromVault(stockPlayer, -amount);
            return true;
        } else if(type.equals("balance")){
            double balance = stockPlayer.getBalance();
            player.sendMessage(balance+" | total: "+stockPlayer.totalBalance());
            return true;
        } else if(type.equals("auto")){
            StockPlayerManager.switchAuto(stockPlayer);
            return true;
        }

        String symbol = parsedCommand.pars.get("s").toUpperCase();
        Stock stock = StockMarket.stock(symbol);
        if(stock == null){
            System.out.println("Could not find stock "+symbol);
            return true;
        }

        if(type.equals("prune-history")){
            if(!player.hasPermission("arc.stocks.prunehistory")) return true;
            StockMarket.pruneHistory(symbol);
            return true;
        }



        double amount = Double.parseDouble(parsedCommand.pars.getOrDefault("amount", "1.0"));
        int leverage = Integer.parseInt(parsedCommand.pars.getOrDefault("leverage", "1"));
        double up = Double.parseDouble(parsedCommand.pars.getOrDefault("up", "1000"));
        double down = Double.parseDouble(parsedCommand.pars.getOrDefault("down", "1000"));


        if(type.equals("buy")){
            StockPlayerManager.buyStock(stockPlayer, stock, amount, leverage, up, down);
            return true;
        } else if(type.equals("short")){
            StockPlayerManager.shortStock(stockPlayer, stock, amount, leverage, up, down);
            return true;
        } else if(type.equals("close")){
            UUID uuid = UUID.fromString(parsedCommand.pars.get("uuid"));
            int reason = Integer.parseInt(parsedCommand.pars.getOrDefault("reason", "1"));
            StockPlayerManager.closePosition(stockPlayer, symbol, uuid, reason);
        } else if(type.equals("gains")){
            UUID uuid = UUID.fromString(parsedCommand.pars.get("uuid"));
            Optional<Position> optional = stockPlayer.find(symbol, uuid);
            if(optional.isEmpty()){
                player.sendMessage("Could not find this position!");
                return true;
            }
            Position position = optional.get();
            Stock stock1 = StockMarket.stock(position.getSymbol());
            player.sendMessage("Gains: "+position.gains(stock1.getPrice())+" | comission: "+position.getCommission() +" | total: "+(position.gains(stock1.getPrice()) - position.getCommission()));
        }

        return true;
    }

    // /arc-invest -t:buy/short/add-money/withdraw-money/close -s:AAPL -amount:1 -leverage:3 -up:1000 -down:1000 -uuid:UUID

    public record ParsedCommand(Map<String, String> pars, List<String> args){}
    private static ParsedCommand parseArgs(String[] strings){
        List<String> args = new ArrayList<>();
        Map<String, String> pars = new HashMap<>();
        for(String s : strings){
            if(!s.startsWith("-")) args.add(s);
            else{
                s = s.substring(1);
                String[] temp = s.split(":");
                pars.put(temp[0], temp.length == 2 ? temp[1] : null);
            }
        }
        return new ParsedCommand(pars, args);
    }
}
