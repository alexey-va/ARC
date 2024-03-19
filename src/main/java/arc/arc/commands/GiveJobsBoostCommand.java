package arc.arc.commands;

import arc.arc.hooks.HookRegistry;
import arc.arc.hooks.jobs.JobsBoost;
import arc.arc.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

public class GiveJobsBoostCommand implements CommandExecutor, TabCompleter {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if(!commandSender.hasPermission("arc.admin.givejobsboost")){
            commandSender.sendMessage(TextUtil.noPermissions());
            return true;
        }

        if (strings.length < 6) {
            commandSender.sendMessage("Usage: /givejobsboost <player> <job> <boost> <type> <duration> <id>");
            return true;
        }

        String playerName = strings[0];
        Player player = Bukkit.getPlayer(playerName);
        if(player == null || !player.getName().equalsIgnoreCase(playerName)){
            commandSender.sendMessage("Player not found");
            return true;
        }


        String jobName = strings[1];
        if(jobName.equalsIgnoreCase("all")) jobName = null;

        double boost = Double.parseDouble(strings[2]);
        String type = strings[3].toUpperCase();
        JobsBoost.Type boostType = JobsBoost.Type.valueOf(type);

        String duration = strings[4];
        long durationLong;

        long l = Long.parseLong(duration.substring(0, duration.length() - 1));
        if(duration.endsWith("s")){
            durationLong = l * 1000;
        } else if(duration.endsWith("m")){
            durationLong = l * 1000 * 60;
        } else if(duration.endsWith("h")){
            durationLong = l * 1000 * 60 * 60;
        } else if(duration.endsWith("d")){
            durationLong = l * 1000 * 60 * 60 * 24;
        } else {
            commandSender.sendMessage("Invalid duration format. Use s, m, h, or d");
            return true;
        }

        String id = strings[5];
        if(id.equals("null")) id = null;

        if(HookRegistry.jobsHook == null){
            commandSender.sendMessage("Jobs plugin not found");
            return true;
        }

        HookRegistry.jobsHook.addBoost(player.getUniqueId(), jobName, boost, System.currentTimeMillis() + durationLong, id, boostType);
        commandSender.sendMessage("Boost added!");

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (strings.length == 1) {
            return null;
        }

        if (strings.length == 2) {
            if(HookRegistry.jobsHook != null){
                return HookRegistry.jobsHook.getJobNames();
            }
            return null;
        }

        if(strings.length == 3){
            return List.of("[double]");
        }

        if(strings.length == 4){
            return List.of(Arrays.stream(JobsBoost.Type.values()).map(Enum::name).toArray(String[]::new));
        }

        if(strings.length == 5){
            return List.of("1d", "1h", "1m", "1s");
        }
        return null;
    }
}
