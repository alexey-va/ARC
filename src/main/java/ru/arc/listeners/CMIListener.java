package ru.arc.listeners;

import ru.arc.ARC;
import ru.arc.Portal;
import ru.arc.PortalData;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import com.Zrips.CMI.events.CMIAsyncPlayerTeleportEvent;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Set;
import java.util.stream.Collectors;

import static ru.arc.PortalData.ActionType.TELEPORT;

@Slf4j
public class CMIListener implements Listener {

    static final Config commandConfig = ConfigManager.of(ARC.plugin.getDataPath(), "portal.yml");

    @EventHandler
    public void onCMITp(CMIAsyncPlayerTeleportEvent event) {
        if (event.getSender().hasPermission("arc.bypass-portal")) return;
        if (event.getType() == null || event.getTo() == null || event.getPlayer() == null) return;
        Set<String> types = commandConfig.stringList("cmi-tp-types").stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        if (!types.contains(event.getType().toString().toLowerCase())) return;
        event.setCancelled(true);
        new Portal(event.getPlayer().getUniqueId(), PortalData.builder()
                .actionType(TELEPORT)
                .location(event.getTo())
                .build());
    }

}
