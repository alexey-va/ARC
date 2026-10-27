package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.luckperms.api.LuckPerms
import net.luckperms.api.cacheddata.CachedDataManager
import net.luckperms.api.cacheddata.CachedPermissionData
import net.luckperms.api.cacheddata.Result
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.data.NodeMap
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import net.luckperms.api.node.Node
import net.luckperms.api.node.NodeType
import net.luckperms.api.node.types.PermissionNode
import net.luckperms.api.query.QueryOptions
import net.luckperms.api.util.Tristate
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.PluginManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAccessor
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import java.util.function.Predicate

class VeinMinerAccessRewardsTest : StringSpec({
    "only fixed durations are accepted and unavailable providers never mutate" {
        val runtime = FakeRuntime()
        val rewards = VeinMinerAccessRewards(runtime) { "survival" }
        listOf(1, 3, 7).forEach { days -> rewards.ready(days) shouldBe true }
        listOf(-1, 0, 2, 30, Int.MAX_VALUE).forEach { days ->
            rewards.ready(days) shouldBe false
            (rewards.redeem(player(), days).get() is PhysicalRewardOutcome.Rejected) shouldBe true
        }
        runtime.available = false
        (rewards.redeem(player(), 1).get() is PhysicalRewardOutcome.Rejected) shouldBe true
        runtime.grants shouldBe emptyList()
    }

    "Spawn can issue a pass without a local VeinMiner plugin but only Survival may redeem" {
        mockkStatic(Bukkit::class)
        try {
            val plugins = mockk<PluginManager>()
            every { Bukkit.getPluginManager() } returns plugins
            every { plugins.isPluginEnabled("LuckPerms") } returns true
            every { plugins.isPluginEnabled("VeinMiner") } returns false
            VeinMinerAccessRewards().ready(3) shouldBe true
            verify(exactly = 0) { plugins.isPluginEnabled("VeinMiner") }

            val runtime = FakeRuntime()
            val spawn = VeinMinerAccessRewards(runtime) { "spawn" }
            spawn.ready(3) shouldBe true
            spawn.canRedeem(player(), 3) shouldBe "<gold>Активируйте пропуск на сервере «Выживание»."
            spawn.redeem(player(), 3).get() shouldBe
                PhysicalRewardOutcome.Rejected("<gold>Активируйте пропуск на сервере «Выживание».")
            runtime.grants shouldBe emptyList()
            runtime.permanent = true
            spawn.canRedeem(player(), 3) shouldBe PERMANENT
            runtime.permanent = false
            VeinMinerAccessRewards(runtime) { "survival" }.redeem(player(), 3).get() shouldBe PhysicalRewardOutcome.Applied

            every { plugins.isPluginEnabled("LuckPerms") } returns false
            VeinMinerAccessRewards().ready(3) shouldBe false
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }

    "permanent ownership rejects before a permission mutation" {
        val runtime = FakeRuntime().apply { permanent = true }
        VeinMinerAccessRewards(runtime) { "survival" }.redeem(player(), 3).get() shouldBe PhysicalRewardOutcome.Rejected(PERMANENT)
        runtime.grants shouldBe emptyList()
    }

    "durable save completion controls applied and failed saves report uncertainty" {
        val save = CompletableFuture<VeinMinerAccessRewards.Mutation>()
        val runtime = FakeRuntime().apply { nextSave = save }
        val rewards = VeinMinerAccessRewards(runtime) { "survival" }
        val outcome = rewards.redeem(player(), 7)
        outcome.isDone shouldBe false
        runtime.grants shouldBe listOf(PLAYER_ID to 7)
        save.complete(VeinMinerAccessRewards.Mutation.APPLIED)
        outcome.get() shouldBe PhysicalRewardOutcome.Applied

        runtime.nextSave = CompletableFuture.failedFuture(IllegalStateException("storage failed"))
        rewards.redeem(player(), 1).get() shouldBe PhysicalRewardOutcome.Uncertain(UNCERTAIN)
        runtime.nextSave = CompletableFuture.completedFuture(VeinMinerAccessRewards.Mutation.UNKNOWN)
        rewards.redeem(player(), 1).get() shouldBe PhysicalRewardOutcome.Uncertain(UNCERTAIN)
        runtime.nextSave = CompletableFuture.completedFuture(VeinMinerAccessRewards.Mutation.PERMANENT_ACCESS)
        rewards.redeem(player(), 1).get() shouldBe PhysicalRewardOutcome.Rejected(PERMANENT)
    }

    "expired and live temporary access extend from the later of now and the exact owned expiry" {
        listOf(NOW.minusSeconds(86_400), NOW.plusSeconds(86_400)).forEach { oldExpiry ->
            LuckPermsFixture().use { fixture ->
                val old = fixture.node(expiry = oldExpiry)
                val global = fixture.node(expiry = NOW.plusSeconds(900_000), contexts = mockk())
                val worldScoped = fixture.node(expiry = NOW.plusSeconds(900_000), contexts = mockk())
                val deny = fixture.node(expiry = NOW.plusSeconds(900_000), value = false)
                val unrelated = fixture.node(expiry = NOW.plusSeconds(900_000), permission = "arc.other")
                fixture.nodes += listOf(old, global, worldScoped, deny, unrelated)

                val save = CompletableFuture<Void>()
                fixture.save = save
                val outcome = fixture.runtime.extendAccess(PLAYER_ID, 3)
                outcome.isDone shouldBe false
                fixture.grantedExpiry shouldBe maxOf(NOW, oldExpiry).plusSeconds(3L * 86_400)
                fixture.nodes shouldBe linkedSetOf(global, worldScoped, deny, unrelated, requireNotNull(fixture.granted))
                verify(exactly = 1) { fixture.builder.withContext("server", "survival") }
                verify(exactly = 1) { fixture.builder.value(true) }
                save.complete(null)
                outcome.get() shouldBe VeinMinerAccessRewards.Mutation.APPLIED
            }
        }
    }

    "a temporary personal node does not hide inherited permanent Caesar access" {
        LuckPermsFixture().use { fixture ->
            val temporary = fixture.node(expiry = NOW.plusSeconds(86_400))
            val permanent = fixture.node()
            fixture.nodes += temporary
            fixture.effective = Tristate.TRUE
            fixture.effectiveNode = temporary
            fixture.inherited = listOf(temporary, permanent, fixture.node(value = false))

            fixture.runtime.hasPermanentAccess(PLAYER_ID) shouldBe true
            fixture.runtime.extendAccess(PLAYER_ID, 1).get() shouldBe VeinMinerAccessRewards.Mutation.PERMANENT_ACCESS
            fixture.nodes shouldBe linkedSetOf(temporary)
            verify(exactly = 0) { fixture.nodeMap.add(any()) }
        }
    }

    "effective permanent access is rejected and a higher-priority permanent denial is preserved" {
        LuckPermsFixture().use { fixture ->
            fixture.effective = Tristate.TRUE
            fixture.effectiveNode = fixture.node(permission = "*")
            fixture.runtime.hasPermanentAccess(PLAYER_ID) shouldBe true

            fixture.effective = Tristate.FALSE
            fixture.inherited = listOf(fixture.node(value = false), fixture.node())
            fixture.runtime.hasPermanentAccess(PLAYER_ID) shouldBe false
        }
    }
}) {
    companion object {
        private val PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        private val NOW = Instant.parse("2026-10-27T12:00:00Z")
        private const val PERMANENT = "<#F4BD6A>Vein Miner уже доступен вам постоянно. Пропуск можно передать другому игроку."
        private const val UNCERTAIN = "<#F4BD6A>Результат активации пока не подтверждён. Обратитесь к администрации."

        private fun player(): Player = mockk<Player>().also { every { it.uniqueId } returns PLAYER_ID }

        private class FakeRuntime : VeinMinerAccessRewards.Runtime {
            var available = true
            var permanent = false
            var nextSave = CompletableFuture.completedFuture(VeinMinerAccessRewards.Mutation.APPLIED)
            val grants = mutableListOf<Pair<UUID, Int>>()
            override fun providersReady(): Boolean = available
            override fun hasPermanentAccess(playerId: UUID): Boolean = permanent
            override fun extendAccess(playerId: UUID, days: Int): CompletableFuture<VeinMinerAccessRewards.Mutation> {
                grants += playerId to days
                return nextSave
            }
        }

        private class LuckPermsFixture : AutoCloseable {
            val nodes = linkedSetOf<Node>()
            val nodeMap = mockk<NodeMap>()
            val builder = mockk<PermissionNode.Builder>()
            var save: CompletableFuture<Void> = CompletableFuture.completedFuture(null)
            var inherited = emptyList<PermissionNode>()
            var effective = Tristate.FALSE
            var effectiveNode: Node? = null
            var grantedExpiry: Instant? = null
            var granted: PermissionNode? = null
            private val contexts = mockk<ImmutableContextSet>()
            private val options = mockk<QueryOptions>()
            private val user = mockk<User>()
            private val users = mockk<UserManager>()
            private val luckPerms = mockk<LuckPerms>()
            private val cache = mockk<CachedDataManager>()
            private val permissions = mockk<CachedPermissionData>()
            private val query = mockk<Result<Tristate, Node>>()
            val runtime = VeinMinerAccessRewards.LuckPermsRuntime({ luckPerms }, Clock.fixed(NOW, ZoneOffset.UTC))

            init {
                mockkStatic(ImmutableContextSet::class, QueryOptions::class, PermissionNode::class)
                every { ImmutableContextSet.of("server", "survival") } returns contexts
                every { QueryOptions.contextual(contexts) } returns options
                every { luckPerms.userManager } returns users
                every { users.getUser(PLAYER_ID) } returns user
                every { users.modifyUser(PLAYER_ID, any()) } answers {
                    secondArg<Consumer<User>>().accept(user)
                    save
                }
                every { user.cachedData } returns cache
                every { cache.getPermissionData(options) } returns permissions
                every { permissions.queryPermission(VeinMinerAccessEntitlement.PERMISSION) } returns query
                every { query.result() } answers { effective }
                every { query.node() } answers { effectiveNode }
                every { user.resolveInheritedNodes(NodeType.PERMISSION, options) } answers { inherited }
                every { user.data() } returns nodeMap
                every { nodeMap.toCollection() } answers { nodes.toList() }
                every { nodeMap.clear(any<Predicate<Node>>()) } answers { nodes.removeIf(firstArg()) }
                every { nodeMap.add(any()) } answers { nodes += firstArg<Node>(); DataMutateResult.SUCCESS }
                every { PermissionNode.builder(VeinMinerAccessEntitlement.PERMISSION) } returns builder
                every { builder.value(true) } returns builder
                every { builder.withContext("server", "survival") } returns builder
                every { builder.expiry(any<TemporalAccessor>()) } answers {
                    grantedExpiry = firstArg<Instant>()
                    builder
                }
                every { builder.build() } answers { node(expiry = grantedExpiry).also { granted = it } }
            }

            fun node(
                expiry: Instant? = null,
                contexts: ImmutableContextSet = this.contexts,
                value: Boolean = true,
                permission: String = VeinMinerAccessEntitlement.PERMISSION,
            ): PermissionNode = mockk<PermissionNode>().also { node ->
                every { node.permission } returns permission
                every { node.contexts } returns contexts
                every { node.value } returns value
                every { node.hasExpiry() } returns (expiry != null)
                every { node.expiry } returns expiry
            }

            override fun close() {
                unmockkStatic(ImmutableContextSet::class, QueryOptions::class, PermissionNode::class)
            }
        }
    }
}
