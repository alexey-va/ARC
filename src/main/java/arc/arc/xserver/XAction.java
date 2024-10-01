package arc.arc.xserver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import arc.arc.ARC;
import arc.arc.network.adapters.JsonSubtype;
import arc.arc.network.adapters.JsonType;
import arc.arc.xserver.playerlist.PlayerManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

@JsonType(
        property = "type",
        subtypes = {
                @JsonSubtype(clazz = XMessage.class, name = "xmessage"),
                @JsonSubtype(clazz = XCommand.class, name = "xcommand")
        }
)
@Data
@Slf4j
public abstract class XAction {
    List<XCondition> conditions;
    Long after;
    Boolean async;

    protected abstract void runInternal(Collection<Player> players);

    public void run() {
        try {
            List<Player> players = new ArrayList<>();
            List<Player> allPlayers = PlayerManager.getOnlinePlayersThreadSafe();
            for (Player player : allPlayers) {
                boolean fits = true;
                if (conditions != null) {
                    for (XCondition xCondition : conditions) {
                        if (!xCondition.test(player)) {
                            fits = false;
                            break;
                        }
                    }
                }
                if (fits) players.add(player);
            }
            long now = System.currentTimeMillis();
            int ticksDelay = Math.max(0, (int) (now / 50 + (now % 50 != 0 ? 1 : 0)));
            if (async == null || !async) run(players, ticksDelay);
            else runAsync(players, ticksDelay);
        } catch (Exception e) {
            log.error("Error executing action: {}", this, e);
        }
    }

    private void runAsync(Collection<Player> players, int ticksDelay) {
        new BukkitRunnable() {
            @Override
            public void run() {
                runInternal(players);
            }
        }.runTaskLaterAsynchronously(ARC.plugin, ticksDelay);
    }

    private void run(Collection<Player> players, int ticksDelay) {
        if (Bukkit.isPrimaryThread()) {
            if (ticksDelay == 0) {
                runInternal(players);
                return;
            }
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                runInternal(players);
            }
        }.runTaskLater(ARC.plugin, ticksDelay);
    }
}


