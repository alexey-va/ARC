package arc.arc.commands;

import arc.arc.ARC;
import arc.arc.treasurechests.TreasureHuntManager;
import arc.arc.treasurechests.rewards.ArcCommand;
import arc.arc.treasurechests.rewards.ArcItem;
import arc.arc.treasurechests.rewards.Treasure;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class TreasurePoolCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if (commandSender instanceof Player player) {
            if (strings.length < 2) {
                commandSender.sendMessage("Need at least 2 arguments");
                return true;
            }

            if (strings[0].equals("add")) {

                String poolId = strings[1];
                if (strings.length >= 3 && strings[2].equals("command")) {
                    StringBuilder builder = new StringBuilder();
                    for (int i = 3; i < strings.length; i++) builder.append(strings[i]).append(" ");
                    String com = builder.toString().trim();
                    Treasure treasure = new ArcCommand(com, false, null, null,1);
                    TreasureHuntManager.addTreasure(treasure, poolId);
                    commandSender.sendMessage("Treasure command added!");
                    return true;
                }

                if(strings[2].equals("item")){
                    ItemStack stack = player.getInventory().getItemInMainHand();
                    int quantity = strings.length >= 4 ? Integer.parseInt(strings[3]) : stack.getAmount();
                    if(stack.getType() == Material.AIR){
                        player.sendMessage("No item in mainhand");
                        return true;
                    }
                    Treasure treasure = new ArcItem(stack.asQuantity(1), quantity, false, null, null, 1);
                    TreasureHuntManager.addTreasure(treasure, poolId);
                    commandSender.sendMessage("Treasure item added!");
                    return true;
                }

            }
        }

        return false;
    }
}
