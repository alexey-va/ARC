package ru.arc.hooks;

import com.olziedev.playerwarps.api.PlayerWarpsAPI;
import com.olziedev.playerwarps.api.warp.Warp;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;

@Slf4j
public class PlayerWarpsHook {

    public boolean warpExists(String name, Player player){
        Warp warp = PlayerWarpsAPI.getInstance().getPlayerWarp(name, player);
        log.info("Warp name {} {}", name, warp);
        return warp != null;
    }

}
