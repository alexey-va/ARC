package ru.arc.hooks.luckperms

import net.luckperms.api.model.group.Group
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import net.luckperms.api.query.QueryOptions
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LuckPermsHookTest {
    @Test
    fun `main thread reads an online user's cached groups without loading`() {
        val userManager = mock<UserManager>()
        val player = mock<Player>()
        val user = mock<User>()
        val group = mock<Group>()
        val queryOptions = mock<QueryOptions>()
        val uuid = UUID.randomUUID()
        whenever(player.uniqueId).thenReturn(uuid)
        whenever(userManager.getUser(uuid)).thenReturn(user)
        whenever(group.name).thenReturn("vip")
        whenever(user.getInheritedGroups(any<QueryOptions>())).thenReturn(listOf(group))

        val groups =
            LuckPermsHook(
                userManager = userManager,
                isPrimaryThread = { true },
                queryOptions = { queryOptions },
            ).getGroups(player)

        assertEquals(listOf("vip"), groups)
        verify(userManager).getUser(uuid)
        verify(userManager, never()).loadUser(any<UUID>())
    }

    @Test
    fun `async lookup loads an offline user when the cache is empty`() {
        val userManager = mock<UserManager>()
        val player = mock<OfflinePlayer>()
        val user = mock<User>()
        val group = mock<Group>()
        val queryOptions = mock<QueryOptions>()
        val uuid = UUID.randomUUID()
        whenever(player.uniqueId).thenReturn(uuid)
        whenever(userManager.getUser(uuid)).thenReturn(null)
        whenever(userManager.loadUser(uuid)).thenReturn(CompletableFuture.completedFuture(user))
        whenever(group.name).thenReturn("default")
        whenever(user.getInheritedGroups(any<QueryOptions>())).thenReturn(listOf(group))

        val groups =
            LuckPermsHook(
                userManager = userManager,
                isPrimaryThread = { false },
                queryOptions = { queryOptions },
            ).getGroups(player)

        assertEquals(listOf("default"), groups)
        verify(userManager).loadUser(uuid)
    }

    @Test
    fun `main thread never loads an uncached offline user`() {
        val userManager = mock<UserManager>()
        val player = mock<OfflinePlayer>()
        val uuid = UUID.randomUUID()
        whenever(player.uniqueId).thenReturn(uuid)
        whenever(userManager.getUser(uuid)).thenReturn(null)

        val groups =
            LuckPermsHook(
                userManager = userManager,
                isPrimaryThread = { true },
                queryOptions = { mock() },
            ).getGroups(player)

        assertEquals(emptyList<String>(), groups)
        verify(userManager, never()).loadUser(any<UUID>())
    }
}
