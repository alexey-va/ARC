package ru.arc.commands;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.reflections.Reflections;
import ru.arc.ARC;
import ru.arc.ai.Conversation;
import ru.arc.ai.GPTManager;
import ru.arc.audit.AuditManager;
import ru.arc.board.guis.BoardGui;
import ru.arc.commands.framework.ArgType;
import ru.arc.commands.framework.CommandContext;
import ru.arc.commands.framework.Par;
import ru.arc.common.locationpools.LocationPool;
import ru.arc.common.locationpools.LocationPoolManager;
import ru.arc.common.treasure.TreasurePool;
import ru.arc.common.treasure.gui.MainTreasuresGui;
import ru.arc.common.treasure.gui.PoolGui;
import ru.arc.common.treasure.impl.SubPoolTreasure;
import ru.arc.common.treasure.impl.TreasureItem;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;
import ru.arc.misc.BaltopGui;
import ru.arc.misc.JoinMessageGui;
import ru.arc.network.repos.RedisRepo;
import ru.arc.treasurechests.TreasureHunt;
import ru.arc.treasurechests.TreasureHuntManager;
import ru.arc.treasurechests.TreasureHuntType;
import ru.arc.util.*;
import ru.arc.xserver.playerlist.PlayerManager;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

@Slf4j
public class Command implements CommandExecutor, TabCompleter {

    public static final Cache<String, Object> playersForRtp = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, org.bukkit.command.@NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if (strings.length == 0) {
            commandSender.sendMessage("No args!");
            return true;
        }

        if (strings[0].equalsIgnoreCase("locpool")) {
            processLocPoolCommand(commandSender, strings);
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

        if (strings[0].equalsIgnoreCase("respawnonrtp")) {
            processRespawnOnRtpCommand(commandSender, strings);
            return true;
        }

        if (strings.length == 1) {
            if (strings[0].equalsIgnoreCase("reload") && commandSender.hasPermission("arc.admin")) {
                ARC.plugin.reloadConfig();
                ARC.plugin.loadConfig(false);
                ARC.hookRegistry.setupHooks();
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
            processTreasuresCommand(commandSender, strings);
        }

        if (strings[0].equals("hunt")) {
            processTreasureHuntCommand(commandSender, strings);
            return true;
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
            Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "logger.yml");
            String logLevel = strings[1];
            Logging.Level level = Logging.Level.valueOf(logLevel.toUpperCase());
            Logging.setLogLevel(level);
            commandSender.sendMessage(config.componentDef(
                    "messages.log-level-set",
                    "<gray>Уровень логов установлен на %level%!",
                    "%level%", level.name())
            );
        }

        return true;
    }

    public void processRespawnOnRtpCommand(CommandSender commandSender, String[] strings) {
        if (!commandSender.hasPermission("arc.rtp-respawn")) {
            commandSender.sendMessage(TextUtil.noPermissions());
            return;
        }
        if (strings.length < 2) {
            commandSender.sendMessage("Not enough args!");
            return;
        }
        String playerName = strings[1];
        playersForRtp.put(playerName, new Object());
        commandSender.sendMessage("Player " + playerName + " added to rtp respawn list!");
    }

    public void processTreasureHuntCommand(CommandSender commandSender, String[] strings) {
        Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "treasure-hunt.yml");
        try {
            if (!commandSender.hasPermission("arc.treasure-hunt")) {
                commandSender.sendMessage(TextUtil.noPermissions());
                return;
            }

            boolean start = strings[1].equals("start");
            boolean stop = strings[1].equals("stop");


            String locationPoolId = strings.length >= 3 ? strings[2] : null;
            int chests = strings.length >= 4 ? Integer.parseInt(strings[3]) : 0;
            String namespaceId = strings.length >= 5 ? strings[4] : null;
            String treasurePoolId = strings.length >= 5 ? strings[5] : null;

            LocationPool locationPool = LocationPoolManager.getPool(locationPoolId);

            if (TreasureHunt.aliases().containsKey(namespaceId)) {
                namespaceId = TreasureHunt.aliases().get(namespaceId);
            }

            if (start) {
                if (strings.length == 3 || strings.length == 4) {
                    TreasureHuntType treasureHuntType = TreasureHuntManager.getTreasureHuntType(locationPoolId);
                    if (treasureHuntType == null) {
                        commandSender.sendMessage(config.componentDef("messages.hunt-type-not-found", "<red>Тип охоты на сокровища не найден!"));
                        return;
                    }
                    LocationPool locationPool1 = treasureHuntType.getLocationPool();
                    if (locationPool1 == null) {
                        commandSender.sendMessage(config.componentDef("messages.location-pool-not-found", "<red>Пул локаций %location_pool_id% не найден!",
                                "%location_pool_id%", locationPoolId));
                        return;
                    }
                    TreasureHuntManager.startHunt(locationPoolId, chests, commandSender);
                    return;
                }
                if (locationPool == null) {
                    commandSender.sendMessage(config.componentDef("messages.location-pool-not-found", "<red>Пул локаций %location_pool_id% не найден!",
                            "%location_pool_id%", locationPoolId));
                    return;
                }
                TreasureHuntManager.startHunt(locationPool, chests, namespaceId, treasurePoolId, commandSender);
                commandSender.sendMessage(config.componentDef("messages.hunt-started", "<gray>Начата охота на сокровища!"));
            } else if (stop) {
                if (locationPool == null) {
                    commandSender.sendMessage(config.componentDef("messages.location-pool-not-found", "<red>Пул локаций %location_pool_id% не найден!",
                            "%location_pool_id%", locationPoolId));
                    return;
                }
                TreasureHuntManager.getByLocationPool(locationPool)
                        .ifPresentOrElse(th -> {
                            TreasureHuntManager.stopHunt(th);
                            commandSender.sendMessage(config.componentDef("messages.hunt-stopped", "<gray>Охота на сокровища остановлена!"));
                        }, () -> commandSender.sendMessage(config.componentDef("messages.hunt-not-found", "<red>Охота на сокровища не найдена!")));
            } else {
                commandSender.sendMessage(config.componentDef("messages.invalid-command", "<red>Неверная команда! <gray>Синтаксис: /arc hunt start <location_pool_id> <chests> <namespace_id> <treasure_pool_id> или /arc hunt stop <location_pool_id> или /arc hunt start <type>"));
            }
        } catch (Exception e) {
            commandSender.sendMessage(config.componentDef("messages.not-enough-args", "<red>Неверная команда! <gray>Синтаксис: /arc hunt start <location_pool_id> <chests> <namespace_id> <treasure_pool_id> или /arc hunt stop <location_pool_id> или /arc hunt start <type>"));
            error("Error: ", e);
        }
    }

    private void processTreasuresCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("arc.treasures.admin")) {
            sender.sendMessage(TextUtil.noPermissions());
            return;
        }
        Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "treasures.yml");
        Player player;
        if (sender instanceof Player) player = (Player) sender;
        else player = null;

        if (args.length == 1) {
            if (player == null) {
                sender.sendMessage("Only players can use this command!");
                return;
            }
            GuiUtils.constructAndShowAsync(() -> new MainTreasuresGui(player), player);
            return;
        }

        if (args.length == 2) {
            if (args[1].equalsIgnoreCase("reload")) {
                TreasurePool.loadAllTreasures();
                sender.sendMessage(config.componentDef("messages.reloaded", "<gray>Награды перезагружены!"));
                return;
            }
            String poolId = args[1];
            TreasurePool pool = TreasurePool.getTreasurePool(poolId);
            if (pool == null) {
                sender.sendMessage(config.componentDef("messages.pool-not-found", "<red>Пул %pool_id% не найден!",
                        "%pool_id%", poolId));
            } else {
                if (player == null) {
                    sender.sendMessage("Only players can use this command!");
                    return;
                }
                GuiUtils.constructAndShowAsync(() -> new PoolGui(player, pool), player);
            }
            return;
        }

        if (args.length >= 3) {
            String poolId = args[1];
            String action = args[2];
            TreasurePool pool = TreasurePool.getTreasurePool(poolId);
            if (pool == null) {
                sender.sendMessage(config.componentDef("messages.pool-not-found-creating", "<red>Пул %pool_id% не найден! Создаем...",
                        "%pool_id%", poolId));
                pool = TreasurePool.getOrCreate(poolId);
            }

            if ("addhand".equalsIgnoreCase(action)) {
                CommandContext context = CommandContext.of(args, List.of(
                        Par.of("weight", ArgType.INTEGER, 1),
                        Par.of("quantity", ArgType.INTEGER, 1)

                ));
                if (player == null) {
                    sender.sendMessage("Only players can use this command!");
                    return;
                }
                ItemStack item = player.getInventory().getItemInMainHand().clone();
                if (item.getType().isAir()) {
                    player.sendMessage(config.componentDef("messages.no-item-in-hand", "<red>В руке нет предмета!"));
                    return;
                }
                TreasureItem treasureItem = TreasureItem.builder()
                        .stack(item)
                        .minAmount(context.getMap().containsKey("quantity") ? context.get("quantity") : item.getAmount())
                        .maxAmount(context.getMap().containsKey("quantity") ? context.get("quantity") : item.getAmount())
                        .gaussData(null)
                        .build();
                treasureItem.setWeight(context.get("weight"));
                boolean add = pool.add(treasureItem);
                if (!add) {
                    player.sendMessage(config.componentDef("messages.item-already-added", "<red>Предмет уже добавлен в пул %pool_id%!",
                            "%pool_id%", poolId));
                } else {
                    player.sendMessage(config.componentDef("messages.item-added", "<gray>Предмет добавлен в пул %pool_id%!",
                            "%pool_id%", poolId,
                            "%item%", item.getType().name()));
                }
            } else if ("addchest".equalsIgnoreCase(action)) {
                if (player == null) {
                    sender.sendMessage("Only players can use this command!");
                    return;
                }
                Block block = player.getTargetBlockExact(5);
                if (block == null) {
                    player.sendMessage(config.componentDef("messages.no-target-block", "<red>Не найден блок!"));
                    return;
                }
                CommandContext context = CommandContext.of(args, List.of(
                        Par.of("weight", ArgType.INTEGER, 1)

                ));
                List<Block> blocks = Utils.connectedChests(block);
                List<ItemStack> items = blocks.stream()
                        .flatMap(b -> Utils.extractItems(b).stream())
                        .filter(Objects::nonNull)
                        .filter(stack -> !stack.getType().isAir())
                        .map(ItemStack::clone)
                        .toList();
                int count = 0;
                for (var item : items) {
                    TreasureItem treasureItem = TreasureItem.builder()
                            .stack(item)
                            .minAmount(item.getAmount())
                            .maxAmount(item.getAmount())
                            .gaussData(null)
                            .build();
                    treasureItem.setWeight(context.get("weight"));
                    boolean add = pool.add(treasureItem);
                    if (add) {
                        player.sendMessage(config.componentDef("messages.item-added", "<gray>Предмет %item% добавлен в пул %pool_id%!",
                                "%pool_id%", poolId, "%item%", item.getType().name()));
                        count++;
                    } else {
                        player.sendMessage(config.componentDef("messages.item-already-added", "<red>Предмет %item% уже добавлен в пул %pool_id%!",
                                "%pool_id%", poolId, "%item%", item.getType().name()));
                    }
                }
                player.sendMessage(config.componentDef("messages.items-added", "<gray>%amount% предметов добавлены в пул %pool_id%!",
                        "%pool_id%", poolId, "%amount%", String.valueOf(count)));
                return;
            } else if ("addsubpool".equalsIgnoreCase(action)) {
                String subPoolId = args[3];
                TreasurePool subPool = TreasurePool.getTreasurePool(subPoolId);
                if (subPool == null) {
                    sender.sendMessage(config.componentDef("messages.pool-not-found", "<red>Пул %pool_id% не найден!",
                            "%pool_id%", subPoolId));
                    return;
                }
                CommandContext context = CommandContext.of(args, List.of(
                        Par.of("weight", ArgType.INTEGER, 1)
                ));
                SubPoolTreasure subPoolTreasure = SubPoolTreasure.builder()
                        .subPoolId(subPoolId)
                        .build();
                subPoolTreasure.setWeight(context.get("weight"));
                boolean add = pool.add(subPoolTreasure);
                if (add) {
                    sender.sendMessage(config.componentDef("messages.subpool-added", "<gray>Сабпул %subpool_id% добавлен в пул %pool_id%!",
                            "%pool_id%", poolId, "%subpool_id%", subPoolId));
                } else {
                    sender.sendMessage(config.componentDef("messages.subpool-already-added", "<red>Сабпул %subpool_id% уже добавлен в пул %pool_id%!",
                            "%pool_id%", poolId, "%subpool_id%", subPoolId));
                }
            } else if ("give".equalsIgnoreCase(action)) {
                CommandContext context = CommandContext.of(args, List.of(
                        Par.of("player", ArgType.STRING, null)
                ));
                String targetName = args.length >= 4 ? args[3] : sender.getName();
                Player playerExact = player;
                if (targetName != null) playerExact = Bukkit.getPlayerExact(targetName);
                if (playerExact == null) {
                    sender.sendMessage(config.componentDef("messages.player-not-found", "<red>Игрок %player% не найден!",
                            "%player%", targetName));
                    return;
                }
                if (pool.size() == 0) {
                    sender.sendMessage(config.componentDef("messages.pool-empty", "<red>Пул %pool_id% пуст!",
                            "%pool_id%", poolId));
                    return;
                }
                pool.random().give(playerExact);
                sender.sendMessage(config.componentDef("messages.given", "<gray>Награда выдана игроку %player%!",
                        "%player%", playerExact.getName()));
            }
        }
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

    private void processLocPoolCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("arc.locpool.admin")) {
            sender.sendMessage(TextUtil.noPermissions());
            return;
        }
        Config config = ConfigManager.of(ARC.plugin.getDataPath(), "location-pools.yml");
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.componentDef("messages.player-only", "<red>Только игроки могут использовать эту команду!"));
            return;
        }

        String current = LocationPoolManager.getEditing(player.getUniqueId());

        // arc locpool - list all pools
        if (args.length == 1) {
            player.sendMessage(config.componentDef("messages.current-pools", "<gray>Текущие пулы локаций: %pools%",
                    "%pools%", LocationPoolManager.getAll().stream()
                            .map(LocationPool::getId)
                            .collect(Collectors.joining(", ", "[", "]"))));
            return;
        }

        // arc locpool edit - cancel editing
        if (args.length == 2 && args[1].equals("edit")) {
            if (current == null) {
                player.sendMessage(config.componentDef("messages.not-editing", "<gray>Вы не редактируете никакой пул локаций!"));
                return;
            }
            LocationPoolManager.cancelEditing(player.getUniqueId(), false);
            return;
        }
        if (args.length == 2 && args[1].equals("delete")) {
            player.sendMessage(config.componentDef("messages.specify-pool", "<gray>Укажите пул локаций для удаления!"));
            return;
        }

        String action = args[1];
        String id = args[2];

        if (action.equals("edit")) {
            if (id.equals(current)) {
                LocationPoolManager.cancelEditing(player.getUniqueId(), false);
                return;
            }
            LocationPoolManager.setEditing(player.getUniqueId(), id);
            player.getInventory().addItem(ItemStack.of(Material.GOLD_BLOCK), ItemStack.of(Material.REDSTONE_BLOCK));
        } else if (action.equals("delete")) {
            boolean res = LocationPoolManager.delete(id);
            if (res) {
                player.sendMessage(config.componentDef("messages.pool-deleted", "<gray>Пул %pool_id% удален успешно!",
                        "%pool_id%", id));
            } else {
                player.sendMessage(config.componentDef("messages.pool-not-found", "<red>Пул %pool_id% не найден!",
                        "%pool_id%", id));
            }
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

        AuditManager.sendAudit(sender, playerName, page, filter);
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
            info("AI command on cooldown for player {}", player.getName());
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
            return List.of("reload", "joinmessage", "quitmessage", "board", "emshop", "jobsboosts", "baltop", "treasures", "logger", "ai", "audit", "repo", "hunt", "locpool");
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

        if (strings.length > 1 && strings[0].equalsIgnoreCase("repo")) {
            return List.of("save", "size");
        }

        if (strings.length > 1 && strings[0].equalsIgnoreCase("locpool")) {
            if (strings.length == 2) {
                return List.of("edit", "delete");
            }
            if (strings.length == 3) {
                return new ArrayList<>(LocationPoolManager.getAll().stream().map(LocationPool::getId).toList());
            }
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

        if (strings.length > 1 && strings[0].equalsIgnoreCase("treasures")) {
            if (strings.length == 2) {
                List<String> result = new ArrayList<>();
                result.add("reload");
                result.addAll(TreasurePool.getTreasurePools().stream().map(TreasurePool::getId).toList());
                return result;
            }
            if (strings.length == 3) {
                return List.of("addhand", "addchest", "addsubpool", "give");
            }
            if (strings.length == 4) {
                if (strings[2].equalsIgnoreCase("addsubpool")) {
                    return TreasurePool.getTreasurePools().stream().map(TreasurePool::getId)
                            .filter(id -> !id.equalsIgnoreCase(strings[1]))
                            .toList();
                }
                if (strings[2].equalsIgnoreCase("give")) {
                    return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                }
            }
            Set<String> args = new HashSet<>();
            Collections.addAll(args, strings);
            List<String> result = new ArrayList<>();
            if (args.contains("addhand") || args.contains("addchest") || args.contains("addsubpool")) {
                result.add("-weight:1");
            }
            if (args.contains("addhand")) {
                result.add("-quantity:1");
            }
            return result;
        }

        if (strings.length > 1 && strings[0].equalsIgnoreCase("hunt")) {

            if (strings.length == 2) {
                return List.of("start", "stop");
            }

            if (strings.length == 3) {
                List<String> res = new ArrayList<>(LocationPoolManager.getAll().stream().map(LocationPool::getId).toList());
                if (strings[1].equalsIgnoreCase("start")) res.addAll(TreasureHuntManager.getTreasureHuntTypes());
                return res;
            }

            if (strings.length == 4) {
                LocationPool locationPool = LocationPoolManager.getPool(strings[1]);
                if (locationPool == null) return List.of("кол-во");
                return List.of((locationPool.getLocations().size() + ""));
            }

            if (strings.length == 5) {
                List<String> list = new ArrayList<>(TreasureHunt.aliases().keySet());
                list.add("vanilla");
                list.add("ItemsAdderId");
                return list;
            }

            if (strings.length == 6) {
                return TreasureHuntManager.getTreasurePools().stream().map(TreasurePool::getId).toList();
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
                info("Listing all classes in arc package");
                Reflections reflections = new Reflections("arc");
                info(reflections.toString());
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
