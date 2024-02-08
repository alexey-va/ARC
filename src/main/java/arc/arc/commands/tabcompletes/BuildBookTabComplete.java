package arc.arc.commands.tabcompletes;

import arc.arc.autobuild.Building;
import arc.arc.autobuild.BuildingManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class BuildBookTabComplete implements TabCompleter {
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if(strings.length == 1){
            return BuildingManager.getBuildings().stream().map(Building::getFileName).toList();
        }

        if(strings.length == 2){
            return List.of("[model-id]");
        }
        return null;
    }
}
