package arc.arc.listeners;

import arc.arc.Portal;
import arc.arc.PortalData;
import arc.arc.audit.AuditManager;
import arc.arc.audit.Type;
import arc.arc.configs.Config;
import arc.arc.hooks.HookRegistry;
import arc.arc.util.TextUtil;
import arc.arc.xserver.playerlist.PlayerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.TabCompleteEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static arc.arc.util.TextUtil.mm;

@Slf4j
@RequiredArgsConstructor
public class CommandListener implements Listener {

    final Config commandConfig;

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPlaceBlock(BlockPlaceEvent ev) {
        if (Portal.isOccupied(ev.getBlockPlaced())) ev.setCancelled(true);
    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent ev) {
        String[] args = ev.getMessage().split(" ");
        warpCommand(ev, args);
        moneyCommand(ev.getPlayer(), ev, args);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerCommand(ServerCommandEvent serverCommandEvent) {
        String[] args = serverCommandEvent.getCommand().split(" ");
        moneyCommandServer(serverCommandEvent, args);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTabComplete(TabCompleteEvent tabCompleteEvent) {
        moneyTabComplete(tabCompleteEvent);
    }

    private void moneyTabComplete(TabCompleteEvent event) {
        if (!event.getBuffer().startsWith("/money ")) return;
        String[] args = event.getBuffer().split(" ");
        int len = args.length;
        if (event.getBuffer().endsWith(" ")) len++;
        if (len == 2) {
            event.setCompletions(new ArrayList<>(List.of("give", "set", "take")));
        } else if (len == 3) {
            List<String> completions = PlayerManager.getPlayerNames().stream().toList();
            event.setCompletions(completions);
        } else if (len == 4) {
            event.setCompletions(List.of("100"));
        }
    }

    private void moneyCommand(Player player, Cancellable ev, String[] args) {
        if (!player.hasPermission("rediseconomy.admin")) {
            player.sendMessage(TextUtil.noPermissions());
            return;
        }
        Set<String> sub = Set.of("give", "set", "take");
        if (args.length > 2 && args[0].equalsIgnoreCase("/money") && sub.contains(args[1])) {
            ev.setCancelled(true);
            // /money give/set/take <player> <amount>
            // into
            // /money <player> vault give <amount>
            if (args.length == 4) {
                try {
                    String amount = args[3];
                    double money = Double.parseDouble(amount);
                    String command = "money " + args[2] + " vault " + args[1] + " " + money;
                    player.performCommand(command);
                    AuditManager.operation(args[2], money, Type.COMMAND, player.getName());
                    log.info("Rerouted {} to {}", String.join(" ", args), command);
                } catch (Exception e) {
                    log.error("Failed to reroute /money give command to /money <player> vault give <amount>", e);
                }
            } else {
                player.sendMessage(mm("<red>Правильное использование: <gray>/money give/set/take <игрок> <сумма>"));
            }
        }
    }

    private void moneyCommandServer(Cancellable ev, String[] args) {
        Set<String> sub = Set.of("give", "set", "take");
        if (args.length > 2 && args[0].equalsIgnoreCase("/money") && sub.contains(args[1])) {
            ev.setCancelled(true);
            // /money give <player> <amount>
            // into
            // /money <player> vault give <amount>
            if (args.length == 4) {
                try {
                    String amount = args[3];
                    double money = Double.parseDouble(amount);
                    String command = "money " + args[2] + " vault " + args[1] + " " + money;
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    AuditManager.operation(args[2], money, Type.COMMAND, "Server");
                    log.info("Rerouted {} to {}", String.join(" ", args), command);
                } catch (Exception e) {
                    log.error("Failed to reroute /money give command to /money <player> vault give <amount>", e);
                }
            }
        }
    }


    private void warpCommand(PlayerCommandPreprocessEvent ev, String[] args) {
        if (ev.getPlayer().hasPermission("arc.bypass-portal")) return;
        if (!commandConfig.bool("command-portals", true)) return;
        if (args.length < 2) return;

        Set<String> excludedSubCommands = new HashSet<>(commandConfig.stringList("excluded-sub-commands"));
        Set<String> aliases = new HashSet<>(commandConfig.stringList("aliases"));
        String mainCommand = args[0].substring(1);

        boolean isCmiWarp = "/cmi".equals(args[0]) && "warp".equals(args[1]);

        if (!aliases.contains(mainCommand) && !isCmiWarp) return;
        if (excludedSubCommands.contains(args[1])) return;

        if (commandConfig.bool("check-player-warps", true)
                && HookRegistry.playerWarpsHook != null
                && !HookRegistry.playerWarpsHook.warpExists(args[1], ev.getPlayer())) return;
        if (commandConfig.bool("check-cmi-warps", true)
                && HookRegistry.cmiHook != null
                && !HookRegistry.cmiHook.warpExists(args[1])) return;
        new Portal(ev.getPlayer().getUniqueId(), PortalData.builder()
                .actionType(PortalData.ActionType.COMMAND)
                .command(ev.getMessage().substring(1))
                .build());
        ev.setCancelled(true);
    }
}
