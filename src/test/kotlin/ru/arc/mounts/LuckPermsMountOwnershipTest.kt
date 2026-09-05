package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.luckperms.api.LuckPerms
import net.luckperms.api.cacheddata.CachedPermissionData
import net.luckperms.api.cacheddata.CachedDataManager
import net.luckperms.api.context.ContextManager
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.model.user.User
import net.luckperms.api.query.QueryMode
import net.luckperms.api.query.QueryOptions
import net.luckperms.api.util.Tristate
import java.util.UUID

class LuckPermsMountOwnershipTest : StringSpec({
    "Bukkit OP does not manufacture mount ownership and effective LP grants still work" {
        val id = UUID.randomUUID()
        val lp = mockk<LuckPerms>()
        val user = mockk<User>()
        val contextManager = mockk<ContextManager>()
        val queryBuilder = mockk<QueryOptions.Builder>()
        val queryOptions = mockk<QueryOptions>()
        val context = mockk<ImmutableContextSet>()
        val cachedData = mockk<CachedDataManager>()
        val defaultPermissions = mockk<CachedPermissionData>()
        val permissions = mockk<CachedPermissionData>()
        every { lp.userManager.getUser(id) } returns user
        every { lp.contextManager } returns contextManager
        every { contextManager.queryOptionsBuilder(QueryMode.CONTEXTUAL) } returns queryBuilder
        every { contextManager.getContext(user) } returns java.util.Optional.of(context)
        every { contextManager.staticContext } returns context
        every { queryBuilder.context(context) } returns queryBuilder
        every { queryBuilder.build() } returns queryOptions
        every { user.cachedData } returns cachedData
        // This is the Bukkit/OP-shaped default calculator that caused the
        // production regression: it reports every permission to an operator.
        every { cachedData.permissionData } returns defaultPermissions
        every { defaultPermissions.checkPermission(any()) } returns Tristate.TRUE
        every { cachedData.getPermissionData(queryOptions) } returns permissions
        every { user.nodes } returns emptySet()
        every { permissions.checkPermission(any()) } returns Tristate.UNDEFINED
        val mount = testMount()
        val ownership = LuckPermsMountOwnership(lp)
        val operator = MountPermissionSubject(id, "Operator") { true }

        ownership.profile(operator, mount).level shouldBe 0
        verify(exactly = 0) { defaultPermissions.checkPermission(any()) }

        // LP resolves active-context group and direct grants through this cache.
        every { permissions.checkPermission(mount.levelPermission(2)) } returns Tristate.TRUE
        ownership.profile(operator, mount).level shouldBe 2
        every { permissions.checkPermission(mount.levelPermission(2)) } returns Tristate.FALSE
        ownership.profile(operator, mount).level shouldBe 0
    }
})
