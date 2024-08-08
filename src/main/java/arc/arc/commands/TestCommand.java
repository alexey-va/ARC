package arc.arc.commands;

import arc.arc.ARC;
import arc.arc.bschests.PersonalLootManager;
import arc.arc.leafdecay.LeafDecayManager;
import com.jeff_media.customblockdata.CustomBlockData;
import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.NBTHandler;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public class TestCommand implements CommandExecutor, TabCompleter {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (!commandSender.hasPermission("arc.test")) return true;

        if (strings.length == 0) {
            commandSender.sendMessage("Hello, World!");
            return true;
        } else if (strings.length == 1) {
            if (strings[0].equalsIgnoreCase("nbt")) {
                Player player = (Player) commandSender;
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    commandSender.sendMessage("You must be holding an item!");
                    return true;
                }
                ReadableNBT readableNBT = NBT.readNbt(hand);
                commandSender.sendMessage(readableNBT.toString());
                System.out.println(readableNBT);
                for (String key : readableNBT.getKeys()) {
                    commandSender.sendMessage(key + ": " + readableNBT.get(key, new NBTHandler<>() {
                        @Override
                        public void set(@NotNull ReadWriteNBT readWriteNBT, @NotNull String s, @NotNull Object o) {

                        }

                        @Override
                        public Object get(@NotNull ReadableNBT readableNBT, @NotNull String s) {
                            return readableNBT.toString();
                        }
                    }));
                }
            } else if (strings[0].equalsIgnoreCase("leaf")) {
                Block targetBlock = ((Player) commandSender).getTargetBlock(null, 5);
                boolean b = LeafDecayManager.leafChecker.shouldDecay(targetBlock, Set.of());
                Collection<Block> floatingBlobs = LeafDecayManager.leafChecker.findFloatingBlobs(targetBlock, Set.of(), 20, 20, true, Set.of());
                commandSender.sendMessage("Floating blobs: " + floatingBlobs);
                commandSender.sendMessage("Should decay: " + b);
            } else if (strings[0].equalsIgnoreCase("ploot")) {
                Block targetBlock = ((Player) commandSender).getTargetBlock(null, 5);
                commandSender.sendMessage("Block: " + targetBlock);
                PersonalLootManager.processChestGen(targetBlock);
            } else if (strings[0].equalsIgnoreCase("blockdata")) {
                Block targetBlock = ((Player) commandSender).getTargetBlock(null, 5);
                commandSender.sendMessage("Block: " + targetBlock);
                CustomBlockData data = new CustomBlockData(targetBlock, ARC.plugin);
                data.getKeys().forEach(key -> {
                    commandSender.sendMessage(key + ": " + data.get(key, data.getDataType(key)));
                });
            } else {
                commandSender.sendMessage("Invalid argument!");
            }
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if(strings.length == 1) {
            return List.of("nbt", "leaf", "ploot", "blockdata");
        }
        return null;
    }
}
