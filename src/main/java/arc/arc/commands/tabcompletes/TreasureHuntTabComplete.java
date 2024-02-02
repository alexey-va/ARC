package arc.arc.commands.tabcompletes;

import arc.arc.treasurechests.TreasureHuntManager;
import arc.arc.treasurechests.locationpools.LocationPool;
import arc.arc.treasurechests.locationpools.LocationPoolManager;
import arc.arc.configs.TreasureHuntConfig;
import arc.arc.treasurechests.rewards.TreasurePool;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class TreasureHuntTabComplete implements TabCompleter {
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(!commandSender.hasPermission("arc.treasure-hunt")) return null;

        if(strings.length == 1){
            return List.of("start", "stop");
        }

        if(strings.length == 2){
            return LocationPoolManager.getAll().stream().map(LocationPool::getId).toList();
        }

        if(strings.length == 3){
            LocationPool locationPool = LocationPoolManager.getPool(strings[1]);
            if(locationPool == null) return List.of("кол-во");
            return List.of((locationPool.getLocations().size()+""));
        }

        if(strings.length == 4){
            List<String> list = new ArrayList<>(TreasureHuntConfig.treasureHuntAliases.keySet());
            list.add("vanilla");
            list.add("ItemsAdderId");
            return list;
        }

        if(strings.length == 5){
            return TreasureHuntManager.getTreasurePools().stream().map(TreasurePool::getId).toList();
        }

        return null;
    }
}
