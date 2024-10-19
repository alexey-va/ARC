package arc.arc.commands.framework;

import arc.arc.util.TextUtil;
import lombok.Data;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;

@Data
public abstract class ArcCommand implements CommandExecutor, TabCompleter {

    ArcCommand origin;

    String name;
    String permission;

    Component description;
    Component usage;
    Component noPermissionMessage;

    Map<String, ArcCommand> subCommands = new HashMap<>();
    List<Par> parameters;

    public abstract boolean execute(CommandSender sender, CommandContext context);

    public abstract Set<String> parameters(CommandSender sender, Par par);

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command var2, @NotNull String var3, @NotNull String[] args) {
        if (permission != null && !sender.hasPermission(this.permission)) {
            if (noPermissionMessage != null) {
                sender.sendMessage(noPermissionMessage);
            } else {
                sender.sendMessage(TextUtil.noPermissions());
            }
            return true;
        }

        String subCommand = args.length > 0 ? args[0] : null;
        boolean res;
        if (subCommands.containsKey(subCommand)) {
            ArcCommand subArcCommand = subCommands.get(subCommand);
            String[] subArgs = new String[args.length - 1];
            System.arraycopy(args, 1, subArgs, 0, args.length - 1);
            res = subArcCommand.onCommand(sender, var2, var3, subArgs);
        } else {
            CommandContext context = CommandContext.of(args, parameters);
            for (CommandContext.ParseError parseError : context.getParseErrorList()) {
                sender.sendMessage(Component.text(parseError.getMessage()));
            }
            res = execute(sender, context);
        }
        if (!res && usage != null) {
            sender.sendMessage(usage);
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        String subCommand = strings.length > 0 ? strings[0] : null;
        if (subCommand != null && subCommands.containsKey(subCommand)) {
            ArcCommand subArcCommand = subCommands.get(subCommand);
            String[] subArgs = new String[strings.length - 1];
            System.arraycopy(strings, 1, subArgs, 0, strings.length - 1);
            return subArcCommand.onTabComplete(commandSender, command, s, subArgs);
        }

        Set<String> completes = new HashSet<>();
        String current = strings.length > 0 ? strings[strings.length - 1] : "";
        parameters.stream()
                .filter(par -> current.startsWith("-" + par.getName()))
                .findFirst()
                .ifPresent(currentPar -> {
                    Set<String> parameters1 = parameters(commandSender, currentPar);
                    for (String str : parameters1) {
                        completes.add("-" + currentPar.getName() + ":" + str);
                    }
                });

        for (ArcCommand sub : subCommands.values()) completes.add(sub.getName());
        for (Par par : parameters) {
            Object defaultValue = par.getDefaultValue();
            if (defaultValue == null) defaultValue = par.getArgType().getDefaultValue();
            completes.add("-" + par.getName() + ":" + defaultValue);
        }
        String last = strings[strings.length - 1];
        return completes.stream().filter(s1 -> s1.startsWith(last)).toList();
    }

}
