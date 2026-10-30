package ru.arc.helpcenter

import com.google.gson.JsonElement
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.bschests.PersonalLootModule
import ru.arc.bschests.PersonalLootPoolSettings
import ru.arc.config.ConfigManager
import ru.arc.core.dataPath
import ru.arc.enchanting.AdvancedEnchantmentLootSettings
import ru.arc.enchanting.EnchantingModule
import ru.arc.enchanting.advancedEnchantmentWildDustPotency
import ru.arc.ops.BundleDefinition
import ru.arc.ops.ItemDefinition
import ru.arc.ops.ItemPresetDefinition
import ru.arc.ops.ItemPresets
import ru.arc.itemcatalog.RewardCatalogSource
import ru.arc.treasure.core.AeArg
import ru.arc.treasure.core.AeKind
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.TreasureRollRange
import ru.arc.treasure.core.Treasures
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.math.pow

/** Immutable, unformatted facts from the currently loaded acquisition owners. */
internal data class HelpCenterEnchantmentSourceFact(
    val key: String,
    val values: Map<String, String> = emptyMap(),
)

internal data class HelpCenterEnchantmentAcquisition(
    val facts: List<HelpCenterEnchantmentSourceFact>,
    val relatedTopics: List<String> = emptyList(),
)

internal data class HelpCenterEnchantmentBossReward(val enabled: Boolean, val preset: String)

internal data class HelpCenterEnchantmentSourceSnapshot(
    val pools: List<TreasurePool>?,
    val presets: List<ItemPresetDefinition>?,
    val naturalLoot: AdvancedEnchantmentLootSettings?,
    val bossReward: HelpCenterEnchantmentBossReward? = null,
    val serverName: String = "",
    val personalLoot: PersonalLootPoolSettings? = null,
)

internal data class HelpCenterEnchantmentRewardOutcome(val topicId: String, val probability: Double, val amount: Int)

/** Main-thread, memory-only view; no reward factories, commands, file reads or player state. */
internal object HelpCenterEnchantmentSources {
    internal val boxTopics = listOf("ae_lootbox", "ae_lootbox_enchanted", "ae_lootbox_master", "enchant_supply", "enchant_boss_supply")
    private const val MAX_POOLS = 256
    private const val MAX_ROWS = 4096
    private val externalFailureLogged = AtomicBoolean(false)

    fun describe(topicId: String): HelpCenterEnchantmentAcquisition = describeLoaded(topicId, snapshot())

    fun describeAsync(topicId: String): CompletableFuture<HelpCenterEnchantmentAcquisition> {
        val snapshot = snapshot()
        val local = describeLoaded(topicId, snapshot)
        val logger = ARC.instance.logger
        return withExternal(local, { HelpCenterEnchantmentExternalSources.describe(topicId, snapshot) }) { failure ->
            if (externalFailureLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "ARC help external acquisition snapshot failed; keeping loaded local source facts", failure)
            }
        }
    }

    internal fun withExternal(
        local: HelpCenterEnchantmentAcquisition,
        request: () -> CompletableFuture<HelpCenterEnchantmentAcquisition>,
        reportFailure: (Throwable) -> Unit,
    ): CompletableFuture<HelpCenterEnchantmentAcquisition> {
        val result = CompletableFuture<HelpCenterEnchantmentAcquisition>()
        fun failed(failure: Throwable) {
            var cause = failure
            while (cause is CompletionException) cause = cause.cause ?: break
            if (cause is CancellationException) result.cancel(false)
            else {
                reportFailure(cause)
                result.complete(merge(local, HelpCenterEnchantmentAcquisition(listOf(unavailable("external-sources")))))
            }
        }
        val external = try { request() }
        catch (failure: Exception) { failed(failure); return result }
        catch (failure: LinkageError) { failed(failure); return result }
        external.whenComplete { facts, failure ->
            if (!result.isCancelled) {
                if (failure != null) failed(failure) else result.complete(merge(local, facts))
            }
        }
        return result
    }

    private fun merge(local: HelpCenterEnchantmentAcquisition, external: HelpCenterEnchantmentAcquisition): HelpCenterEnchantmentAcquisition {
        val localFacts = local.facts.filterNot { it.key == "unavailable" && it.values["source"] in setOf("rank-quests", "crate-rewards") }
        return HelpCenterEnchantmentAcquisition(
            (localFacts + external.facts).distinct(), (local.relatedTopics + external.relatedTopics).distinct(),
        )
    }

    private fun snapshot(): HelpCenterEnchantmentSourceSnapshot {
        check(Bukkit.isPrimaryThread()) { "Enchantment acquisition snapshots require the main thread" }
        val pools = Treasures.loadedPools()?.takeIf { pools ->
            pools.size <= MAX_POOLS && pools.sumOf { it.treasures.size.toLong() } <= MAX_ROWS
        }
        val presetIds = boxTopics + pools.orEmpty().flatMap { pool -> pool.treasures.filterIsInstance<Treasure.Preset>().map { it.preset } }
        val definitions = ItemPresets.loadedDefinitions(presetIds)
        val bundleItems = definitions.orEmpty().filterIsInstance<BundleDefinition>().flatMap { it.items.map { entry -> entry.preset } }
        val presets = definitions?.let { (it + ItemPresets.loadedDefinitions(bundleItems).orEmpty()).distinctBy(ItemPresetDefinition::id) }
        val boss = if (Bukkit.getPluginManager().isPluginEnabled("ArcDungeons") && Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) {
            ConfigManager.get("${ARC.instance.dataPath}/modules/elitemobs.yml")?.let { config ->
                HelpCenterEnchantmentBossReward(
                    config.bool("boss-enchantment-rewards.enabled", true),
                    config.string("boss-enchantment-rewards.preset", "enchant_boss_supply").trim(),
                )
            }
        } else null
        return HelpCenterEnchantmentSourceSnapshot(
            pools, presets, EnchantingModule.activeLootSettings(), boss, ARC.serverName.orEmpty(), PersonalLootModule.activePoolLootSettings(),
        )
    }

    private fun describeLoaded(topicId: String, snapshot: HelpCenterEnchantmentSourceSnapshot): HelpCenterEnchantmentAcquisition {
        val result = describe(topicId, snapshot)
        val native = if (topicId.lowercase(Locale.ROOT) in ITEM_TOPICS) HelpCenterAeNativeSources.describe(topicId) else emptyList()
        return result.copy(facts = result.facts + native + HelpCenterDenizenSources.describe(topicId))
    }

    internal fun describe(topicId: String, snapshot: HelpCenterEnchantmentSourceSnapshot): HelpCenterEnchantmentAcquisition {
        val topic = topicId.lowercase(Locale.ROOT)
        val pools = snapshot.pools.orEmpty().associateBy(TreasurePool::id)
        val definitions = snapshot.presets.orEmpty().associateBy(ItemPresetDefinition::id)
        val boxes = boxTopics.mapNotNull { id -> (definitions[id] as? ItemDefinition)?.let(::box) }
        var remainingRows = MAX_ROWS
        val outcomes = pools.mapValues { (_, pool) -> poolOutcomes(pool, pools, definitions) { remainingRows-- > 0 } }
        val missingPools = (boxes.map(Box::pool) - pools.keys) + outcomes.filterValues { it == null }.keys
        val facts = mutableListOf<HelpCenterEnchantmentSourceFact>()
        val related = linkedSetOf<String>()
        if (snapshot.serverName.isNotBlank()) facts += fact("scope", "server" to snapshot.serverName)

        if (topic == "overview" || topic == "magic") {
            val settings = snapshot.naturalLoot
            if (settings != null && settings.survivalBackend && settings.worldNames.isNotEmpty()) {
                val shared = mapOf("worlds" to settings.worldNames.sorted().joinToString(", "))
                if (topic == "overview") facts += fact("natural-books", shared + mapOf(
                    "kill" to number(settings.naturalKillBookPercent), "fishing" to number(settings.openWaterBookPercent), "eligibility" to "configured-candidates",
                ))
                facts += fact("natural-dust", shared + mapOf(
                    "kill" to number(settings.naturalKillDustPercent), "fishing" to number(settings.openWaterDustPercent),
                    "min" to advancedEnchantmentWildDustPotency.first.toString(), "max" to advancedEnchantmentWildDustPotency.last.toString(),
                ))
            } else facts += if (settings == null) unavailable("natural-loot") else fact("natural-inactive")
        }

        if (topic == "overview") {
            related += boxes.map(Box::id)
            facts += unavailable("rank-quests")
            facts += unavailable("crate-rewards")
        } else if (topic in boxTopics) {
            val selected = boxes.find { it.id == topic }
            if (selected == null) facts += unavailable("item-presets")
            else {
                facts += fact("box-rolls", "min" to selected.rolls.minimum.toString(), "max" to selected.rolls.maximum.toString(), "pool" to selected.pool)
                val contents = outcomes[selected.pool]
                if (contents == null) facts += unavailable(selected.pool)
                else contents.forEach { outcome ->
                    facts += fact("box-content", outcome.values() + mapOf("per_roll" to percent(outcome.probability)))
                    if (outcome.shape.item in ITEM_TOPICS) related += outcome.shape.item
                }
            }
            outcomes.forEach { (poolId, entries) ->
                val found = entries.orEmpty().filter { it.shape.item == "preset:$topic" }
                if (found.isNotEmpty()) facts += fact("pool-source",
                    "pool" to poolId, "per_roll" to percent(found.sumOf(Outcome::probability)), "amount" to amount(found),
                )
            }
            snapshot.bossReward?.takeIf { it.enabled && it.preset == topic }?.let {
                facts += fact("boss-source", "chance" to "100", "amount" to "1")
            }
            if (topic == "enchant_boss_supply" && snapshot.bossReward == null) facts += unavailable("boss-rewards")
        } else {
            var found = false
            boxes.forEach { box ->
                val matches = outcomes[box.pool].orEmpty().filter { it.shape.item == topic }
                if (matches.isEmpty()) return@forEach
                found = true
                val probability = matches.sumOf(Outcome::probability)
                facts += fact("pool-item", "box" to box.id, "pool" to box.pool,
                    "per_roll" to percent(probability), "per_box" to percent(atLeastOne(probability, box.rolls)), "amount" to amount(matches),
                )
                matches.forEach { outcome -> facts += fact("pool-item-value", outcome.values() + mapOf("box" to box.id)) }
                related += box.id
            }
            outcomes.filterKeys { poolId -> boxes.none { it.pool == poolId } }.forEach { (poolId, entries) ->
                val matches = entries.orEmpty().filter { it.shape.item == topic }
                if (matches.isNotEmpty()) {
                    found = true
                    facts += fact("pool-source", "pool" to poolId, "per_roll" to percent(matches.sumOf(Outcome::probability)), "amount" to amount(matches))
                }
            }
            if (!found && snapshot.pools != null && snapshot.presets != null && missingPools.isEmpty()) facts += fact("no-drop")
        }
        snapshot.personalLoot?.let { settings ->
            val valid = settings.commonPool.isNotEmpty() && settings.minRolls in 1..50 && settings.maxRolls in settings.minRolls..50 &&
                settings.bonusChance.isFinite() && settings.bonusChance in 0.0..1.0 && (settings.bonusChance == 0.0 || settings.bonusPool.isNotEmpty())
            val common = outcomes[settings.commonPool]
            val bonus = if (settings.bonusChance > 0.0) outcomes[settings.bonusPool] else emptyList()
            if (!valid || common == null || bonus == null) facts += unavailable("personal-loot")
            else {
                val items = if (topic == "overview") boxes.map { "preset:${it.id}" } else listOf(if (topic in boxTopics) "preset:$topic" else topic)
                items.forEach { item ->
                    val perCommonRoll = common.filter { it.shape.item == item }.sumOf(Outcome::probability)
                    val perBonusRoll = bonus.filter { it.shape.item == item }.sumOf(Outcome::probability)
                    val chance = 1.0 - (1.0 - atLeastOne(perCommonRoll, TreasureRollRange(settings.minRolls, settings.maxRolls))) *
                        (1.0 - settings.bonusChance * perBonusRoll)
                    if (chance > 0.0) facts += fact("personal-loot-source", "item" to item.removePrefix("preset:"), "chance" to percent(chance),
                        "common_pool" to settings.commonPool, "bonus_pool" to settings.bonusPool, "min_rolls" to settings.minRolls.toString(),
                        "max_rolls" to settings.maxRolls.toString(), "bonus" to number(settings.bonusChance * 100.0),
                    )
                }
            }
        }
        missingPools.distinct().forEach { facts += unavailable(it) }
        if (snapshot.pools == null) facts += unavailable("treasure-pools")
        if (snapshot.presets == null) facts += unavailable("item-presets")
        return HelpCenterEnchantmentAcquisition(facts.distinct(), related.toList())
    }

    private data class Box(val id: String, val pool: String, val rolls: TreasureRollRange)

    private fun box(definition: ItemDefinition): Box? {
        val data = definition.item.getAsJsonObject("customData") ?: return null
        val pool = data.get("arc:treasure_key")?.asString?.takeIf(String::isNotBlank) ?: return null
        return Box(definition.id, pool, TreasureRollRange.resolve(
            data.get("arc:treasure_rolls_min")?.integer(), data.get("arc:treasure_rolls_max")?.integer(),
        ))
    }

    private data class Shape(
        val item: String,
        val amount: Int,
        val valueKind: String = "none",
        val minimum: Int? = null,
        val maximum: Int? = null,
        val group: String = "",
        val level: Int? = null,
    )

    /** Resolve the named catalogue recipe itself, without weighted selection or materializing an item. */
    internal fun rewardOutcomes(
        source: RewardCatalogSource,
        snapshot: HelpCenterEnchantmentSourceSnapshot,
        presetAmount: Int = 1,
    ): List<HelpCenterEnchantmentRewardOutcome> {
        val pools = snapshot.pools.orEmpty().associateBy(TreasurePool::id)
        val treasure = when (source) {
            is RewardCatalogSource.Preset -> Treasure.Preset(source.id, presetAmount.coerceIn(1, 64))
            is RewardCatalogSource.Treasure -> pools[source.pool]?.findById(source.id) ?: return emptyList()
            else -> return emptyList()
        }
        val selected = TreasurePool("help_catalog_recipe", listOf(treasure.withWeight(1)))
        var remainingRows = MAX_ROWS
        return poolOutcomes(selected, pools, snapshot.presets.orEmpty().associateBy(ItemPresetDefinition::id)) { remainingRows-- > 0 }
            .orEmpty().map { HelpCenterEnchantmentRewardOutcome(it.shape.item.removePrefix("preset:"), it.probability, it.shape.amount) }
    }

    private data class Outcome(val shape: Shape, val probability: Double) {
        fun values(): Map<String, String> = mapOf(
            "item" to shape.item.removePrefix("preset:"), "amount" to shape.amount.toString(),
            "valueKind" to shape.valueKind, "min" to shape.minimum?.toString().orEmpty(), "max" to shape.maximum?.toString().orEmpty(),
            "group" to shape.group, "level" to shape.level?.toString().orEmpty(),
        )
    }

    /** One pool draw is exclusive. Sub-pool weights multiply; repeated identical rows add. */
    private fun poolOutcomes(
        pool: TreasurePool,
        pools: Map<String, TreasurePool>,
        presets: Map<String, ItemPresetDefinition>,
        consumeRow: () -> Boolean,
    ): List<Outcome>? {
        fun visit(current: TreasurePool, ancestors: Set<String>): List<Outcome>? {
            if (current.id in ancestors || ancestors.size >= 8) return null
            val positive = current.treasures.filter { it.weight > 0 }
            val total = positive.sumOf { it.weight.toLong() }
            if (total !in 1L..Int.MAX_VALUE.toLong()) return emptyList()
            val result = mutableListOf<Outcome>()
            for (treasure in positive) {
                if (!consumeRow()) return null
                val probability = treasure.weight.toDouble() / total
                when (treasure) {
                    is Treasure.Ae -> result += aeOutcomes(treasure).map { Outcome(it.first, probability * it.second) }
                    is Treasure.Preset -> when (val preset = presets[treasure.preset]) {
                        is BundleDefinition -> preset.items.groupBy { it.preset }.forEach { (id, entries) ->
                            if (presets[id] !is ItemDefinition) return null
                            result += Outcome(Shape("preset:$id", entries.sumOf { it.amount ?: treasure.amount }), probability)
                        }
                        is ItemDefinition -> result += Outcome(Shape("preset:${treasure.preset}", treasure.amount), probability)
                        null -> return null
                    }
                    is Treasure.SubPool -> {
                        val child = pools[treasure.poolId] ?: return null
                        val contents = visit(child, ancestors + current.id) ?: return null
                        result += contents.map { it.copy(probability = it.probability * probability) }
                    }
                    else -> Unit
                }
            }
            return result
        }
        return visit(pool, emptySet())?.groupBy(Outcome::shape)?.map { (shape, rows) -> Outcome(shape, rows.sumOf(Outcome::probability)) }
    }

    private fun aeOutcomes(treasure: Treasure.Ae): List<Pair<Shape, Double>> {
        if (treasure.kind == AeKind.RANDOM_BOOK) return listOf(Shape("book", treasure.amount,
            group = treasure.group ?: "random", level = treasure.maxLevel,
        ) to 1.0)
        val nativeId = treasure.itemName?.lowercase(Locale.ROOT) ?: return emptyList()
        val id = if (nativeId == "renametag") "itemnametag" else nativeId
        val value = treasure.args.filterIsInstance<AeArg.IntRange>().firstOrNull()
        val kind = when (id) { "magic" -> "dust"; "blackscroll" -> "blackscroll"; "soulgem" -> "souls"; "orb" -> "orb-limit"; else -> "none" }
        if (id == "orb") {
            if (treasure.args.firstOrNull() != AeArg.RandomSlot) return emptyList()
            return listOf("orb_weapon", "orb_armor", "orb_tool").map {
                Shape(it, treasure.amount, kind, value?.min, value?.max) to 1.0 / 3.0
            }
        }
        if (id !in ITEM_TOPICS) return emptyList()
        return listOf(Shape(id, treasure.amount, kind, value?.min, value?.max,
            group = if (AeArg.RandomTier in treasure.args) "random" else "",
        ) to 1.0)
    }

    /** The roll count itself is selected uniformly, then every reward draw is independent. */
    internal fun atLeastOne(probability: Double, rolls: TreasureRollRange): Double {
        require(probability.isFinite() && probability in 0.0..1.0)
        return (rolls.minimum..rolls.maximum).map { count -> 1.0 - (1.0 - probability).pow(count) }.average()
    }

    private fun amount(outcomes: List<Outcome>): String {
        val minimum = outcomes.minOf { it.shape.amount }
        val maximum = outcomes.maxOf { it.shape.amount }
        return if (minimum == maximum) minimum.toString() else "$minimum–$maximum"
    }

    private fun JsonElement.integer(): Int? = if (isJsonPrimitive && asJsonPrimitive.isNumber) runCatching { asInt }.getOrNull() else null
    private fun percent(probability: Double): String = number(probability * 100.0)
    private fun number(value: Double): String = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')
    private fun fact(key: String, vararg values: Pair<String, String>) = HelpCenterEnchantmentSourceFact(key, values.toMap())
    private fun fact(key: String, values: Map<String, String>) = HelpCenterEnchantmentSourceFact(key, values)
    private fun unavailable(source: String) = fact("unavailable", "source" to source)

    private val ITEM_TOPICS = setOf("whitescroll", "blackscroll", "magic", "holywhitescroll", "secret", "mystery", "randomizer", "transmog", "soulgem", "soultracker", "slotincreaser", "blocktrak", "stattrak", "mobtrak", "fishtrak", "itemnametag", "orb_weapon", "orb_armor", "orb_tool")
}
