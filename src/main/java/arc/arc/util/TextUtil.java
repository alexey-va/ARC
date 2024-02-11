package arc.arc.util;

import arc.arc.ARC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.temporal.TemporalUnit;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class TextUtil {

    public static Component strip(Component component) {
        if (component == null) return null;
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static Component parseTime(long duration, TimeUnit unit) {
        String s = String.format("&a%d &eчасов",
                unit.toHours(duration)
        );
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }


    public static Component mm(String s) {
        return MiniMessage.miniMessage().deserialize(s);
    }

    public static void noMoneyMessage(Player player, double need) {
        double balance = ARC.getEcon().getBalance(player);
        Component text = strip(
                Component.text("Недостаточно денег!", NamedTextColor.RED)
                        .append(Component.text(" Вам нужно еще ", NamedTextColor.GRAY))
                        .append(Component.text(TextUtil.formatAmount(need - balance), NamedTextColor.GREEN))
                        .append(Component.text("\uD83D\uDCB0", NamedTextColor.WHITE))
        );
        player.sendMessage(text);
    }

    public static String formatAmount(double amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator('\'');
        if (amount < 1000) {
            // Format with 1 digit after the decimal point
            return new DecimalFormat("#,##0.0", symbols).format(amount);
        } else if (amount < 1_000_000) {
            // Format in the format 2.2K
            return new DecimalFormat("#,##0.0K", symbols).format(amount / 1000);
        } else {
            // Format in the format 2.2M
            return new DecimalFormat("#,##0.0M", symbols).format(amount / 1_000_000);
        }
    }

    public static Component noPermissions() {
        return strip(Component
                .text("[", NamedTextColor.GRAY)
                .append(Component.text("!", NamedTextColor.DARK_RED))
                .append(Component.text("] ", NamedTextColor.GRAY))
                .append(Component.text("У вас нет на это разрешения!", NamedTextColor.DARK_RED)));
    }

    public static Component noWGPermission() {
        return strip(Component.text("Эй! ", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                .append(Component.text("Ты не можешь здесь делать это.", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false)));
    }


    public static String randomBossBarColor() {
        List<String> list = List.of("red", "blue", "white", "yellow");
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

}
