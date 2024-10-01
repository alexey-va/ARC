package arc.arc.xserver;

import java.util.Collection;
import java.util.UUID;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@Slf4j
public class XCommand extends XAction {

    String command;
    String playerName;
    UUID playerUuid;
    int ticksTimeout;

    @Override
    protected void runInternal(Collection<Player> players) {
        if (playerName == null && playerUuid == null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } else {
            Player target = null;
            if (playerName != null) {
                target = Bukkit.getPlayerExact(playerName);
            } else {
                target = Bukkit.getPlayer(playerUuid);
            }
            if (target == null) {
                log.warn("Could not find player for {}", this);
            } else {
                executeForPlayers(players);
            }
        }
    }

    private void executeForPlayers(Collection<Player> players) {

    }
}
