package arc.arc.stock;

import lombok.*;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static arc.arc.util.TextUtil.formatAmount;
import static arc.arc.util.TextUtil.mm;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class Position {

    String symbol;
    double startPrice;
    double leverage;
    double upperBoundMargin, lowerBoundMargin;
    double commission;
    long timestamp;
    UUID positionUuid;
    Type type;
    double amount;
    Material iconMaterial = Material.PAPER;
    double receivedDividend;

    public double gains(double currentPrice) {
        return (type == Type.BOUGHT ? 1 : -1) * (currentPrice - startPrice) * amount * leverage;
    }


    public double gains() {
        //System.out.println("Getting gains for " + symbol);
        double price = getStockPrice();
        if(price == 0.0){
            return 0;
        }
        return gains(price);
    }

    public int marginCall(double currentPrice) {
        double gains = gains(currentPrice);
        if (gains > upperBoundMargin) return 1;
        else if (gains < 0 && -gains > lowerBoundMargin) return -1;
        return 0;
    }

    public Stock getStock() {
        return StockMarket.stock(symbol);
    }

    public double totalValue() {
        Stock stock = getStock();
        return gains(stock.price)*(leverage-1)+amount*stock.price;
    }

    public record BankruptResponse(boolean bankrupt, double total) {
    }

    public record AutoClosePrices(double low, double high){}
    public AutoClosePrices marginCallAtPrice(double balance, boolean isAutoTake){
        double bankruptPrice = startPrice - balance/amount/leverage;
        double lowMarginCallPrice = lowerBoundMargin > 1_000_000_000 ? -1 : startPrice - lowerBoundMargin/amount/leverage;
        double upperMarginCallPrice = upperBoundMargin > 1_000_000_000 ? -1 : startPrice + upperBoundMargin/amount/leverage;
        double low = Math.min(bankruptPrice, lowMarginCallPrice);
        if(low < 0) low = -1;
        return new AutoClosePrices(low, upperMarginCallPrice);
    }

    public TagResolver resolver() {
        DecimalFormat decimalFormat = new DecimalFormat();
        // Set the formatting pattern
        Instant timestampInstant = Instant.ofEpochMilli(this.getTimestamp());
        Duration timePassed = Duration.between(timestampInstant, Instant.now());
        Stock stock = getStock();
        double gains = gains(stock.price);
        decimalFormat.applyPattern(this.getAmount() >= 1 ? "###,###" : "0.###");
        return TagResolver.builder()
                .resolver(TagResolver.resolver("amount", Tag.inserting(
                        mm(formatAmount(this.getAmount()), true)
                )))
                .resolver(TagResolver.resolver("dividend_amount", Tag.inserting(
                        mm(formatAmount(this.getAmount()*stock.dividend), true)
                )))
                .resolver(TagResolver.resolver("position_gains", Tag.inserting(
                        mm(formatAmount(gains), true)
                )))
                .resolver(TagResolver.resolver("symbol", Tag.inserting(
                        mm(symbol, true)
                )))
                .resolver(TagResolver.resolver("total_position_gains", Tag.inserting(
                        mm(formatAmount(gains-commission), true)
                )))
                .resolver(TagResolver.resolver("total_gains_with_dividends", Tag.inserting(
                        mm(formatAmount(gains-commission+receivedDividend), true)
                )))
                .resolver(TagResolver.resolver("type", Tag.inserting(
                        mm(this.getType().display, true)
                )))
                .resolver(TagResolver.resolver("buy_price", Tag.inserting(
                        mm(formatAmount(commission+amount*startPrice), true)
                )))
                .resolver(TagResolver.resolver("uuid", Tag.inserting(
                        mm(this.getPositionUuid().toString().split("-")[0], true)
                )))
                .resolver(TagResolver.resolver("leverage", Tag.inserting(
                        mm(formatAmount(this.getLeverage()), true)
                )))
                .resolver(TagResolver.resolver("leveraged_price", Tag.inserting(
                        mm(formatAmount(leverage*amount*startPrice), true)
                )))
                .resolver(TagResolver.resolver("stock_price", Tag.inserting(
                        mm(formatAmount(this.getStockPrice()), true)
                )))
                .resolver(TagResolver.resolver("received_dividend", Tag.inserting(
                        mm(formatAmount(receivedDividend), true)
                )))
                .resolver(TagResolver.resolver("dividend", Tag.inserting(
                        mm(formatAmount(getStock().dividend), true)
                )))
                .resolver(TagResolver.resolver("upper", Tag.inserting(
                        upperBoundMargin > 1_000_000_000 ? mm("<red>Нет") :
                        mm(formatAmount(this.getUpperBoundMargin()), true)
                )))
                .resolver(TagResolver.resolver("lower", Tag.inserting(
                        lowerBoundMargin > 1_000_000_000 ? mm("<red>Нет") :
                        mm(formatAmount(this.getLowerBoundMargin()), true)
                )))
                .resolver(TagResolver.resolver("starting_price", Tag.inserting(
                        mm(formatAmount(this.getStartPrice()), true)
                )))
                .resolver(TagResolver.resolver("commission", Tag.inserting(
                        mm(formatAmount(this.getCommission()), true)
                )))
                .resolver(TagResolver.resolver("buy_price", Tag.inserting(
                        mm(formatAmount(this.getAmount() * this.getStartPrice() + this.getCommission()), true)
                )))
                .resolver(TagResolver.resolver("hours_since_bought", Tag.inserting(
                        mm(timePassed.toHours() + "", true)
                )))
                .build();
    }

    private double getStockPrice() {
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) {
            System.out.println("Could not find: " + symbol);
            return 0;
        }
        return stock.price;
    }

    public BankruptResponse bankrupt(double currentPrice, double currentBalance) {
        double gains = gains(currentPrice);
        if (gains + currentBalance >= 0) return new BankruptResponse(false, gains + currentBalance);
        else return new BankruptResponse(true, gains + currentBalance);
    }

    public enum Type {
        BOUGHT("<green>Покупка", "buy"), SHORTED("<red>Шорт", "short");

        public final String display;
        public final String command;

        Type(String display, String command) {
            this.display = display;
            this.command = command;
        }
    }
}
