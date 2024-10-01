package arc.arc.xserver;

import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import arc.arc.hooks.HookRegistry;
import arc.arc.hooks.PAPIHook;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;

@Data
@Builder
@Slf4j
public class XCondition implements Predicate<Player> {
    String playerName;
    UUID playerUuid;
    String permission;
    Map<String, String> placeholders;

    @Override
    public boolean test(Player player) {
        if(playerName != null && !player.getName().equalsIgnoreCase(playerName)) {
            return false;
        } else if(playerUuid != null && !player.getUniqueId().equals(playerUuid)){
            return false;
        } else if(permission != null && !player.hasPermission(permission)){
            return false;
        } else if(placeholders != null && !placeholders.isEmpty()){
            PAPIHook papiHook = HookRegistry.papiHook;
            if(papiHook == null){
                log.warn("PAPI hook is not active!");
                return false;
            }
            for(var entry : placeholders.entrySet()){
                String res = papiHook.parse(entry.getKey(), player);
                if(!res.equalsIgnoreCase(entry.getValue())) return false;
            }
        }
        return true;
    }
}
