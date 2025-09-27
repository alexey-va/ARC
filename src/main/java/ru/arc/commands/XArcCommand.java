package ru.arc.commands;

import ru.arc.ARC;
import ru.arc.commands.framework.ArcCommand;
import ru.arc.commands.framework.ArgType;
import ru.arc.commands.framework.CommandContext;
import ru.arc.commands.framework.Par;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.TextUtil;
import ru.arc.xserver.XActionManager;
import ru.arc.xserver.XCommand;
import ru.arc.xserver.playerlist.PlayerManager;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static ru.arc.util.Logging.warn;
import static ru.arc.util.TextUtil.mm;

@Slf4j
public class XArcCommand extends ArcCommand {

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "commands.yml");

    public XArcCommand() {
        setParameters(List.of(
                Par.of("servers", ArgType.STRING, null),
                Par.of("player", ArgType.STRING, null),
                Par.of("timeout", ArgType.INTEGER, 100),
                Par.of("uuid", ArgType.STRING, null),
                Par.of("move-to-server", ArgType.BOOLEAN, false),
                Par.of("delay", ArgType.INTEGER, 0),
                Par.of("sender", ArgType.STRING, "console")
        ));
    }

    @Override
    public boolean execute(CommandSender cmdSender, CommandContext context) {
        if (!cmdSender.hasPermission("arc.x")) {
            cmdSender.sendMessage(TextUtil.noPermissions());
            return true;
        }

        String command = String.join(" ", context.getTrimmedArgs());
        String servers = context.get("servers");
        Set<String> serverList;
        if (servers == null || servers.equalsIgnoreCase("all")) {
            serverList = null;
        } else {
            serverList = new HashSet<>(List.of(servers.split(",")));
        }
        String player = context.get("player");
        int timeout = context.get("timeout");
        String uuidStr = context.get("uuid");
        UUID uuid = uuidStr != null ? UUID.fromString(uuidStr) : null;
        Integer delay = context.get("delay");
        String senderStr = context.get("sender");
        XCommand.Sender sender = XCommand.Sender.valueOf(senderStr.toUpperCase());

        XCommand xCommand = XCommand.builder()
                .command(command)
                .playerName(player)
                .sender(sender)
                .ticksTimeout(timeout)
                .playerUuid(uuid)
                .servers(serverList)
                .ticksDelay(delay)
                .build();

        XActionManager.publish(xCommand);
        boolean moveToServer = context.get("move-to-server");
        if (moveToServer) {
            if (player == null) {
                warn("Cannot move player to server without specifying player {}", context);
                return true;
            }
            if (serverList == null || serverList.isEmpty()) {
                warn("Cannot move player to server without specifying server {}", context);
                return true;
            }
            if (serverList.size() != 1) {
                warn("Cannot move player to server, multiple servers specified: {}", serverList);
                return true;
            }
            Player player1;
            if (uuid != null) {
                player1 = Bukkit.getPlayer(uuid);
            } else {
                player1 = Bukkit.getPlayerExact(player);
            }
            if (player1 == null) {
                warn("Cannot move player to server, player not found: {}", player);
                return true;
            }
            XActionManager.movePlayerToServer(player1, serverList.iterator().next());
        }
        String message = config.string("xcommand.success-message", "<gold>Команда <gray>%command% <gold>успешна отправлена!");
        message = message.replace("%command%", command);
        cmdSender.sendMessage(mm(message));
        return true;
    }

    @Override
    public Set<String> parameters(CommandSender sender, Par par) {
        return switch (par.getName()) {
            case "player" -> PlayerManager.getPlayerNames();
            case "servers" -> PlayerManager.getServerNames();
            case "uuid" -> PlayerManager.getPlayerUuids().stream().map(UUID::toString).collect(Collectors.toSet());
            case "timeout" -> Set.of("100", "200", "300", "400", "500");
            case "move-to-server" -> Set.of("true", "false");
            case "delay" -> Set.of("0", "10", "20", "30", "40", "50", "60");
            case "sender" -> Set.of("console", "player");
            default -> Set.of();
        };
    }


}
