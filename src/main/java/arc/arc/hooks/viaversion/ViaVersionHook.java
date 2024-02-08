package arc.arc.hooks.viaversion;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import org.bukkit.entity.Player;

public class ViaVersionHook {

    public int getPlayerVersion(Player player) {
        UserConnection userConnection = Via.getManager().getConnectionManager().getConnectedClient(player.getUniqueId());
        if (userConnection != null) {
            System.out.println("Version: "+userConnection.getProtocolInfo().getProtocolVersion());
            return userConnection.getProtocolInfo().getProtocolVersion();
        }
        return -1; // Return -1 if unable to get the version
    }

}
