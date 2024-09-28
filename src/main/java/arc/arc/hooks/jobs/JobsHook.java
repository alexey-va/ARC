package arc.arc.hooks.jobs;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.hooks.jobs.guis.JobsListGui;
import arc.arc.network.repos.RedisRepo;
import arc.arc.util.GuiUtils;
import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.container.CurrencyType;
import com.gamingmesh.jobs.container.Job;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Log4j2
public class JobsHook {

    private static JobsListener jobsListener;
    @Getter(AccessLevel.PUBLIC)
    private static RedisRepo<BoostData> repo;
    Config config;

    public JobsHook() {
        log.info("Jobs hook enabled");
        if (jobsListener == null) {
            jobsListener = new JobsListener();
            Bukkit.getPluginManager().registerEvents(jobsListener, ARC.plugin);
        }
        config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "jobs.yml");
        createRepo();
    }

    public void createRepo() {
        if (repo == null) {
            repo = RedisRepo.builder(BoostData.class)
                    .loadAll(true)
                    .redisManager(ARC.redisManager)
                    .storageKey("arc.jobs_boosts")
                    .updateChannel("arc.jobs_boosts_update")
                    .clazz(BoostData.class)
                    .id("jobs")
                    .saveBackups(false)
                    .backupFolder(ARC.plugin.getDataFolder().toPath().resolve("backups/jobs"))
                    .saveInterval(10L)
                    .build();
        }
    }

    public String jobDisplayMinimessage(String jobName) {
        Component name = LegacyComponentSerializer.legacyAmpersand().deserialize(
                Jobs.getJob(jobName).getDisplayName().replace("§", "&")
        ).decoration(TextDecoration.ITALIC, false);
        return MiniMessage.miniMessage().serialize(name);
    }

    public void addBoost(UUID player, List<String> jobs, double boost, long expires, String boostId, List<JobsBoost.Type> type) {
        boolean allJobs = jobs.stream().anyMatch(j -> j.equalsIgnoreCase("all")) || jobs.isEmpty();
        boolean allTypes = type.contains(JobsBoost.Type.ALL) || type.isEmpty();


        repo.getOrCreate(player.toString(), () -> new BoostData(player))
                .thenAccept(data -> {
                    log.info("Adding boost for player " + player + " jobs " + jobs + " boost " + boost + " expires " + expires + " boostId " + boostId + " type " + type);
                    List<String> jobsUpdated = new ArrayList<>();
                    if (allJobs) jobsUpdated.add(null);
                    else jobsUpdated.addAll(jobs);

                    List<JobsBoost.Type> typeUpdated = new ArrayList<>();
                    if (allTypes) typeUpdated.add(JobsBoost.Type.ALL);
                    else typeUpdated.addAll(type);

                    for (String jobName : jobsUpdated) {
                        for (JobsBoost.Type t : typeUpdated) {
                            JobsBoost jobsBoost = new JobsBoost();
                            jobsBoost.setBoost(boost);
                            jobsBoost.setExpires(expires);
                            jobsBoost.setBoostUuid(player);
                            jobsBoost.setId(boostId);
                            jobsBoost.setType(t);
                            jobsBoost.setJobName(jobName);
                            data.addBoost(jobsBoost);
                        }
                    }
                    log.info("Boost added for player " + player + " jobs " + jobs + " boost " + boost + " expires " + expires + " boostId " + boostId + " type " + type);
                });
    }

    public List<String> getJobNames() {
        return Jobs.getJobs().stream().map(Job::getName).toList();
    }

    public void openBoostGui(Player player) {
        GuiUtils.constructAndShowAsync(() -> new JobsListGui(config, player), player);
    }

    public boolean hasBoost(OfflinePlayer player, String par) {
        return !repo.getOrCreate(player.getUniqueId().toString(), () -> new BoostData(player.getUniqueId()))
                .thenApply(data -> data == null || data.findById(par) == null)
                .join();
    }

    public double getBoost(Player player, String jobName, JobsBoost.Type type) {
        CurrencyType currencyType = switch (type) {
            case EXP -> CurrencyType.EXP;
            case MONEY -> CurrencyType.MONEY;
            case POINTS -> CurrencyType.POINTS;
            case ALL -> null;
        };
        if (currencyType == null) {
            log.error("Jobs does not have ALL currency type");
            return 0;
        }
        return Jobs.getPlayerManager().getJobsPlayer(player).getBoost(jobName, currencyType);
    }

    public void resetBoosts(Player player) {
        repo.getOrCreate(player.getUniqueId().toString(), () -> new BoostData(player.getUniqueId()))
                .thenAccept(bd -> {
                    bd.setBoosts(Set.of());
                    bd.setDirty(true);
                });
    }
}

