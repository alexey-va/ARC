package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.economy.EconomyHandler
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.types.PermissionNode
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.util.Logging
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val ELITEMOBS_SKILL_XP_BOOST_PERMISSION = "elitemobs.perks.xp.25percentboost.all"

internal data class SkillXpBoostOffer(val id: String, val duration: Duration, val price: Double)

internal enum class SkillXpBoostResult(val success: Boolean = false) {
    BOUGHT(true), CHANGED, PENDING, NO_MONEY, PAYMENT_FAILED, GRANT_FAILED,
}

internal interface SkillXpBoostWallet {
    fun balance(player: Player): Double
    fun withdraw(player: Player, amount: Double): Boolean
    fun deposit(player: Player, amount: Double): Boolean
}

private object NativeSkillXpBoostWallet : SkillXpBoostWallet {
    override fun balance(player: Player): Double = EconomyHandler.checkCurrency(player.uniqueId)

    override fun withdraw(player: Player, amount: Double): Boolean {
        val before = balance(player)
        if (!before.isFinite() || before < amount) return false
        runCatching { EconomyHandler.subtractCurrency(player.uniqueId, amount) }
            .onFailure { Logging.error("EliteMobs skill XP boost payment failed", it) }
        return kotlin.math.abs(balance(player) - (before - amount)) < 0.005
    }

    override fun deposit(player: Player, amount: Double): Boolean {
        val before = balance(player)
        runCatching { EconomyHandler.addCurrency(player.uniqueId, amount) }
            .onFailure { Logging.error("EliteMobs skill XP boost refund failed", it) }
        return kotlin.math.abs(balance(player) - (before + amount)) < 0.005
    }
}

internal interface SkillXpBoostAccess {
    fun currentExpiry(playerId: UUID): Instant?
    fun extend(playerId: UUID, duration: Duration, complete: (Boolean) -> Unit)
}

private object NativeSkillXpBoostAccess : SkillXpBoostAccess {
    override fun currentExpiry(playerId: UUID): Instant? = LuckPermsProvider.get().userManager.getUser(playerId)
        ?.nodes?.asSequence()?.filterIsInstance<PermissionNode>()
        ?.filter { it.permission == ELITEMOBS_SKILL_XP_BOOST_PERMISSION && it.value && !it.hasExpired() }
        ?.mapNotNull { it.expiry }
        ?.maxOrNull()

    override fun extend(playerId: UUID, duration: Duration, complete: (Boolean) -> Unit) {
        val now = Instant.now()
        LuckPermsProvider.get().userManager.modifyUser(playerId) { user ->
            val matching = user.nodes.filterIsInstance<PermissionNode>().filter {
                it.permission == ELITEMOBS_SKILL_XP_BOOST_PERMISSION && it.contexts.isEmpty && it.value
            }
            val current = matching.asSequence().filterNot { it.hasExpired() }.mapNotNull { it.expiry }.maxOrNull()
            matching.filter { it.hasExpiry() }.forEach { user.data().remove(it) }
            if (matching.none { !it.hasExpiry() }) {
                user.data().add(PermissionNode.builder(ELITEMOBS_SKILL_XP_BOOST_PERMISSION)
                    .value(true).expiry(extendedSkillBoostExpiry(now, current, duration)).build())
            }
        }.whenComplete { _, error ->
            Bukkit.getScheduler().runTask(ARC.instance, Runnable { complete(error == null) })
        }
    }
}

internal class DungeonSkillXpBoosts(
    private val offers: () -> List<SkillXpBoostOffer> = { DEFAULT_SKILL_XP_BOOSTS },
    private val wallet: SkillXpBoostWallet = NativeSkillXpBoostWallet,
    private val access: SkillXpBoostAccess = NativeSkillXpBoostAccess,
) {
    private val pending = ConcurrentHashMap.newKeySet<UUID>()

    fun list(): List<SkillXpBoostOffer> = offers().filter(::valid).distinctBy { it.id }
    fun currentExpiry(player: Player): Instant? = access.currentExpiry(player.uniqueId)
    fun isPending(player: Player): Boolean = player.uniqueId in pending

    fun buy(player: Player, displayed: SkillXpBoostOffer, complete: (SkillXpBoostResult) -> Unit) {
        val offer = list().singleOrNull { it == displayed } ?: run { complete(SkillXpBoostResult.CHANGED); return }
        if (!pending.add(player.uniqueId)) { complete(SkillXpBoostResult.PENDING); return }
        val balance = runCatching { wallet.balance(player) }.getOrNull()
        if (balance == null || !balance.isFinite()) return finish(player, SkillXpBoostResult.PAYMENT_FAILED, complete)
        if (balance < offer.price) return finish(player, SkillXpBoostResult.NO_MONEY, complete)
        if (!runCatching { wallet.withdraw(player, offer.price) }.getOrDefault(false))
            return finish(player, SkillXpBoostResult.PAYMENT_FAILED, complete)
        runCatching {
            access.extend(player.uniqueId, offer.duration) { granted ->
                if (granted) finish(player, SkillXpBoostResult.BOUGHT, complete)
                else refund(player, offer.price, complete)
            }
        }.onFailure {
            Logging.error("EliteMobs skill XP boost permission grant failed", it)
            refund(player, offer.price, complete)
        }
    }

    private fun refund(player: Player, price: Double, complete: (SkillXpBoostResult) -> Unit) {
        if (!runCatching { wallet.deposit(player, price) }.getOrDefault(false))
            Logging.error("Could not refund {} crystals for failed EliteMobs skill XP boost to {}", price, player.uniqueId)
        finish(player, SkillXpBoostResult.GRANT_FAILED, complete)
    }

    private fun finish(player: Player, result: SkillXpBoostResult, complete: (SkillXpBoostResult) -> Unit) {
        pending.remove(player.uniqueId)
        complete(result)
    }

    private fun valid(offer: SkillXpBoostOffer): Boolean = offer.id.matches(Regex("[a-z][a-z0-9_]{0,31}")) &&
        !offer.duration.isZero && !offer.duration.isNegative && offer.duration <= Duration.ofHours(24) &&
        offer.price.isFinite() && offer.price > 0 && offer.price.toBigDecimal().stripTrailingZeros().scale() <= 2
}

internal fun extendedSkillBoostExpiry(now: Instant, current: Instant?, duration: Duration): Instant =
    maxOf(now, current ?: now).plus(duration)

internal val DEFAULT_SKILL_XP_BOOSTS = listOf(
    SkillXpBoostOffer("skill_xp_30m", Duration.ofMinutes(30), 150.0),
    SkillXpBoostOffer("skill_xp_2h", Duration.ofHours(2), 500.0),
    SkillXpBoostOffer("skill_xp_4h", Duration.ofHours(4), 900.0),
)
