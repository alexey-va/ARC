package arc.arc.commands;

import arc.arc.network.NetworkRegistry;
import arc.arc.util.TextUtil;
import arc.arc.xserver.commands.CommandData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class MexCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(!commandSender.hasPermission("arc.mex")){
            commandSender.sendMessage(TextUtil.noPermissions());
            return true;
        }
        boolean notThis = false;
        StringBuilder commandBuilder = new StringBuilder();
        for(String str : strings){
            if(str.equals("--not-this")){
                notThis = true;
                continue;
            }
            commandBuilder.append(str).append(" ");
        }
        String com = commandBuilder.toString().trim();
        CommandData data = CommandData.builder()
                .sender(CommandData.Sender.CONSOLE)
                .everywhere(true)
                .notOrigin(notThis)
                .command(com)
                .build();
        NetworkRegistry.commandSender.dispatch(data);
        commandSender.sendMessage(
                Component.text("Команда [", NamedTextColor.GREEN)
                        .append(Component.text(com, NamedTextColor.GRAY))
                        .append(Component.text("] успешно отправлена!", NamedTextColor.GREEN))
        );

        return true;
    }
}
