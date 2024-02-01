package arc.arc.commands;

import arc.arc.treasurechests.locationpools.LocationPool;
import arc.arc.treasurechests.locationpools.LocationPoolManager;
import arc.arc.treasurechests.TreasureHuntManager;
import arc.arc.configs.TreasureHuntConfig;
import arc.arc.util.TextUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class TreasureHuntCommand implements CommandExecutor {
    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {

        if (!commandSender.hasPermission("arc.treasure-hunt")) {
            commandSender.sendMessage(TextUtil.noPermissions());
            return true;
        }

        boolean start = strings[0].equals("start");
        if (start && strings.length < 5) {
            commandSender.sendMessage("Not enough args! need 5");
            return true;
        }

        String locationPoolId = strings[1];
        int chests = strings.length >= 3 ? Integer.parseInt(strings[2]) : 0;
        String namespaceId = strings.length >= 4 ? strings[3] : null;
        String treasureHuntId = strings.length >= 5 ? strings[4] : null;

        LocationPool locationPool = LocationPoolManager.getPool(locationPoolId);
        if (locationPool == null) {
            commandSender.sendMessage("No such location pool! " + locationPoolId);
            return true;
        }

        if(!TreasureHuntConfig.treasureHuntCommands.containsKey(treasureHuntId)){
            commandSender.sendMessage("No command specified for treasure hunt: "+treasureHuntId);
            return true;
        }

        if(TreasureHuntConfig.treasureHuntAliases.containsKey(namespaceId)){
            namespaceId = TreasureHuntConfig.treasureHuntAliases.get(namespaceId);
        }

        if (start) {
            TreasureHuntManager.startHunt(locationPool, chests, namespaceId, treasureHuntId);
            commandSender.sendMessage("Hunt started!");
        } else {
            TreasureHuntManager.getByLocationPool(locationPool)
                    .ifPresentOrElse(th -> {
                        TreasureHuntManager.stopHunt(th);
                        commandSender.sendMessage("Hunt stopped!");
                    }, () -> commandSender.sendMessage("No hunt for " + locationPoolId));

        }


        return true;
    }
}
