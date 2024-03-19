package arc.arc.commands;

import arc.arc.ARC;
import arc.arc.board.guis.BoardGui;
import arc.arc.hooks.HookRegistry;
import arc.arc.util.GuiUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class Command implements CommandExecutor {
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

            String playerName = strings[1];

            Player player = ARC.plugin.getServer().getPlayer(playerName);
            if(player == null || !player.getName().equalsIgnoreCase(playerName)){
                commandSender.sendMessage("Игрок не найден!");
                return true;
            }

            HookRegistry.jobsHook.openBoostGui(player);
            return true;
        }

        return false;
    }
}
