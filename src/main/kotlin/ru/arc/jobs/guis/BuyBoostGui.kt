package ru.arc.jobs.guis

import com.gamingmesh.jobs.Jobs
import com.gamingmesh.jobs.container.Job
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.core.modules.EconomyModule
import ru.arc.core.sync
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.jobs.BoostType
import ru.arc.jobs.JobsModule
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount

/**
 * Factory for creating BuyBoostGui.
 */
object BuyBoostGuiFactory {
    /**
     * Creates a boost shop GUI.
     *
     * @param player The player viewing the shop
     * @param job Optional job filter (null = show all)
     * @param config Configuration
     * @param currentType Current boost type to display (for type switching)
     */
    fun open(
        player: Player,
        job: Job?,
        config: Config,
        currentType: BoostType = BoostType.MONEY,
    ) {
        val boostsByType = loadAllBoosts(config)

        // Find first non-empty type
        val nonEmptyTypes = BoostType.entries.filter { boostsByType[it]?.isNotEmpty() == true }
        val activeType =
            if (nonEmptyTypes.contains(currentType)) currentType else nonEmptyTypes.firstOrNull() ?: BoostType.MONEY

        val currentBoosts = boostsByType[activeType] ?: emptyList()
        val filteredBoosts = filterBoostsForJob(currentBoosts, job)

        val entries = filteredBoosts.mapNotNull { boost ->
            createBoostItem(boost, player, job, config) { open(player, job, config, activeType) }
        }
        val typeName = config.string("type-names.${activeType.name.lowercase()}", activeType.display)
        val typeData = getTypeStackData(activeType)
        val typeItem = ArcMenus.item(
            ArcMenuSchema.BOOST_SHOP,
            "type",
            PaperMenuItemRenderContext(values = mapOf("type" to TextUtil.mm(typeName, true))),
        ).withType(typeData.material)
        if (typeData.modelData > 0) {
            @Suppress("DEPRECATION")
            typeItem.editMeta { it.setCustomModelData(typeData.modelData) }
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.BOOST_SHOP,
            config.component("boostbuy-menu.title", "<dark_gray>Магазин бустов"),
            elements = mapOf(
                "type" to ArcMenus.entry(typeItem) { open(it, job, config, getNextType(activeType, nonEmptyTypes)) },
                "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.BOOST_SHOP, "back")) { createJobsListGui(config, it) },
            ),
            regions = mapOf(ArcMenuSchema.BOOST_SHOP_ENTRIES to entries),
        )
    }

    // ==================== Helper Functions ====================

    private fun loadAllBoosts(config: Config): Map<BoostType, List<Boost>> =
        BoostType.entries.associateWith { type -> loadBoostsForType(config, type) }

    private fun loadBoostsForType(
        config: Config,
        type: BoostType,
    ): List<Boost> {
        val basePath = "boosts.${type.name.lowercase()}"
        return config.keys(basePath).mapNotNull { key ->
            val path = "$basePath.$key"
            try {
                val boost =
                    Boost(
                        display = config.string("$path.display"),
                        lore = config.stringList("$path.lore"),
                        price = config.real("$path.price", 1000.0),
                        boostAmount = config.real("$path.boost-amount", 0.1),
                        seconds = config.long("$path.seconds", 3600),
                        permission = config.string("$path.permission", ""),
                        material = Material.valueOf(config.string("$path.material", "GOLD_INGOT").uppercase()),
                        modelData = config.integer("$path.model-data", 0),
                        currency = BuyCurrency.valueOf(config.string("$path.currency", "MONEY").uppercase()),
                        id = config.string("$path.id", "none"),
                        jobs = config.stringList("$path.jobs").map { it.lowercase().intern() },
                        types =
                            config.stringList("$path.types").mapNotNull {
                                try {
                                    BoostType.valueOf(it.uppercase())
                                } catch (_: Exception) {
                                    null
                                }
                            },
                    )
                if (boost.isValid()) {
                    boost
                } else {
                    warn("Ignoring invalid Jobs boost config at {}", path)
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun filterBoostsForJob(
        boosts: List<Boost>,
        job: Job?,
    ): List<Boost> =
        boosts.filter { boost ->
            val allJobs = boost.jobs.isEmpty() || boost.jobs.contains("all")
            when {
                job == null -> allJobs
                allJobs -> true
                else -> boost.jobs.contains(job.name.lowercase())
            }
        }

    private fun createBoostItem(
        boost: Boost,
        player: Player,
        job: Job?,
        config: Config,
        onPurchase: () -> Unit,
    ): PaperMenuEntry? {
        val allJobs = boost.jobs.isEmpty() || boost.jobs.contains("all")
        val allTypes = boost.types.contains(BoostType.ALL) || boost.types.isEmpty()

        // Filter by job
        if (job == null && !allJobs) return null
        if (job != null && !allJobs && !boost.jobs.contains(job.name.lowercase())) return null

        val economyCheck = checkEconomy(player, boost.currency, boost.price)
        val currencyName = config.string("currency-names.${boost.currency.name.lowercase()}", "Money")
        val hasBoost = JobsModule.hasBoost(player, boost.id, boost.jobs, boost.types)

        val playerCurrency = getCurrency(player, boost.currency)
        val boostAmountStr = "${boost.boostAmount * 100}%"
        val typeStr = if (allTypes) "Все" else boost.types.joinToString(", ") { it.name }
        val jobStr = if (allJobs) "Все" else boost.jobs.joinToString(", ") { JobsModule.jobDisplayMinimessage(it) }
        val values = mapOf(
            "name" to boost.display,
            "price" to formatAmount(boost.price),
            "boost" to boostAmountStr,
            "currency" to currencyName,
            "permission" to boost.permission.ifEmpty { "Нет" },
            "time" to "${boost.seconds / 60} минут",
            "type" to typeStr,
            "job" to jobStr,
            "player-currency" to formatAmount(playerCurrency),
            "currency-lack" to formatAmount(economyCheck.currencyNeeded),
        )
        val flags = when {
            hasBoost -> setOf("owned")
            boost.permission.isNotEmpty() && !player.hasPermission(boost.permission) -> setOf("no-permission")
            !economyCheck.hasEnough -> setOf("no-funds")
            else -> setOf("available")
        }
        val details = boost.lore.map { line ->
            values.entries.fold(line) { rendered, (key, value) -> rendered.replace("<$key>", value) }
        }
        val stack = ArcMenus.item(
            "boost-shop-offer",
            PaperMenuItemRenderContext(
                values = values.mapValues { (_, value) -> TextUtil.mm(value, true) },
                flags = flags,
                repeats = mapOf("details" to details.map { mapOf("line" to TextUtil.mm(it, true)) }),
            ),
        ).withType(boost.material)
        if (boost.modelData > 0) {
            @Suppress("DEPRECATION")
            stack.editMeta { it.setCustomModelData(boost.modelData) }
        }
        if (hasBoost) stack.editMeta { it.setEnchantmentGlintOverride(true) }

        return ArcMenus.entry(stack) {
                val coordinator =
                    BoostPurchaseCoordinator(
                        alreadyOwned = {
                            JobsModule.hasBoost(player, boost.id, boost.jobs, boost.types)
                        },
                        hasPermission = {
                            boost.permission.isEmpty() || player.hasPermission(boost.permission)
                        },
                        hasFunds = {
                            checkEconomy(player, boost.currency, boost.price).hasEnough
                        },
                        reserveBoost = {
                            JobsModule.addBoost(
                                player.uniqueId,
                                boost.jobs,
                                boost.boostAmount,
                                calculateBoostExpiration(System.currentTimeMillis(), boost.seconds) ?: 0L,
                                boost.id,
                                boost.types,
                            )
                        },
                        chargeCurrency = {
                            takeCurrency(player, boost.currency, boost.price)
                        },
                        rollbackBoost = {
                            JobsModule.removeBoosts(
                                player.uniqueId,
                                boost.id,
                                boost.jobs,
                                boost.types,
                            )
                        },
                        runOnMainThread = { action -> sync { action() } },
                    )
                coordinator.purchase().whenComplete { result, failure ->
                    sync {
                        if (failure != null) {
                            error("Jobs boost purchase failed for {}", player.uniqueId, failure)
                            showPurchaseMessage(player, config, "boostbuy-menu.purchase-failed")
                            return@sync
                        }
                        when (result) {
                            BoostPurchaseResult.PURCHASED -> onPurchase()
                            BoostPurchaseResult.ALREADY_OWNED ->
                                showPurchaseMessage(player, config, "boostbuy-menu.already-have-boost")
                            BoostPurchaseResult.NO_PERMISSION ->
                                showPurchaseMessage(player, config, "boostbuy-menu.no-permission")
                            BoostPurchaseResult.INSUFFICIENT_FUNDS ->
                                showPurchaseMessage(player, config, "boostbuy-menu.not-enough-money")
                            BoostPurchaseResult.PAYMENT_FAILED,
                            BoostPurchaseResult.UNAVAILABLE,
                            null,
                            ->
                                showPurchaseMessage(player, config, "boostbuy-menu.purchase-failed")
                        }
                    }
                }
        }
    }

    private fun showPurchaseMessage(
        player: Player,
        config: Config,
        configKey: String,
    ) {
        val fallback =
            when (configKey) {
                "boostbuy-menu.already-have-boost" -> "<red>У вас уже есть этот буст"
                "boostbuy-menu.no-permission" -> "<red>Нет доступа к этому бусту"
                "boostbuy-menu.not-enough-money" -> "<red>Недостаточно средств"
                else -> "<red>Не удалось купить буст"
            }
        player.sendActionBar(config.component(configKey, fallback))
    }

    private fun getNextType(
        current: BoostType,
        available: List<BoostType>,
    ): BoostType {
        if (available.size <= 1) return current
        val currentIndex = available.indexOf(current)
        return available[(currentIndex + 1) % available.size]
    }

    private fun getCurrency(
        player: Player,
        currency: BuyCurrency,
    ): Double =
        when (currency) {
            BuyCurrency.MONEY -> {
                EconomyModule.getEconomy()?.getBalance(player) ?: 0.0
            }

            BuyCurrency.POINTS -> {
                Jobs
                    .getPlayerManager()
                    .getJobsPlayer(player)
                    .pointsData.currentPoints
            }

            BuyCurrency.EXP -> {
                player.totalExperience.toDouble()
            }
        }

    private fun takeCurrency(
        player: Player,
        currency: BuyCurrency,
        price: Double,
    ): Boolean {
        if (!price.isFinite() || price < 0.0) return false
        return when (currency) {
            BuyCurrency.MONEY -> {
                val economy = EconomyModule.getEconomy() ?: return false
                economy.has(player, price) &&
                    economy.withdrawPlayer(player, price).transactionSuccess()
            }

            BuyCurrency.POINTS -> {
                val pointsData = Jobs.getPlayerManager().getJobsPlayer(player).pointsData
                if (pointsData.currentPoints < price) return false
                pointsData.setPoints(pointsData.currentPoints - price)
                true
            }

            BuyCurrency.EXP -> {
                if (player.totalExperience < price) return false
                player.totalExperience = (player.totalExperience - price).toInt()
                true
            }
        }
    }

    private fun checkEconomy(
        player: Player,
        currency: BuyCurrency,
        price: Double,
    ): EconomyCheck {
        val balance = getCurrency(player, currency)
        return calculateEconomyCheck(balance, price)
    }

    private fun getTypeStackData(type: BoostType): TypeStackData =
        when (type) {
            BoostType.MONEY -> TypeStackData(Material.STICK, 11138)
            BoostType.EXP -> TypeStackData(Material.EXPERIENCE_BOTTLE, 0)
            BoostType.POINTS -> TypeStackData(Material.NETHER_STAR, 0)
            BoostType.ALL -> TypeStackData(Material.GOLD_INGOT, 0)
        }

    // ==================== Data Classes ====================

    data class Boost(
        val display: String,
        val lore: List<String>,
        val price: Double,
        val boostAmount: Double,
        val seconds: Long,
        val permission: String,
        val material: Material,
        val modelData: Int,
        val currency: BuyCurrency,
        val id: String,
        val jobs: List<String>,
        val types: List<BoostType>,
    ) {
        internal fun isValid(): Boolean =
            display.isNotBlank() &&
                price.isFinite() &&
                price >= 0.0 &&
                boostAmount.isFinite() &&
                boostAmount > 0.0 &&
                seconds > 0 &&
                seconds <= Long.MAX_VALUE / 1000L &&
                id.isNotBlank() &&
                id == id.trim() &&
                !id.equals("none", ignoreCase = true) &&
                (currency != BuyCurrency.EXP || price <= Int.MAX_VALUE.toDouble() && price % 1.0 == 0.0)
    }

    data class EconomyCheck(
        val hasEnough: Boolean,
        val currencyNeeded: Double,
    )

    data class TypeStackData(
        val material: Material,
        val modelData: Int,
    )

    enum class BuyCurrency {
        MONEY,
        POINTS,
        EXP,
    }
}

internal fun calculateEconomyCheck(
    balance: Double,
    price: Double,
): BuyBoostGuiFactory.EconomyCheck {
    if (!balance.isFinite() || !price.isFinite() || price < 0.0) {
        return BuyBoostGuiFactory.EconomyCheck(hasEnough = false, currencyNeeded = 0.0)
    }
    val difference = balance - price
    return BuyBoostGuiFactory.EconomyCheck(
        hasEnough = difference >= 0.0,
        currencyNeeded = (-difference).coerceAtLeast(0.0),
    )
}

internal fun calculateBoostExpiration(
    now: Long,
    seconds: Long,
): Long? {
    if (seconds <= 0L) return null
    return try {
        Math.addExact(now, Math.multiplyExact(seconds, 1000L))
    } catch (_: ArithmeticException) {
        null
    }
}
