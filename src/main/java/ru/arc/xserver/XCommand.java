package ru.arc.xserver;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;

import static ru.arc.util.Logging.debug;
import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.warn;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class XCommand extends XAction {

    String command;
    @Builder.Default
    Sender sender = Sender.CONSOLE;
    String playerName;
    UUID playerUuid;
    @Builder.Default
    int ticksTimeout = 20 * 5;
    @Builder.Default
    Integer ticksDelay = 40;
    Set<String> servers;

    private static final Config miscConfig = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "misc.yml");

    @Override
    protected void runInternal() {
        if (servers != null && !servers.contains(ARC.serverName)) {
            debug("Server {} not in list of servers: {}", ARC.serverName, servers);
            return;
        }
        if (command == null || sender == null || command.isEmpty()) {
            error("Something wrong with xcommand: {}", this);
            return;
        }
        if (sender == Sender.PLAYER && playerName == null && playerUuid == null) {
            error("Player name or uuid must be set for player sender {}", this);
            return;
        }
        if (playerName != null) {
            command = command.replace("%player_name%", playerName);
        }
        if (playerUuid != null) {
            command = command.replace("%player_uuid%", playerUuid.toString());
        }
        if (sender == Sender.CONSOLE && playerName == null && playerUuid == null) {
            ARC.trySeverCommand(command);
        } else {
            createAwaitingCommand();
        }
    }

    private void createAwaitingCommand() {
        AtomicInteger ticks = new AtomicInteger(-1);
        new BukkitRunnable() {
            @Override
            public void run() {
                Player player = null;
                if (playerName != null) {
                    player = Bukkit.getPlayer(playerName);
                } else if (playerUuid != null) {
                    player = Bukkit.getPlayer(playerUuid);
                }
                if (player == null) {
                    ticks.getAndIncrement();
                } else {
                    int delay = miscConfig.integer("xaction.command-delay-ticks", 10);
                    if (ticksDelay != null) {
                        delay = ticksDelay;
                    }
                    Player finalPlayer = player;
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (finalPlayer.isOnline() && sender == Sender.PLAYER) {
                                finalPlayer.performCommand(command);
                            } else {
                                ARC.trySeverCommand(command);
                            }
                        }
                    }.runTaskLater(ARC.plugin, delay);
                    cancel();
                }
                if (ticks.get() >= ticksTimeout) {
                    warn("Player not found for xcommand: {}", XCommand.this);
                    cancel();
                }
            }
        }.runTaskTimer(ARC.plugin, 0, 1);
    }

    public enum Sender {
        PLAYER,
        CONSOLE
    }

    /**
     * Creates a new XCommand with the given parameters.
     * Use this instead of the Lombok builder for Kotlin compatibility.
     */
    public static XCommand create(
            String command,
            Sender sender,
            String playerName,
            UUID playerUuid,
            int ticksTimeout,
            Integer ticksDelay,
            Set<String> servers
    ) {
        XCommand xCommand = new XCommand();
        xCommand.command = command;
        xCommand.sender = sender != null ? sender : Sender.CONSOLE;
        xCommand.playerName = playerName;
        xCommand.playerUuid = playerUuid;
        xCommand.ticksTimeout = ticksTimeout > 0 ? ticksTimeout : 100;
        xCommand.ticksDelay = ticksDelay != null ? ticksDelay : 40;
        xCommand.servers = servers;
        return xCommand;
    }
}
