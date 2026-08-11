package ru.arc.audit

import java.util.Locale

/** Bounded source labels used by persistence, Prometheus and ops reports. */
enum class EconomySource(val label: String, val type: Type) {
    JOBS("jobs", Type.JOB),
    SHOP("shop", Type.SHOP),
    AUTOSELL("autosell", Type.SHOP),
    CUSTOM_FISHING("custom_fishing", Type.OTHER),
    CRATES("crates", Type.OTHER),
    BATTLE_PASS("battle_pass", Type.OTHER),
    REFERRAL("referral", Type.OTHER),
    VOTING("voting", Type.OTHER),
    PLAYER_TRANSFER("player_transfer", Type.PAY),
    QUICKSHOP("quickshop", Type.CHEST_SHOP),
    AUCTION("auction", Type.AUCTION),
    PLAYER_AUCTIONS("player_auctions", Type.AUCTION),
    BANK("bank", Type.BANK),
    LANDS("lands", Type.LAND),
    PLAYER_WARPS("player_warps", Type.PLAYER_WARP),
    QUESTS("quests", Type.QUEST),
    GAMBLING("gambling", Type.GAMBLING),
    CMI("cmi", Type.CMI),
    ARC("arc", Type.ARC),
    HUSKHOMES("huskhomes", Type.OTHER),
    GRAVES("graves", Type.OTHER),
    WILDLOADERS("wildloaders", Type.OTHER),
    ELITEMOBS("elitemobs", Type.OTHER),
    ADVANCED_ENCHANTMENTS("advanced_enchantments", Type.OTHER),
    TREASURE("treasure", Type.OTHER),
    LOOT("loot", Type.OTHER),
    DENIZEN("denizen", Type.OTHER),
    ADMIN_COMMAND("admin_command", Type.COMMAND),
    BALANCE_SET("balance_set", Type.BALANCE_SET),
    INTERNAL_STOCK("internal_stock", Type.STOCK),
    LEGACY("legacy", Type.OTHER),
    UNKNOWN("unknown", Type.OTHER),
}

enum class EconomyFlow(val label: String) {
    MINT("mint"),
    BURN("burn"),
    TRANSFER("transfer"),
    ADJUSTMENT("adjustment"),
    INTERNAL("internal"),
    UNKNOWN("unknown"),
}

data class AuditMetadata(
    val source: EconomySource,
    val flow: EconomyFlow,
    val currency: String = "vault",
    val server: String = "unknown",
    val origin: String = "",
) {
    companion object {
        fun legacy(): AuditMetadata = AuditMetadata(EconomySource.LEGACY, EconomyFlow.UNKNOWN)
    }
}

data class EconomyAttribution(
    val metadata: AuditMetadata,
    val type: Type,
    val reason: String,
)

/** Converts RedisEconomy's call trace into stable low-cardinality audit labels. */
object EconomyAttributionResolver {
    private val transferSources =
        setOf(
            EconomySource.PLAYER_TRANSFER,
            EconomySource.QUICKSHOP,
            EconomySource.AUCTION,
            EconomySource.PLAYER_AUCTIONS,
            EconomySource.BANK,
            EconomySource.PLAYER_WARPS,
            EconomySource.INTERNAL_STOCK,
        )

    fun resolve(
        rawReason: String?,
        amount: Double,
        currency: String?,
        server: String?,
    ): EconomyAttribution {
        val lines = rawReason.orEmpty().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val reason = lines.firstOrNull().orEmpty().ifBlank { "Unspecified" }
        val origin = lines.firstOrNull { it.startsWith("Call:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
        val source = source(reason, origin)
        val flow = flow(reason, source, amount)
        val metadata =
            AuditMetadata(
                source = source,
                flow = flow,
                currency = normalizeLabel(currency, "vault"),
                server = normalizeLabel(server, "unknown"),
                origin = origin.take(240),
            )
        return EconomyAttribution(metadata, source.type, reason.take(240))
    }

    private fun source(reason: String, origin: String): EconomySource {
        val haystack = "$reason $origin".lowercase(Locale.ROOT)
        return when {
            reason.equals("Payment", ignoreCase = true) -> EconomySource.PLAYER_TRANSFER
            haystack.contains("reset balance") || haystack.contains("set balance") -> EconomySource.BALANCE_SET
            haystack.contains("commandgive") || haystack.contains("commandtake") -> EconomySource.ADMIN_COMMAND
            haystack.contains("gamingmesh.jobs") || haystack.contains("zrips.jobs") -> EconomySource.JOBS
            haystack.contains("autosellchest") -> EconomySource.AUTOSELL
            haystack.contains("economyshopgui") -> EconomySource.SHOP
            haystack.contains("customfishing") -> EconomySource.CUSTOM_FISHING
            haystack.contains("excellentcrates") -> EconomySource.CRATES
            haystack.contains("battlepass") -> EconomySource.BATTLE_PASS
            haystack.contains("referral") -> EconomySource.REFERRAL
            haystack.contains("votifier") || haystack.contains("votingplugin") -> EconomySource.VOTING
            haystack.contains("quickshop") -> EconomySource.QUICKSHOP
            haystack.contains("zauction") -> EconomySource.AUCTION
            haystack.contains("olziedev.playerauctions") -> EconomySource.PLAYER_AUCTIONS
            haystack.contains("dablakbandit.bank") -> EconomySource.BANK
            haystack.contains("angeschossen.lands") -> EconomySource.LANDS
            haystack.contains("olziedev.playerwarps") -> EconomySource.PLAYER_WARPS
            haystack.contains("leonardobishop.quests") -> EconomySource.QUESTS
            haystack.contains("blackjack") || haystack.contains("roulette") || haystack.contains("slotmachine") -> EconomySource.GAMBLING
            haystack.contains("zrips.cmi") -> EconomySource.CMI
            haystack.contains("ru.arc.stock") -> EconomySource.INTERNAL_STOCK
            haystack.contains("ru.arc.treasure.") -> EconomySource.TREASURE
            haystack.contains("ru.arc.") -> EconomySource.ARC
            haystack.contains("william278.huskhomes") -> EconomySource.HUSKHOMES
            haystack.contains("graves") -> EconomySource.GRAVES
            haystack.contains("wildloaders") -> EconomySource.WILDLOADERS
            haystack.contains("elitemobs") -> EconomySource.ELITEMOBS
            haystack.contains("net.advancedplugins.ae") -> EconomySource.ADVANCED_ENCHANTMENTS
            haystack.contains("lootchest") || haystack.contains("betterstructures") -> EconomySource.LOOT
            haystack.contains("denizenscript") -> EconomySource.DENIZEN
            else -> EconomySource.UNKNOWN
        }
    }

    private fun flow(reason: String, source: EconomySource, amount: Double): EconomyFlow =
        when {
            source == EconomySource.BALANCE_SET -> EconomyFlow.ADJUSTMENT
            source in transferSources -> EconomyFlow.TRANSFER
            reason.contains("set balance", ignoreCase = true) -> EconomyFlow.ADJUSTMENT
            amount > 0.0 -> EconomyFlow.MINT
            amount < 0.0 -> EconomyFlow.BURN
            else -> EconomyFlow.UNKNOWN
        }

    private fun normalizeLabel(value: String?, fallback: String): String {
        val normalized = value.orEmpty().trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]+"), "_")
        return normalized.trim('_').take(32).ifBlank { fallback }
    }
}
