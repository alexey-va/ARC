package arc.arc.commands.tabcompletes;

import arc.arc.treasurechests.locationpools.LocationPool;
import arc.arc.treasurechests.locationpools.LocationPoolManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class LocationPoolTabComplete implements TabCompleter {
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if(strings.length == 2){
            return LocationPoolManager.getAll().stream().map(LocationPool::getId).toList();
        }

        if(strings.length == 1){
            return List.of("delete", "edit");
        }
        return null;
    }
}
