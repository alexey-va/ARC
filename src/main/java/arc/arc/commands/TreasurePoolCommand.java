package arc.arc.commands;

import arc.arc.generic.treasure.Treasure;
import arc.arc.generic.treasure.TreasureCommand;
import arc.arc.generic.treasure.TreasureItem;
import arc.arc.generic.treasure.TreasurePool;
import arc.arc.treasurechests.TreasureHuntManager;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

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
                    Treasure treasure = new TreasureCommand(com, Map.of(), 1);
                    TreasureHuntManager.addTreasure(treasure, poolId);
                    commandSender.sendMessage("Treasure command added! "+treasure);
                    return true;
                }

                if (strings[2].equals("item")) {
                    ItemStack stack = player.getInventory().getItemInMainHand();
                    int quantity = strings.length >= 4 ? Integer.parseInt(strings[3]) : stack.getAmount();
                    if (stack.getType() == Material.AIR) {
                        player.sendMessage("No item in mainhand");
                        return true;
                    }
                    Treasure treasure = new TreasureItem(stack.asQuantity(1), quantity, null, 1, Map.of());
                    TreasurePool pool = TreasurePool.getOrCreate(poolId);
                    pool.add(treasure);
                    TreasurePool.saveAllTreasurePools();
                    commandSender.sendMessage("Treasure item added! "+treasure);
                    return true;
                }

            }
        }

        return false;
    }
}
