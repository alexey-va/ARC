package arc.arc.commands;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.guis.StoreGui;
import arc.arc.store.Store;
import arc.arc.store.StoreManager;
import arc.arc.util.GuiUtils;
import arc.arc.util.TextUtil;
import arc.arc.xserver.playerlist.PlayerManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public class ArcStoreCommand implements CommandExecutor, TabCompleter {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (!(commandSender instanceof Player player)) {
            commandSender.sendMessage("This command is player-only!");
            return true;
        }
        String permission = "arc.store";
        Path configPath = ARC.plugin.getDataFolder().toPath().resolve("store");
        Config config = ConfigManager.getOrCreate(configPath, "store.yml", "store");

        if (!player.hasPermission(permission)) {
            player.sendMessage(TextUtil.noPermissions());
            return true;
        }

        if(strings.length == 1 && strings[0].equalsIgnoreCase("dump")){
            List<ItemStack> stacks = StoreManager.getStore(player.getUniqueId()).join().getItemList();
            System.out.println("Dumping store for " + player.getName());
            for (ItemStack stack : stacks) {
                System.out.println(stack);
            }
        }

        String playerName = strings.length == 0 ? player.getName() : strings[0];
        boolean self = playerName.equalsIgnoreCase(player.getName());

        if (!self && !player.hasPermission("arc.store.others")) {
            player.sendMessage(TextUtil.noPermissions());
            return true;
        }

        // check
        UUID uuid = player.getUniqueId();
        if (!self) {
            if(playerName.length() > 30){
                uuid = UUID.fromString(playerName);
            } else {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
                if (offlinePlayer.hasPlayedBefore()) {
                    uuid = offlinePlayer.getUniqueId();
                } else {
                    player.sendMessage(
                            TextUtil.mm(
                                    config.string("store.messages.player-not-found")
                                            .replace("%player%", playerName)
                            )
                    );
                    return true;
                }
            }
        }

        var future = StoreManager.getStore(uuid);
        future.thenAccept(store -> GuiUtils.constructAndShowAsync(
                () -> new StoreGui(config, player, store), player, 0));

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (commandSender.hasPermission("arc.store.others")) {
            return PlayerManager.getPlayerNames().stream().toList();
        }
        return List.of();
    }
}
