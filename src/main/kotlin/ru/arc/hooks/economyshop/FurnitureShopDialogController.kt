package ru.arc.hooks.economyshop

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.hooks.HookRegistry
import java.math.BigDecimal

/** Native, feature-specific furniture catalogue backed by EconomyShopGUI. */
internal class FurnitureShopDialogController(
    private val service: () -> ShopPurchaseService? = { HookRegistry.shopPurchaseService },
    private val open: (Player, PaperDialogScreen, (() -> Unit)?) -> Unit = { player, screen, reopen ->
        ArcMenus.openDialog(player, screen, reopen = reopen)
    },
) {
    private data class Category(
        val name: String,
        val offers: List<FurnitureShopOffer>,
    )

    fun open(player: Player) {
        ArcMenus.beginDialogFlow(player)
        openCategories(player)
    }

    private fun openCategories(player: Player) {
        val shop = service()
        if (shop == null) {
            openMessage(player, "Магазин мебели недоступен", "Сервис магазина временно не готов. Попробуйте ещё раз.")
            return
        }
        val offers = runCatching { shop.furnitureOffers(player, AMOUNT) }.getOrDefault(emptyList())
        if (offers.isEmpty()) {
            openMessage(player, "Мебель недоступна", "Сейчас нет доступных предметов мебели.")
            return
        }
        val categories = categoriesOf(offers)
        val buttons = categories.mapIndexed { index, category ->
            PaperDialogButton(
                id = action("category_$index"),
                label = categoryLabel(category),
                tooltip = light("Открыть категорию и выбрать предмет."),
                width = BUTTON_WIDTH,
            ) { openCategory(player, category.name, 0, index) }
        }.toMutableList()
        val screen = PaperDialogScreen(
            id = "furniture.shop.categories",
            title = light("Мебель", TRADE_COLOR).decorate(TextDecoration.BOLD),
            body = listOf(
                PaperDialogBody(
                    light("Выберите категорию. Показаны актуальные товары из магазина · ${offers.size} позиций."),
                    BODY_WIDTH,
                ),
            ),
            buttons = buttons,
            columns = 1,
        )
        open(player, screen) { openCategories(player) }
    }

    private fun openCategory(player: Player, categoryName: String, requestedPage: Int, categoryIndex: Int) {
        val shop = service() ?: return openMessage(player, "Магазин мебели недоступен", "Сервис магазина временно не готов. Попробуйте ещё раз.")
        val offers = runCatching { shop.furnitureOffers(player, AMOUNT) }.getOrDefault(emptyList())
        val category = categoriesOf(offers).firstOrNull { it.name == categoryName }
            ?: return openCategories(player)
        val page = pageOf(category.offers, requestedPage)
        val buttons = page.entries.mapIndexed { index, offer ->
            PaperDialogButton(
                id = action("item_$index"),
                label = offerLabel(offer),
                tooltip = light("Открыть карточку и проверить цену перед покупкой."),
                width = BUTTON_WIDTH,
            ) { openDetails(player, offer, category.name, page.page, categoryIndex) }
        }.toMutableList()
        if (page.page > 0) {
            buttons += PaperDialogButton(
                id = action("previous"),
                label = light("‹ Предыдущая страница", PAGE_COLOR),
                width = BUTTON_WIDTH,
            ) { openCategory(player, category.name, page.page - 1, categoryIndex) }
        }
        if (page.page + 1 < page.pages) {
            buttons += PaperDialogButton(
                id = action("next"),
                label = light("Следующая страница ›", PAGE_COLOR),
                width = BUTTON_WIDTH,
            ) { openCategory(player, category.name, page.page + 1, categoryIndex) }
        }
        val screen = PaperDialogScreen(
            id = "furniture.shop.category.$categoryIndex.page.${page.page}",
            title = light(categoryTitle(category.name), TRADE_COLOR).decorate(TextDecoration.BOLD),
            body = listOf(
                PaperDialogBody(
                    light("Выберите предмет. Показана актуальная цена из магазина · страница ${page.page + 1}/${page.pages}."),
                    BODY_WIDTH,
                ),
            ),
            buttons = buttons,
            columns = 1,
        )
        open(player, screen) { openCategory(player, category.name, page.page, categoryIndex) }
    }

    private fun openDetails(player: Player, listed: FurnitureShopOffer, categoryName: String, page: Int, categoryIndex: Int) {
        val shop = service() ?: return openMessage(player, "Магазин мебели недоступен", "Сервис магазина временно не готов. Попробуйте ещё раз.")
        val current = runCatching { shop.furnitureOffer(player, listed.itemPath, AMOUNT) }.getOrNull()
        if (current == null) {
            openMessage(player, "Предмет недоступен", "Этот предмет больше нельзя купить.")
            return
        }
        val screen = PaperDialogScreen(
            id = "furniture.shop.details",
            title = display(current.displayName).decorate(TextDecoration.BOLD),
            body = listOf(
                DialogTables.body(
                    rows = listOf(
                        light("Предмет") to display(current.displayName),
                        light("Цена за 1 шт.") to price(current.totalPrice),
                    ),
                    frame = DialogTables.Frame.EPIC,
                    width = BODY_WIDTH,
                    columns = DialogTables.Columns.LABEL_WIDE,
                ),
            ),
            buttons = listOf(
                PaperDialogButton(
                    id = action("buy"),
                    label = light("Купить 1 шт.", SUCCESS_COLOR),
                    tooltip = light("Цена будет проверена ещё раз перед списанием."),
                    width = BUTTON_WIDTH,
                ) { confirmPurchase(player, current, categoryName, page, categoryIndex) },
            ),
            columns = 1,
        )
        open(player, screen) { openDetails(player, listed, categoryName, page, categoryIndex) }
    }

    private fun confirmPurchase(player: Player, quoted: FurnitureShopOffer, categoryName: String, page: Int, categoryIndex: Int) {
        val shop = service() ?: return openMessage(player, "Магазин мебели недоступен", "Сервис магазина временно не готов. Попробуйте ещё раз.")
        val current = runCatching { shop.furnitureOffer(player, quoted.itemPath, AMOUNT) }.getOrNull()
        if (current == null) {
            openMessage(player, "Покупка отменена", "Предмет больше недоступен по текущим условиям.")
            return
        }
        if (!sameQuote(quoted, current)) {
            openDetails(player, current, categoryName, page, categoryIndex)
            return
        }
        val outcome = runCatching { shop.purchase(player, current.itemPath, AMOUNT) }.getOrElse {
            return openMessage(player, "Покупка не выполнена", "Внутренняя ошибка магазина. Попробуйте ещё раз.")
        }
        openOutcome(player, outcome, categoryName, page, categoryIndex, current.displayName, current.totalPrice)
    }

    private fun openOutcome(
        player: Player,
        outcome: ShopPurchaseOutcome,
        categoryName: String,
        page: Int,
        categoryIndex: Int,
        displayName: String,
        totalPrice: Double,
    ) {
        val success = outcome.status == ShopPurchaseStatus.SUCCESS
        val item = plain(displayName.takeIf(String::isNotBlank) ?: outcome.itemName ?: outcome.itemPath)
        val message = when {
            success -> "Куплено: $item · ${formatPrice(totalPrice)}"
            outcome.status == ShopPurchaseStatus.INSUFFICIENT_FUNDS -> "Недостаточно средств для покупки $item."
            outcome.status == ShopPurchaseStatus.NO_INVENTORY_SPACE -> "В инвентаре недостаточно места."
            outcome.status == ShopPurchaseStatus.NO_PERMISSIONS -> "У вас нет доступа к этому предмету."
            outcome.status == ShopPurchaseStatus.REQUIREMENTS_FAILED -> "Условия покупки не выполнены."
            outcome.status == ShopPurchaseStatus.OUT_OF_STOCK -> "Предмет закончился на складе."
            outcome.status == ShopPurchaseStatus.TRANSACTION_CANCELLED -> "Покупка отменена."
            outcome.status == ShopPurchaseStatus.BELOW_MINIMUM -> "Количество меньше минимального для покупки."
            outcome.status == ShopPurchaseStatus.ABOVE_MAXIMUM -> "Количество больше максимального для покупки."
            else -> "Покупка не выполнена. Попробуйте ещё раз."
        }
        val color = if (success) SUCCESS_COLOR else ERROR_COLOR
        val screen = PaperDialogScreen(
            id = "furniture.shop.result",
            title = light(if (success) "Покупка выполнена" else "Покупка не выполнена", color)
                .decorate(TextDecoration.BOLD),
            body = listOf(PaperDialogBody(light(message), BODY_WIDTH)),
            buttons = listOf(
                PaperDialogButton(
                    id = action("catalog"),
                    label = light("‹ Вернуться к каталогу", WHITE),
                    width = BUTTON_WIDTH,
                ) { openCategory(player, categoryName, page, categoryIndex) },
            ),
            columns = 1,
        )
        open(player, screen) { openOutcome(player, outcome, categoryName, page, categoryIndex, displayName, totalPrice) }
    }

    private fun openMessage(player: Player, title: String, message: String) {
        val screen = PaperDialogScreen(
            id = "furniture.shop.message",
            title = light(title, ERROR_COLOR).decorate(TextDecoration.BOLD),
            body = listOf(PaperDialogBody(light(message), BODY_WIDTH)),
            buttons = emptyList(),
            columns = 1,
        )
        open(player, screen) { openMessage(player, title, message) }
    }

    private fun offerLabel(offer: FurnitureShopOffer): Component =
        light("○ ", WHITE)
            .append(display(offer.displayName))
            .append(light(" · ", WARM_COLOR))
            .append(price(offer.totalPrice))
            .append(light(" ›", TRADE_COLOR))

    private fun price(amount: Double): Component = light(formatPrice(amount), TRADE_COLOR)

    private fun categoryLabel(category: Category): Component =
        light("○ ", WHITE)
            .append(light(categoryTitle(category.name), PAGE_COLOR))
            .append(light(" · ${category.offers.size} поз. ›", PAGE_COLOR))

    private fun categoriesOf(offers: List<FurnitureShopOffer>): List<Category> =
        offers.groupBy { it.category }
            .toList()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
            .map { (name, entries) -> Category(name, entries) }

    private fun display(value: String): Component =
        LegacyComponentSerializer.legacySection().deserialize(value.replace('&', '§'))
            .decoration(TextDecoration.ITALIC, false)

    private fun plain(value: String): String =
        PlainTextComponentSerializer.plainText().serialize(display(value)).ifBlank { "предмет" }

    private fun light(value: String, color: TextColor = BODY_COLOR): Component =
        Component.text(value, color).decoration(TextDecoration.ITALIC, false)

    private fun action(value: String): PaperDialogActionId = PaperDialogActionId.of("furniture_$value")

    companion object {
        // Player labels from the existing Furniture section's category links.
        // Stable EconomyShopGUI section keys continue to own all purchases.
        internal fun categoryTitle(section: String): String = when (section.lowercase()) {
            "fountains" -> "Фонтаны"
            "casino" -> "Казино"
            "market" -> "Рынок"
            "smith" -> "Кузня"
            "farm" -> "Ферма"
            "halloween" -> "Хэллоуин"
            "royal" -> "Королевская мебель"
            "forest" -> "Лес"
            "park" -> "Парк"
            "school" -> "Школа"
            "china" -> "Китайская мебель"
            "summon" -> "Призыв"
            "egypt" -> "Египет"
            "dungeon" -> "Подземелья"
            "restaraunt", "restaurant" -> "Ресторан"
            else -> section
        }

        internal fun formatPrice(amount: Double): String =
            "${BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()} монет"

        private const val AMOUNT = 1
        private const val PAGE_SIZE = 12
        private const val BODY_WIDTH = 320
        private const val BUTTON_WIDTH = 320
        private val WHITE = TextColor.color(0xFFFFFF)
        private val BODY_COLOR = TextColor.color(0xE8DFD2)
        private val WARM_COLOR = TextColor.color(0xD7B486)
        private val TRADE_COLOR = TextColor.color(0xF4D87A)
        private val PAGE_COLOR = TextColor.color(0x92BED8)
        private val SUCCESS_COLOR = TextColor.color(0x9BD48D)
        private val ERROR_COLOR = TextColor.color(0xFF6B61)

        internal data class OfferPage(
            val page: Int,
            val pages: Int,
            val entries: List<FurnitureShopOffer>,
        )

        internal fun pageOf(offers: List<FurnitureShopOffer>, requestedPage: Int, pageSize: Int = PAGE_SIZE): OfferPage {
            require(pageSize > 0) { "Furniture shop page size must be positive" }
            val pages = ((offers.size + pageSize - 1) / pageSize).coerceAtLeast(1)
            val page = requestedPage.coerceIn(0, pages - 1)
            return OfferPage(page, pages, offers.drop(page * pageSize).take(pageSize))
        }

        internal fun sameQuote(expected: FurnitureShopOffer, current: FurnitureShopOffer): Boolean =
            expected.itemPath == current.itemPath &&
                expected.amount == current.amount &&
                expected.totalPrice.isFinite() &&
                current.totalPrice.isFinite() &&
                expected.totalPrice == current.totalPrice
    }
}
