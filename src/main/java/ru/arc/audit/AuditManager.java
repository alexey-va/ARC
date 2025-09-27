package ru.arc.audit;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.network.repos.RedisRepo;
import ru.arc.util.Utils;
import ru.arc.xserver.playerlist.PlayerManager;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static ru.arc.util.Logging.*;
import static ru.arc.util.TextUtil.mm;
import static java.nio.file.StandardOpenOption.*;

@Slf4j
public class AuditManager {

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "audit.yml");
    private static RedisRepo<AuditData> repo;

    private static BukkitTask pruneTask;
    private static BukkitTask balanceHistoryTask;

    private static final Path balanceHistoryPath = ARC.plugin.getDataPath().resolve("balance-history");

    public static void init() {
        cancel();
        if (!Files.exists(balanceHistoryPath)) {
            try {
                Files.createDirectories(balanceHistoryPath);
            } catch (IOException e) {
                error("Failed to create balance history directory", e);
            }
        }
        if (repo == null) {
            repo = RedisRepo.builder(AuditData.class)
                    .saveBackups(false)
                    .id("audit")
                    .redisManager(ARC.redisManager)
                    .storageKey("arc.audits")
                    .saveInterval((long) config.integer("save-interval", 20))
                    .updateChannel("arc.audit-update")
                    .loadAll(false)
                    .build();
        }

        pruneTask = new BukkitRunnable() {
            @Override
            public void run() {
                long maxAge = config.integer("max-age-seconds", 86400 * 30) * 1000L;
                int maxWeight = config.integer("max-weight", 100000);
                long weight = weight();
                int count = 0;
                while (weight > maxWeight) {
                    info("Pruning audit data, weight: {}, maxAge: {}", weight, maxAge);
                    for (AuditData auditData : repo.all()) {
                        auditData.trim(maxAge);
                    }
                    weight = weight();
                    maxAge /= 2;
                    count++;
                    if (count > 10) {
                        warn("Pruning audit data failed to reduce weight below maxWeight: {}", maxWeight);
                        break;
                    }
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin,
                config.integer("prune-interval", 6000),
                config.integer("prune-interval", 6000));

        balanceHistoryTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!config.bool("balance-history", false)) {
                    return;
                }
                for (String player : PlayerManager.getPlayerNames()) {
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(player);
                    if (offlinePlayer.getName() == null || !offlinePlayer.getName().equalsIgnoreCase(player)) {
                        error("Failed to get offline player for {}", player);
                        continue;
                    }
                    double balance = ARC.getEcon().getBalance(offlinePlayer);
                    Path playerPath = balanceHistoryPath.resolve(player + ".csv");
                    long timestamp = System.currentTimeMillis();
                    try {
                        if (!Files.exists(playerPath)) {
                            Files.createFile(playerPath);
                        }
                        Files.write(playerPath, (timestamp + "," + balance + "\n").getBytes(), CREATE, APPEND, WRITE);
                        //info("Saved balance data for {}", player);
                    } catch (IOException e) {
                        error("Failed to write balance history for {}", player, e);
                    }
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 20, 20L * 60 * 5);
    }

    public static void join(String name) {
        repo.addContext(name.toLowerCase());
    }

    public static void leave(String name) {
        repo.removeContext(name.toLowerCase());
    }


    public static void cancel() {
        if (pruneTask != null && !pruneTask.isCancelled()) {
            pruneTask.cancel();
        }
        if (balanceHistoryTask != null && !balanceHistoryTask.isCancelled()) {
            balanceHistoryTask.cancel();
        }
    }

    public static void operation(String name, double amount, Type type, String comment) {
        repo.getOrCreate(name.toLowerCase(), () -> new AuditData(new ConcurrentLinkedDeque<>(), name, System.currentTimeMillis()))
                .thenAccept(auditData -> auditData.operation(amount, type, comment));
    }

    public static long weight() {
        long weight = 0;
        for (AuditData auditData : repo.all()) {
            weight += auditData.getTransactions().size();
        }
        return weight;
    }

    public static void sendAudit(Audience player, String auditedPlayer, int page, Filter filter) {
        repo.getOrCreate(auditedPlayer.toLowerCase(), () -> new AuditData(new ConcurrentLinkedDeque<>(), auditedPlayer, System.currentTimeMillis()))
                .thenAccept(auditData -> {
                    if (auditData == null) {
                        player.sendMessage(config.componentDef("messages.no-audit-data", "<red>No audit data found for %player_name%",
                                "%player_name%", auditedPlayer));
                        return;
                    }
                    List<Transaction> transactions = new ArrayList<>(auditData.getTransactions());
                    if (filter != null && filter != Filter.ALL) {
                        if (filter == Filter.INCOME) {
                            transactions.removeIf(transaction -> transaction.getAmount() < 0);
                        } else if (filter == Filter.EXPENSE) {
                            transactions.removeIf(transaction -> transaction.getAmount() > 0);
                        } else if (filter == Filter.SHOP) {
                            transactions.removeIf(transaction -> transaction.getType() != Type.SHOP);
                        } else if (filter == Filter.JOB) {
                            transactions.removeIf(transaction -> transaction.getType() != Type.JOB);
                        } else if (filter == Filter.PAY) {
                            transactions.removeIf(transaction -> transaction.getType() != Type.PAY);
                        }
                    }
                    player.sendMessage(formatAudit(transactions.reversed(), auditedPlayer, page, filter));
                });
    }

    public enum Filter {
        INCOME, EXPENSE, ALL, SHOP, JOB, PAY
    }

    private static Component formatAudit(Collection<Transaction> reversedAuditData, String playerName, int page, Filter filter) {
        int pageSize = config.integer("messages.page-size", 20);
        int start = pageSize * (page - 1);
        int end = Math.min(start + pageSize, reversedAuditData.size());
        int counter = 0;
        String headerFormat = config.string("messages.header-format", "\n<gold>%player_name%'s Audit Data");
        String transactionFormat = config.string("messages.transaction-format", "<hover:<yellow>%comment%><gray>%counter%. <gray>[%date%] <white>%type% <gold>%amount%</hover>");
        String incomeFormat = config.string("messages.income-format", "<green>+%amount%");
        String expenseFormat = config.string("messages.expense-format", "<red>-%amount%");
        String nextPage = config.string("messages.next-page", "<click:run_command:/arc audit %player_name% %filter% %next_page%><hover:show_text:'Click to view next page'><gold>>");
        String prevPage = config.string("messages.prev-page", "<click:run_command:/arc audit %player_name% %filter% %prev_page%><hover:show_text:'Click to view previous page'><gold><");
        String footerFormat = config.string("messages.footer-format", "%prev%<gray>Page <gold>%page% <gray>of <gold>%total_pages%%next%\n");
        List<String> lines = new ArrayList<>();
        lines.add(headerFormat.replace("%player_name%", playerName));
        for (Transaction transaction : reversedAuditData) {
            if (counter >= start && counter < end) {
                String format = transaction.getAmount() > 0 ? incomeFormat : expenseFormat;
                String rounded = String.format("%.2f", Math.abs(transaction.getAmount()));
                String amount = format.replace("%amount%", rounded);
                String counterWithPadding = String.format("%03d", counter + 1);
                String line = transactionFormat
                        .replace("%counter%", counterWithPadding)
                        .replace("%date%", Utils.formatDate(transaction.getTimestamp()))
                        .replace("%type%", transaction.getType().name())
                        .replace("%amount%", amount)
                        .replace("%date2%", Utils.formatDate(transaction.getTimestamp2()))
                        .replace("%comment%", transaction.getComment() == null ? "-" : transaction.getComment());
                lines.add(line);
            }
            counter++;
            if (counter >= end) break;
        }
        int totalPages = (int) Math.ceil((double) reversedAuditData.size() / pageSize);
        String footer = footerFormat
                .replace("%prev%", page > 1 ? prevPage.replace("%player_name%", playerName).replace("%prev_page%", String.valueOf(page - 1)) : "")
                .replace("%page%", String.valueOf(page))
                .replace("%total_pages%", String.valueOf(totalPages))
                .replace("%next%", page < totalPages ? nextPage.replace("%player_name%", playerName).replace("%next_page%", String.valueOf(page + 1)) : "")
                .replace("%filter%", filter == null ? "ALL" : filter.name().toLowerCase());
        lines.add(footer);
        return mm(String.join("\n", lines));
    }

    public static void clear(String player) {
        repo.getOrNull(player.toLowerCase()).thenAccept(auditData -> {
            if (auditData != null) {
                auditData.getTransactions().clear();
                auditData.setDirty(true);
            }
        });
    }

    public static void clearAll() {
        for (AuditData auditData : repo.all()) {
            auditData.getTransactions().clear();
            auditData.setDirty(true);
        }
    }
}

