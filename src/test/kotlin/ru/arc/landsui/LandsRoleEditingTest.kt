package ru.arc.landsui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.Enabled
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.angeschossen.lands.api.events.player.role.PlayerToggleRoleFlagEvent
import me.angeschossen.lands.api.flags.enums.RoleFlagCategory
import me.angeschossen.lands.api.flags.type.RoleFlag
import me.angeschossen.lands.api.framework.exceptions.NoAccessException
import me.angeschossen.lands.api.land.Area
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.player.LandPlayer
import me.angeschossen.lands.api.role.Role
import me.angeschossen.lands.api.role.enums.RoleType
import org.bukkit.entity.Player
import java.lang.reflect.InvocationTargetException

class LandsRoleEditingTest : StringSpec({
    "unverified versions cannot bind the role editor" {
        shouldThrow<IllegalArgumentException> { nativeLandsRoleEditing(Role::class.java.classLoader, "8.9.7") }
    }

    "binds the inspected Lands 8.9.6 role guard and event signatures".config(enabledOrReasonIf = {
        if (runCatching {
            Class.forName("me.angeschossen.lands.Dgduf.RLHXh.BdHcv.pdjAO", false, Role::class.java.classLoader)
        }.isSuccess) Enabled.enabled
        else Enabled.disabled("Exact Lands compatibility check requires -PlandsJar=/path/to/Lands-8.9.6.jar")
    }) {
        nativeLandsRoleEditing(Role::class.java.classLoader, "8.9.6").shouldNotBeNull()
    }

    "rendering checks the current native policy without firing events" {
        val f = RoleEditingFixture()
        repeat(2) { f.canEdit() shouldBe true }
        f.guards shouldBe 2
        f.events shouldBe 0
        every { f.player.hasPermission("lands.role.setting.block_break") } returns false
        f.canEdit() shouldBe false
        f.guards shouldBe 2
    }

    "management flags, another holder and nation-managed permissions stay read-only" {
        val f = RoleEditingFixture()
        every { f.flag.category } returns RoleFlagCategory.MANAGEMENT
        f.canEdit() shouldBe false
        every { f.flag.category } returns RoleFlagCategory.ACTION
        every { f.role.holder } returns mockk<Area>()
        f.canEdit() shouldBe false
        every { f.role.holder } returns f.area
        every { f.role.type } returns RoleType.NATION
        every { f.flag.isToggleableByNation } returns true
        f.canEdit() shouldBe false
        f.guards shouldBe 0
        every { f.flag.isToggleableByNation } returns false
        f.canEdit() shouldBe true
    }

    "native permission denial is expected and does not disable later checks" {
        val f = RoleEditingFixture()
        f.guardFailure = InvocationTargetException(NoAccessException("revoked"))
        f.canEdit() shouldBe false
        f.failures shouldBe emptyList()
        f.guardFailure = null
        f.canEdit() shouldBe true
    }

    "unexpected native failure disables role editing once without a fallback menu" {
        val f = RoleEditingFixture()
        val failure = NoSuchMethodError("changed native policy")
        f.guardFailure = failure
        f.canEdit() shouldBe false
        f.canEdit() shouldBe false
        f.policy.isCancelled(mockk(relaxed = true)) shouldBe true
        f.guards shouldBe 1
        f.events shouldBe 0
        f.failures shouldBe listOf(failure)
    }

    "event gate preserves both native and Bukkit cancellation" {
        val f = RoleEditingFixture()
        val event = mockk<PlayerToggleRoleFlagEvent>()
        every { event.isCancelled } returns false
        f.policy.isCancelled(event) shouldBe false
        f.cancelled = true
        f.policy.isCancelled(event) shouldBe true
        f.cancelled = false
        every { event.isCancelled } returns true
        f.policy.isCancelled(event) shouldBe true
        f.events shouldBe 3
        f.guards shouldBe 0
    }
})

private class RoleEditingFixture {
    val player = mockk<Player>()
    val landPlayer = mockk<LandPlayer>()
    val land = mockk<Land>()
    val area = mockk<Area>()
    val role = mockk<Role>()
    val flag = mockk<RoleFlag>()
    var guards = 0
    var events = 0
    var cancelled = false
    var guardFailure: Throwable? = null
    val failures = mutableListOf<Throwable>()
    val policy = LandsRoleEditing(NativeLandsRoleEditing(
        canEdit = { actualPlayer, actualLand, actualArea, actualRole, actualFlag ->
            actualPlayer shouldBe landPlayer
            actualLand shouldBe land
            actualArea shouldBe area
            actualRole shouldBe role
            actualFlag shouldBe flag
            guards++
            guardFailure?.let { throw it }
            true
        },
        isCancelled = { events++; cancelled },
    ), failures::add)

    init {
        every { flag.category } returns RoleFlagCategory.ACTION
        every { flag.togglePermission } returns "lands.role.setting.block_break"
        every { player.hasPermission("lands.role.setting.block_break") } returns true
        every { role.holder } returns area
        every { role.type } returns RoleType.VISITOR
    }

    fun canEdit(): Boolean = policy.canEdit(player, landPlayer, land, area, role, flag)
}
