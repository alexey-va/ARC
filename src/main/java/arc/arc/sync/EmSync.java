package arc.arc.sync;

import arc.arc.ARC;
import arc.arc.configs.MainConfig;
import arc.arc.sync.base.Context;
import arc.arc.sync.base.Sync;
import arc.arc.sync.base.SyncData;
import arc.arc.sync.base.SyncRepo;
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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
public class EmSync implements Sync {


    SyncRepo<EmDataDTO> repo;

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
                    log.trace("Player is offline, cancelling em sync task.");
                    cancel();
                    return;
                }
                PlayerData pd = PlayerData.getPlayerData(uuid);
                if (pd == null) {
                    log.trace("PlayerData is null for " + uuid + ". Skipping cycle " + counter.get());
                    if (counter.incrementAndGet() > 20) {
                        log.warn("PlayerData is null for " + uuid + " for 20 cycles. Cancelling task.");
                        cancel();
                    }
                    return;
                }
                log.trace("calling load data EM for " + uuid);
                repo.loadAndApplyData(uuid, false);
                cancel();
            }
        }.runTaskTimer(ARC.plugin, 5L, 5L);
    }

    @Override
    public void playerQuit(UUID uuid) {
        Context context = new Context();
        context.put("uuid", uuid);
        repo.saveAndPersistData(context, false);
    }

    @Override
    public void processEvent(Event event) {
        //repo.processEvent(event);
    }

    @Override
    public void forceSave(UUID uuid) {
        Context context = new Context();
        context.put("uuid", uuid);
        repo.saveAndPersistData(context, false);
    }

    public void deserializeAndSavePlayerData(EmDataDTO data) {
        UUID uuid = data.getUuid();
        PlayerData pd = PlayerData.getPlayerData(uuid);
        if (pd == null) {
            log.warn("PlayerData is not yet loaded for " + uuid);
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
        log.debug("Serializing player data " + context);
        UUID uuid = context.get("uuid");
        PlayerData pd = PlayerData.getPlayerData(uuid);
        if (pd == null) {
            log.warn("PlayerData is null for " + uuid);
            return null;
        }

        return EmDataDTO.builder()
                .timestamp(System.currentTimeMillis())
                .server(MainConfig.server)
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
