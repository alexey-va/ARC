package ru.arc.spy

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

object SpyMessageRenderer {
    private val miniMessage = MiniMessage.miniMessage()

    fun render(
        message: SpyRelayMessage,
        serverLabel: String,
        settings: CrossServerSpySettings,
    ): Component {
        val serverHover =
            miniMessage.deserialize(
                settings.serverHoverTemplate,
                literal("server", serverLabel),
            )
        val marker =
            when (message.type) {
                SpyRelayType.COMMAND ->
                    Component.text("C", NamedTextColor.DARK_PURPLE)
                        .append(Component.text("Spy", NamedTextColor.DARK_GREEN))
                SpyRelayType.CHAT -> Component.text("Spy", NamedTextColor.DARK_GREEN)
            }.hoverEvent(serverHover)

        return miniMessage.deserialize(
            if (message.type == SpyRelayType.CHAT) settings.privateMessageTemplate else settings.commandTemplate,
            component("marker", marker),
            literal("sender", message.senderName),
            literal("target", message.targetName.orEmpty()),
            literal("content", message.content),
        )
    }

    private fun literal(
        name: String,
        value: String,
    ): TagResolver = TagResolver.resolver(name, Tag.inserting(Component.text(value)))

    private fun component(
        name: String,
        value: Component,
    ): TagResolver = TagResolver.resolver(name, Tag.inserting(value))
}
