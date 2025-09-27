package ru.arc.sync;

import ru.arc.ARC;
import ru.arc.sync.base.Context;
import ru.arc.sync.base.Sync;
import ru.arc.sync.base.SyncData;
import ru.arc.sync.base.SyncRepo;
import com.google.gson.annotations.SerializedName;
import com.magmaguy.elitemobs.playerdata.database.PlayerData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static ru.arc.util.Logging.debug;
import static ru.arc.util.Logging.warn;

@Log4j2
public class EmSync implements Sync {


    SyncRepo<EmDataDTO> repo;
    Map<UUID, Boolean> loaded = new ConcurrentHashMap<>();

    public EmSync() {
        this.repo = SyncRepo.builder(EmDataDTO.class)
                .key("arc.em_data")
                .redisManager(ARC.redisManager)
                .dataApplier(this::deserializeAndSavePlayerData)
                .dataProducer(this::serializePlayerData)
                .build();
    }

    @Override
    public void playerJoin(UUID uuid) {
        AtomicInteger counter = new AtomicInteger(0);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (Bukkit.getPlayer(uuid) == null) {
                    cancel();
                    return;
                }
                PlayerData pd = PlayerData.getPlayerData(uuid);
                if (pd == null) {
                    if (counter.incrementAndGet() > 20) {
                        warn("PlayerData is null for {} for 20 cycles. Cancelling task.", uuid);
                        cancel();
                    }
                    return;
                }
                repo.loadAndApplyData(uuid, false);
                loaded.put(uuid, true);
                cancel();
            }
        }.runTaskTimer(ARC.plugin, 5L, 5L);
    }

    @Override
    public void playerQuit(UUID uuid) {
        forceSave(uuid);
        loaded.remove(uuid);
    }

    @Override
    public void processEvent(Event event) {
        //repo.processEvent(event);
    }

    @Override
    public void forceSave(UUID uuid) {
        if (!loaded.containsKey(uuid)) return;
        Context context = new Context();
        context.put("uuid", uuid);
        repo.saveAndPersistData(context, false);
    }

    public void deserializeAndSavePlayerData(EmDataDTO data) {
        UUID uuid = data.getUuid();
        PlayerData pd = PlayerData.getPlayerData(uuid);
        if (pd == null) {
            warn("PlayerData is not yet loaded for " + uuid);
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);

        if (PlayerData.getCurrency(uuid) != data.getCurrency()) PlayerData.setCurrency(uuid, data.getCurrency());
        if (pd.getActiveGuildLevel() != data.getLevel()) {
            PlayerData.setActiveGuildLevel(uuid, data.getLevel());
        }
        if (pd.getGuildPrestigeLevel() != data.getPrestige())
            PlayerData.setGuildPrestigeLevel(uuid, data.getPrestige());
        if (pd.getHighestLevelKilled() != data.getHighestLevelKilled())
            PlayerData.setHighestLevelKilled(uuid, data.getHighestLevelKilled());
        if (pd.isUseBookMenus() != data.isUseBookMenu() && offlinePlayer instanceof Player player)
            PlayerData.setUseBookMenus(player, data.isUseBookMenu());
        if (pd.isDismissEMStatusScreenMessage() != data.isDismissEmStatus() && offlinePlayer instanceof Player player)
            PlayerData.setDismissEMStatusScreenMessage(player, data.isDismissEmStatus());

        if (data.getScore() != PlayerData.getScore(uuid)) {
            pd.setScore(data.getScore());
            PlayerData.setDatabaseValue(uuid, "Score", data.getScore());
        }

        if (data.getKills() != PlayerData.getKills(uuid)) {
            pd.setKills(data.getKills());
            PlayerData.setDatabaseValue(uuid, "Kills", data.getKills());
        }

        if (data.getDeaths() != PlayerData.getDeaths(uuid)) {
            pd.setDeaths(data.getDeaths());
            PlayerData.setDatabaseValue(uuid, "Deaths", data.getDeaths());
        }
    }

    public EmDataDTO serializePlayerData(Context context) {
        debug("Serializing player data " + context);
        UUID uuid = context.get("uuid");
        PlayerData pd = PlayerData.getPlayerData(uuid);
        if (pd == null) {
            warn("PlayerData is null for " + uuid);
            return null;
        }

        return EmDataDTO.builder()
                .timestamp(System.currentTimeMillis())
                .server(ARC.serverName)
                .uuid(uuid)
                .currency(pd.getCurrency())
                .level(pd.getActiveGuildLevel())
                .prestige(pd.getGuildPrestigeLevel())
                .highestLevelKilled(pd.getHighestLevelKilled())
                .useBookMenu(pd.isUseBookMenus())
                .dismissEmStatus(pd.isDismissEMStatusScreenMessage())
                .score(pd.getScore())
                .kills(pd.getKills())
                .deaths(pd.getDeaths())
                .build();
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class EmDataDTO implements SyncData {

        @SerializedName("t")
        long timestamp;
        @SerializedName("s")
        String server;
        @SerializedName("u")
        UUID uuid;
        @SerializedName("c")
        double currency;
        @SerializedName("l")
        int level;
        @SerializedName("p")
        int prestige;
        @SerializedName("h")
        int highestLevelKilled;
        @SerializedName("b")
        boolean useBookMenu;
        @SerializedName("d")
        boolean dismissEmStatus;
        @SerializedName("sc")
        int score;
        @SerializedName("k")
        int kills;
        @SerializedName("de")
        int deaths;


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
