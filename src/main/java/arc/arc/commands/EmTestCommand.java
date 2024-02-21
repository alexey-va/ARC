package arc.arc.commands;

import arc.arc.hooks.HookRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class EmTestCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        int tier = strings.length < 1 ? 0 : Integer.parseInt(strings[0]);
        int id = strings.length < 2 ? 0 : Integer.parseInt(strings[1]);
        ItemStack stack = HookRegistry.emHook.generateDrop(tier, (Player) commandSender, id);

        ((Player) commandSender).getInventory().addItem(stack);

        return false;
    }
}
