package arc.arc.commands.tabcompletes;

import arc.arc.treasurechests.TreasureHuntManager;
import arc.arc.generic.treasure.TreasurePool;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class TreasurePoolTabcomplete implements TabCompleter {
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (strings.length == 1) {
            return List.of("add");
        }

        if (strings.length == 2) {
            List<String> list = TreasurePool.getTreasurePools().stream()
                    .map(TreasurePool::getId)
                    .collect(Collectors.toList());
            list.add("[new]");
            return list;
        }

        if (strings.length == 3) {
            return List.of("item", "command");
        }

        if (strings.length == 4 && strings[2].equals("item")) {
            return List.of("weight");
        }
        return null;
    }
}
