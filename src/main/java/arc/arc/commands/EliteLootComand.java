package arc.arc.commands;

import arc.arc.eliteloot.*;
import arc.arc.util.GuiUtils;
import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class EliteLootComand implements CommandExecutor, TabCompleter {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if (!commandSender.hasPermission("arc.eliteloot")) return true;
        if (!(commandSender instanceof Player player)) return true;
        if (strings.length >= 1) {
            if (strings[0].equalsIgnoreCase("add")) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType() == Material.AIR) {
                    player.sendMessage("You must hold an item in your hand");
                    return true;
                }
                LootType lootType = EliteLootManager.toLootType(hand);
                if (lootType == null) {
                    player.sendMessage("This item is not supported by EliteLoot");
                    return true;
                }
                player.sendMessage("This item is of type: " + lootType.name());
                DecorItem decorItem = new DecorItem();
                decorItem.setMaterial(hand.getType());
                double weight = strings.length > 1 ? Double.parseDouble(strings[1]) : 1;
                decorItem.setWeight(weight);
                Integer modelId = hand.getItemMeta().hasCustomModelData() ? hand.getItemMeta().getCustomModelData() : 0;
                decorItem.setModelId(modelId);

                // read itemsadder nbt data from itemstack
                ReadableNBT readableNBT = NBT.readNbt(hand);
                if (readableNBT.hasTag("itemsadder")) {
                    ReadableNBT itemsadder = readableNBT.getCompound("itemsadder");
                    if (itemsadder != null) {
                        decorItem.setIaNamespace(itemsadder.getString("namespace"));
                        decorItem.setIaId(itemsadder.getString("id"));
                    }
                }


                ItemMeta meta = hand.getItemMeta();
                if (meta instanceof LeatherArmorMeta leatherMeta) {
                    decorItem.setColor(leatherMeta.getColor());
                }

                DecorPool pool = EliteLootManager.getMap().get(lootType);
                if (pool == null) {
                    pool = new DecorPool();
                    EliteLootManager.getMap().put(lootType, pool);
                }

                if (pool.contains(decorItem)) {
                    player.sendMessage("This item is already in the EliteLoot config");
                    return true;
                }

                EliteLootManager.getEliteLootConfigParser().addDecor(lootType, decorItem);
                EliteLootManager.getMap().get(lootType).add(decorItem, weight);
                player.sendMessage("Decor added to EliteLoot config");
            } else if (strings[0].equalsIgnoreCase("list")) {
                GuiUtils.constructAndShowAsync(() -> new EliteLootGui(player), player);
            }
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (strings.length == 1) {
            return List.of("add", "list");
        }
        return null;
    }
}
