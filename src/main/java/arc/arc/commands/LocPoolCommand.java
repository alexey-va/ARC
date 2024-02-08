package arc.arc.commands;

import arc.arc.treasurechests.locationpools.LocationPool;
import arc.arc.treasurechests.locationpools.LocationPoolManager;
import arc.arc.util.TextUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;

public class LocPoolCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if (!commandSender.hasPermission("arc.loc-pool")) {
            commandSender.sendMessage(TextUtil.noPermissions());
            return true;
        }
        if (commandSender instanceof ConsoleCommandSender) {
            commandSender.sendMessage("No console!");
            return true;
        }
        Player player = (Player) commandSender;
        String current = LocationPoolManager.getEditing(player.getUniqueId());

        if (strings.length == 0) {
            commandSender.sendMessage("Current location pools: " +
                    LocationPoolManager.getAll().stream()
                            .map(LocationPool::getId)
                            .collect(Collectors.joining(", ", "[", "]"))
            );
            return true;
        }

        if (strings.length == 1 && strings[0].equals("edit")) {
            if (current == null) {
                player.sendMessage("You are not editing any Location Pool!");
                return true;
            }
            LocationPoolManager.cancelEditing(player.getUniqueId());
            player.sendMessage("You are not editing " + current + " anymore!");
            return true;
        }
        if (strings.length == 1 && strings[0].equals("delete")) {
            player.sendMessage("Specify location pool to delete");
            return true;
        }

        String action = strings[0];
        String id = strings[1];

        if (action.equals("edit")) {
            if (id.equals(current)) {
                LocationPoolManager.cancelEditing(player.getUniqueId());
                player.sendMessage("You are not editing " + current + " anymore!");
                return true;
            }

            LocationPoolManager.setEditing(player.getUniqueId(), id);
            player.sendMessage("Now editing " + id + " location pool!");
        } else if (action.equals("delete")) {
            boolean res = LocationPoolManager.delete(id);
            if (res) {
                commandSender.sendMessage("Deleted " + id + " successfully!");
                LocationPoolManager.cancelEditing(player.getUniqueId());
            } else {
                commandSender.sendMessage("No such location pool " + id + "!");
            }
            return true;
        }

        return true;
    }
}
