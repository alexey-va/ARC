package ru.arc.spy

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

object SpyMessageRenderer {
    private val miniMessage = MiniMessage.miniMessage()

    fun render(
        message: SpyRelayMessage,
        serverLabel: String,
        settings: CrossServerSpySettings,
    ): Component =
        miniMessage.deserialize(
            if (message.type == SpyRelayType.CHAT) settings.privateMessageTemplate else settings.commandTemplate,
            literal("server", serverLabel),
            literal("sender", message.senderName),
            literal("target", message.targetName.orEmpty()),
            literal("content", message.content),
        )

    private fun literal(
        name: String,
        value: String,
    ): TagResolver = TagResolver.resolver(name, Tag.inserting(Component.text(value)))
}
