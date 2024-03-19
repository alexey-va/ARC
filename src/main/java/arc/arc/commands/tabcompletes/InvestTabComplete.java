package arc.arc.commands.tabcompletes;

import arc.arc.stock.*;
import arc.arc.xserver.playerlist.PlayerManager;
import org.apache.http.cookie.SM;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

public class InvestTabComplete implements TabCompleter {
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(strings.length == 0) return null;
        String last = strings[strings.length-1];

        if(last.isEmpty()){
            return List.of("-t", "-s", "-amount", "-leverage", "-up", "-down", "-uuid", "-player");
        }

        if(last.startsWith("-t")){
            return Stream.of("-t:buy","-t:short","-t:close","-t:add-money", "-t:withdraw-money", "-t:balance", "-t:gains", "-t:auto",
                    "-t:prune-history", "-t:menu", "-t:update", "-t:give-dividend")
                    .filter(str -> str.indexOf(last)==0)
                    .toList();
        }

        if(last.startsWith("-player")){
            return PlayerManager.getPlayerNames().stream().toList();
        }

        if(last.startsWith("-s")){
            return StockMarket.stocks().stream()
                    .map(Stock::getSymbol)
                    .map(str -> "-s:"+str)
                    .toList();
        }

        if(last.startsWith("-a")){
            return List.of("-amount:1");
        }

        if(last.startsWith("-l")){
            return List.of("-leverage:1");
        }

        if(last.startsWith("-uu")){
            if(commandSender instanceof Player player){
                StockPlayer stockPlayer = StockPlayerManager.getNow(player.getUniqueId());
                if(stockPlayer == null) return null;
                //System.out.println(stockPlayer.getPositionMap().values());
                return stockPlayer.getPositionMap().values()
                        .stream().flatMap(list -> list.stream().map(Position::getPositionUuid))
                        .filter(Objects::nonNull)
                        .map(UUID::toString)
                        .map(str -> "-uuid:"+str)
                        .toList();
            }
        }

        if(last.startsWith("-u")){
            return List.of("-up:1000");
        }

        if(last.startsWith("-d")){
            return List.of("-down:1000");
        }

        return null;
    }
}
