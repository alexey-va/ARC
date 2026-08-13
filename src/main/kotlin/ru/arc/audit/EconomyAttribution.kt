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
    PUBLIC_PROJECTS("public_projects", Type.ARC),
    DUNGEON_ENTRY("dungeon_entry", Type.ARC),
    MOUNTS("mounts", Type.ARC),
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

/** Bounded action labels derived only from an observed delta and trusted ARC context. */
enum class EconomyAction(val label: String) {
    SHOP_BUY("shop_buy"),
    SHOP_SELL("shop_sell"),
    AUTOSELL_SALE("autosell_sale"),
    WALLET_TO_BANK("wallet_to_bank"),
    BANK_TO_WALLET("bank_to_wallet"),
    GAMBLING_WAGER("gambling_wager"),
    GAMBLING_PAYOUT("gambling_payout"),
    TRANSFER_IN("transfer_in"),
    TRANSFER_OUT("transfer_out"),
    ADMIN_GIVE("admin_give"),
    ADMIN_TAKE("admin_take"),
    BALANCE_SET("balance_set"),
    STOCK_BUY("stock_buy"),
    STOCK_SHORT("stock_short"),
    STOCK_CLOSE("stock_close"),
    STOCK_DIVIDEND("stock_dividend"),
    JOB_REWARD("job_reward"),
    QUEST_REWARD("quest_reward"),
    TREASURE_REWARD("treasure_reward"),
    ELITEMOBS_REWARD("elitemobs_reward"),
    LAND_CHARGE("land_charge"),
    LAND_CREDIT("land_credit"),
    ENCHANTMENT_PURCHASE("enchantment_purchase"),
    ENCHANTMENT_CREDIT("enchantment_credit"),
    CMI_REPAIR("cmi_repair"),
    CMI_FLIGHT_CHARGE("cmi_flight_charge"),
    CMI_COMMAND_COST("cmi_command_cost"),
    CMI_RANKUP("cmi_rankup"),
    CMI_SCAVENGE("cmi_scavenge"),
    CMI_SHULKER_OPEN("cmi_shulker_open"),
    CMI_SELL("cmi_sell"),
    CMI_PAY_IN("cmi_pay_in"),
    CMI_PAY_OUT("cmi_pay_out"),
    CMI_CHEQUE_CREATE("cmi_cheque_create"),
    CMI_CHEQUE_REDEEM("cmi_cheque_redeem"),
    CMI_SERVICE_CHARGE("cmi_service_charge"),
    CMI_SERVICE_CREDIT("cmi_service_credit"),
    SOURCE_CREDIT("source_credit"),
    SOURCE_DEBIT("source_debit"),
    ZERO_CHANGE("zero_change"),
}

/**
 * Normalizes optional provider actions into a fixed vocabulary. Unknown
 * provider strings never become metric labels; direction remains observed
 * while source-specific semantics are inferred only where they are unambiguous.
 */
object EconomyActionClassifier {
    fun classify(
        source: EconomySource,
        amount: Double,
        providerAction: String? = null,
        providerOrigin: String? = null,
    ): EconomyAction {
        val action = providerAction.orEmpty().trim().lowercase(Locale.ROOT)
        val origin = providerOrigin.orEmpty().trim().lowercase(Locale.ROOT)
        when {
            action == "balance_set" || action == "set" -> return EconomyAction.BALANCE_SET
            source == EconomySource.ADMIN_COMMAND && action == "give" -> return EconomyAction.ADMIN_GIVE
            source == EconomySource.ADMIN_COMMAND && action == "take" -> return EconomyAction.ADMIN_TAKE
            source in setOf(EconomySource.SHOP, EconomySource.AUTOSELL) && action.contains("auto_sell") ->
                return EconomyAction.AUTOSELL_SALE
            source == EconomySource.SHOP && action.startsWith("sell") -> return EconomyAction.SHOP_SELL
            source == EconomySource.SHOP && action.startsWith("buy") -> return EconomyAction.SHOP_BUY
            source == EconomySource.INTERNAL_STOCK && action == "stock_buy" -> return EconomyAction.STOCK_BUY
            source == EconomySource.INTERNAL_STOCK && action == "stock_short" -> return EconomyAction.STOCK_SHORT
            source == EconomySource.INTERNAL_STOCK && action == "stock_close" -> return EconomyAction.STOCK_CLOSE
            source == EconomySource.INTERNAL_STOCK && action == "stock_dividend" -> return EconomyAction.STOCK_DIVIDEND
        }

        if (amount == 0.0 || !amount.isFinite()) return EconomyAction.ZERO_CHANGE

        if (source == EconomySource.CMI) {
            EconomyAction.entries
                .firstOrNull { it.label == action && it.name.startsWith("CMI_") }
                ?.let { return it }
            if (origin.contains(".commands.list.pay")) {
                return if (amount > 0.0) EconomyAction.CMI_PAY_IN else EconomyAction.CMI_PAY_OUT
            }
            if (origin.contains(".commands.list.cheque") || origin.contains(".modules.moneycheque.")) {
                return if (amount > 0.0) EconomyAction.CMI_CHEQUE_REDEEM else EconomyAction.CMI_CHEQUE_CREATE
            }
            if (amount > 0.0 && origin.contains(".commands.list.sell")) return EconomyAction.CMI_SELL
            if (amount < 0.0) {
                return when {
                    origin.contains(".commands.list.repair") -> EconomyAction.CMI_REPAIR
                    origin.contains(".commands.list.flightcharge") -> EconomyAction.CMI_FLIGHT_CHARGE
                    origin.contains(".modules.cmdcost.") -> EconomyAction.CMI_COMMAND_COST
                    origin.contains(".modules.ranks.") || origin.contains(".commands.list.rankup") -> EconomyAction.CMI_RANKUP
                    origin.contains(".modules.scavenger.") -> EconomyAction.CMI_SCAVENGE
                    origin.contains(".modules.shulkerboxinventory.") -> EconomyAction.CMI_SHULKER_OPEN
                    else -> EconomyAction.CMI_SERVICE_CHARGE
                }
            }
            return EconomyAction.CMI_SERVICE_CREDIT
        }

        return when (source) {
            EconomySource.BANK -> if (amount > 0.0) EconomyAction.BANK_TO_WALLET else EconomyAction.WALLET_TO_BANK
            EconomySource.GAMBLING -> if (amount > 0.0) EconomyAction.GAMBLING_PAYOUT else EconomyAction.GAMBLING_WAGER
            EconomySource.SHOP -> if (amount > 0.0) EconomyAction.SHOP_SELL else EconomyAction.SHOP_BUY
            EconomySource.AUTOSELL -> EconomyAction.AUTOSELL_SALE
            EconomySource.JOBS -> if (amount > 0.0) EconomyAction.JOB_REWARD else EconomyAction.SOURCE_DEBIT
            EconomySource.QUESTS -> if (amount > 0.0) EconomyAction.QUEST_REWARD else EconomyAction.SOURCE_DEBIT
            EconomySource.TREASURE -> if (amount > 0.0) EconomyAction.TREASURE_REWARD else EconomyAction.SOURCE_DEBIT
            EconomySource.ELITEMOBS -> if (amount > 0.0) EconomyAction.ELITEMOBS_REWARD else EconomyAction.SOURCE_DEBIT
            EconomySource.LANDS -> if (amount > 0.0) EconomyAction.LAND_CREDIT else EconomyAction.LAND_CHARGE
            EconomySource.ADVANCED_ENCHANTMENTS ->
                if (amount > 0.0) EconomyAction.ENCHANTMENT_CREDIT else EconomyAction.ENCHANTMENT_PURCHASE
            EconomySource.PLAYER_TRANSFER,
            EconomySource.QUICKSHOP,
            EconomySource.AUCTION,
            EconomySource.PLAYER_AUCTIONS,
            EconomySource.PLAYER_WARPS,
            EconomySource.INTERNAL_STOCK,
            -> if (amount > 0.0) EconomyAction.TRANSFER_IN else EconomyAction.TRANSFER_OUT
            EconomySource.BALANCE_SET -> EconomyAction.BALANCE_SET
            EconomySource.ADMIN_COMMAND -> if (amount > 0.0) EconomyAction.ADMIN_GIVE else EconomyAction.ADMIN_TAKE
            else -> if (amount > 0.0) EconomyAction.SOURCE_CREDIT else EconomyAction.SOURCE_DEBIT
        }
    }
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
        val flow = flow(reason, source, amount, origin)
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
            haystack.contains("arc-season:public_projects:") -> EconomySource.PUBLIC_PROJECTS
            haystack.contains("arc-season:dungeon_entry:") -> EconomySource.DUNGEON_ENTRY
            haystack.contains("arc-mount:") || haystack.contains("arc-mount-refund:") -> EconomySource.MOUNTS
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
            haystack.contains("blackjack") || haystack.contains("roulette") || haystack.contains("slotmachine") ||
                haystack.contains("gambling") -> EconomySource.GAMBLING
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

    private fun flow(reason: String, source: EconomySource, amount: Double, origin: String): EconomyFlow =
        when {
            source == EconomySource.BALANCE_SET -> EconomyFlow.ADJUSTMENT
            source == EconomySource.MOUNTS && reason.startsWith("arc-mount-refund:") -> EconomyFlow.INTERNAL
            source in transferSources -> EconomyFlow.TRANSFER
            source == EconomySource.CMI && origin.contains(".commands.list.pay", ignoreCase = true) -> EconomyFlow.TRANSFER
            source == EconomySource.CMI &&
                (origin.contains(".commands.list.cheque", ignoreCase = true) ||
                    origin.contains(".modules.moneycheque.", ignoreCase = true)) -> EconomyFlow.INTERNAL
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
