package ru.arc.commands.arc.subcommands

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.economyshop.ShopPurchaseOutcome
import ru.arc.hooks.economyshop.ShopPurchaseStatus
import ru.arc.util.Logging
import ru.arc.util.TextUtils

/** Buy an exact item quantity through EconomyShopGUI: /arc buy or /buy. */
internal object BuySubCommand : SubCommand {
    override val configKey = "buy"
    override val defaultName = "buy"
    override val defaultPermission: String? = null
    override val defaultPlayerOnly = true
    override val defaultDescription = "Купить точное количество товара из магазина"
    override val defaultUsage = "/buy <путь> <кол-во> или /buy <раздел> <товар> <кол-во>"

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        val player = requirePlayer(sender) ?: return true
        val maxAmount = CommandConfig.getCommandInt(configKey, "max-amount", DEFAULT_MAX_AMOUNT).coerceAtLeast(1)
        when (val parsed = BuyCommandInput.parse(args, maxAmount)) {
            BuyParseResult.InvalidSyntax -> sendUsage(sender)
            is BuyParseResult.InvalidAmount -> {
                sender.sendMessage(
                    CommandConfig.get(
                        "buy.invalid-amount",
                        "<red>Количество должно быть целым числом от <white>1<red> до <white>%max%<red>.",
                        "%max%",
                        maxAmount.toString(),
                    ),
                )
            }
            is BuyParseResult.Valid -> executePurchase(sender, player, parsed.request)
        }
        return true
    }

    private fun executePurchase(
        sender: CommandSender,
        player: Player,
        request: BuyRequest,
    ) {
        val service = HookRegistry.shopPurchaseService
        if (service == null) {
            sender.sendMessage(
                CommandConfig.get(
                    "buy.unavailable",
                    "<red>Покупка командой недоступна на этом сервере: магазин не загружен.",
                ),
            )
            return
        }

        val outcome = try {
            service.purchase(player, request.itemPath, request.amount)
        } catch (failure: Throwable) {
            Logging.error(
                "EconomyShopGUI command purchase failed for player {} item {} amount {}",
                player.name,
                request.itemPath,
                request.amount,
                failure,
            )
            sender.sendMessage(
                CommandConfig.get(
                    "buy.failed",
                    "<red>Не удалось выполнить покупку из-за внутренней ошибки. Попробуйте ещё раз.",
                ),
            )
            return
        }

        sendOutcome(sender, outcome)
    }

    private fun sendOutcome(sender: CommandSender, outcome: ShopPurchaseOutcome) {
        val itemName = outcome.itemName?.takeIf(String::isNotBlank) ?: outcome.itemPath
        val (key, fallback) =
            when (outcome.status) {
                ShopPurchaseStatus.SUCCESS -> {
                    if (outcome.formattedPrice == null) {
                        "buy.success-no-price" to
                            "<green>Куплено <white>%amount% шт.<green> товара <white>%item%<green>."
                    } else {
                        "buy.success" to
                            "<green>Куплено <white>%amount% шт.<green> товара <white>%item%<green> за <white>%price%<green>."
                    }
                }
                ShopPurchaseStatus.ITEM_NOT_FOUND ->
                    "buy.item-not-found" to
                        "<red>Товар <white>%item%<red> не найден. <gray>Используйте Tab, чтобы выбрать путь."
                ShopPurchaseStatus.ITEM_ERROR ->
                    "buy.item-error" to "<red>Товар <white>%item%<red> временно недоступен из-за ошибки настройки."
                ShopPurchaseStatus.NOT_BUYABLE ->
                    "buy.not-buyable" to "<red>Товар <white>%item%<red> нельзя купить."
                ShopPurchaseStatus.NO_PERMISSIONS ->
                    "buy.no-permissions" to "<red>У вас нет доступа к товару <white>%item%<red>."
                ShopPurchaseStatus.REQUIREMENTS_FAILED ->
                    "buy.requirements-failed" to "<red>Вы не выполнили требования для покупки товара <white>%item%<red>."
                ShopPurchaseStatus.INSUFFICIENT_FUNDS ->
                    "buy.insufficient-funds" to "<red>Недостаточно средств для покупки <white>%amount% шт.<red> товара <white>%item%<red>."
                ShopPurchaseStatus.NO_INVENTORY_SPACE ->
                    "buy.no-inventory-space" to "<red>В инвентаре недостаточно места для всех <white>%amount% шт.<red>"
                ShopPurchaseStatus.TRANSACTION_CANCELLED ->
                    "buy.cancelled" to "<red>Покупка отменена другим плагином. Деньги не списаны."
                ShopPurchaseStatus.BELOW_MINIMUM ->
                    "buy.below-minimum" to "<red>Это количество меньше минимальной покупки для товара <white>%item%<red>."
                ShopPurchaseStatus.ABOVE_MAXIMUM ->
                    "buy.above-maximum" to "<red>Это количество превышает лимит покупки товара <white>%item%<red>."
                ShopPurchaseStatus.OUT_OF_STOCK ->
                    "buy.out-of-stock" to "<red>На складе недостаточно товара <white>%item%<red> для покупки <white>%amount% шт.<red>"
                ShopPurchaseStatus.FAILED ->
                    "buy.failed" to "<red>Не удалось выполнить покупку. Деньги не списаны."
            }

        sender.sendMessage(
            CommandConfig.get(
                key,
                fallback,
                "%item%",
                TextUtils.escapeMM(itemName),
                "%amount%",
                outcome.amount.toString(),
                "%price%",
                safePriceText(outcome.formattedPrice.orEmpty()),
            ),
        )
    }

    /** Economy formatters may return legacy section codes, which MiniMessage deliberately rejects. */
    private fun safePriceText(formattedPrice: String): String =
        TextUtils.escapeMM(
            TextUtils.plain(
                LegacyComponentSerializer.legacySection().deserialize(formattedPrice),
            ),
        )

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? {
        val player = sender as? Player ?: return null
        val queries = runCatching { HookRegistry.shopPurchaseService?.itemQueries(player).orEmpty() }.getOrDefault(emptyList())
        val maxAmount = CommandConfig.getCommandInt(configKey, "max-amount", DEFAULT_MAX_AMOUNT).coerceAtLeast(1)
        return BuyCommandInput.complete(args, queries, maxAmount)
    }

    private const val DEFAULT_MAX_AMOUNT = 2304
}

internal data class BuyRequest(
    val itemPath: String,
    val amount: Int,
)

internal sealed interface BuyParseResult {
    data class Valid(val request: BuyRequest) : BuyParseResult
    data class InvalidAmount(val value: String) : BuyParseResult
    data object InvalidSyntax : BuyParseResult
}

internal object BuyCommandInput {
    private val amountSuggestions = listOf("1", "16", "32", "64", "128", "256", "512", "1024", "2304")

    fun parse(args: Array<String>, maxAmount: Int): BuyParseResult {
        val itemPath: String
        val amountValue: String
        when (args.size) {
            2 -> {
                itemPath = args[0]
                amountValue = args[1]
            }
            3 -> {
                itemPath = "${args[0]}.${args[1]}"
                amountValue = args[2]
            }
            else -> return BuyParseResult.InvalidSyntax
        }

        val amount = amountValue.toIntOrNull()
        if (amount == null || amount !in 1..maxAmount) return BuyParseResult.InvalidAmount(amountValue)
        return BuyParseResult.Valid(BuyRequest(itemPath, amount))
    }

    fun complete(
        args: Array<String>,
        itemQueries: List<String>,
        maxAmount: Int = 2304,
    ): List<String>? {
        val allowedAmounts = amountSuggestions.filter { it.toInt() <= maxAmount }
        return when (args.size) {
            1 -> {
                val input = args[0]
                val candidates =
                    if ('.' in input) {
                        itemQueries
                    } else {
                        itemQueries.map { it.substringBefore('.') }.distinct()
                    }
                candidates.tabComplete(input)
            }
            2 -> {
                val first = args[0]
                if (itemQueries.any { it.equals(first, ignoreCase = true) }) {
                    allowedAmounts.tabComplete(args[1])
                } else {
                    itemQueries
                        .mapNotNull { path ->
                            path.takeIf { it.startsWith("$first.", ignoreCase = true) }
                                ?.substring(first.length + 1)
                        }
                        .tabComplete(args[1])
                }
            }
            3 -> allowedAmounts.tabComplete(args[2])
            else -> null
        }
    }
}
