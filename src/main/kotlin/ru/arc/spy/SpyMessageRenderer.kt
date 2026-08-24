package ru.arc.spy

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor

object SpyMessageRenderer {
    private val identity = color("#92bed8")
    private val divider = color("#666666")
    private val body = color("#e6fff3")
    private val structure = color("#8c8c8c")
    private val metadata = color("#969696")
    private val player = color("#d8b892")

    fun render(message: SpyRelayMessage, serverLabel: String): Component {
        var component =
            Component.text("[", structure)
                .append(Component.text(serverLabel, metadata))
                .append(Component.text("] ", structure))
                .append(Component.text(if (message.type == SpyRelayType.CHAT) "ЛС" else "Команда", identity))
                .append(Component.text(" • ", divider))
                .append(Component.text(message.senderName, player))

        component =
            if (message.type == SpyRelayType.CHAT) {
                component
                    .append(Component.text(" → ", structure))
                    .append(Component.text(checkNotNull(message.targetName), player))
                    .append(Component.text(": ", structure))
            } else {
                component.append(Component.text(": ", structure))
            }

        return component.append(Component.text(message.content, body))
    }

    private fun color(hex: String): TextColor = checkNotNull(TextColor.fromHexString(hex))
}
