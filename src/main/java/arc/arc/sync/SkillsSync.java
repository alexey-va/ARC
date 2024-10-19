package arc.arc.sync;

import arc.arc.ARC;
import arc.arc.sync.base.Context;
import arc.arc.sync.base.Sync;
import arc.arc.sync.base.SyncData;
import arc.arc.sync.base.SyncRepo;
import com.google.gson.annotations.SerializedName;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.registry.NamespacedId;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.user.SkillsUser;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SkillsSync implements Sync {



    SyncRepo<UserSkillData> syncRepo;
    Map<UUID, Boolean> loaded = new ConcurrentHashMap<>();

    public SkillsSync() {
        this.syncRepo = SyncRepo.builder(UserSkillData.class)
                .key("arc.skills_data")
                .redisManager(ARC.redisManager)
                .dataApplier(this::applySkillData)
                .dataProducer(this::getSkillData)
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
                SkillsUser user = AuraSkillsApi.get().getUser(uuid);
                if (user == null) {
                    if (counter.incrementAndGet() > 20) {
                        ARC.warn("PlayerData is null for " + uuid + " for 20 cycles. Cancelling task.");
                        cancel();
                    }
                    return;
                }
                syncRepo.loadAndApplyData(uuid, false);
                loaded.put(uuid, true);
                cancel();
            }
        }.runTaskTimer(ARC.plugin, 5L, 5L);
    }

    @Override
    public void forceSave(UUID uuid) {
        playerQuit(uuid);
    }

    @Override
    public void playerQuit(UUID uuid) {
        if(!loaded.containsKey(uuid)) return;
        Context context = new Context();
        context.put("uuid", uuid);
        syncRepo.saveAndPersistData(context, false);
        loaded.remove(uuid);
    }


    @Data
    @AllArgsConstructor @NoArgsConstructor
    public static class SkillInfo {
        @SerializedName("i")
        String id;
        @SerializedName("l")
        int level;
        @SerializedName("x")
        double xp;
    }


    public UserSkillData getSkillData(Context context) {
        UUID uuid = context.get("uuid");
        Collection<Skill> skills = AuraSkillsApi.get().getGlobalRegistry().getSkills();
        SkillsUser user = AuraSkillsApi.get().getUser(uuid);
        if (user == null) {
            ARC.warn("User with uuid {} not found while getting skill data", uuid);
            return null;
        }

        List<SkillInfo> skillInfoList = new ArrayList<>();
        for (Skill skill : skills) {
            skillInfoList.add(new SkillInfo(skill.getId().toString(), user.getSkillLevel(skill), user.getSkillXp(skill)));
        }


        return UserSkillData.builder()
                .skills(skillInfoList)
                .mana(user.getMana())
                .uuid(uuid)
                .timestamp(System.currentTimeMillis())
                .server(ARC.serverName)
                .build();
    }

    public void applySkillData(UserSkillData data) {
        UUID uuid = data.uuid;
        SkillsUser user = AuraSkillsApi.get().getUser(uuid);
        if(user == null){
            ARC.warn("User with uuid {} not found while applying skill data", uuid);
            return;
        }
        for (SkillInfo skillInfo : data.skills) {
            NamespacedId id = NamespacedId.fromString(skillInfo.id);
            Skill skill = AuraSkillsApi.get().getGlobalRegistry().getSkill(id);
            if (skill == null) {
                ARC.warn("Skill with id {} not found", skillInfo.id);
                continue;
            }
            user.setSkillLevel(skill, skillInfo.level);
            user.setSkillXp(skill, skillInfo.xp);
        }
        user.setMana(data.mana);
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @Builder
    public static class UserSkillData implements SyncData {
        @SerializedName("u")
        UUID uuid;
        @SerializedName("sk")
        List<SkillInfo> skills;
        @SerializedName("m")
        double mana;
        @SerializedName("t")
        long timestamp;
        @SerializedName("s")
        String server;

        @Override
        public long timestamp() {return timestamp;}

        @Override
        public String server() {return server;}

        @Override
        public UUID uuid() {return uuid;}
    }


}
