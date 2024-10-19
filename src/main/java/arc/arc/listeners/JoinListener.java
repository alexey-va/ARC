package arc.arc.listeners;

import arc.arc.ARC;
import arc.arc.audit.AuditManager;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.sync.SyncManager;
import arc.arc.xserver.playerlist.PlayerManager;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Optional;

@Slf4j
public class JoinListener implements Listener {

    Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "misc.yml");

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
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        SyncManager.playerQuit(event.getPlayer().getUniqueId());
        AuditManager.leave(event.getPlayer().getName());
    }

    private void invulnerable(Player player) {
        if (!config.bool("join.invulnerable-enabled", false)) return;
        if (player == null || !player.isOnline()) return;
        player.setInvulnerable(true);

        int defaultTicks = config.integer("join.invulnerable.ticks", 20 * 5);
        int resourcepackTicks = config.integer("join.invulnerable.resourcepack-ticks", 20 * 15);
        PlayerManager.PlayerData playerData = PlayerManager.getPlayerData(player.getUniqueId());
        boolean withResourcepack = player.hasPermission("mcfine.apply-rp")
                && (playerData == null || Math.abs(playerData.getJoinTime() - System.currentTimeMillis()) < 1000);

        Bukkit.getScheduler().runTaskLater(ARC.plugin, () -> {
            if (!player.isOnline()) return;
            if (player.hasPermission("arc.bypass-invulnerable")) return;
            player.setInvulnerable(false);
        }, withResourcepack ? resourcepackTicks : defaultTicks);

    }

    private void fullHeal(Player player) {
        if (!config.bool("join.full-heal", true)) return;
        if (player == null || !player.isOnline()) return;
        double currentHealth = player.getHealth();
        double maxHealth = Optional.ofNullable(player.getAttribute(Attribute.GENERIC_MAX_HEALTH))
                .map(AttributeInstance::getDefaultValue)
                .orElse(currentHealth);
        if (currentHealth < maxHealth) player.setHealth(maxHealth);
    }

}
