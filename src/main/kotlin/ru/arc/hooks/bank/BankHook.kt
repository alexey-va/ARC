package ru.arc.hooks.bank

import me.dablakbandit.bank.api.BankAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.ARC
import ru.arc.util.Logging.warn

class BankHook(
    private val accountReader: (String) -> Account = { playerId ->
        val api = BankAPI.getInstance()
        Account(
            balance = api.getMoney(playerId),
            pendingInterest = api.getOfflineMoney(playerId),
        )
    },
) : Listener {
    data class Account(
        val balance: Double,
        val pendingInterest: Double,
    )

    fun offlineBalance(name: String): Double = BankAPI.getInstance().getMoney(name)
    fun balance(player: Player): Double = BankAPI.getInstance().getMoney(player)

    /**
     * Bank's first asynchronous load can race the previous Paper server releasing
     * the shared SQL lock. Its configured short retry handles that transfer race.
     * This later call activates Bank 5.0.3's own 30-second stale-lock recovery so
     * an unclean disconnect cannot leave the account locked for the whole session.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun recoverStaleLoad(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(
            ARC.instance,
            Runnable {
                if (!player.isOnline) return@Runnable
                runCatching { retryLockedPlayer(player) }
                    .onFailure { failure ->
                        warn("Bank stale-lock recovery failed for {}: {}", player.uniqueId, failure.javaClass.simpleName)
                    }
            },
            STALE_LOCK_RECOVERY_TICKS,
        )
    }

    private fun retryLockedPlayer(player: Player) {
        val bankPlugin = checkNotNull(Bukkit.getPluginManager().getPlugin("Bank"))
        val loader = bankPlugin.javaClass.classLoader
        val managerClass = loader.loadClass("me.dablakbandit.core.players.CorePlayerManager")
        val manager = managerClass.getMethod("getInstance").invoke(null)
        val corePlayer = managerClass.getMethod("getPlayer", Player::class.java).invoke(manager, player) ?: return
        val bankInfoClass = loader.loadClass("me.dablakbandit.bank.player.info.BankInfo")
        val bankInfo = corePlayer.javaClass.getMethod("getInfo", Class::class.java).invoke(corePlayer, bankInfoClass) ?: return
        bankInfoClass.getMethod("isLocked", Boolean::class.javaPrimitiveType).invoke(bankInfo, true)
    }

    /**
     * Bank 5.0.3 delegates its String overloads to CorePlayers(String), whose
     * verified runtime bytecode parses the value with UUID.fromString. A cached
     * username is display metadata, never a valid account identifier here.
     */
    @Suppress("UNUSED_PARAMETER")
    fun account(playerId: String, knownName: String?): Account = accountReader(playerId)

    companion object {
        internal const val STALE_LOCK_RECOVERY_TICKS = 620L
    }
}
