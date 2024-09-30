package arc.arc.util;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.configs.MainConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Map;
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

    public static Component error() {
        return Component.text("Произошла ошибка!", NamedTextColor.RED)
                .append(Component.text(" Обратитесь к администратуору", NamedTextColor.GRAY));
    }

    public static Component mm(String s) {
        return MiniMessage.miniMessage().deserialize(s);
    }

    public static Component mm(String s, TagResolver resolver) {
        return MiniMessage.miniMessage().deserialize(s, resolver);
    }

    public static String formatTime(long millis) {
        long days = millis / (24 * 60 * 60 * 1000);
        long hours = (millis % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
        long minutes = (millis % (60 * 60 * 1000)) / (60 * 1000);

        return String.format(MainConfig.timeFormat, days, hours, minutes);
    }

    public static String centerInLore(String s, int length) {
        int spaces = (length - s.length()) / 2;
        return " ".repeat(spaces) + s + " ".repeat(spaces);
    }

    public static Component mm(String s, boolean strip) {
        Component component = MiniMessage.miniMessage().deserialize(s);
        return strip ? strip(component) : component;
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

    public static String toLegacy(String miniMessageString, String... tagReplacers) {
        TagResolver.Builder builder = TagResolver.builder();
        for (int i = 0; i < tagReplacers.length; i += 2) {
            if (tagReplacers.length < i + 1) break;
            builder.resolver(TagResolver.resolver(tagReplacers[i], Tag.inserting(mm(tagReplacers[i + 1], true))));
        }
        TagResolver resolver = builder.build();

        return LegacyComponentSerializer.legacyAmpersand().serialize(strip(MiniMessage.miniMessage().deserialize(miniMessageString, resolver)));
    }

    public static String formatAmount(double amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(',');
        if (Math.abs(amount) < 0.0001) return "0";
        if (Math.abs(amount) < 1) {
            return new DecimalFormat("#,##0.###", symbols).format(amount);
        }
        if (Math.abs(amount) < 10) {
            return new DecimalFormat("#,##0.##", symbols).format(amount);
        } else if (Math.abs(amount) < 1000) {
            // Format with 1 digit after the decimal point
            return new DecimalFormat("#,##0.#", symbols).format(amount);
        } else if (Math.abs(amount) < 100_000) {
            // Format with 1 digit after the decimal point
            return new DecimalFormat("#,##0.##K", symbols).format(amount / 1000.0);
        } else if (Math.abs(amount) < 1_000_000) {
            // Format in the format 2.2K
            return new DecimalFormat("#,##0.#K", symbols).format(amount / 1000);
        } else {
            // Format in the format 2.2M
            return new DecimalFormat("#,##0.#M", symbols).format(amount / 1_000_000);
        }
    }

    public static String formatAmount(double amount, int precision) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');

        if (Math.abs(amount) < Math.pow(10, -precision)) {
            return "0";
        }

        // Determine the order of magnitude of the amount
        int orderOfMagnitude = (int) Math.floor(Math.log10(Math.abs(amount)));

        // Calculate the number of digits before and after the decimal point
        int digitsBeforeDecimal = Math.max(1, Math.min(precision + 1, orderOfMagnitude + 1));
        int digitsAfterDecimal = Math.max(0, precision + 1 - digitsBeforeDecimal);

        // Construct the pattern based on the calculated digits
        StringBuilder patternBuilder = new StringBuilder("#,##0");
        if (digitsAfterDecimal > 0) {
            patternBuilder.append('.');
            patternBuilder.append("#".repeat(digitsAfterDecimal));
        }

        // Format the amount based on the constructed pattern
        DecimalFormat decimalFormat = new DecimalFormat(patternBuilder.toString(), symbols);
        // Check if 'K' or 'M' can be added based on the order of magnitude
        if (orderOfMagnitude >= 3 && orderOfMagnitude < 6 && precision < 3) {
            amount /= 1000.0;
            return decimalFormat.format(amount) + "K";
        } else if (orderOfMagnitude >= 6 && precision < 6) {
            amount /= 1_000_000.0;
            return decimalFormat.format(amount) + "M";
        }

        // Format the amount without 'K' or 'M'
        return decimalFormat.format(amount);
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

    public static Component timeComponent(long l, TimeUnit timeUnit) {
        Config config = ConfigManager.of(ARC.plugin.getDataPath(), "config.yml");
        String format = config.string("time-format", "dd HH mm ss");
        Map<String, String> names = Map.of(
                "dd", config.string("days", " дней"),
                "HH", config.string("hours", " часов"),
                "mm", config.string("minutes", " минут"),
                "ss", config.string("seconds", " секунд")
        );
        int days = (int) timeUnit.toDays(l);
        int hours = (int) timeUnit.toHours(l) % 24;
        int minutes = (int) timeUnit.toMinutes(l) % 60;
        int seconds = (int) timeUnit.toSeconds(l) % 60;

        String s = format;
        if (days == 0) s = s.replace("dd", "");
        else s = s.replace("dd", days + names.get("dd"));
        if (hours == 0) s = s.replace("HH", "");
        else s = s.replace("HH", hours + names.get("HH"));
        if (minutes == 0) s = s.replace("mm", "");
        else s = s.replace("mm", minutes + names.get("mm"));
        if (seconds == 0) s = s.replace("ss", "");
        else s = s.replace("ss", seconds + names.get("ss"));
        return mm(s.trim(), true);
    }

    private static Map<String, String> convertMap = Map.ofEntries(
            Map.entry("<red>", "&c"),
            Map.entry("<green>", "&a"),
            Map.entry("<yellow>", "&e"),
            Map.entry("<blue>", "&9"),
            Map.entry("<gray>", "&7"),
            Map.entry("<gold>", "&6"),
            Map.entry("<white>", "&f"),
            Map.entry("<black>", "&0"),
            Map.entry("<dark_red>", "&4"),
            Map.entry("<dark_green>", "&2"),
            Map.entry("<dark_blue>", "&1"),
            Map.entry("<dark_aqua>", "&3"),
            Map.entry("<dark_purple>", "&5"),
            Map.entry("<dark_gray>", "&8"),
            Map.entry("<bold>", "&l"),
            Map.entry("<italic>", "&o"),
            Map.entry("<underline>", "&n"),
            Map.entry("<strikethrough>", "&m"),
            Map.entry("<obfuscated>", "&k"),
            Map.entry("<reset>", "&r")
    );

    public static String mmToLegacy(String message) {
        for (Map.Entry<String, String> entry : convertMap.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        return message;
    }
}
