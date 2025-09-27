package ru.arc.xserver;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;
import ru.arc.hooks.PAPIHook;

import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static ru.arc.util.Logging.warn;

@Data
@Builder
@Slf4j
public class XCondition implements Predicate<Player> {
    String playerName;
    UUID playerUuid;
    String permission;
    String serverName;
    Map<String, String> placeholders;

    private static final Config miscConfig = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "misc.yml");

    public static XCondition ofPermission(String permission) {
        return XCondition.builder().permission(permission).build();
    }

    public static XCondition ofServerName(String serverName) {
        return XCondition.builder().serverName(serverName).build();
    }

    public static XCondition ofPlayerName(String playerName) {
        return XCondition.builder().playerName(playerName).build();
    }

    public static XCondition ofPlayerUuid(UUID playerUuid) {
        return XCondition.builder().playerUuid(playerUuid).build();
    }

    @Override
    public boolean test(Player player) {
        if (serverName != null && !serverName.equalsIgnoreCase(miscConfig.string("redis.server-name"))) {
            return false;
        } else if (playerName != null && !player.getName().equalsIgnoreCase(playerName)) {
            return false;
        } else if (playerUuid != null && !player.getUniqueId().equals(playerUuid)) {
            return false;
        } else if (permission != null && !player.hasPermission(permission)) {
            return false;
        } else if (placeholders != null && !placeholders.isEmpty()) {
            PAPIHook papiHook = HookRegistry.papiHook;
            if (papiHook == null) {
                warn("PAPI hook is not active!");
                return false;
            }
            for (var entry : placeholders.entrySet()) {
                String res = papiHook.parse(entry.getKey(), player);
                if (!res.equalsIgnoreCase(entry.getValue())) return false;
            }
        }
        return true;
    }
}
