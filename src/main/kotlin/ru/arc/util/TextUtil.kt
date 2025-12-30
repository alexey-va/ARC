package ru.arc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import org.jetbrains.annotations.NotNull
import ru.arc.ARC
import ru.arc.configs.ConfigManager
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object TextUtil {

    @JvmStatic
    fun strip(component: Component?): Component? {
        if (component == null) return null
        return component.decoration(TextDecoration.ITALIC, false)
    }

    @JvmStatic
    fun parseTime(duration: Long, unit: TimeUnit): Component {
        val s = String.format("&a%d &eчасов", unit.toHours(duration))
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
    }

    @JvmStatic
    fun error(): Component {
        return Component.text("Произошла ошибка!", NamedTextColor.RED)
            .append(Component.text(" Обратитесь к администратуору", NamedTextColor.GRAY))
    }

    @JvmStatic
    fun mm(s: String): Component {
        return MiniMessage.miniMessage().deserialize(s)
    }

    @JvmStatic
    fun mm(s: String, strip: Boolean, vararg replacers: String): Component {
        val replace = mutableMapOf<String, String>()
        for (i in replacers.indices step 2) {
            if (replacers.size < i + 1) break
            replace[replacers[i]] = replacers[i + 1]
        }
        var result = s
        for ((key, value) in replace) {
            result = result.replace(key, value)
        }
        return mm(result, strip)
    }

    @JvmStatic
    fun mm(s: String, resolver: TagResolver): Component {
        return MiniMessage.miniMessage().deserialize(s, resolver)
    }

    @JvmStatic
    fun centerInLore(s: String, length: Int): String {
        val spaces = (length - s.length) / 2
        return " ".repeat(spaces) + s + " ".repeat(spaces)
    }

    @JvmStatic
    fun mm(s: String, strip: Boolean): Component {
        val component = MiniMessage.miniMessage().deserialize(s)
        return if (strip) strip(component)!! else component
    }

    @JvmStatic
    fun noMoneyMessage(player: Player, need: Double) {
        // ARC.econ is private, access via reflection or skip if not available
        try {
            val econField = ARC::class.java.getDeclaredField("econ")
            econField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val econ = econField.get(null) as? net.milkbowl.vault.economy.Economy ?: return
            val balance = econ.getBalance(player)
            val text = strip(
                Component.text("Недостаточно денег!", NamedTextColor.RED)
                    .append(Component.text(" Вам нужно еще ", NamedTextColor.GRAY))
                    .append(Component.text(formatAmount(need - balance), NamedTextColor.GREEN))
                    .append(Component.text("\uD83D\uDCB0", NamedTextColor.WHITE))
            )
            player.sendMessage(text!!)
        } catch (e: Exception) {
            // Economy not available
        }
    }

    @JvmStatic
    fun toLegacy(miniMessageString: String, vararg tagReplacers: String): String {
        val builder = TagResolver.builder()
        for (i in tagReplacers.indices step 2) {
            if (tagReplacers.size < i + 1) break
            builder.resolver(TagResolver.resolver(tagReplacers[i], Tag.inserting(mm(tagReplacers[i + 1], true))))
        }
        val resolver = builder.build()
        return LegacyComponentSerializer.legacyAmpersand()
            .serialize(strip(MiniMessage.miniMessage().deserialize(miniMessageString, resolver))!!)
    }

    @JvmStatic
    fun formatAmount(amount: Double): String {
        val symbols = DecimalFormatSymbols().apply {
            groupingSeparator = ','
        }
        if (Math.abs(amount) < 0.0001) return "0"
        if (Math.abs(amount) < 1) {
            return DecimalFormat("#,##0.###", symbols).format(amount)
        }
        if (Math.abs(amount) < 10) {
            return DecimalFormat("#,##0.##", symbols).format(amount)
        } else if (Math.abs(amount) < 1000) {
            return DecimalFormat("#,##0.#", symbols).format(amount)
        } else if (Math.abs(amount) < 100_000) {
            return DecimalFormat("#,##0.##K", symbols).format(amount / 1000.0)
        } else if (Math.abs(amount) < 1_000_000) {
            return DecimalFormat("#,##0.#K", symbols).format(amount / 1000)
        } else {
            return DecimalFormat("#,##0.#M", symbols).format(amount / 1_000_000)
        }
    }

    @JvmStatic
    fun formatAmount(amount: Double, precision: Int): String {
        val symbols = DecimalFormatSymbols().apply {
            groupingSeparator = ','
            decimalSeparator = '.'
        }

        if (Math.abs(amount) < Math.pow(10.0, -precision.toDouble())) {
            return "0"
        }

        val orderOfMagnitude = Math.floor(Math.log10(Math.abs(amount))).toInt()
        val digitsBeforeDecimal = Math.max(1, Math.min(precision + 1, orderOfMagnitude + 1))
        val digitsAfterDecimal = Math.max(0, precision + 1 - digitsBeforeDecimal)

        val patternBuilder = StringBuilder("#,##0")
        if (digitsAfterDecimal > 0) {
            patternBuilder.append('.')
            patternBuilder.append("#".repeat(digitsAfterDecimal))
        }

        val decimalFormat = DecimalFormat(patternBuilder.toString(), symbols)
        if (orderOfMagnitude >= 3 && orderOfMagnitude < 6 && precision < 3) {
            return decimalFormat.format(amount / 1000.0) + "K"
        } else if (orderOfMagnitude >= 6 && precision < 6) {
            return decimalFormat.format(amount / 1_000_000.0) + "M"
        }

        return decimalFormat.format(amount)
    }

    @JvmStatic
    fun noPermissions(): Component {
        return strip(
            Component.text("[", NamedTextColor.GRAY)
                .append(Component.text("!", NamedTextColor.DARK_RED))
                .append(Component.text("] ", NamedTextColor.GRAY))
                .append(Component.text("У вас нет на это разрешения!", NamedTextColor.DARK_RED))
        )!!
    }

    @JvmStatic
    fun noWGPermission(): Component {
        return strip(
            Component.text("Эй! ", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                .append(
                    Component.text("Ты не можешь здесь делать это.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false)
                )
        )!!
    }

    @JvmStatic
    fun randomBossBarColor(): String {
        val list = listOf("red", "blue", "white", "yellow")
        return list[ThreadLocalRandom.current().nextInt(list.size)]
    }

    @JvmStatic
    fun timeComponent(l: Long, timeUnit: TimeUnit): Component {
        return mm(time(l, timeUnit), true)
    }

    @JvmStatic
    fun time(l: Long, timeUnit: TimeUnit): String {
        val config = ConfigManager.of(ARC.plugin!!.dataPath, "config.yml")
        val format = config.string("time-format", "dd HH mm ss")
        val names = mapOf(
            "dd" to config.string("days", " дней"),
            "HH" to config.string("hours", " часов"),
            "mm" to config.string("minutes", " минут"),
            "ss" to config.string("seconds", " секунд")
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

    private val convertMap = mapOf(
        "<red>" to "&c",
        "<green>" to "&a",
        "<yellow>" to "&e",
        "<blue>" to "&9",
        "<gray>" to "&7",
        "<gold>" to "&6",
        "<white>" to "&f",
        "<black>" to "&0",
        "<dark_red>" to "&4",
        "<dark_green>" to "&2",
        "<dark_blue>" to "&1",
        "<dark_aqua>" to "&3",
        "<dark_purple>" to "&5",
        "<dark_gray>" to "&8",
        "<bold>" to "&l",
        "<italic>" to "&o",
        "<underline>" to "&n",
        "<strikethrough>" to "&m",
        "<obfuscated>" to "&k",
        "<reset>" to "&r"
    )

    @JvmStatic
    fun mmToLegacy(message: String): String {
        var result = message
        for ((key, value) in convertMap) {
            result = result.replace(key, value)
        }
        return result
    }

    @JvmStatic
    fun legacy(serializedMessage: String): Component {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(serializedMessage)
    }

    @JvmStatic
    fun plain(serializedMessage: String): Component {
        return Component.text(serializedMessage)
    }

    @JvmStatic
    fun playerOnly(): Component {
        return Component.text("Эта команда доступна только игрокам!", NamedTextColor.RED)
    }

    private val MINIMESSAGE_TAG_PATTERN = Pattern.compile("<[a-zA-Z_]+>")

    @JvmStatic
    fun splitLoreString(input: String?, maxLength: Int, nSpaces: Int): List<String> {
        if (input == null) return emptyList()
        val result = mutableListOf<String>()
        var currentLine = StringBuilder()
        var currentFormat = "" // Для сохранения текущего форматирования

        val indent = " ".repeat(nSpaces)
        val words = input.split(" ")

        for (word in words) {
            if (currentLine.length + word.length > maxLength) {
                result.add(currentLine.toString())
                currentLine = StringBuilder(currentFormat + indent)
            }

            if (isNonEmptyWithoutTags(currentLine)) {
                currentLine.append(" ")
            }

            currentLine.append(word)

            val matcher = MINIMESSAGE_TAG_PATTERN.matcher(word)
            while (matcher.find()) {
                currentFormat = matcher.group()
            }
        }

        if (currentLine.isNotEmpty()) {
            result.add(currentLine.toString())
        }

        return result
    }

    private fun isNonEmptyWithoutTags(line: StringBuilder): Boolean {
        val lineWithoutTags = line.toString().replace(MINIMESSAGE_TAG_PATTERN.pattern().toRegex(), "")
        return lineWithoutTags.trim().isNotEmpty()
    }

    @JvmStatic
    fun toMM(@NotNull component: Component): String {
        return MiniMessage.miniMessage().serialize(component)
    }

    @JvmStatic
    fun join(names: Set<Component>, s: String): Component {
        var result = Component.empty()
        val iterator = names.iterator()
        while (iterator.hasNext()) {
            val name = iterator.next()
            result = result.append(name)
            if (iterator.hasNext()) {
                result = result.append(mm(s))
            }
        }
        return result
    }
}

