package arc.arc.hooks.jobs;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.hooks.jobs.guis.BoostGui;
import arc.arc.network.repos.RedisRepo;
import arc.arc.network.repos.RepoData;
import arc.arc.util.GuiUtils;
import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.container.Boost;
import com.gamingmesh.jobs.container.Job;
import lombok.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;

public class JobsHook {

    private static JobsListener jobsListener;
    @Getter(AccessLevel.PUBLIC)
    private static RedisRepo<BoostData> repo;
    Config config;

    public JobsHook() {
        if (jobsListener == null) {
            jobsListener = new JobsListener();
            Bukkit.getPluginManager().registerEvents(jobsListener, ARC.plugin);
        }
        config = ConfigManager.getOrCreate(ARC.plugin.getDataFolder().toPath(), "jobs.yml", "jobs");
    }

    public void createRepo(){
        if (repo == null) {
            repo = RedisRepo.builder(BoostData.class)
                    .loadAll(true)
                    .redisManager(ARC.redisManager)
                    .storageKey("arc.jobs_boosts")
                    .updateChannel("arc.jobs_boosts_update")
                    .clazz(BoostData.class)
                    .id("jobs")
                    .backupFolder(ARC.plugin.getDataFolder().toPath().resolve("backups/jobs"))
                    .saveInterval(10L)
                    .build();
        }
    }

    public void addBoost(UUID player, String jobName, double boost, long expires, String boostId, JobsBoost.Type type) {
        repo.getOrCreate(player.toString(), () -> new BoostData(player))
                .thenAccept(data -> data.addBoost(
                        new JobsBoost(boost, type, jobName, expires, UUID.randomUUID(), boostId))
                );
    }

    public List<String> getJobNames() {
        return Jobs.getJobs().stream().map(Job::getName).toList();
    }

    public void openBoostGui(Player player) {
        GuiUtils.constructAndShowAsync(() -> new BoostGui(config, player), player);
    }

    public boolean hasBoost(OfflinePlayer player, String par) {
        return repo.getOrNull(player.getUniqueId().toString())
                .thenApply(data -> data.findById(par) == null)
                .join();
    }
}

