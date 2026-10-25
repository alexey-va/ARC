package ru.arc.listeners

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.flattener.ComponentFlattener
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class LegacyItemsAdderAttributesTest : FreeSpec({
    "live katana keeps current damage speed protection and mob counter in one native section" {
        val protection = Component.text("ЗАЩИЩЕНО", NamedTextColor.WHITE).decorate(TextDecoration.BOLD)
        val counter = Component.text("Мобов: ", NamedTextColor.DARK_AQUA).append(Component.text("259", NamedTextColor.WHITE))
        val damage = nativeAttribute("9", "attack_damage")
        val input = listOf(
            Component.empty(),
            Component.text("When in Main Hand:"),
            Component.text(" 11.5 Attack Damage", NamedTextColor.DARK_GREEN),
            Component.text(" 2 Attack Speed", NamedTextColor.DARK_GREEN),
            protection,
            Component.empty(),
            Component.text("When in Main Hand:"),
            Component.text(" 12 Attack Damage", NamedTextColor.DARK_GREEN),
            counter,
            Component.empty(),
            Component.translatable("item.modifiers.mainhand"),
            damage,
        )

        val output = requireNotNull(LegacyItemsAdderAttributes.normalizeLore(input))

        equal(listOf("", "item.modifiers.mainhand", " 9 attribute.name.attack_damage",
            " 2 attribute.name.attack_speed", "", "ЗАЩИЩЕНО", "Мобов: 259"), output.map(plain::serialize))
        equal(damage, output[2])
        equal(protection, output[5])
        equal(counter, output[6])
        isNull(LegacyItemsAdderAttributes.normalizeLore(output))
    }

    "native rows override legacy values while authored lore and other equipment sections remain exact" {
        val before = Component.text("История оружия", NamedTextColor.AQUA)
        val after = Component.text("При использовании исчезает")
        val offhand = Component.translatable("item.modifiers.offhand")
        val input = listOf(before, Component.empty(), Component.text("When in Main Hand:"),
            Component.text(" 12 Attack Damage"), Component.text(" 4 Attack Speed"), after,
            Component.empty(), Component.translatable("item.modifiers.mainhand"),
            nativeAttribute("9", "attack_damage"), nativeAttribute("2", "attack_speed"),
            Component.empty(), offhand, nativeAttribute("1", "attack_damage"))

        val output = requireNotNull(LegacyItemsAdderAttributes.normalizeLore(input))

        equal(listOf("История оружия", "", "item.modifiers.mainhand", " 9 attribute.name.attack_damage",
            " 2 attribute.name.attack_speed", "", "При использовании исчезает", "", "item.modifiers.offhand",
            " 1 attribute.name.attack_damage"), output.map(plain::serialize))
        equal(before, output.first())
        equal(after, output[6])
        equal(offhand, output[8])
    }

    "AE and grindstone flattened katana keeps the latest stats in one translated section" {
        val protection = Component.text("ЗАЩИЩЕНО", NamedTextColor.WHITE).decorate(TextDecoration.BOLD)
        val counter = Component.text("Мобов: ", NamedTextColor.DARK_AQUA).append(Component.text("259", NamedTextColor.WHITE))
        val input = listOf(
            Component.empty(), Component.text("When in Main Hand:", NamedTextColor.GRAY),
            Component.text(" 11.5 Attack Damage", NamedTextColor.DARK_GREEN),
            Component.text(" 2 Attack Speed", NamedTextColor.DARK_GREEN),
            protection,
            Component.empty(), Component.text("When in Main Hand:", NamedTextColor.GRAY),
            Component.text(" 12 Attack Damage", NamedTextColor.DARK_GREEN),
            counter,
            Component.empty(), Component.text("When in Main Hand:", NamedTextColor.GRAY),
            Component.text(" 9 Attack Damage", NamedTextColor.DARK_GREEN),
            Component.empty(), Component.text("When in Main Hand:", NamedTextColor.GRAY),
            Component.text(" 9 Attack Damage", NamedTextColor.DARK_GREEN),
        )

        val output = requireNotNull(LegacyItemsAdderAttributes.normalizeLore(input))

        equal(listOf("", "item.modifiers.mainhand", " 9 attribute.name.attack_damage",
            " 2 attribute.name.attack_speed", "", "ЗАЩИЩЕНО", "Мобов: 259"), output.map(plain::serialize))
        equal(NamedTextColor.GRAY, output[1].color())
        equal(protection, output[5])
        equal(counter, output[6])
        isNull(LegacyItemsAdderAttributes.normalizeLore(output))
    }

    "single legacy native only and unknown attribute sections stay untouched" {
        val legacy = listOf(Component.text("When in Main Hand:"), Component.text(" 12 Attack Damage"))
        val native = listOf(Component.translatable("item.modifiers.mainhand"), nativeAttribute("9", "attack_damage"))
        isNull(LegacyItemsAdderAttributes.normalizeLore(legacy))
        equal(listOf("", "item.modifiers.mainhand", " 12 attribute.name.attack_damage"),
            requireNotNull(LegacyItemsAdderAttributes.normalizeLore(legacy + legacy)).map(plain::serialize))
        isNull(LegacyItemsAdderAttributes.normalizeLore(native))
        isNull(LegacyItemsAdderAttributes.normalizeLore(legacy + native + Component.text(" 3 Spell Power")))
        isNull(LegacyItemsAdderAttributes.normalizeLore(listOf(Component.text("When in Main Hand:"),
            Component.text("Урон зависит от цели")) + native))
    }

})

        private val plain = PlainTextComponentSerializer.builder()
        .flattener(ComponentFlattener.basic().toBuilder().mapper(TranslatableComponent::class.java) { it.key() }.build())
        .build()

        private fun nativeAttribute(value: String, attribute: String): Component =
            Component.text(" $value ", NamedTextColor.DARK_GREEN)
                .append(Component.translatable("attribute.name.$attribute", NamedTextColor.DARK_GREEN))

private fun <T> equal(expected: T, actual: T) { actual shouldBe expected }
private fun isNull(actual: Any?) { actual shouldBe null }
