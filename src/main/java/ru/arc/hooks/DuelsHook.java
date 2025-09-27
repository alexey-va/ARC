package ru.arc.hooks;

import com.google.gson.annotations.SerializedName;
import com.meteordevelopments.duels.api.Duels;
import com.meteordevelopments.duels.api.user.User;
import lombok.Builder;
import lombok.Data;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.arc.ARC;
import ru.arc.sync.base.SyncData;

import java.util.UUID;

public class DuelsHook {

     Duels api;

    public DuelsHook() {
        api = (Duels) Bukkit.getServer().getPluginManager().getPlugin("Duels");
    }

    public void stopDuelsIfAny(Player quitter){

    }

    public DuelsData getUserData(UUID uuid) {
        User user = api.getUserManager().get(uuid);
        if (user == null) {
            return null;
        }
        return DuelsData.builder()
                .server(ARC.serverName)
                .timestamp(System.currentTimeMillis())
                .uuid(uuid)
                .wins(user.getWins())
                .losses(user.getLosses())
                .accept(user.canRequest())
                .build();
    }

    public void setUserData(UUID uuid, DuelsData data) {
        User user = api.getUserManager().get(uuid);
        if (user == null) {
            return;
        }
        if(user.canRequest() != data.isAccept()) {
            user.setRequests(data.accept);
        }
        if (user.getWins() != data.getWins()) {
            user.setWins(data.getWins());
        }
        if (user.getLosses() != data.getLosses()) {
            user.setLosses(data.getLosses());
        }
    }

    @Data
    @Builder
    public static class DuelsData implements SyncData {

        @SerializedName("t")
        long timestamp;
        @SerializedName("s")
        String server;
        @SerializedName("u")
        UUID uuid;
        @SerializedName("w")
        int wins;
        @SerializedName("l")
        int losses;
        @SerializedName("a")
        boolean accept;

        @Override
        public long timestamp() {
            return timestamp;
        }

        @Override
        public String server() {
            return server;
        }

        @Override
        public UUID uuid() {
            return uuid;
        }
    }

}
