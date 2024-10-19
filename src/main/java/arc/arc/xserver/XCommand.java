package arc.arc.xserver;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class XCommand extends XAction {

    String command;
    @Builder.Default
    Sender sender = Sender.CONSOLE;
    String playerName;
    UUID playerUuid;
    int ticksTimeout = 20 * 5;
    Integer ticksDelay = 40;
    Set<String> servers;

    private static final Config miscConfig = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "misc.yml");

    @Override
    protected void runInternal() {
        if (servers != null && !servers.contains(ARC.serverName)) {
            log.debug("Server {} not in list of servers: {}", ARC.serverName, servers);
            return;
        }
        if (command == null || sender == null || command.isEmpty()) {
            log.error("Something wrong with xcommand: {}", this);
            return;
        }
        if (sender == Sender.PLAYER && playerName == null && playerUuid == null) {
            log.error("Player name or uuid must be set for player sender {}", this);
            return;
        }
        if (playerName != null) {
            command = command.replace("%player_name%", playerName);
        }
        if (playerUuid != null) {
            command = command.replace("%player_uuid%", playerUuid.toString());
        }
        if (sender == Sender.CONSOLE && playerName == null && playerUuid == null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
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
                                Bukkit.dispatchCommand(finalPlayer, command);
                            } else {
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                            }
                        }
                    }.runTaskLater(ARC.plugin, delay);
                    cancel();
                }
                if (ticks.get() >= ticksTimeout) {
                    log.warn("Player not found for xcommand: {}", XCommand.this);
                    cancel();
                }
            }
        }.runTaskTimer(ARC.plugin, 0, 1);
    }

    public enum Sender {
        PLAYER,
        CONSOLE
    }
}
