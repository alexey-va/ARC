package ru.arc.integrations.mounts

import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.ARC
import ru.arc.core.modules.RedisModule
import ru.arc.economy.CurrencyMoneyEvidence
import ru.arc.economy.RedisEconomyWallet
import ru.arc.hooks.HookRegistry
import ru.arc.mounts.api.ArcMountConfiguration
import ru.arc.mounts.api.ArcMountEconomy
import ru.arc.mounts.api.ArcMountService
import ru.arc.mounts.api.ArcMountVisibility
import java.util.UUID

/** ARC retains the shared transport and exact provider-payment boundary. */
object MountHostServices {
    fun register(plugin: Plugin) {
        val services = plugin.server.servicesManager
        fun <T : Any> register(type: Class<T>, provider: T) =
            services.register(type, provider, plugin, ServicePriority.Normal)
        register(ArcMountConfiguration::class.java, object : ArcMountConfiguration {
            override fun dataPath() = ARC.instance.dataPath
            override fun serverName() = requireNotNull(ARC.serverName)
            override fun sharedRedisAvailable() = ARC.redisManager != null
            override fun sharedRedisGeneration() = RedisModule.bindingGeneration
        })
        register(ArcMountVisibility::class.java, object : ArcMountVisibility {
            override fun available() = HookRegistry.packetEventsHook != null
            override fun setEntityInvisibleFor(entity: LivingEntity, player: Player, invisible: Boolean) {
                HookRegistry.packetEventsHook?.setEntityInvisibleFor(entity, player, invisible)
            }
            override fun synchronizePassengersFor(player: Player, vehicles: List<LivingEntity>) {
                HookRegistry.packetEventsHook?.synchronizePassengersFor(player, vehicles)
            }
        })
        register(ArcMountEconomy::class.java, MountEconomyProvider())
    }
}

internal class MountEconomyProvider : ArcMountEconomy {
    private val wallet = RedisEconomyWallet()
    private fun currency(name: String) = requireNotNull(wallet.walletForCurrency(name))

    override fun available(currency: String) = currency(currency).available
    override fun balanceMinor(currency: String, playerId: UUID) = currency(currency).balanceMinor(playerId)
    override fun withdraw(currency: String, playerId: UUID, amountMinor: Long, reason: String, expectedBeforeMinor: Long) =
        currency(currency).withdraw(playerId, amountMinor, reason, expectedBeforeMinor).toApi()
    override fun deposit(currency: String, playerId: UUID, amountMinor: Long, reason: String, expectedBeforeMinor: Long) =
        currency(currency).deposit(playerId, amountMinor, reason, expectedBeforeMinor).toApi()
    override fun findTransaction(currency: String, playerId: UUID, amountMinor: Long, reason: String, notBeforeMillis: Long) =
        currency(currency).findTransaction(playerId, amountMinor, reason, notBeforeMillis).thenApply {
            ArcMountEconomy.TransactionEvidence(it.transactionId, it.historyAvailable)
        }

    private fun CurrencyMoneyEvidence.toApi() = ArcMountEconomy.MoneyEvidence(
        providerAccepted, providerCallAttempted, balanceAfterMinor, failureCode,
    )
}

/** Optional until ArcMounts has enabled; callers retain their existing unavailable behavior. */
fun mountService(): ArcMountService? = Bukkit.getServicesManager().load(ArcMountService::class.java)
