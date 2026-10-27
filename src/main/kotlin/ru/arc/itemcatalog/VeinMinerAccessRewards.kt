package ru.arc.itemcatalog

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.user.User
import net.luckperms.api.node.Node
import net.luckperms.api.node.NodeType
import net.luckperms.api.node.types.PermissionNode
import net.luckperms.api.query.QueryOptions
import net.luckperms.api.util.Tristate
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.util.Logging.warn
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/** Fixed Survival entitlement; bearer replay and recovery belong to PhysicalRewardController. */
internal class VeinMinerAccessRewards(
    private val runtime: Runtime = LuckPermsRuntime(),
    private val currentServer: () -> String? = { ARC.serverName },
) {
    internal interface Runtime {
        fun providersReady(): Boolean
        fun hasPermanentAccess(playerId: UUID): Boolean
        fun extendAccess(playerId: UUID, days: Int): CompletableFuture<Mutation>
    }

    internal enum class Mutation { APPLIED, PERMANENT_ACCESS, UNKNOWN }

    fun ready(days: Int): Boolean = days in VeinMinerAccessEntitlement.DURATIONS &&
        runCatching { runtime.providersReady() }.getOrDefault(false)

    fun canRedeem(player: Player, days: Int): String? {
        if (!ready(days)) return UNAVAILABLE
        val playerId = player.uniqueId
        return try {
            when {
                runtime.hasPermanentAccess(playerId) -> PERMANENT_ACCESS
                currentServer() != VeinMinerAccessEntitlement.SERVER -> WRONG_SERVER
                else -> null
            }
        } catch (failure: Throwable) {
            warn("VeinMiner access reward preflight failed: player={} days={}", playerId, days, failure)
            UNAVAILABLE
        }
    }

    /** Completion means LP finished its durable save; callbacks never access Bukkit state. */
    fun redeem(player: Player, days: Int): CompletableFuture<PhysicalRewardOutcome> {
        canRedeem(player, days)?.let {
            return CompletableFuture.completedFuture(PhysicalRewardOutcome.Rejected(it))
        }
        val playerId = player.uniqueId
        return try {
            runtime.extendAccess(playerId, days).handle { mutation, failure ->
                if (failure != null) {
                    warn("VeinMiner access reward save failed: player={} days={}", playerId, days, failure)
                } else if (mutation == null || mutation == Mutation.UNKNOWN) {
                    warn("VeinMiner access reward save returned no confirmed grant: player={} days={}", playerId, days)
                }
                when {
                    failure != null -> PhysicalRewardOutcome.Uncertain(UNCERTAIN)
                    mutation == Mutation.APPLIED -> PhysicalRewardOutcome.Applied
                    mutation == Mutation.PERMANENT_ACCESS -> PhysicalRewardOutcome.Rejected(PERMANENT_ACCESS)
                    else -> PhysicalRewardOutcome.Uncertain(UNCERTAIN)
                }
            }
        } catch (failure: Throwable) {
            warn("VeinMiner access reward save could not start: player={} days={}", playerId, days, failure)
            // modifyUser may already have accepted work. Retain the durable claim for recovery.
            CompletableFuture.completedFuture(PhysicalRewardOutcome.Uncertain(UNCERTAIN))
        }
    }

    internal class LuckPermsRuntime(
        private val luckPerms: () -> LuckPerms = { LuckPermsProvider.get() },
        private val clock: Clock = Clock.systemUTC(),
    ) : Runtime {
        // Spawn can issue a pass; redemption is restricted to the configured Survival backend.
        override fun providersReady(): Boolean = Bukkit.getPluginManager().isPluginEnabled("LuckPerms")

        override fun hasPermanentAccess(playerId: UUID): Boolean =
            luckPerms().userManager.getUser(playerId)?.let(::hasPermanentAccess) ?: false

        override fun extendAccess(playerId: UUID, days: Int): CompletableFuture<Mutation> {
            require(days in VeinMinerAccessEntitlement.DURATIONS) { "Unsupported VeinMiner duration: $days" }
            val result = AtomicReference(Mutation.UNKNOWN)
            return luckPerms().userManager.modifyUser(playerId) { user ->
                // Rank or direct permission may have changed since the main-thread preflight.
                if (hasPermanentAccess(user)) {
                    result.set(Mutation.PERMANENT_ACCESS)
                } else {
                    val data = user.data()
                    val now = clock.instant()
                    val previousExpiry = data.toCollection().asSequence()
                        .filter(::isOwnedTemporaryNode).mapNotNull { it.expiry }.maxOrNull()
                    val expiry = maxOf(now, previousExpiry ?: now).plus(days.toLong(), ChronoUnit.DAYS)
                    val node = PermissionNode.builder(VeinMinerAccessEntitlement.PERMISSION)
                        .value(true)
                        .withContext("server", VeinMinerAccessEntitlement.SERVER)
                        .expiry(expiry)
                        .build()
                    // Do not touch global grants, denials, world-specific nodes, or other permissions.
                    data.clear(::isOwnedTemporaryNode)
                    if (data.add(node) == DataMutateResult.SUCCESS) result.set(Mutation.APPLIED)
                }
            }.thenApply { result.get() }
        }

        private fun hasPermanentAccess(user: User): Boolean {
            val contexts = ImmutableContextSet.of("server", VeinMinerAccessEntitlement.SERVER)
            val options = QueryOptions.contextual(contexts)
            val effective = user.cachedData.getPermissionData(options).queryPermission(VeinMinerAccessEntitlement.PERMISSION)
            if (effective.result() == Tristate.TRUE && effective.node()?.hasExpiry() == false) return true

            // A personal temporary grant can mask a permanent Caesar grant in the cached result.
            // LP returns inherited nodes in permission resolution order; retain its first permanent value.
            return user.resolveInheritedNodes(NodeType.PERMISSION, options).firstOrNull {
                it.permission == VeinMinerAccessEntitlement.PERMISSION && !it.hasExpiry()
            }?.value == true
        }

        private fun isOwnedTemporaryNode(node: Node): Boolean = node is PermissionNode &&
            node.permission == VeinMinerAccessEntitlement.PERMISSION && node.value && node.hasExpiry() &&
            node.contexts == ImmutableContextSet.of("server", VeinMinerAccessEntitlement.SERVER)
    }

    private companion object {
        val UNAVAILABLE = RewardCatalogMessages.DEFAULT.unavailable
        const val PERMANENT_ACCESS = "<#F4BD6A>Vein Miner уже доступен вам постоянно. Пропуск можно передать другому игроку."
        const val WRONG_SERVER = "<gold>Активируйте пропуск на сервере «Выживание»."
        const val UNCERTAIN = "<#F4BD6A>Результат активации пока не подтверждён. Обратитесь к администрации."
    }
}
