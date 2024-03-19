package arc.arc.hooks.jobs;

import arc.arc.network.repos.RepoData;
import com.gamingmesh.jobs.container.Job;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.util.*;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@Log4j2
public class BoostData extends RepoData<BoostData> {
    UUID player;
    Set<JobsBoost> boosts = new HashSet<>();
    record Context(String jobName, JobsBoost.Type type) { }
    transient Map<Context, Double> cachedBoosts = new HashMap<>();

    public BoostData(UUID player) {
        this.player = player;
    }

    @Override
    public String id() {
        return player.toString();
    }

    @Override
    public boolean isRemove() {
        return boosts.isEmpty();
    }

    @Override
    public void merge(BoostData other) {
        if (other == null) return;
        if (other.boosts.isEmpty()) return;
        synchronized (this) {
            boosts.clear();
            boosts.addAll(other.boosts);
            cachedBoosts.clear();
        }
    }

    private synchronized void removeExpired() {
        if (boosts.removeIf(jobsBoost -> jobsBoost.getExpires() < System.currentTimeMillis())){
            setDirty(true);
            cachedBoosts.clear();
        }

    }

    public synchronized double getBoost(Job job, JobsBoost.Type type) {
        removeExpired();

        Context context = new Context(job.getName(), type);
        Double cached = cachedBoosts.get(context);
        if (cached != null) return cached;

        double boost = 1.0 + boosts.stream()
                .filter(jobsBoost -> jobsBoost.getType() == type)
                .filter(jobsBoost -> jobsBoost.getJobName() == null || job.getName().equalsIgnoreCase(jobsBoost.getJobName()))
                .mapToDouble(JobsBoost::getBoost)
                .sum();
        cachedBoosts.put(context, boost);
        return boost;
    }

    public JobsBoost findById(String id){
        return boosts.stream().filter(jobsBoost -> jobsBoost.getId().equals(id)).findFirst().orElse(null);
    }

    public Collection<JobsBoost> boosts(Job job){
        return boosts.stream()
                .filter(jobsBoost -> jobsBoost.getJobName() == null || job.getName().equalsIgnoreCase(jobsBoost.getJobName()))
                .toList();
    }

    public synchronized void addBoost(JobsBoost jobsBoost) {
        removeExpired();
        if(findById(jobsBoost.getId()) != null){
            log.error("Boost with id " + jobsBoost.getId() + " already exists for "+player);
            return;
        }
        boosts.add(jobsBoost);
        setDirty(true);
        cachedBoosts.clear();
    }
}
