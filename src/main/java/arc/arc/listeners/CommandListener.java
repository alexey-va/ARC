package arc.arc.listeners;

import arc.arc.Portal;
import arc.arc.hooks.HookRegistry;
import arc.arc.network.NetworkRegistry;
import arc.arc.network.ServerLocation;
import arc.arc.xserver.playerlist.PlayerManager;
import com.olziedev.playerwarps.api.PlayerWarpsAPI;
import com.olziedev.playerwarps.api.warp.Warp;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.TabCompleteEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CommandListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPlaceBlock(BlockPlaceEvent ev) {
        if (Portal.occupied(ev.getBlockPlaced())) ev.setCancelled(true);
    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent ev) {
        String[] args = ev.getMessage().split(" ");
        pwCommand(ev, args);
        moneyCommand(ev.getPlayer(), ev, args);
    }

    @EventHandler(priority =  EventPriority.LOWEST)
    public void onServerCommand(ServerCommandEvent serverCommandEvent){
        String[] args = serverCommandEvent.getCommand().split(" ");
        moneyCommandServer(serverCommandEvent, args);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTabComplete(TabCompleteEvent tabCompleteEvent){
        moneyTabComplete(tabCompleteEvent);
    }

    private void moneyTabComplete(TabCompleteEvent event){
        if(!event.getBuffer().startsWith("/money ")) return;
        String[] args = event.getBuffer().split(" ");
        if(args.length == 2){
            List<String> completions = new ArrayList<>(event.getCompletions());
            completions.add("give");
            event.setCompletions(completions);
        } else if(args.length == 3 && args[1].equalsIgnoreCase("give")){
            List<String> completions = PlayerManager.getPlayerNames().stream().toList();
            event.setCompletions(completions);
        } else if(args.length == 4 && args[1].equalsIgnoreCase("give")){
            List<String> completions = event.getCompletions();
            completions.add("100");
            event.setCompletions(completions);
        }
    }

    private void moneyCommand(Player player, Cancellable ev, String[] args) {
        if (args.length > 2 && args[0].equalsIgnoreCase("/money") && args[1].equalsIgnoreCase("give")) {
            ev.setCancelled(true);
            // /money give <player> <amount>
            // into
            // /money <player> vault give <amount>
            if (args.length == 4) {
                try {
                    String amount = args[3];
                    double money = Double.parseDouble(amount);
                    player.performCommand("money " + args[2] + " vault give " + money);
                    log.info("Rerouted /money give command to /money <player> vault give <amount>");
                } catch (Exception e) {
                    log.error("Failed to reroute /money give command to /money <player> vault give <amount>", e);
                }
            }
        }
    }

    private void moneyCommandServer(Cancellable ev, String[] args) {
        if (args.length > 2 && args[0].equalsIgnoreCase("/money") && args[1].equalsIgnoreCase("give")) {
            ev.setCancelled(true);
            // /money give <player> <amount>
            // into
            // /money <player> vault give <amount>
            if (args.length == 4) {
                try {
                    String amount = args[3];
                    double money = Double.parseDouble(amount);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "money " + args[2] + " vault give " + money);
                    log.info("Rerouted console /money give command to /money <player> vault give <amount>");
                } catch (Exception e) {
                    log.error("Failed to reroute /money give command to /money <player> vault give <amount>", e);
                }
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

/*    private void landsSpawnCommand(PlayerCommandPreprocessEvent event) {
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
    }*/

}
