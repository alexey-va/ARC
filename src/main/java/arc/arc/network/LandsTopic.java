package arc.arc.network;

import arc.arc.ARC;
import arc.arc.Config;
import arc.arc.network.entries.CrossServerLocation;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LandsTopic implements Listener {

    private static final Map<UUID, PendingRequest> pendingRequests = new HashMap<>();

    @EventHandler
    public void onLandsMessage(MessageEvent event){

    }

    private static void processLandsRequest(String message, String server) {
        if (server.equalsIgnoreCase(Config.server) || ARC.plugin.landsHook == null) return;
        UUID uuid = UUID.fromString(message);
        ARC.plugin.landsHook.getSpawnLocation(uuid).thenAccept(
                crossServerLocation -> {

                }
        );
    }

    public static void publishLandsRequest(UUID uuid) {
        String request = uuid + ":::" + Config.server;
        pendingRequests.put(uuid, new PendingRequest(uuid));
        //executorService.execute(() -> pub.publish("arc.lands_requests", request));
    }

    private static void publishLandsLocation(CrossServerLocation crossServerLocation){

    }

    static class PendingRequest {
        long timestamp;
        UUID uuid;

        public PendingRequest(UUID uuid) {
            this.uuid = uuid;
            timestamp = System.currentTimeMillis();
        }

        boolean isStale() {
            return (System.currentTimeMillis() - timestamp > 3000);
        }
    }

}
