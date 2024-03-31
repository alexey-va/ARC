package arc.arc.commands;

import arc.arc.ARC;
import arc.arc.board.guis.BoardGui;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.guis.BaltopGui;
import arc.arc.hooks.HookRegistry;
import arc.arc.util.GuiUtils;
import arc.arc.util.HeadUtil;
import com.google.gson.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class Command implements CommandExecutor, TabCompleter {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, org.bukkit.command.@NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(strings.length == 0) {
            commandSender.sendMessage("No args!");
            return true;
        }
        if(strings.length==1){
            if(strings[0].equalsIgnoreCase("reload") && commandSender.hasPermission("arc.admin")){
                ARC.plugin.reloadConfig();
                ARC.plugin.loadConfig();
                ARC.hookRegistry.reloadHooks();
                commandSender.sendMessage(Component.text("Перезагрузка успешна!", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
                return true;
            }
            if(strings[0].equalsIgnoreCase("board")){
                Player player = (Player) commandSender;
                GuiUtils.constructAndShowAsync(() ->new BoardGui(player), player);
                return true;
            }

        }

        if(strings[0].equals("emshop")){

            if(HookRegistry.emHook == null){
                commandSender.sendMessage("EMHook is not loaded!");
                return true;
            }

            String playerName = strings[1];

            if(playerName.equalsIgnoreCase("reset")){
                HookRegistry.emHook.resetShop();
                commandSender.sendMessage("Shop reset!");
                return true;
            }

            Player player = ARC.plugin.getServer().getPlayer(playerName);
            if(player == null || !player.getName().equalsIgnoreCase(playerName)){
                commandSender.sendMessage("Игрок не найден!");
                return true;
            }


            boolean isGear = strings.length > 2 && strings[2].equalsIgnoreCase("gear");

            HookRegistry.emHook.openShopGui(player, isGear);
            return true;
        }

        if(strings[0].equalsIgnoreCase("jobsboosts")){
            if(HookRegistry.jobsHook == null){
                commandSender.sendMessage("JobsHook is not loaded!");
                return true;
            }

            String playerName;
            if (strings.length >= 2) playerName = strings[1];
            else playerName = commandSender.getName();

            Player player = ARC.plugin.getServer().getPlayer(playerName);
            if(player == null || !player.getName().equalsIgnoreCase(playerName)){
                commandSender.sendMessage("Игрок не найден!");
                return true;
            }

            HookRegistry.jobsHook.openBoostGui(player);
            return true;
        }

        if(strings[0].equalsIgnoreCase("baltop")){
            Config config = ConfigManager.getOrCreate(ARC.plugin.getDataFolder().toPath(), "baltop.yml", "baltop");
            GuiUtils.constructAndShowAsync(() -> new BaltopGui(config, (Player) commandSender), (Player) commandSender);
            return true;
        }
        return false;
    }


    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, org.bukkit.command.@NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(strings.length == 1){
            return List.of("reload", "board", "emshop", "jobsboosts", "baltop");
        }

        if(strings.length == 2){
            if(strings[0].equalsIgnoreCase("emshop")){
                return ARC.plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
            }
            if(strings[0].equalsIgnoreCase("jobsboosts")){
                return ARC.plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
            }
        }

        if(strings.length == 3){
            if(strings[0].equalsIgnoreCase("emshop")){
                return List.of("gear", "trinket");
            }
        }

        return null;
    }
}
