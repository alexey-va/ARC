package ru.arc.listeners;

import org.bukkit.scheduler.BukkitRunnable;
import ru.arc.ARC;
import ru.arc.audit.AuditManager;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;
import ru.arc.sync.SyncManager;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.arc.treasurechests.TreasureHuntManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static ru.arc.util.Logging.info;

@Slf4j
public class JoinListener implements Listener {

    Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "misc.yml");
    Map<UUID, String> invMap = new ConcurrentHashMap<>();

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        SyncManager.playerJoin(event.getPlayer().getUniqueId());
        invulnerable(event.getPlayer());
        fullHeal(event.getPlayer());
        AuditManager.join(event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerLeave(PlayerQuitEvent event) {
        SyncManager.playerQuit(event.getPlayer().getUniqueId());
        AuditManager.leave(event.getPlayer().getName());
        if (invMap.containsKey(event.getPlayer().getUniqueId())) {
            stripInvulnerable(event.getPlayer());
        }
        if(HookRegistry.duelsHook != null) {
            HookRegistry.duelsHook.stopDuelsIfAny(event.getPlayer());
        }
        TreasureHuntManager.onPlayerQuit(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        SyncManager.playerQuit(event.getPlayer().getUniqueId());
        AuditManager.leave(event.getPlayer().getName());
        if (invMap.containsKey(event.getPlayer().getUniqueId())) {
            stripInvulnerable(event.getPlayer());
        }
        if(HookRegistry.duelsHook != null) {
            HookRegistry.duelsHook.stopDuelsIfAny(event.getPlayer());
        }
        TreasureHuntManager.onPlayerQuit(event.getPlayer());
    }

    private void invulnerable(Player player) {
        if (!config.bool("join.invulnerable-enabled", true)) return;
        if (player == null || !player.isOnline()) return;
        if (player.hasPermission("arc.bypass-invulnerable")) return;
        player.setInvulnerable(true);
        invMap.put(player.getUniqueId(), player.getName());
        info("Player {} is invulnerable", player.getName());

        int ticks = config.integer("join.invulnerable-ticks", 20 * 7);

        Bukkit.getScheduler().runTaskLater(ARC.plugin, () -> stripInvulnerable(player), ticks);
    }


    private void stripInvulnerable(Player player) {
        if (!player.isOnline()) return;
        player.setInvulnerable(false);
        invMap.remove(player.getUniqueId());
        info("Player {} is not invulnerable anymore", player.getName());
    }

    private void fullHeal(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!config.bool("join.full-heal", true)) return;
                if (player == null || !player.isOnline()) return;
                double currentHealth = player.getHealth();
                double maxHealth = player.getMaxHealth();
                info("Player {} health {} maxhealth {}", player.getName(), currentHealth, maxHealth);
                if (currentHealth < maxHealth) player.setHealth(maxHealth);
            }
        }.runTaskLater(ARC.plugin, config.integer("join.full-heal-delay-ticks", 10));
    }

}
