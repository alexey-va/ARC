package arc.arc.commands;

import arc.arc.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ResourcePackCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        String playerName = strings[0];
        Player player = Bukkit.getPlayer(playerName);
        if(player == null){
            commandSender.sendMessage("Could not find the player named "+playerName);
            return true;
        }

        Utils.sendResourcePack(player, "");
        return false;
    }
}
