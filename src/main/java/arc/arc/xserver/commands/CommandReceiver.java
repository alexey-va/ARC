package arc.arc.xserver.commands;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Log4j2
public class CommandReceiver implements ChannelListener {

    private static final List<AwaitingExecution> awaitingCommands = new ArrayList<>();
    private static BukkitTask commandTask;
    @Getter
    private final String channel;
    Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "misc.yml");

    public CommandReceiver(String channel) {
        this.channel = channel;
        if (commandTask != null && !commandTask.isCancelled()) commandTask.cancel();
        createTask();
    }

    private void createTask() {
        commandTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (awaitingCommands.isEmpty()) return;
                Iterator<AwaitingExecution> iterator = awaitingCommands.iterator();
                while (iterator.hasNext()) {
                    AwaitingExecution execution = iterator.next();
                    if (execution.playerName != null) {
                        Player player = Bukkit.getPlayer(execution.playerName);
                        if (player == null || !player.isOnline() || !player.getName().equals(execution.playerName))
                            continue;
                        player(player, execution.command);
                        iterator.remove();
                    } else if (execution.playerUuid != null) {
                        Player player = Bukkit.getPlayer(execution.playerUuid);
                        if (player == null || !player.isOnline() || !player.getUniqueId().equals(execution.playerUuid))
                            continue;
                        player(player, execution.command);
                        iterator.remove();
                    } else if (System.currentTimeMillis() - execution.created > execution.timeout) {
                        System.out.print("Could not execute " + execution.command + " for " + execution.playerName + " in time!");
                        iterator.remove();
                    }
                }
            }
        }.runTaskTimer(ARC.plugin, 0L, 1L);
    }

    @Override
    public void consume(String channel, String message, String server) {
        System.out.println("Received command: " + channel + " " + message + " " + server);
        CommandData data = RedisSerializer.fromJson(message, CommandData.class);

        if (data == null) return;
        if (!data.everywhere && !data.servers.contains(ARC.serverName)) {
            System.out.println("Command not for this server!");
            return;
        }
        if (data.notOrigin && server.equals(ARC.serverName)) {
            System.out.println("Command not for origin server!");
            return;
        }
        if (data.sender == CommandData.Sender.PLAYER) {
            AwaitingExecution awaitingExecution = new AwaitingExecution.AwaitingExecutionBuilder()
                    .created(System.currentTimeMillis())
                    .timeout(5000)
                    .playerName(data.playerName)
                    .playerUuid(data.playerUuid)
                    .command(data.getCommand())
                    .build();
            awaitingCommands.add(awaitingExecution);
        } else {
            console(data.getCommand());
        }
    }

    private void console(String command) {
        if (command == null) {
            System.out.println("Received null command!");
            return;
        }
        System.out.println("Executing command: " + command);
        new BukkitRunnable() {
            @Override
            public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        }.runTask(ARC.plugin);
    }

    private void player(Player player, String command) {
        if (player == null || !player.isOnline()) {
            System.out.println("Player " + player.getName() + " is not online! Cant execute command " + command);
            return;
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                player.performCommand(command);
            }
        }.runTaskLater(ARC.plugin, config.integer("xaction.command-delay-ticks", 60));
    }


    @Builder
    @AllArgsConstructor
    static class AwaitingExecution {
        String command;
        String playerName;
        UUID playerUuid;
        long created;
        long timeout;
    }
}
