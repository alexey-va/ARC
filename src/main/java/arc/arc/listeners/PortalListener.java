package arc.arc.listeners;

import arc.arc.ARC;
import arc.arc.Portal;
import com.olziedev.playerwarps.api.PlayerWarpsAPI;
import com.olziedev.playerwarps.api.warp.Warp;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class PortalListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPlaceBlock(BlockPlaceEvent ev) {
        if (Portal.occupied(ev.getBlockPlaced())) ev.setCancelled(true);
    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent ev) {

        if (ev.getPlayer().hasPermission("myhome.bypass-portal")) return;
        String[] args = ev.getMessage().split(" ");
        if (args.length == 2 && (args[0].equalsIgnoreCase("/pw") || args[0].equalsIgnoreCase("/warp"))) {
            if (!(args[1].equalsIgnoreCase("about") || args[1].equalsIgnoreCase("addwarps") || args[1].equalsIgnoreCase("amount") || args[1].equalsIgnoreCase("ban") || args[1].equalsIgnoreCase("category") || args[1].equalsIgnoreCase("desc") || args[1].equalsIgnoreCase("favourite") || args[1].equalsIgnoreCase("icon") || args[1].equalsIgnoreCase("list") || args[1].equalsIgnoreCase("open") || args[1].equalsIgnoreCase("rate") || args[1].equalsIgnoreCase("reload") || args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("removeall") || args[1].equalsIgnoreCase("rename") || args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("setowner"))) {
                Warp warp = PlayerWarpsAPI.getInstance().getPlayerWarp(args[1], ev.getPlayer());
                if (warp == null) return;
                System.out.println(ev.getMessage() + " | " + ev.getMessage().substring(1));
                new Portal(ev.getPlayer().getUniqueId().toString(), ev.getMessage().substring(1));
                ev.setCancelled(true);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("/lands") || (args[0].equalsIgnoreCase("/land")))
                && args[1].equalsIgnoreCase("spawn")) {
            ev.setCancelled(true);
            if (ARC.plugin.landsHook == null) ARC.plugin.redisManager.publishLandsRequest(ev.getPlayer().getUniqueId());
            else ARC.plugin.landsHook.tpSpawn(ev.getPlayer());
        } else if (args.length == 1 && (args[0].equalsIgnoreCase("/lands") || (args[0].equalsIgnoreCase("/land")))) {
            if (ARC.plugin.landsHook == null) {
                ev.setCancelled(true);
                ev.getPlayer().sendMessage(Component.text("Здесь эта команда недоступна. ", NamedTextColor.RED)
                        .append(Component.text("Используйте /ls для телепортации на спавн поселения", NamedTextColor.GRAY)));
            }
        }
    }

}
