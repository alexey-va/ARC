package ru.arc.rtp

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.luckperms.api.LuckPerms
import net.luckperms.api.cacheddata.CachedDataManager
import net.luckperms.api.cacheddata.CachedPermissionData
import net.luckperms.api.context.ContextManager
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.context.MutableContextSet
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import net.luckperms.api.query.QueryOptions
import net.luckperms.api.util.Tristate
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID

class DestinationRtpPermissionTest :
    FreeSpec({
        "replaces the restored Nether world while preserving the player's other contexts" {
            val lp = mockk<LuckPerms>()
            val users = mockk<UserManager>()
            val contexts = mockk<ContextManager>()
            val user = mockk<User>()
            val cached = mockk<CachedDataManager>()
            val permissions = mockk<CachedPermissionData>()
            val player = mockk<Player>()
            val world = mockk<World>()
            val options = mockk<QueryOptions>()
            val modifiedOptions = mockk<QueryOptions>()
            val builder = mockk<QueryOptions.Builder>()
            val original = mockk<ImmutableContextSet>()
            val modified = mockk<MutableContextSet>()
            val playerId = UUID.randomUUID()
            val values = mutableMapOf("server" to "survival", "world" to "vanilla_nether")
            every { player.uniqueId } returns playerId
            every { world.name } returns "vanilla"
            every { lp.userManager } returns users
            every { users.getUser(playerId) } returns user
            every { lp.contextManager } returns contexts
            every { contexts.getQueryOptions(player) } returns options
            every { options.context() } returns original
            every { original.mutableCopy() } returns modified
            every { modified.removeAll("world") } answers { values.remove("world"); Unit }
            every { modified.add("world", "vanilla") } answers { values["world"] = "vanilla"; Unit }
            every { options.toBuilder() } returns builder
            every { builder.context(modified) } returns builder
            every { builder.build() } returns modifiedOptions
            every { user.cachedData } returns cached
            every { cached.getPermissionData(modifiedOptions) } returns permissions
            every { permissions.checkPermission("rtp.use") } answers {
                if (values == mapOf("server" to "survival", "world" to "vanilla")) Tristate.TRUE else Tristate.FALSE
            }

            DestinationRtpPermission.canUse(lp, player, world) shouldBe true
            verify(exactly = 1) { modified.removeAll("world") }
            verify(exactly = 1) { modified.add("world", "vanilla") }
        }
    })
