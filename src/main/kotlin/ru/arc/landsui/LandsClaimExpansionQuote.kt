package ru.arc.landsui

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.land.enums.LandType
import me.angeschossen.lands.api.limits.Limit
import me.angeschossen.lands.api.player.LandPlayer
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.util.Logging.warn
import java.lang.reflect.Modifier
import java.util.UUID

/** Read-only fee preview; placement still performs Lands' native validation and payment. */
internal class LandsClaimExpansionQuote internal constructor(
    private val integration: LandsIntegration,
    private var nativeQuote: ((LandPlayer, Land?, Int) -> Double)?,
    private val clock: () -> Long = System::nanoTime,
    private val reportFailure: (Throwable) -> Unit = ::claimQuoteDisabled,
) {
    constructor(integration: LandsIntegration, landsPlugin: Plugin) : this(
        integration,
        loadNativeClaimQuote(landsPlugin),
    )

    private data class Key(val land: Land?, val landChunks: Int, val freeLimit: Int, val additional: Int)
    private data class Cached(val key: Key, val createdAt: Long, val cost: Double)
    private val cache = linkedMapOf<UUID, Cached>()

    fun quote(player: Player, target: Location, radius: Int): Double? {
        val calculate = nativeQuote ?: return null
        try {
            val world = target.world ?: return null
            if (radius !in 0..4 || !player.isOnline || player.world != world || integration.getWorld(world) == null) return null
            val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return null
            if (landPlayer.selection != null) return null
            val selected = landPlayer.getEditLand(false)?.takeIf { it.exists() }
            // These are the native charging guard's free paths, including administrator land.
            if (selected != null && (!selected.isTrusted(player.uniqueId) || selected.landType == LandType.ADMIN)) return 0.0
            val centerX = target.blockX shr 4
            val centerZ = target.blockZ shr 4
            var additional = 0
            for (x in centerX - radius..centerX + radius) {
                for (z in centerZ - radius..centerZ + radius) {
                    val claimed = integration.getLandByUnloadedChunk(world, x, z)
                    if (claimed == null) additional++
                    else if (selected == null || claimed.ulid != selected.ulid) return null
                }
            }
            if (additional == 0) return 0.0

            val now = clock()
            cache.entries.removeIf { now - it.value.createdAt >= CACHE_NANOS }
            val key = Key(selected, selected?.chunksAmount ?: 0, landPlayer.getLimit(Limit.PLAYER_CHUNKS_FREE), additional)
            cache[player.uniqueId]?.takeIf { it.key == key }?.let { return it.cost }
            // Native pricing scans recorded FREE/NORMAL claims. Reuse that result briefly,
            // while the bounded footprint and cheap quota/size changes remain live.
            val cost = calculate(landPlayer, selected, additional)
            check(cost.isFinite() && cost >= 0.0) { "Lands returned an invalid claim fee" }
            if (cache.size >= MAX_CACHED_PLAYERS) cache.remove(cache.keys.first())
            cache[player.uniqueId] = Cached(key, now, cost)
            return cost
        } catch (failure: Exception) {
            disable(failure)
        } catch (failure: LinkageError) {
            disable(failure)
        }
        return null
    }

    private fun disable(failure: Throwable) {
        nativeQuote = null
        cache.clear()
        reportFailure(failure)
    }

    private companion object {
        const val CACHE_NANOS = 1_000_000_000L
        const val MAX_CACHED_PLAYERS = 256
    }
}

private fun loadNativeClaimQuote(plugin: Plugin): ((LandPlayer, Land?, Int) -> Double)? = try {
    nativeLandsClaimQuote(plugin.javaClass.classLoader, plugin.description.version)
} catch (failure: Exception) {
    claimQuoteDisabled(failure)
    null
} catch (failure: LinkageError) {
    claimQuoteDisabled(failure)
    null
}

private fun claimQuoteDisabled(failure: Throwable) {
    warn("Lands claim fee preview disabled: {}", failure.cause?.message ?: failure.message, failure)
}

/**
 * Exact third-party boundary: Lands 8.6.6, SHA-256 c4444a4716f0f78279ff0d6644c2e93e4abd8dac1eff05d3cd42f494793c4ae9.
 * Its public API has no fee quote. These are the same NORMAL-count and aggregate
 * calculator methods used by Selection.claim; no configuration formula is copied.
 * Bind once at module startup and disable only the preview when the ABI changes.
 */
internal fun nativeLandsClaimQuote(loader: ClassLoader, version: String): (LandPlayer, Land?, Int) -> Double {
    require(version == "8.6.6") { "Claim fee preview is verified for Lands 8.6.6; found $version" }
    val playerType = Class.forName("me.angeschossen.lands.Dgduf.RLHXh.lGdKv.RLHXh.Dgduf", false, loader)
    val landType = Class.forName("me.angeschossen.lands.Dgduf.RLHXh.ztTjN.RLHXh.RLHXh", false, loader)
    val claimType = Class.forName("me.angeschossen.lands.Dgduf.RLHXh.RLHXh.RLHXh.Dgduf", false, loader)
    val calculatorType = Class.forName("me.angeschossen.lands.cKfg.RLHXh.RLHXh", false, loader)
    require(LandPlayer::class.java.isAssignableFrom(playerType) && Land::class.java.isAssignableFrom(landType)) {
        "Lands claim types do not match the installed API"
    }
    val normal = claimType.enumConstants.single { (it as Enum<*>).name == "NORMAL" }
    val normalCount = landType.getMethod("RLHXh", claimType)
    val calculate = calculatorType.getMethod("RLHXh", playerType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    require(normalCount.returnType == Int::class.javaPrimitiveType &&
        calculate.returnType == Double::class.javaPrimitiveType && Modifier.isStatic(calculate.modifiers)) {
        "Lands claim calculator signature changed"
    }
    return { player, land, additional ->
        val from = if (land == null) 0 else normalCount.invoke(land, normal) as Int
        calculate.invoke(null, player, from, Math.addExact(from, additional)) as Double
    }
}
