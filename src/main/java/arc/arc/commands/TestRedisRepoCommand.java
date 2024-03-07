package arc.arc.commands;

import arc.arc.ARC;
import arc.arc.network.repos.RedisRepo;
import arc.arc.network.repos.RepoData;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class TestRedisRepoCommand implements CommandExecutor {

    @Data
    @AllArgsConstructor @NoArgsConstructor
    static class TestData extends RepoData {
        String id;
        boolean isRemove;

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean isRemove() {
            return false;
        }
    }

    RedisRepo<TestData> repo;

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(strings[0].equals("start")){
            repo = new RedisRepo<>(true, ARC.redisManager, "test_data", "test_data_channel", TestData.class);
        }
        else if(strings[0].equals("add")){
            TestData data = new TestData(strings[1], false);
            repo.createNewEntry(data);
        } else if(strings[0].equals("remove")){
            TestData data = new TestData(strings[1], true);
            repo.deleteEntry(data);
        }
        else if(strings[0].equals("stop")){
            repo.cancelTasks();
        }

        return true;
    }
}
