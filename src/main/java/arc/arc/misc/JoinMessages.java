package arc.arc.misc;


import arc.arc.ARC;
import arc.arc.network.repos.RedisRepo;
import arc.arc.network.repos.RepoData;

import java.util.HashSet;
import java.util.Set;

public class JoinMessages extends RepoData<JoinMessages> {

    String player;
    Set<String> joinMessages = new HashSet<>();
    Set<String> leaveMessages = new HashSet<>();
    long timestamp = System.currentTimeMillis();

    public static RedisRepo<JoinMessages> repo;

    public static void init() {
        if (repo != null) return;
        repo = RedisRepo.builder(JoinMessages.class)
                .id("join_messages")
                .loadAll(true)
                .updateChannel("arc.join_messages_update")
                .redisManager(ARC.redisManager)
                .storageKey("arc.join_messages")
                .saveInterval(20L)
                .build();
    }

    public JoinMessages(String player) {
        this.player = player;
        this.timestamp = System.currentTimeMillis();
    }

    public void addJoinMessage(String message) {
        joinMessages.add(message);
        setDirty(true);
    }

    public void removeJoinMessage(String message) {
        joinMessages.remove(message);
        setDirty(true);
    }

    public void addLeaveMessage(String message) {
        leaveMessages.add(message);
        setDirty(true);
    }

    public void removeLeaveMessage(String message) {
        leaveMessages.remove(message);
        setDirty(true);
    }

    @Override
    public String id() {
        return player;
    }

    @Override
    public boolean isRemove() {
        return System.currentTimeMillis() - timestamp > 1000 * 60 * 60 * 24 * 7 && joinMessages.isEmpty() && leaveMessages.isEmpty();
    }

    @Override
    public void merge(JoinMessages other) {
        joinMessages.clear();
        joinMessages.addAll(other.joinMessages);
        leaveMessages.clear();
        leaveMessages.addAll(other.leaveMessages);
    }
}
