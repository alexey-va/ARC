package ru.arc.hooks.luckperms;

import lombok.extern.slf4j.Slf4j;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.query.Flag;
import net.luckperms.api.query.QueryMode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static ru.arc.util.Logging.error;

@Slf4j
public class LuckPermsHook {

    public boolean hasPermission(OfflinePlayer offlinePlayer, String perm) {
        UserManager userManager = LuckPermsProvider.get().getUserManager();
        if (offlinePlayer instanceof Player player) {
            return player.hasPermission(perm);
        }
        try {
            if (Thread.currentThread().getName().contains("main")) {
                System.out.println("Loading permission data from main thread!!!");
            }
            return userManager.loadUser(offlinePlayer.getUniqueId()).get()
                    .getCachedData()
                    .getPermissionData()
                    .checkPermission(perm)
                    .asBoolean();
        } catch (InterruptedException | ExecutionException e) {
            error("Error while checking permission", e);
            return false;
        }
    }

    public List<String> getGroups(OfflinePlayer offlinePlayer) {
        UserManager userManager = LuckPermsProvider.get().getUserManager();
        try {
            if (Thread.currentThread().getName().contains("main")) {
                error("Loading groups data from main thread!!!");
            }
            return userManager.loadUser(offlinePlayer.getUniqueId()).get()
                    .getInheritedGroups(QueryOptions.builder(QueryMode.NON_CONTEXTUAL)
                            .flag(Flag.RESOLVE_INHERITANCE, true)
                            .build()).stream()
                    .map(Group::getName)
                    .toList();
        } catch (InterruptedException | ExecutionException e) {
            error("Error while getting groups", e);
            return List.of();
        }
    }

    public CompletableFuture<Void> setMeta(UUID uuid, String key, String value) {
        UserManager userManager = LuckPermsProvider.get().getUserManager();
        return userManager.modifyUser(uuid, user -> {
            user.getNodes().stream()
                    .filter(node -> node instanceof MetaNode)
                    .map(node -> (MetaNode) node)
                    .filter(node -> node.getMetaKey().equals(key))
                    .forEach(node -> user.data().remove(node));
            if (value == null) return;
            MetaNode node = MetaNode.builder()
                    .key(key)
                    .value(value)
                    .build();
            user.data().add(node);
        });
    }

    public CompletableFuture<String> getMeta(UUID uuid, String key) {
        UserManager userManager = LuckPermsProvider.get().getUserManager();
        return userManager.loadUser(uuid)
                .thenApply(user -> user.getCachedData().getMetaData().getMetaValue(key));
    }

}
