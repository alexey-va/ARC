package ru.arc.listeners;

import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitRunnable;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;

import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

public class RespawnListener implements Listener {

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "misc.yml");

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        info("Respawning player {} reason {} location {}", event.getPlayer().getName(), event.getRespawnReason(), event.getRespawnLocation());
        World world = event.getPlayer().getLocation().getWorld();
        info("World {}", world.getName());
        Set<String> interceptWorlds = config.stringSet("respawn-intercept-worlds");
        if (interceptWorlds.contains(world.getName())) {
            info("Intercepting respawn for player {}", event.getPlayer().getName());
            Location respawnLocation = event.getPlayer().getRespawnLocation();
            info("Respawn location {}", respawnLocation);
            if (respawnLocation == null) return;
            event.setRespawnLocation(respawnLocation);
        }
    }

    @EventHandler
    public void onBedUse(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) { // check if they're trying to sleep
            if (e.getClickedBlock() != null && Tag.BEDS.isTagged(e.getClickedBlock().getType())) { // check if the block is a bed
                if ((player.getWorld().getTime() < 12541 || player.getWorld().getTime() > 23458) && !player.getWorld().hasStorm()) {
                    e.setCancelled(true);
                    info("Player {} tried to sleep during the day", player.getName());
                }
                final Location oldRespawn = player.getRespawnLocation();
                HookRegistry.huskHomesHook.hasHome(player)
                        .thenAccept(hasHome -> {
                            info("Player {} has home {}", player.getName(), hasHome);
                            try {
                                if (!hasHome) {
                                    HookRegistry.huskHomesHook.createDefaultHome(player, player.getLocation());
                                    player.sendMessage(config.componentDef("rtp-respawn.bed-create-home", "<green>Ваш <gold>/home<green> установлен здесь! <gray>Чтобы изменить его, используйте команду /sethome"));
                                } else {
                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            info("Setting respawn location for player {} to {}", player.getName(), oldRespawn);
                                            player.setRespawnLocation(oldRespawn, true);
                                        }
                                    }.runTaskLater(ARC.plugin, 3L);
                                }
                            } catch (Exception ex) {
                                error("Error setting respawn location for player {}", player.getName(), ex);
                            }
                        });

            }
        }
    }


}
