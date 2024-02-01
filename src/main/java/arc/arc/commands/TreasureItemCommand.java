package arc.arc.commands;

import arc.arc.ARC;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class TreasureItemCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(commandSender instanceof Player player){
            ItemStack stack = player.getInventory().getItemInMainHand();
            if(stack.getType() == Material.AIR) return true;

            File file = new File(ARC.plugin.getDataFolder()+File.separator+"test.yml");
            if(!file.exists()){
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
            String id = UUID.randomUUID().toString();
            configuration.set(id+".a", stack.serialize());
            configuration.set(id+".b", stack.getItemMeta().serialize());
            configuration.set(id+".c", stack.getItemMeta().getAsString());
            try {
                configuration.save(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return false;
    }
}
