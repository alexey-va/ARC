package ru.arc.audit;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.network.repos.RepoData;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AuditData extends RepoData<AuditData> {

    @SerializedName("t")
    Deque<Transaction> transactions = new ConcurrentLinkedDeque<>();
    @SerializedName("n")
    String name;
    @SerializedName("c")
    long created;

    private static Config config = ConfigManager.of(ARC.plugin.getDataPath(), "audit.yml");

    public void operation(double amount, Type type, String comment) {
        Transaction latestFitting = null;
        int count = 0;
        for (Transaction t : transactions.reversed()) {
            if (t.getType() == type && t.getComment().equals(comment)) {
                latestFitting = t;
                break;
            }
            count++;
            if (count > 10) {
                break;
            }
        }
        if (latestFitting != null) {
            double newAmount = latestFitting.getAmount() + amount;
            latestFitting.setAmount(newAmount);
            latestFitting.setTimestamp2(System.currentTimeMillis());
            setDirty(true);
            return;
        }
        transactions.add(new Transaction(type, amount, comment));
        setDirty(true);
    }

    public void trim(Long lifetime) {
        int maxTransactions = config.integer("max-transactions", 50000);
        long maxAge = lifetime == null ? config.integer("max-age-seconds", 86400 * 30) * 1000L : lifetime;
        boolean removedAny = false;
        while (!transactions.isEmpty() && (transactions.size() > maxTransactions || transactions.peek().getTimestamp() < System.currentTimeMillis() - maxAge)) {
            transactions.poll();
            removedAny = true;
        }
        if (removedAny) {
            setDirty(true);
        }
    }

    @Override
    public String id() {
        return name.toLowerCase();
    }

    @Override
    public boolean isRemove() {
        return created < System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30 && transactions.isEmpty();
    }

    @Override
    public void merge(AuditData other) {
        transactions.clear();
        transactions.addAll(other.transactions);
    }
}
