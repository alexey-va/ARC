package arc.arc.commands;

import arc.arc.ARC;
import arc.arc.ai.Conversation;
import arc.arc.ai.GPTManager;
import arc.arc.audit.AuditManager;
import arc.arc.board.guis.BoardGui;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.generic.treasure.MainTreasuresGui;
import arc.arc.guis.BaltopGui;
import arc.arc.hooks.HookRegistry;
import arc.arc.misc.JoinMessageGui;
import arc.arc.network.repos.RedisRepo;
import arc.arc.util.CooldownManager;
import arc.arc.util.GuiUtils;
import arc.arc.util.TextUtil;
import arc.arc.xserver.playerlist.PlayerManager;
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

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class Command implements CommandExecutor, TabCompleter {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, org.bukkit.command.@NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if (strings.length == 0) {
            commandSender.sendMessage("No args!");
            return true;
        }

        if (strings[0].equalsIgnoreCase("ai")) {
            processAiCommand(commandSender, strings);
            return true;
        }

        if (strings[0].equalsIgnoreCase("joinmessage")) {
            processJoinMessageCommand(commandSender, strings, true);
            return true;
        }

        if (strings[0].equalsIgnoreCase("quitmessage")) {
            processJoinMessageCommand(commandSender, strings, false);
            return true;
        }

        if (strings[0].equalsIgnoreCase("audit")) {
            processAuditCommand(commandSender, strings);
            return true;
        }

        if (strings[0].equalsIgnoreCase("repo")) {
            processRepoCommand(commandSender, strings);
            return true;
        }

        if (strings.length == 1) {
            if (strings[0].equalsIgnoreCase("reload") && commandSender.hasPermission("arc.admin")) {
                ARC.plugin.reloadConfig();
                ARC.plugin.loadConfig(false);
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

    private void processRepoCommand(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage("No args!");
            return;
        }
        if (args[1].equalsIgnoreCase("save")) {
            RedisRepo.saveAll();
            sender.sendMessage("All repos saved!");
        } else if (args[1].equalsIgnoreCase("size")) {
            Map<String, Long> stringLongMap = RedisRepo.bytesTotal();
            for (Map.Entry<String, Long> entry : stringLongMap.entrySet()) {
                sender.sendMessage(entry.getKey() + " : " + entry.getValue());
            }
            sender.sendMessage("Total: " + stringLongMap.values().stream().mapToLong(Long::longValue).sum());
        }
    }

    private void processAuditCommand(CommandSender sender, String[] args) {
        Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "audit.yml");
        if (!sender.hasPermission("arc.audit")) {
            sender.sendMessage(TextUtil.noPermissions());
            return;
        }
        if (args.length == 1) {
            sender.sendMessage("No args!");
            return;
        }
        String playerName = args[1];
        if (args[1].equalsIgnoreCase("clearall")) {
            AuditManager.clearAll();
            sender.sendMessage(config.componentDef("messages.audit-cleared", "<gray>Аудит очищен!"));
            return;
        }
        AuditManager.Filter filter = AuditManager.Filter.ALL;
        int page = 1;
        if (args.length >= 3) {
            if (args[2].equalsIgnoreCase("clear")) {
                AuditManager.clear(playerName);
                sender.sendMessage(config.componentDef("messages.audit-cleared", "<gray>Аудит очищен для игрока %player_name%!",
                        "%player_name%", playerName));
                return;
            }
            try {
                page = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(config.componentDef("messages.invalid-page", "<red>Неверный формат страницы %text%!",
                        "%text%", args[2]));
                return;
            }
        }
        if (args.length >= 4) {
            String action = args[3];
            if (action.equalsIgnoreCase("income")) filter = AuditManager.Filter.INCOME;
            if (action.equalsIgnoreCase("expense")) filter = AuditManager.Filter.EXPENSE;
            if (action.equalsIgnoreCase("shop")) filter = AuditManager.Filter.SHOP;
            if (action.equalsIgnoreCase("job")) filter = AuditManager.Filter.JOB;
            if (action.equalsIgnoreCase("pay")) filter = AuditManager.Filter.PAY;
        }

        AuditManager.sendAudit((Player) sender, playerName, page, filter);
    }

    private void processJoinMessageCommand(CommandSender sender, String[] args, boolean isJoin) {
        if (!sender.hasPermission("arc.join-message-gui")) {
            sender.sendMessage(TextUtil.noPermissions());
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(TextUtil.playerOnly());
            return;
        }
        GuiUtils.constructAndShowAsync(() -> new JoinMessageGui((Player) sender, isJoin, 0), (Player) sender);
    }

    private void processAiCommand(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage("No args!");
            return;
        }
        Player player = (Player) sender;
        long ai = CooldownManager.cooldown(player.getUniqueId(), "ai_command");
        if (ai > 0) {
            Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "gpt.yml");
            sender.sendMessage(config.componentDef("ai-cooldown-message", "<gray>Вы слишком разогнались, подождите немного."));
            log.info("AI command on cooldown for player {}", player.getName());
            return;
        }
        CooldownManager.addCooldown(player.getUniqueId(), "ai_command", 60);

        if (args[1].equalsIgnoreCase("stop")) {
            String id = args.length > 2 ? args[2] : "all";
            if (id.equalsIgnoreCase("all")) {
                GPTManager.endAllConversations(player);
                //sender.sendMessage("All GPT conversations stopped!");
            } else {
                GPTManager.endConversation(player, id);
                //sender.sendMessage("GPT conversation with id " + id + " stopped!");
            }
        } else if (args[1].equalsIgnoreCase("start")) {

            if (args.length < 4) {
                sender.sendMessage("Not enough args!");
                return;
            }
            StockCommand.ParsedCommand parsedCommand = parseArgs(args);
            String archetype = parsedCommand.pars().getOrDefault("archetype", "default");
            String id = parsedCommand.pars().getOrDefault("id", UUID.randomUUID().toString());
            String talkerName = parsedCommand.pars().getOrDefault("name", "GPT");
            double radius = Double.parseDouble(parsedCommand.pars().getOrDefault("radius", "50"));
            long lifeTime = Long.parseLong(parsedCommand.pars().getOrDefault("life-time", "60000"));
            String initialMessage = parsedCommand.pars().getOrDefault("initial-message", null);
            String npcIdString = parsedCommand.pars().getOrDefault("npc-id", null);
            String endMessage = parsedCommand.pars().getOrDefault("end-message", null);
            Integer npcId = npcIdString != null ? Integer.parseInt(npcIdString) : null;
            boolean privateConversation = parsedCommand.pars().containsKey("private");

            GPTManager.startConversation(player,
                    id,
                    archetype,
                    talkerName,
                    player.getLocation(),
                    radius,
                    lifeTime,
                    initialMessage,
                    endMessage,
                    npcId,
                    privateConversation);
            //sender.sendMessage("GPT conversation with id " + id + " started!");
        }
    }

    private static StockCommand.ParsedCommand parseArgs(String[] strings) {
        List<String> args = new ArrayList<>();
        Map<String, String> pars = new HashMap<>();
        for (String s : strings) {
            if (!s.startsWith("-")) args.add(s);
            else {
                s = s.substring(1);
                String[] temp = s.split(":");
                pars.put(temp[0], temp.length == 2 ? temp[1] : null);
            }
        }
        return new StockCommand.ParsedCommand(pars, args);
    }


    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, org.bukkit.command.@NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if (strings.length == 1) {
            return List.of("reload", "joinmessage", "quitmessage", "board", "emshop", "jobsboosts", "baltop", "treasures", "logger", "ai", "audit", "repo");
        }

        if (strings.length > 1 && strings[0].equalsIgnoreCase("ai")) {
            if (strings.length == 2) {
                return List.of("start", "stop");
            }
            if (strings[1].equalsIgnoreCase("start")) {
                return List.of("-archetype:default", "-id:", "-name:GPT", "-radius:50", "-life-time:60000", "-initial-message:", "-npc-id:", "-end-message:пока", "-private");
            }
            if (strings[1].equalsIgnoreCase("stop")) {
                List<String> conv = GPTManager.getConversations((Player) commandSender).stream()
                        .map(Conversation::getGptId)
                        .collect(Collectors.toList());
                conv.add("all");
                return conv;
            }
        }

        if(strings.length > 1 && strings[0].equalsIgnoreCase("repo")) {
            return List.of("save", "size");
        }

        if (strings.length > 1 && strings[0].equalsIgnoreCase("audit")) {
            if (strings.length == 2) {
                List<String> list = new ArrayList<>();
                list.add("clearall");
                list.addAll(PlayerManager.getPlayerNames());
                return list;
            }
            if (strings.length == 3) {
                return List.of("income", "expense", "shop", "job", "pay", "clear");
            }
            if (strings.length == 4) {
                return List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
            }
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
