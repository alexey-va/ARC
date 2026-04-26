package ru.arc.listeners;

import java.util.Set;
import java.util.stream.Collectors;

import com.Zrips.CMI.events.CMIAsyncPlayerTeleportEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import ru.arc.ARC;
import ru.arc.Portal;
import ru.arc.PortalData;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;

import static ru.arc.PortalData.ActionType.TELEPORT;

public class CMIListener implements Listener {

    /**
     * CMI teleport types that trigger portal effect — {@code portal.cmi-tp-types} in misc.yml.
     */
    static final Config commandConfig = ConfigManager.of(ARC.getInstance().getDataPath(), "misc.yml");

    @EventHandler
    public void onCMITp(CMIAsyncPlayerTeleportEvent event) {
        if (event.getSender().hasPermission("arc.bypass-portal")) return;
        if (event.getType() == null || event.getTo() == null || event.getPlayer() == null) return;
        Set<String> types = commandConfig.stringList("portal.cmi-tp-types").stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        if (!types.contains(event.getType().toString().toLowerCase())) return;
        event.setCancelled(true);
        new Portal(event.getPlayer().getUniqueId(), new PortalData(TELEPORT, null, event.getTo(), null));
    }

}
