package arc.arc.commands;

import arc.arc.ARC;
import arc.arc.board.guis.BoardGui;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.generic.treasure.MainTreasuresGui;
import arc.arc.guis.BaltopGui;
import arc.arc.hooks.HookRegistry;
import arc.arc.util.GuiUtils;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Slf4j
public class Command implements CommandExecutor, TabCompleter {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, org.bukkit.command.@NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if (strings.length == 0) {
            commandSender.sendMessage("No args!");
            return true;
        }
        if (strings.length == 1) {
            if (strings[0].equalsIgnoreCase("reload") && commandSender.hasPermission("arc.admin")) {
                ARC.plugin.reloadConfig();
                ARC.plugin.loadConfig();
                ARC.hookRegistry.reloadHooks();
                commandSender.sendMessage(Component.text("Перезагрузка успешна!", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
                return true;
            }
            if (strings[0].equalsIgnoreCase("board")) {
                Player player = (Player) commandSender;
                GuiUtils.constructAndShowAsync(() -> new BoardGui(player), player);
                return true;
            }

        }


        if (strings[0].equals("treasures") && commandSender.hasPermission("arc.admin")) {
            Player player = (Player) commandSender;
            Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "treasures.yml");
            GuiUtils.constructAndShowAsync(() -> new MainTreasuresGui(player, config), player);
        }

        if (strings[0].equals("emshop")) {

            if (HookRegistry.emHook == null) {
                commandSender.sendMessage("EMHook is not loaded!");
                return true;
            }

            String playerName = strings[1];

            if (playerName.equalsIgnoreCase("reset")) {
                HookRegistry.emHook.resetShop();
                commandSender.sendMessage("Shop reset!");
                return true;
            }

            Player player = ARC.plugin.getServer().getPlayer(playerName);
            if (player == null || !player.getName().equalsIgnoreCase(playerName)) {
                commandSender.sendMessage("Игрок не найден!");
                return true;
            }


            boolean isGear = strings.length > 2 && strings[2].equalsIgnoreCase("gear");

            HookRegistry.emHook.openShopGui(player, isGear);
            return true;
        }

        if (strings[0].equalsIgnoreCase("jobsboosts")) {

            if (strings.length >= 2 && strings[1].equalsIgnoreCase("reset")) {
                if (!commandSender.hasPermission("arc.admin")) {
                    commandSender.sendMessage("No permission!");
                    return true;
                }

                String playerName = strings[2];
                Player player = ARC.plugin.getServer().getPlayer(playerName);
                if (player == null || !player.getName().equalsIgnoreCase(playerName)) {
                    commandSender.sendMessage("Игрок не найден!");
                    return true;
                }
                HookRegistry.jobsHook.resetBoosts(player);
                commandSender.sendMessage("Boosts reset for player: " + playerName);
            }

            if (HookRegistry.jobsHook == null) {
                commandSender.sendMessage("JobsHook is not loaded!");
                return true;
            }

            String playerName;
            if (strings.length >= 2) playerName = strings[1];
            else playerName = commandSender.getName();

            Player player = ARC.plugin.getServer().getPlayer(playerName);
            if (player == null || !player.getName().equalsIgnoreCase(playerName)) {
                commandSender.sendMessage("Игрок не найден!");
                return true;
            }

            HookRegistry.jobsHook.openBoostGui(player);
            return true;
        }

        if (strings[0].equalsIgnoreCase("baltop")) {
            Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "baltop.yml");
            GuiUtils.constructAndShowAsync(() -> new BaltopGui(config, (Player) commandSender), (Player) commandSender);
            return true;
        }

        if (strings[0].equals("logger")) {
            try {
                if (strings.length < 2) {
                    // list all loggers in arc.arc package
                    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
                    Configuration config = ctx.getConfiguration();
                    for (Map.Entry<String, LoggerConfig> entry : config.getLoggers().entrySet()) {
                        commandSender.sendMessage(entry.getKey() + " : " + entry.getValue().getLevel());
                    }
                    return true;
                }
                String prefix = strings[1];
                Level logLevel = Level.valueOf(strings[2].toUpperCase());

                Logger logger = LoggerFactory.getLogger(prefix);
                log.info(logger.getClass().getCanonicalName());
                //org.apache.logging.slf4j.Log4jLogger log4jLogger =
                //log4jLogger.setLevel(logLevel);

                // update loggers
                //ctx.updateLoggers();
            } catch (Exception e) {
                commandSender.sendMessage("Error: " + e.getMessage());
                e.printStackTrace();
                return true;
            }
        }


        return true;
    }


    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, org.bukkit.command.@NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if (strings.length == 1) {
            return List.of("reload", "board", "emshop", "jobsboosts", "baltop", "treasures", "logger");
        }

        if (strings.length == 2) {
            if (strings[0].equalsIgnoreCase("emshop")) {
                return ARC.plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
            }
            if (strings[0].equalsIgnoreCase("jobsboosts")) {
                return ARC.plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
            }
            if (strings[0].equalsIgnoreCase("logger")) {
                // list all classes in arc.arc package
                log.info("Listing all classes in arc package");
                Reflections reflections = new Reflections("arc");
                log.info(reflections.toString());
                reflections.getSubTypesOf(Object.class).forEach(System.out::println);
                return reflections.getSubTypesOf(Object.class).stream().map(Class::getName)
                        //.map(s1 -> s.replace(".class", ""))
                        .toList();
            }
        }

        if (strings.length == 3) {
            if (strings[0].equalsIgnoreCase("emshop")) {
                return List.of("gear", "trinket");
            }
            if (strings[0].equalsIgnoreCase("logger")) {
                return List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");
            }
        }

        return null;
    }
}
