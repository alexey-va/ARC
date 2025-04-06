package ru.arc.commands.framework;

import ru.arc.util.TextUtil;
import lombok.Data;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiConsumer;

@Data
public abstract class ArcCommand implements CommandExecutor, TabCompleter {

    String permission;
    Component noPermissionMessage;

    public Map<String, BiConsumer<CommandSender, CommandContext>> subCommands = new HashMap<>();
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
        CommandContext context = CommandContext.of(args, parameters);
        for (CommandContext.ParseError parseError : context.getParseErrorList()) {
            sender.sendMessage(Component.text(parseError.getMessage()));
        }
        if (subCommands.containsKey(subCommand)) {
            subCommands.get(subCommand).accept(sender, context);
        } else {
            execute(sender, context);
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
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

        completes.addAll(subCommands.keySet());
        for (Par par : parameters) {
            Object defaultValue = par.getDefaultValue();
            if (defaultValue == null) defaultValue = par.getArgType().getDefaultValue();
            completes.add("-" + par.getName() + ":" + defaultValue);
        }
        String last = strings[strings.length - 1];
        return completes.stream().filter(s1 -> s1.startsWith(last)).toList();
    }

}
