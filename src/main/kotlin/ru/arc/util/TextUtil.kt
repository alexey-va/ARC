package ru.arc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.jetbrains.annotations.NotNull
import ru.arc.ARC
import ru.arc.config.ConfigManager
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * Paper-specific text helpers. Platform-agnostic formatting lives in [TextUtils].
 */
object TextUtil {

    @JvmStatic
    fun strip(component: Component?): Component? = TextUtils.strip(component)

    @JvmStatic
    fun parseTime(duration: Long, unit: TimeUnit): Component {
        val s = String.format("&a%d &eчасов", unit.toHours(duration))
        return TextUtils.legacy(s)
    }

    @JvmStatic
    fun error(): Component =
        Component.text("Произошла ошибка!", NamedTextColor.RED)
            .append(Component.text(" Обратитесь к администратуору", NamedTextColor.GRAY))

    @JvmStatic
    fun mm(s: String): Component = TextUtils.mm(s)

    @JvmStatic
    fun mm(s: String, strip: Boolean, vararg replacers: String): Component = TextUtils.mm(s, strip, *replacers)

    @JvmStatic
    fun mm(s: String, resolver: TagResolver): Component = TextUtils.mm(s, resolver)

    @JvmStatic
    fun centerInLore(s: String, length: Int): String = TextUtils.centerInLore(s, length)

    @JvmStatic
    fun mm(s: String, strip: Boolean): Component = TextUtils.mm(s, strip)

    @JvmStatic
    fun noMoneyMessage(player: Player, need: Double) {
        try {
            val econField = ARC::class.java.getDeclaredField("econ")
            econField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val econ = econField.get(null) as? net.milkbowl.vault.economy.Economy ?: return
            val balance = econ.getBalance(player)
            val text =
                strip(
                    Component.text("Недостаточно денег!", NamedTextColor.RED)
                        .append(Component.text(" Вам нужно еще ", NamedTextColor.GRAY))
                        .append(Component.text(formatAmount(need - balance), NamedTextColor.GREEN))
                        .append(Component.text("\uD83D\uDCB0", NamedTextColor.WHITE)),
                )
            player.sendMessage(text!!)
        } catch (_: Exception) {
            // Economy not available
        }
    }

    @JvmStatic
    fun toLegacy(miniMessageString: String, vararg tagReplacers: String): String =
        TextUtils.toLegacy(miniMessageString, *tagReplacers)

    @JvmStatic
    fun formatAmount(amount: Double): String = TextUtils.formatAmount(amount)

    @JvmStatic
    fun formatAmount(amount: Double, precision: Int): String = TextUtils.formatAmount(amount, precision)

    @JvmStatic
    fun noPermissions(): Component =
        strip(
            Component.text("[", NamedTextColor.GRAY)
                .append(Component.text("!", NamedTextColor.DARK_RED))
                .append(Component.text("] ", NamedTextColor.GRAY))
                .append(Component.text("У вас нет на это разрешения!", NamedTextColor.DARK_RED)),
        )!!

    @JvmStatic
    fun noWGPermission(): Component =
        strip(
            Component.text("Эй! ", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                .append(
                    Component.text("Ты не можешь здесь делать это.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false),
                ),
        )!!

    @JvmStatic
    fun randomBossBarColor(): String {
        val list = listOf("red", "blue", "white", "yellow")
        return list[ThreadLocalRandom.current().nextInt(list.size)]
    }

    @JvmStatic
    fun timeComponent(l: Long, timeUnit: TimeUnit): Component = mm(time(l, timeUnit), true)

    @JvmStatic
    fun time(l: Long, timeUnit: TimeUnit): String {
        val config = ConfigManager.of(ARC.instance.dataPath, "config.yml")
        val format = config.string("time-format", "dd HH mm ss")
        val names =
            mapOf(
                "dd" to config.string("days", " дней"),
                "HH" to config.string("hours", " часов"),
                "mm" to config.string("minutes", " минут"),
                "ss" to config.string("seconds", " секунд"),
            )
        val days = timeUnit.toDays(l).toInt()
        val hours = (timeUnit.toHours(l) % 24).toInt()
        val minutes = (timeUnit.toMinutes(l) % 60).toInt()
        val seconds = (timeUnit.toSeconds(l) % 60).toInt()

        var s = format
        s = if (days == 0) s.replace("dd", "") else s.replace("dd", days.toString() + names["dd"])
        s = if (hours == 0) s.replace("HH", "") else s.replace("HH", hours.toString() + names["HH"])
        s = if (minutes == 0) s.replace("mm", "") else s.replace("mm", minutes.toString() + names["mm"])
        s = if (seconds == 0) s.replace("ss", "") else s.replace("ss", seconds.toString() + names["ss"])
        return s.trim()
    }

    @JvmStatic
    fun mmToLegacy(message: String): String = TextUtils.mmToLegacy(message)

    @JvmStatic
    fun legacy(serializedMessage: String): Component = TextUtils.legacy(serializedMessage)

    @JvmStatic
    fun plain(serializedMessage: String): Component = TextUtils.text(serializedMessage)

    @JvmStatic
    fun playerOnly(): Component =
        Component.text("Эта команда доступна только игрокам!", NamedTextColor.RED)

    @JvmStatic
    fun splitLoreString(input: String?, maxLength: Int, nSpaces: Int): List<String> =
        TextUtils.splitLoreString(input, maxLength, nSpaces)

    @JvmStatic
    fun toMM(@NotNull component: Component): String = TextUtils.toMM(component)

    @JvmStatic
    fun join(names: Set<Component>, s: String): Component = TextUtils.join(names, s)
}
