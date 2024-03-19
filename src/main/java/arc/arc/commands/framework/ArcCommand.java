package arc.arc.commands.framework;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;

import java.util.HashMap;
import java.util.Map;

public abstract class ArcCommand implements CommandExecutor, TabCompleter {

    String name;
    String description;
    String usage;
    String permission;
    Map<String, SubCommand> subCommands = new HashMap<>();


}
