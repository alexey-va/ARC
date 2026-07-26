package ru.arc.jobs.guis

import com.gamingmesh.jobs.Jobs
import com.gamingmesh.jobs.container.Job
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import ru.arc.config.Config
import ru.arc.core.modules.EconomyModule
import ru.arc.core.sync
import ru.arc.gui.dynamicGui
import ru.arc.jobs.BoostType
import ru.arc.jobs.JobsModule
import ru.arc.util.GuiUtils
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.guiItem
import ru.arc.util.itemComponents
import ru.arc.util.itemLore

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
    fun create(
        player: Player,
        job: Job?,
        config: Config,
        currentType: BoostType = BoostType.MONEY,
    ): ChestGui {
        val boostsByType = loadAllBoosts(config)

        // Find first non-empty type
        val nonEmptyTypes = BoostType.entries.filter { boostsByType[it]?.isNotEmpty() == true }
        val activeType =
            if (nonEmptyTypes.contains(currentType)) currentType else nonEmptyTypes.firstOrNull() ?: BoostType.MONEY

        val currentBoosts = boostsByType[activeType] ?: emptyList()
        val filteredBoosts = filterBoostsForJob(currentBoosts, job)

        val boostCount = filteredBoosts.size
        val rows = minOf(6, maxOf(2, (boostCount + 6) / 7 + 2))

        val typeResolver = createTypeResolver(config, activeType)

        return dynamicGui(
            title = config.string("boostbuy-menu.title", "Магазин бустов"),
            itemCount = boostCount,
            minRows = 2,
            maxRows = 6,
            navRows = 2, // Top and bottom rows for nav
            player = player,
            config = config,
        ) {
            background()

            // Boost items pane (rows 1 to rows-2)
            staticPane(1, 1, 7, rows - 2) {
                var x = 0
                var y = 0
                filteredBoosts.forEach { boost ->
                    val guiItem =
                        createBoostItem(boost, player, job, config) {
                            // Refresh GUI after purchase
                            GuiUtils.constructAndShowAsync({ create(player, job, config, activeType) }, player)
                        }
                    if (guiItem != null) {
                        if (x == 3) x++ // Skip center column
                        item(x++, y, guiItem)
                        if (x == 7) {
                            x = 0
                            y++
                        }
                    }
                }
            }

            navBar {
                // Back button
                button(0) {
                    material(Material.BLUE_STAINED_GLASS_PANE)
                    modelData(11013)
                    display("<gray>« Назад")
                    lore(emptyList())
                    tagResolver(typeResolver)
                    fromConfig(config, "boostbuy-menu.back")
                    onClick {
                        GuiUtils.constructAndShowAsync({ createJobsListGui(config, player) }, it.whoClicked)
                    }
                }
            }

            // Type switcher at top center
            staticPane(4, 0, 1, 1) {
                item(0) {
                    val typeData = getTypeStackData(activeType)
                    material(typeData.material)
                    modelData(typeData.modelData)
                    display("<gold>Тип буста: <yellow><type>")
                    lore(listOf("", "<gray>Нажмите для смены типа"))
                    tagResolver(typeResolver)
                    fromConfig(config, "boostbuy-menu.type")
                    onClick {
                        val nextType = getNextType(activeType, nonEmptyTypes)
                        GuiUtils.constructAndShowAsync({ create(player, job, config, nextType) }, player)
                    }
                }
            }
        }
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
    ): GuiItem? {
        val allJobs = boost.jobs.isEmpty() || boost.jobs.contains("all")
        val allTypes = boost.types.contains(BoostType.ALL) || boost.types.isEmpty()

        // Filter by job
        if (job == null && !allJobs) return null
        if (job != null && !allJobs && !boost.jobs.contains(job.name.lowercase())) return null

        val economyCheck = checkEconomy(player, boost.currency, boost.price)
        val currencyName = config.string("currency-names.${boost.currency.name.lowercase()}", "Money")
        val hasBoost = JobsModule.hasBoost(player, boost.id, boost.jobs, boost.types)

        val lore =
            when {
                hasBoost -> config.itemLore("boostbuy-menu.already-have-boost")

                boost.permission.isNotEmpty() && !player.hasPermission(boost.permission) ->
                    config.itemLore("boostbuy-menu.no-permission")

                !economyCheck.hasEnough -> config.itemLore("boostbuy-menu.not-enough-money")

                else -> boost.lore.ifEmpty { config.itemLore("boostbuy-menu.boost") }
            }

        val playerCurrency = getCurrency(player, boost.currency)
        val boostAmountStr = "${boost.boostAmount * 100}%"
        val typeStr = if (allTypes) "Все" else boost.types.joinToString(", ") { it.name }
        val jobStr = if (allJobs) "Все" else boost.jobs.joinToString(", ") { JobsModule.jobDisplayMinimessage(it) }

        return guiItem(boost.material) {
            display(boost.display)
            modelData(boost.modelData)

            tags {
                "price" to formatAmount(boost.price)
                "boost" to boostAmountStr
                "currency" to currencyName
                "permission" to boost.permission.ifEmpty { "Нет" }
                "time" to "${boost.seconds / 60} минут"
                "type" to typeStr
                "job" to jobStr
                "player_currency" to formatAmount(playerCurrency)
                "currency_lack" to formatAmount(economyCheck.currencyNeeded)
            }

            lore(lore)
            flags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES)

            if (hasBoost) {
                enchantUnsafe(Enchantment.VANISHING_CURSE, 1)
            }

            onClick { click ->
                click.isCancelled = true
                val clickedItem = click.currentItem ?: return@onClick
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
                            showPurchaseMessage(clickedItem, config, "boostbuy-menu.purchase-failed")
                            return@sync
                        }
                        when (result) {
                            BoostPurchaseResult.PURCHASED -> onPurchase()
                            BoostPurchaseResult.ALREADY_OWNED ->
                                showPurchaseMessage(clickedItem, config, "boostbuy-menu.already-have-boost")
                            BoostPurchaseResult.NO_PERMISSION ->
                                showPurchaseMessage(clickedItem, config, "boostbuy-menu.no-permission")
                            BoostPurchaseResult.INSUFFICIENT_FUNDS ->
                                showPurchaseMessage(clickedItem, config, "boostbuy-menu.not-enough-money")
                            BoostPurchaseResult.PAYMENT_FAILED,
                            BoostPurchaseResult.UNAVAILABLE,
                            null,
                            ->
                                showPurchaseMessage(clickedItem, config, "boostbuy-menu.purchase-failed")
                        }
                    }
                }
            }
        }
    }

    private fun showPurchaseMessage(
        item: org.bukkit.inventory.ItemStack,
        config: Config,
        configKey: String,
    ) {
        val configuredDisplay = config.itemComponents(configKey).first
        val fallback =
            when (configKey) {
                "boostbuy-menu.already-have-boost" -> "<red>У вас уже есть этот буст"
                "boostbuy-menu.no-permission" -> "<red>Нет доступа к этому бусту"
                "boostbuy-menu.not-enough-money" -> "<red>Недостаточно средств"
                else -> "<red>Не удалось купить буст"
            }
        val display = configuredDisplay ?: TextUtil.mm(fallback, true)
        GuiUtils.temporaryChange(item, display, null, 60) {}
    }

    private fun createTypeResolver(
        config: Config,
        currentType: BoostType,
    ): TagResolver =
        TagResolver
            .builder()
            .tag(
                "type",
                Tag.inserting(
                    TextUtil.mm(config.string("type-names.${currentType.name.lowercase()}", currentType.display), true),
                ),
            ).build()

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
