package arc.arc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class Command implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, org.bukkit.command.@NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(strings.length == 0) {
            commandSender.sendMessage("No args!");
            return true;
        }
        if(strings.length==1){
            if(strings[0].equalsIgnoreCase("reload")){
                ARC.plugin.reloadConfig();
                ARC.plugin.load();
                commandSender.sendMessage(Component.text("Перезагрузка успешна!", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
                return true;
            }
        }

        return false;
    }
}
