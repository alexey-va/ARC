package ru.arc.hooks;

import ru.arc.ARC;
import ru.arc.audit.AuditManager;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import dev.unnm3d.rediseconomy.api.TransactionEvent;
import dev.unnm3d.rediseconomy.transaction.AccountID;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import static ru.arc.audit.Type.PAY;
import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

@Slf4j
public class RedisEcoListener implements Listener {

    private static Config miscConfig = ConfigManager.of(ARC.plugin.getDataPath(), "misc.yml");

    @EventHandler
    public void onTransaction(TransactionEvent event) {
        //if (!miscConfig.bool("redis.main-server", false)) return;
        //info("Transaction: {}", event.getTransaction());
        double amount = event.getTransaction().getAmount();
        AccountID accountIdentifier = event.getTransaction().getAccountIdentifier();
        if (!accountIdentifier.isPlayer()) {
            return;
        }
        if (!"Payment".equalsIgnoreCase(event.getTransaction().getReason())) {
            return;
        }
        AccountID otherPlayerUuid = amount > 0 ? event.getTransaction().getActor() : event.getTransaction().getAccountIdentifier();
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(accountIdentifier.getUUID());
        OfflinePlayer otherPlayer = null;
        try {
            otherPlayer = Bukkit.getOfflinePlayer(event.getTransaction().getActor().getUUID());
        } catch (Exception e) {
            error("Error getting player", e);
        }

        if (offlinePlayer.getName() == null) {
            info("Transaction of {} for unknown player {}", amount, accountIdentifier);
            return;
        }
        if (Math.abs(amount) < 1.0) {
            return;
        }
        AuditManager.operation(offlinePlayer.getName(), amount, PAY,
                otherPlayer == null || otherPlayer.getName() == null ? otherPlayerUuid + "" : otherPlayer.getName());
    }

}
