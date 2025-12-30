package ru.arc.xserver;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;

import static ru.arc.util.Logging.debug;
import static ru.arc.util.Logging.error;

@EqualsAndHashCode(callSuper = true)
@Data
@ToString(callSuper = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class XPay extends XAction {

    double amount;
    String playerName;
    UUID playerUuid;

    private static final Config miscConfig = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "misc.yml");

    @Override
    protected void runInternal() {
        if (amount == 0) {
            error("Amount is 0: {}", this);
            return;
        }
        if (!miscConfig.bool("xaction.main-server", false)) {
            debug("Main server is disabled, skipping pay action");
            return;
        }
        OfflinePlayer player = null;
        if (playerName != null) {
            player = Bukkit.getOfflinePlayer(playerName);
        } else if (playerUuid != null) {
            player = Bukkit.getOfflinePlayer(playerUuid);
        }
        if (player == null) {
            error("Player not found: {}", this);
            return;
        }
        EconomyResponse economyResponse = ARC.getEcon().depositPlayer(player, amount);
        if (!economyResponse.transactionSuccess()) {
            error("Error depositing money: {}", economyResponse.errorMessage);
        }
    }
}
