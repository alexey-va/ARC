package ru.arc.hooks;

import com.olziedev.playerwarps.api.PlayerWarpsAPI;
import com.olziedev.playerwarps.api.warp.Warp;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;

import static ru.arc.util.Logging.info;

@Slf4j
public class PlayerWarpsHook {

    public boolean warpExists(String name, Player player){
        Warp warp = PlayerWarpsAPI.getInstance().getPlayerWarp(name, player);
        info("Warp name {} {}", name, warp);
        return warp != null;
    }

}
