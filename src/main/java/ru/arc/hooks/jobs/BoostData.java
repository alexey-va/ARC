package ru.arc.hooks.jobs;

import ru.arc.network.repos.RepoData;
import com.gamingmesh.jobs.container.Job;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.*;
import lombok.extern.log4j.Log4j2;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@Log4j2
public class BoostData extends RepoData<BoostData> {
    UUID player;
    Set<JobsBoost> boosts = new HashSet<>();

    record Context(String jobName, JobsBoost.Type type) {
    }

    transient Cache<Context, Double> cachedBoosts = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();

    public BoostData(UUID player) {
        this.player = player;
    }

    @Override
    public String id() {
        return player.toString();
    }

    @Override
    public boolean isRemove() {
        return false;
    }

    @Override
    public void merge(BoostData other) {
        if (other == null) return;
        if (other.boosts.isEmpty()) return;
        synchronized (this) {
            boosts.clear();
            boosts.addAll(other.boosts);
            cachedBoosts.invalidateAll();
        }
    }

    private synchronized void removeExpired() {
        if (boosts.removeIf(jobsBoost -> jobsBoost.getExpires() < System.currentTimeMillis())) {
            setDirty(true);
            cachedBoosts.invalidateAll();
        }

    }

    @SneakyThrows
    public synchronized double getBoost(Job job, JobsBoost.Type type) {
        removeExpired();

        Context context = new Context(job.getName(), type);
        Double cached = cachedBoosts.getIfPresent(context);
        if (cached != null) return cached;

        double boost = 1.0 + boosts.stream()
                .filter(jobsBoost -> jobsBoost.getType() == type || jobsBoost.getType() == JobsBoost.Type.ALL)
                .filter(jobsBoost -> jobsBoost.getJobName() == null || job.getName().equalsIgnoreCase(jobsBoost.getJobName()))
                .mapToDouble(JobsBoost::getBoost)
                .sum();
        cachedBoosts.put(context, boost);
        return boost;
    }

    public JobsBoost findById(String id) {
        return boosts.stream().filter(jobsBoost -> jobsBoost.getId().equals(id)).findFirst().orElse(null);
    }

    public Collection<JobsBoost> boosts(Job job) {
        return boosts.stream()
                .filter(jobsBoost -> jobsBoost.getJobName() == null || job.getName().equalsIgnoreCase(jobsBoost.getJobName()))
                .toList();
    }

    public synchronized void addBoost(JobsBoost jobsBoost) {
        removeExpired();
        if (findById(jobsBoost.getId()) != null) {
            log.error("Boost with id {} already exists for {}", jobsBoost.getId(), player);
            return;
        }
        boosts.add(jobsBoost);
        setDirty(true);
        cachedBoosts.invalidateAll();
    }
}
