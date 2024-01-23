package arc.arc.util;

import arc.arc.ARC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.time.temporal.TemporalUnit;
import java.util.concurrent.TimeUnit;

public class TextUtil {

    public static Component strip(Component component){
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static Component parseTime(long duration, TimeUnit unit){
        String s = String.format("&a%d &eчасов",
                unit.toHours(duration)
        );
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    public static void noMoneyMessage(Player player, double need){
        double balance = ARC.getEcon().getBalance(player);
        Component text = strip(
                Component.text("Недостаточно денег!", NamedTextColor.RED).append(
                        Component.text(" Вам нужно еще ", NamedTextColor.GRAY)
                ).append(
                        Component.text((int)(Math.ceil(need-balance)), NamedTextColor.GREEN)
                ).append(
                        Component.text("\uD83D\uDCB0", NamedTextColor.WHITE)
                )
        );
        player.sendMessage(text);
    }


    public static Component noPermissions(){
        return Component
                .text("[", NamedTextColor.GRAY)
                .append(Component.text("!", NamedTextColor.DARK_RED))
                .append(Component.text("] ", NamedTextColor.GRAY))
                .append(Component.text("У вас нет на это разрешения!", NamedTextColor.DARK_RED));
    }

}
