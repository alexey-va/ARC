package ru.arc.helpcenter

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import ru.arc.itemcatalog.ItemsCatalogModule
import ru.arc.itemcatalog.RewardCatalogSource
import ru.arc.ops.BundleDefinition
import ru.arc.ops.ItemPresetDefinition
import ru.arc.ops.ItemPresets
import ru.ruscrafting.ecia.api.CrateLocationService
import ru.ruscrafting.ranks.api.RankQuestApi
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level

internal data class HelpCenterExternalEnchantmentReward(
    val kind: String,
    val sourceId: String,
    val sourceName: String,
    val probability: Double,
    val outcomes: List<HelpCenterEnchantmentRewardOutcome>,
)

/** Optional typed providers are isolated so their absence cannot prevent help from loading. */
internal object HelpCenterEnchantmentExternalSources {
    private const val MAX_CRATES = 64
    private const val MAX_PRESETS = 256
    private const val MAX_CATALOG_ENTRIES = 2048
    private val reportedFailures = ConcurrentHashMap.newKeySet<String>()

    fun describe(topicId: String, local: HelpCenterEnchantmentSourceSnapshot): CompletableFuture<HelpCenterEnchantmentAcquisition> {
        check(Bukkit.isPrimaryThread())
        val ranks = if (provider("ArcRanks", "ru.ruscrafting.ranks.api.RankQuestApi\$ItemRewardSourceSnapshot") != null) {
            try { Ranks.capture() } catch (failure: Exception) { report("rank-quests", failure); QuestSnapshot(false, emptyList()) }
            catch (failure: LinkageError) { report("rank-quests", failure); QuestSnapshot(false, emptyList()) }
        } else QuestSnapshot(false, emptyList())
        val catalog = ItemsCatalogModule.activeRewardSettings()
        val catalogEntries = catalog?.categories?.filter { it.rolls == 1 }?.flatMap { category -> category.entries.map { (category.id to it.id) to it.source } }
        val requested = (catalogEntries.orEmpty().mapNotNull { (_, source) -> (source as? RewardCatalogSource.Preset)?.id } + ranks.sources.map(Quest::preset)).distinct()
        val additional = if (requested.size <= MAX_PRESETS) ItemPresets.loadedDefinitions(requested) else null
        val bundleItems = additional.orEmpty().filterIsInstance<BundleDefinition>().flatMap { it.items.map { entry -> entry.preset } }.distinct()
        val bundleDefinitions = if (bundleItems.size <= MAX_PRESETS) ItemPresets.loadedDefinitions(bundleItems) else null
        val prepared = local.copy(presets = if (additional != null && bundleDefinitions != null) {
            (local.presets.orEmpty() + additional + bundleDefinitions).distinctBy(ItemPresetDefinition::id)
        } else local.presets)
        val catalogOutcomes = if (catalogEntries != null && catalogEntries.size <= MAX_CATALOG_ENTRIES) catalogEntries.associate { (ref, source) ->
            ref to HelpCenterEnchantmentSources.rewardOutcomes(source, prepared)
        } else null
        val rankRewards = ranks.sources.map { quest ->
            HelpCenterExternalEnchantmentReward("quest", quest.id, plain(quest.title), 1.0,
                HelpCenterEnchantmentSources.rewardOutcomes(RewardCatalogSource.Preset(quest.preset), prepared, quest.amount),
            )
        }
        val unavailable = buildList {
            if (!ranks.available) add("rank-quests")
            if (catalogOutcomes == null) add("reward-catalog")
            if (additional == null || bundleDefinitions == null) add("item-presets")
        }
        val crateProvider = provider("ArcExcellentCrates", "ru.ruscrafting.ecia.api.CrateAcquisitionSnapshot")
        val crates = if (crateProvider != null && catalogOutcomes != null) {
            try { Crates.capture(crateProvider, catalogOutcomes) }
            catch (failure: Exception) { report("crate-rewards", failure); CompletableFuture.completedFuture(CrateSnapshot(emptyList(), listOf("crate-rewards"))) }
            catch (failure: LinkageError) { report("crate-rewards", failure); CompletableFuture.completedFuture(CrateSnapshot(emptyList(), listOf("crate-rewards"))) }
        } else CompletableFuture.completedFuture(CrateSnapshot(emptyList(), listOf("crate-rewards")))
        return crates.handle { crate, failure ->
            if (failure != null) {
                report("crate-rewards", failure)
                summarize(topicId, rankRewards, unavailable + "crate-rewards")
            } else summarize(topicId, rankRewards + crate.rewards, unavailable + crate.unavailable)
        }
    }

    /** All rows from one case are exclusive; each quest row is a completion reward candidate. */
    internal fun summarize(
        topicId: String,
        rewards: List<HelpCenterExternalEnchantmentReward>,
        unavailable: List<String> = emptyList(),
    ): HelpCenterEnchantmentAcquisition {
        val facts = mutableListOf<HelpCenterEnchantmentSourceFact>()
        val related = linkedSetOf<String>()
        rewards.groupBy { it.kind to it.sourceId }.forEach { (_, sourceRows) ->
            val topics = if (topicId == "overview") HelpCenterEnchantmentSources.boxTopics else listOf(topicId)
            for (topic in topics) {
                val matches = sourceRows.flatMap { row -> row.outcomes.filter { it.topicId == topic }.map { it to row.probability * it.probability } }
                if (matches.isEmpty()) continue
                val probability = matches.sumOf { it.second }
                if (probability <= 0.0 || probability > 1.00000001 || !probability.isFinite()) continue
                val minimum = matches.minOf { it.first.amount }
                val maximum = matches.maxOf { it.first.amount }
                val first = sourceRows.first()
                val values = mapOf("source" to first.sourceId, "item" to topic,
                    "amount" to if (minimum == maximum) minimum.toString() else "$minimum–$maximum",
                )
                facts += if (first.kind == "quest") HelpCenterEnchantmentSourceFact("quest-source", values + mapOf("quest" to first.sourceName))
                else HelpCenterEnchantmentSourceFact("crate-source", values + mapOf("crate" to first.sourceName, "chance" to number(probability.coerceAtMost(1.0) * 100.0)))
                if (topicId == "overview") related += topic
            }
        }
        facts += unavailable.distinct().map { HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to it)) }
        return HelpCenterEnchantmentAcquisition(facts, related.toList())
    }

    private data class Quest(val id: String, val title: String, val preset: String, val amount: Int)
    private data class QuestSnapshot(val available: Boolean, val sources: List<Quest>)
    private data class CrateSnapshot(val rewards: List<HelpCenterExternalEnchantmentReward>, val unavailable: List<String>)

    private object Ranks {
        fun capture(): QuestSnapshot {
            val owner = Bukkit.getPluginManager().getPlugin("ArcRanks") ?: return QuestSnapshot(false, emptyList())
            val registration = Bukkit.getServicesManager().getRegistration(RankQuestApi::class.java)
                ?.takeIf { it.plugin === owner } ?: return QuestSnapshot(false, emptyList())
            val loaded = registration.provider.itemRewardSources()
            if (!loaded.available() || loaded.sources().size > MAX_CATALOG_ENTRIES) return QuestSnapshot(false, emptyList())
            return QuestSnapshot(true, loaded.sources().map { Quest(it.questId(), it.title(), it.presetId(), it.amount()) })
        }
    }

    private object Crates {
        fun capture(owner: Plugin, catalog: Map<Pair<String, String>, List<HelpCenterEnchantmentRewardOutcome>>): CompletableFuture<CrateSnapshot> {
            val registration = Bukkit.getServicesManager().getRegistration(CrateLocationService::class.java)
                ?.takeIf { it.plugin === owner } ?: return CompletableFuture.completedFuture(CrateSnapshot(emptyList(), listOf("crate-rewards")))
            val service = registration.provider
            val ids = service.acquisitionCrateIds().distinct()
            if (ids.isEmpty() || ids.size > MAX_CRATES) return CompletableFuture.completedFuture(CrateSnapshot(emptyList(), listOf("crate-rewards")))
            var combined = CompletableFuture.completedFuture(CrateSnapshot(emptyList(), emptyList()))
            for (id in ids) {
                val captured = service.acquisitionSnapshotAsync(id).handle { snapshot, failure ->
                    if (failure != null) {
                        report("crate-rewards", failure)
                        CrateSnapshot(emptyList(), listOf("crate-rewards"))
                    } else if (snapshot == null || !snapshot.available()) CrateSnapshot(emptyList(), listOf("crate:$id"))
                    else CrateSnapshot(snapshot.rewards().map { reward ->
                        HelpCenterExternalEnchantmentReward("crate", snapshot.crateId(), plain(snapshot.displayName()), reward.probability(),
                            catalog[reward.catalogCategoryId() to reward.catalogEntryId()].orEmpty())
                    }, emptyList())
                }.completeOnTimeout(CrateSnapshot(emptyList(), listOf("crate:$id")), 10, TimeUnit.SECONDS)
                combined = combined.thenCombine(captured) { before, next -> CrateSnapshot(before.rewards + next.rewards, before.unavailable + next.unavailable) }
            }
            return combined
        }
    }

    /** Class-presence guard only; first-party calls below use their compile-time API. */
    private fun provider(name: String, apiClass: String): Plugin? {
        val owner = Bukkit.getPluginManager().getPlugin(name)?.takeIf(Plugin::isEnabled) ?: return null
        return try {
            Class.forName(apiClass, false, HelpCenterEnchantmentExternalSources::class.java.classLoader)
            owner
        } catch (_: ClassNotFoundException) { null }
        catch (_: LinkageError) { null }
    }

    private fun plain(value: String): String = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(
        value.replace(Regex("(?i)[&§][0-9A-FK-ORX]"), ""),
    ))
    private fun number(value: Double) = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')
    private fun report(source: String, failure: Throwable) {
        if (reportedFailures.add(source)) ARC.instance.logger.log(Level.WARNING, "ARC help acquisition snapshot failed: source=$source", failure)
    }
}
