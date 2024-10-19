package arc.arc.listeners;

import arc.arc.Portal;
import arc.arc.PortalData;
import arc.arc.configs.Config;
import com.Zrips.CMI.events.CMIAsyncPlayerTeleportEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Set;
import java.util.stream.Collectors;

import static arc.arc.PortalData.*;
import static arc.arc.PortalData.ActionType.*;

@Slf4j
@RequiredArgsConstructor
public class CMIListener implements Listener {

    final Config commandConfig;

    @EventHandler
    public void onCMITp(CMIAsyncPlayerTeleportEvent event) {
        if (event.getSender().hasPermission("arc.bypass-portal")) return;
        if (event.getType() == null || event.getTo() == null || event.getPlayer() == null) return;
        Set<String> types = commandConfig.stringList("cmi-tp-types").stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        if (!types.contains(event.getType().toString().toLowerCase())) return;
        event.setCancelled(true);
        new Portal(event.getPlayer().getUniqueId(), builder()
                .actionType(TELEPORT)
                .location(event.getTo())
                .build());
    }

}
