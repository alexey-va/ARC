package arc.arc.audit;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.network.repos.RedisRepo;
import arc.arc.util.Utils;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static arc.arc.util.TextUtil.mm;

@Slf4j
public class AuditManager {

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "audit.yml");
    private static RedisRepo<AuditData> repo;

    private static BukkitTask pruneTask;

    public static void init() {
        cancel();
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
                    log.info("Pruning audit data, weight: {}, maxAge: {}", weight, maxAge);
                    for (AuditData auditData : repo.all()) {
                        auditData.trim(maxAge);
                    }
                    weight = weight();
                    maxAge /= 2;
                    count++;
                    if (count > 10) {
                        log.warn("Pruning audit data failed to reduce weight below maxWeight: {}", maxWeight);
                        break;
                    }
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin,
                config.integer("prune-interval", 6000),
                config.integer("prune-interval", 6000));
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

    public static void sendAudit(Player player, String auditedPlayer, int page, Filter filter) {
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
        String headerFormat = config.string("messages.header-format", "<gold>%player_name%'s Audit Data");
        String transactionFormat = config.string("messages.transaction-format", "<hover:<yellow>%comment%><gray>%counter%. <gray>[%date%] <white>%type% <gold>%amount%</hover>");
        String incomeFormat = config.string("messages.income-format", "<green>+%amount%");
        String expenseFormat = config.string("messages.expense-format", "<red>-%amount%");
        String nextPage = config.string("messages.next-page", "<click:run_command:/arc audit %player_name% %filter% %next_page%><hover:show_text:'Click to view next page'><gold><");
        String prevPage = config.string("messages.prev-page", "<click:run_command:/arc audit %player_name% %filter% %prev_page%><hover:show_text:'Click to view previous page'><gold>>");
        String footerFormat = config.string("messages.footer-format", "%prev%<gray>Page <gold>%page% <gray>of <gold>%total_pages%%next%");
        List<String> lines = new ArrayList<>();
        lines.add(headerFormat.replace("%player_name%", playerName));
        for (Transaction transaction : reversedAuditData) {
            if (counter >= start && counter < end) {
                String format = transaction.getAmount() > 0 ? incomeFormat : expenseFormat;
                String rounded = String.format("%.2f", Math.abs(transaction.getAmount()));
                String amount = format.replace("%amount%", rounded);
                String line = transactionFormat
                        .replace("%counter%", String.valueOf(counter + 1))
                        .replace("%date%", Utils.formatDate(transaction.getTimestamp()))
                        .replace("%type%", transaction.getType().name())
                        .replace("%amount%", amount)
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
                .replace("%filter%", filter == null ? "ALL" : filter.name().toLowerCase())
                .replace("%next%", page < totalPages ? nextPage.replace("%player_name%", playerName).replace("%next_page%", String.valueOf(page + 1)) : "");
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

