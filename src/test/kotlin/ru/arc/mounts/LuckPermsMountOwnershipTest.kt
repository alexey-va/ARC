package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.luckperms.api.LuckPerms
import net.luckperms.api.cacheddata.CachedPermissionData
import net.luckperms.api.model.user.User
import net.luckperms.api.util.Tristate
import java.util.UUID

class LuckPermsMountOwnershipTest : StringSpec({
    "Bukkit OP does not manufacture mount ownership and effective LP grants still work" {
        val id = UUID.randomUUID()
        val lp = mockk<LuckPerms>()
        val user = mockk<User>()
        val permissions = mockk<CachedPermissionData>()
        every { lp.userManager.getUser(id) } returns user
        every { user.cachedData.permissionData } returns permissions
        every { user.nodes } returns emptySet()
        every { permissions.checkPermission(any()) } returns Tristate.UNDEFINED
        val mount = testMount()
        val ownership = LuckPermsMountOwnership(lp)
        val operator = MountPermissionSubject(id, "Operator") { true }

        ownership.profile(operator, mount).level shouldBe 0

        // LP resolves active-context group and direct grants through this cache.
        every { permissions.checkPermission(mount.levelPermission(2)) } returns Tristate.TRUE
        ownership.profile(operator, mount).level shouldBe 2
        every { permissions.checkPermission(mount.levelPermission(2)) } returns Tristate.FALSE
        ownership.profile(operator, mount).level shouldBe 0
    }
})
