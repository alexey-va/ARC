package arc.arc.commands.framework;

import java.util.HashMap;
import java.util.Map;

public class SubCommand {


    String name;
    String permission;
    String usage;
    String description;

    Map<String, SubCommand> subCommands = new HashMap<>();

}
