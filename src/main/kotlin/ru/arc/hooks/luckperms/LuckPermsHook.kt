package ru.arc.hooks.luckperms

import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.group.Group
import net.luckperms.api.node.types.MetaNode
import net.luckperms.api.query.Flag
import net.luckperms.api.query.QueryMode
import net.luckperms.api.query.QueryOptions
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import ru.arc.util.Logging.error
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LuckPermsHook {

    fun hasPermission(offlinePlayer: OfflinePlayer, perm: String): Boolean {
        val userManager = LuckPermsProvider.get().userManager
        if (offlinePlayer is Player) return offlinePlayer.hasPermission(perm)
        return try {
            if (Thread.currentThread().name.contains("main")) {
                println("Loading permission data from main thread!!!")
            }
            userManager.loadUser(offlinePlayer.uniqueId).get()
                .cachedData.permissionData.checkPermission(perm).asBoolean()
        } catch (e: Exception) {
            error("Error while checking permission", e)
            false
        }
    }

    fun getGroups(offlinePlayer: OfflinePlayer): List<String> {
        val userManager = LuckPermsProvider.get().userManager
        return try {
            if (Thread.currentThread().name.contains("main")) {
                error("Loading groups data from main thread!!!")
            }
            userManager.loadUser(offlinePlayer.uniqueId).get()
                .getInheritedGroups(
                    QueryOptions.builder(QueryMode.NON_CONTEXTUAL)
                        .flag(Flag.RESOLVE_INHERITANCE, true)
                        .build()
                ).map(Group::getName)
        } catch (e: Exception) {
            error("Error while getting groups", e)
            emptyList()
        }
    }

    fun setMeta(uuid: UUID, key: String, value: String?): CompletableFuture<Void> {
        val userManager = LuckPermsProvider.get().userManager
        return userManager.modifyUser(uuid) { user ->
            user.nodes.filterIsInstance<MetaNode>()
                .filter { it.metaKey == key }
                .forEach { user.data().remove(it) }
            if (value == null) return@modifyUser
            val node = MetaNode.builder().key(key).value(value).build()
            user.data().add(node)
        }
    }

    fun getMeta(uuid: UUID, key: String): CompletableFuture<String?> {
        val userManager = LuckPermsProvider.get().userManager
        return userManager.loadUser(uuid)
            .thenApply { it.cachedData.metaData.getMetaValue(key) }
    }
}
