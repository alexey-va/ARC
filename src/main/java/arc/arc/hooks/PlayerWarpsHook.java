package arc.arc.hooks;

import com.olziedev.playerwarps.api.PlayerWarpsAPI;
import com.olziedev.playerwarps.api.warp.Warp;
import org.bukkit.entity.Player;

public class PlayerWarpsHook {

    public boolean warpExists(String name, Player player){
        Warp warp = PlayerWarpsAPI.getInstance().getPlayerWarp(name, player);
        return warp != null;
    }

}
