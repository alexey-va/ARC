package ru.arc.enchanting

import com.magmaguy.elitemobs.entitytracker.EntityTracker
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.inventory.ItemStack
import ru.arc.helpcenter.HelpCenterEnchantmentsCatalog
import ru.arc.core.LifecycleTaskScope
import ru.arc.treasure.core.AeNativeItems
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.Treasures
import ru.arc.util.Logging
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

internal data class AdvancedEnchantmentLootSettings(
    val survivalBackend: Boolean,
    val worldNames: Set<String>,
    val naturalKillBookPercent: Double,
    val naturalKillDustPercent: Double,
    val openWaterBookPercent: Double,
    val openWaterDustPercent: Double,
    val groupWeights: Map<String, Int>,
) {
    internal val normalizedWorldNames = worldNames.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }
    internal val normalizedGroupWeights = groupWeights.entries.associate { it.key.trim().uppercase(Locale.ROOT) to it.value }

    init {
        require(worldNames.none(String::isBlank)) { "Loot world names must not be blank" }
        require(normalizedWorldNames.size == worldNames.size) { "Loot world names must be unique ignoring case" }
        require(normalizedGroupWeights.size == groupWeights.size) { "Loot group names must be unique ignoring case" }
        require(normalizedGroupWeights.keys.all(PUBLIC_GROUPS::contains)) { "Loot groups must be public AE groups" }
        require(normalizedGroupWeights.values.all { it >= 0 }) { "Loot group weights must be non-negative" }
        require(normalizedGroupWeights.values.sumOf { it.toLong() } in 1L..Int.MAX_VALUE.toLong()) {
            "At least one positive AE loot group weight is required"
        }
        validateLootChance(naturalKillBookPercent, naturalKillDustPercent)
        validateLootChance(openWaterBookPercent, openWaterDustPercent)
    }

    internal fun allowsWorld(worldName: String): Boolean =
        survivalBackend && worldName.trim().lowercase(Locale.ROOT) in normalizedWorldNames

    private fun validateLootChance(bookPercent: Double, dustPercent: Double) {
        require(bookPercent.isFinite() && dustPercent.isFinite()) { "Loot chances must be finite" }
        require(bookPercent in 0.0..100.0 && dustPercent in 0.0..100.0) { "Loot chances must be between 0 and 100" }
        require(bookPercent + dustPercent <= 100.0) { "Exclusive loot chances must not exceed 100%" }
    }

    private companion object {
        val PUBLIC_GROUPS = setOf("SIMPLE", "UNIQUE", "ELITE", "ULTIMATE", "LEGENDARY")
    }
}

internal enum class AdvancedEnchantmentLootKind { BOOK, DUST }

/** One draw makes the book and dust outcomes exclusive; values are percentage points. */
internal fun advancedEnchantmentLootKind(
    roll: Double,
    bookPercent: Double,
    dustPercent: Double,
): AdvancedEnchantmentLootKind? {
    require(roll.isFinite() && roll in 0.0..<1.0)
    require(bookPercent.isFinite() && dustPercent.isFinite())
    require(bookPercent in 0.0..100.0 && dustPercent in 0.0..100.0 && bookPercent + dustPercent <= 100.0)
    val position = roll * 100.0
    return when {
        position < bookPercent -> AdvancedEnchantmentLootKind.BOOK
        position < bookPercent + dustPercent -> AdvancedEnchantmentLootKind.DUST
        else -> null
    }
}

internal fun eligibleNaturalMobLoot(
    survivalBackend: Boolean,
    worldAllowed: Boolean,
    gameMode: GameMode,
    enemy: Boolean,
    naturalSpawn: Boolean,
    playerAttack: Boolean,
    samePlayer: Boolean,
    eliteMobsEntity: Boolean,
): Boolean =
    survivalBackend && worldAllowed && gameMode in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE) && enemy &&
        naturalSpawn && playerAttack && samePlayer && !eliteMobsEntity

internal data class AdvancedBookCandidate(val id: String, val levels: List<Int>)

internal fun eligibleAdvancedBookCandidates(
    catalog: HelpCenterEnchantmentsCatalog,
    groupWeights: Map<String, Int>,
    configuredLevels: (String) -> List<Int>,
): Map<String, List<AdvancedBookCandidate>> {
    if (!catalog.available) return emptyMap()
    val allowedGroups = groupWeights.filterValues { it > 0 }.keys.mapTo(mutableSetOf()) { it.uppercase(Locale.ROOT) }
    return catalog.entries.asSequence()
        .filter { it.availableFromEnchanter && it.group.uppercase(Locale.ROOT) in allowedGroups }
        .mapNotNull { entry ->
            val levels = configuredLevels(entry.id).filter { it > 0 }.distinct().sorted()
            AdvancedBookCandidate(entry.id, levels).takeIf { levels.isNotEmpty() }?.let { entry.group.uppercase(Locale.ROOT) to it }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, candidates) -> candidates.sortedBy(AdvancedBookCandidate::id) }
}

internal fun selectWeightedLootGroup(
    weights: Map<String, Int>,
    eligibleGroups: Set<String>,
    roll: Int,
): String? {
    val active = weights.entries.asSequence()
        .filter { it.value > 0 && it.key.uppercase(Locale.ROOT) in eligibleGroups }
        .map { it.key.uppercase(Locale.ROOT) to it.value }
        .toList()
    val total = active.sumOf { it.second.toLong() }
    if (total <= 0) return null
    require(total <= Int.MAX_VALUE && roll in 0 until total.toInt())
    var position = roll
    for ((group, weight) in active) {
        if (position < weight) return group
        position -= weight
    }
    return null
}

internal fun weightedLootGroupTotal(weights: Map<String, Int>, eligibleGroups: Set<String>): Int =
    weights.entries.asSequence()
        .filter { it.value > 0 && it.key.uppercase(Locale.ROOT) in eligibleGroups }
        .sumOf { it.value.toLong() }
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

/** Append a clone as one physical bonus drop without replacing native drops. */
internal fun appendSingleBonusDrop(drops: MutableList<ItemStack>, reward: ItemStack) {
    require(!reward.type.isAir && reward.amount == 1)
    drops.add(reward.clone())
}

/** Natural-world acquisition only; provider-owned AE factories create both reward types. */
internal class AdvancedEnchantmentLoot(
    private val settings: AdvancedEnchantmentLootSettings,
    private val catalog: () -> HelpCenterEnchantmentsCatalog = HelpCenterEnchantmentsCatalog::fromAeApi,
    private val configuredLevels: (String) -> List<Int> = ::configuredAdvancedBookLevels,
    private val createBook: (String, Int, Player) -> ItemStack = ::createFreshAdvancedBook,
    private val createDust: (String, Int) -> ItemStack? = AeNativeItems::createMagicDust,
    private val nextDouble: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    private val nextInt: (Int) -> Int = { bound -> ThreadLocalRandom.current().nextInt(bound) },
    private val isEliteMobsEntity: (Entity) -> Boolean = { entity ->
        Bukkit.getPluginManager().isPluginEnabled("EliteMobs") && EntityTracker.isEliteMob(entity)
    },
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val afterEvent: (() -> Unit) -> Unit = { task -> tasks.runLater(1) { task() }; Unit },
    private val deliverFishingReward: (ItemStack, Player) -> Unit = { reward, player ->
        val result = Treasures.service.give(Treasure.Item(reward, min = 1, max = 1), player)
        check(result is ru.arc.treasure.core.GiveResult.Success) { "Fishing bonus delivery: $result" }
    },
) : Listener, AutoCloseable {
    private val failureLogged = AtomicBoolean(false)
    private var bookCandidates: Map<String, List<AdvancedBookCandidate>>? = null
    private var bookCandidatesLoaded = false

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onNaturalEnemyDeath(event: EntityDeathEvent) {
        val mob = event.entity
        if (mob !is Enemy || mob.entitySpawnReason != CreatureSpawnEvent.SpawnReason.NATURAL) return
        val damage = mob.lastDamageCause as? EntityDamageByEntityEvent ?: return
        val attacker = playerAttacker(damage) ?: return
        val killer = mob.killer ?: return
        val eliteMobsEntity =
            try {
                isEliteMobsEntity(mob)
            } catch (failure: Exception) {
                reportFailure("EliteMobs entity classification", failure)
                return
            } catch (failure: LinkageError) {
                reportFailure("EliteMobs entity classification", failure)
                return
            }
        if (!eligibleNaturalMobLoot(
                survivalBackend = settings.survivalBackend,
                worldAllowed = settings.allowsWorld(mob.world.name),
                gameMode = attacker.gameMode,
                enemy = true,
                naturalSpawn = true,
                playerAttack = true,
                samePlayer = killer.uniqueId == attacker.uniqueId && attacker.world.uid == mob.world.uid && attacker.isOnline,
                eliteMobsEntity = eliteMobsEntity,
            ) || !settings.allowsWorld(attacker.world.name)
        ) return

        val kind = advancedEnchantmentLootKind(nextDouble(), settings.naturalKillBookPercent, settings.naturalKillDustPercent) ?: return
        val reward = createReward(kind, attacker) ?: return
        try {
            appendSingleBonusDrop(event.drops, reward)
        } catch (failure: Exception) {
            reportFailure("natural mob bonus drop", failure)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onOpenWaterCatch(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val player = event.player
        val caught = event.caught as? Item ?: return
        if (!event.hook.isInOpenWater || !isEligiblePlayer(player) || caught.world.uid != player.world.uid) return
        val worldId = player.world.uid
        // Other listeners may still cancel the catch after this handler returns.
        afterEvent {
            if (event.isCancelled || !player.isOnline || player.world.uid != worldId || !isEligiblePlayer(player)) return@afterEvent
            val kind = advancedEnchantmentLootKind(nextDouble(), settings.openWaterBookPercent, settings.openWaterDustPercent) ?: return@afterEvent
            val reward = createReward(kind, player) ?: return@afterEvent
            try {
                deliverFishingReward(reward, player)
            } catch (failure: Exception) {
                reportFailure("open-water fishing bonus delivery", failure)
            }
        }
    }

    override fun close() = tasks.close()

    private fun playerAttacker(event: EntityDamageByEntityEvent): Player? = when (event.cause) {
        EntityDamageEvent.DamageCause.ENTITY_ATTACK,
        EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK,
        -> event.damager as? Player

        EntityDamageEvent.DamageCause.PROJECTILE ->
            (event.damager as? Projectile)?.shooter as? Player

        else -> null
    }

    private fun isEligiblePlayer(player: Player): Boolean =
        settings.allowsWorld(player.world.name) && player.gameMode in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE)

    private fun createReward(kind: AdvancedEnchantmentLootKind, player: Player): ItemStack? {
        return try {
            val item = when (kind) {
                AdvancedEnchantmentLootKind.BOOK -> createBook(player)
                AdvancedEnchantmentLootKind.DUST -> {
                    val groupTotal = weightedLootGroupTotal(settings.normalizedGroupWeights, settings.normalizedGroupWeights.keys)
                    val group = selectWeightedLootGroup(
                        settings.normalizedGroupWeights,
                        settings.normalizedGroupWeights.keys,
                        nextInt(groupTotal),
                    ) ?: error("No weighted public AE group for magic dust")
                    createDust(group, nextInt(15) + 1)
                        ?: error("Native AE magic-dust factory unavailable for group=$group")
                }
            }
            if (item.type.isAir || item.amount != 1) error("AE loot factory returned an invalid single-item stack")
            item
        } catch (failure: Exception) {
            reportFailure("$kind reward materialization", failure)
            null
        } catch (failure: LinkageError) {
            reportFailure("$kind reward materialization", failure)
            null
        }
    }

    private fun createBook(player: Player): ItemStack {
        val candidates = cachedBookCandidates()
        val groupTotal = weightedLootGroupTotal(settings.normalizedGroupWeights, candidates.keys)
        if (groupTotal <= 0) error("No configured public AE book group has eligible enchantments")
        val group = selectWeightedLootGroup(
            settings.normalizedGroupWeights,
            candidates.keys,
            nextInt(groupTotal),
        ) ?: error("No configured public AE book group has eligible enchantments")
        val groupCandidates = candidates.getValue(group)
        val candidate = groupCandidates[nextInt(groupCandidates.size)]
        val level = candidate.levels[nextInt(candidate.levels.size)]
        return createBook(candidate.id, level, player)
    }

    private fun cachedBookCandidates(): Map<String, List<AdvancedBookCandidate>> {
        bookCandidates?.let { return it }
        if (!bookCandidatesLoaded) {
            bookCandidatesLoaded = true
            try {
                val snapshot = catalog()
                bookCandidates = eligibleAdvancedBookCandidates(snapshot, settings.normalizedGroupWeights, configuredLevels)
                if (!snapshot.available || bookCandidates.isNullOrEmpty()) {
                    reportFailure("AE book catalogue has no eligible configured enchantments")
                }
            } catch (failure: Exception) {
                reportFailure("AE book catalogue lookup", failure)
                bookCandidates = emptyMap()
            } catch (failure: LinkageError) {
                reportFailure("AE book catalogue lookup", failure)
                bookCandidates = emptyMap()
            }
        }
        return bookCandidates.orEmpty()
    }

    private fun reportFailure(reason: String, failure: Throwable? = null) {
        if (!failureLogged.compareAndSet(false, true)) return
        if (failure == null) Logging.error("Advanced Enchantment loot unavailable reason={}", reason)
        else Logging.error("Advanced Enchantment loot unavailable reason={}", reason, failure)
    }
}

