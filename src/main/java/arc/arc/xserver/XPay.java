package arc.arc.xserver;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Data
@ToString(callSuper = true)
@Slf4j
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
            log.error("Amount is 0: {}", this);
            return;
        }
        if (!miscConfig.bool("xaction.main-server", false)) {
            log.debug("Main server is disabled, skipping pay action");
            return;
        }
        OfflinePlayer player = null;
        if (playerName != null) {
            player = Bukkit.getOfflinePlayer(playerName);
        } else if (playerUuid != null) {
            player = Bukkit.getOfflinePlayer(playerUuid);
        }
        if (player == null) {
            log.error("Player not found: {}", this);
            return;
        }
        EconomyResponse economyResponse = ARC.getEcon().depositPlayer(player, amount);
        if (!economyResponse.transactionSuccess()) {
            log.error("Error depositing money: {}", economyResponse.errorMessage);
        }
    }
}
