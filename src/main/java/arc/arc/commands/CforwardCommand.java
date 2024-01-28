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

import java.util.List;
import java.util.UUID;

public class CforwardCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(!commandSender.hasPermission("arc.cforward")){
            commandSender.sendMessage(TextUtil.noPermissions());
            return true;
        }

        if(strings.length < 3){
            commandSender.sendMessage("Wrong number of arguments!");
            return true;
        }

        StringBuilder commandBuilder = new StringBuilder();
        for(int i=2;i<strings.length;i++){
            String str = strings[i];
            commandBuilder.append(str).append(" ");
        }
        String com = commandBuilder.toString().trim();
        String serv = strings[1];

        CommandData data = CommandData.builder()
                .sender(CommandData.Sender.PLAYER)
                .everywhere(false)
                .servers(List.of(serv))
                .command(com)
                .build();
        if(strings[0].length() == 36) data.setPlayerUuid(UUID.fromString(strings[0]));
        else data.setPlayerName(strings[0]);

        NetworkRegistry.commandSender.dispatch(data);
        commandSender.sendMessage(
                Component.text("Команда [", NamedTextColor.GREEN)
                        .append(Component.text(com, NamedTextColor.GRAY))
                        .append(Component.text("] успешно отправлена для игрока "+strings[0]+" на сервер "+serv+"!", NamedTextColor.GREEN))
        );

        return true;
    }
}
