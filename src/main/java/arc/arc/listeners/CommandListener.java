package arc.arc.listeners;

import arc.arc.Portal;
import arc.arc.hooks.HookRegistry;
import arc.arc.network.NetworkRegistry;
import arc.arc.network.ServerLocation;
import com.olziedev.playerwarps.api.PlayerWarpsAPI;
import com.olziedev.playerwarps.api.warp.Warp;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.concurrent.TimeUnit;

public class CommandListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPlaceBlock(BlockPlaceEvent ev) {
        if (Portal.occupied(ev.getBlockPlaced())) ev.setCancelled(true);
    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent ev) {
        String[] args = ev.getMessage().split(" ");
        pwCommand(ev, args);
        landsSpawnCommand(ev);
        landsCommand(ev, args);
    }

    private void landsCommand(PlayerCommandPreprocessEvent ev, String[] args) {
        if (args.length == 1 && (args[0].equalsIgnoreCase("/lands") || (args[0].equalsIgnoreCase("/land")))) {
            if (HookRegistry.landsHook == null) {
                ev.setCancelled(true);
                ev.getPlayer().sendMessage(Component.text("Здесь эта команда недоступна. ", NamedTextColor.RED)
                        .append(Component.text("Используйте /ls для телепортации на спавн поселения", NamedTextColor.GRAY)));
            }
        }
    }

    private void pwCommand(PlayerCommandPreprocessEvent ev, String[] args) {
        if (ev.getPlayer().hasPermission("myhome.bypass-portal")) return;
        if (args.length == 2 && (args[0].equalsIgnoreCase("/pw") || args[0].equalsIgnoreCase("/warp"))) {
            if (!(args[1].equalsIgnoreCase("about") || args[1].equalsIgnoreCase("addwarps") || args[1].equalsIgnoreCase("amount") || args[1].equalsIgnoreCase("ban") || args[1].equalsIgnoreCase("category") || args[1].equalsIgnoreCase("desc") || args[1].equalsIgnoreCase("favourite") || args[1].equalsIgnoreCase("icon") || args[1].equalsIgnoreCase("list") || args[1].equalsIgnoreCase("open") || args[1].equalsIgnoreCase("rate") || args[1].equalsIgnoreCase("reload") || args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("removeall") || args[1].equalsIgnoreCase("rename") || args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("setowner"))) {
                Warp warp = PlayerWarpsAPI.getInstance().getPlayerWarp(args[1], ev.getPlayer());
                if (warp == null) return;
                new Portal(ev.getPlayer().getUniqueId().toString(), ev.getMessage().substring(1));
                ev.setCancelled(true);
            }
        }
    }

    private void landsSpawnCommand(PlayerCommandPreprocessEvent event) {
        if (!(event.getMessage().trim().equals("/lands spawn")
                || event.getMessage().trim().equals("/land spawn"))) return;
        event.setCancelled(true);
        if (HookRegistry.landsHook == null) {
            NetworkRegistry.landsMessager.getSpawnLocation(event.getPlayer().getUniqueId())
                    .orTimeout(5, TimeUnit.SECONDS)
                    .exceptionally((e) -> null)
                    .thenAccept(loc -> {
                        //System.out.println("Loc: " + loc);
                        sendPlayerLands(event.getPlayer(), loc);
                    });
        } else HookRegistry.landsHook.tpSpawn(event.getPlayer());
    }

    private void sendLandsDeny(Player player) {
        player.sendMessage(Component.text("Не удалось найти спавн поселения!", NamedTextColor.RED));
    }

    private void sendPlayerLands(Player player, ServerLocation serverLocation) {
        if (serverLocation == null) {
            sendLandsDeny(player);
        } else if (HookRegistry.huskHomesHook != null) {
            HookRegistry.huskHomesHook.teleport(player, serverLocation);
        }
    }

}
