package ru.arc.misc

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.EventManager
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.settings.PacketEventsSettings
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import java.util.UUID

class PluginUpdateNotificationsTest : StringSpec({
    val russian = "Доступна новая версия zAuctionHouse!\n   " +
        String(Character.toChars(0xF0F02)) + "Текущая: 4.0.1.4; новая: 4.0.1.4"
    val english = "There is a new version of zAuctionHouse available ! (current: 4.0.1.4 | latest: 4.1.0)"
    val redis = "(zAuctionHouse Redis) You do not use the latest version of the plugin! " +
        "Thank you for taking the latest version to avoid any risk of problem!"
    val redisDownload = "(zAuctionHouse Redis) Download plugin here: https://groupez.dev/resources/210"
    val models = "[FreeMinecraftModels] Your version of FreeMinecraftModels is outdated. " +
        "You can download the latest version from https://nightbreak.io/plugin/freeminecraftmodels/download/"
    val structures = "[BetterStructures] Your version of BetterStructures is outdated. " +
        "You can download the latest version from https://nightbreak.io/plugin/betterstructures/download/"

    "matches exact localized and upstream auction version notices with component and literal formatting" {
        isPluginUpdateNotice(Component.text("\uE51B $russian", NamedTextColor.RED)) shouldBe true
        isPluginUpdateNotice(Component.text(english)) shouldBe true
        isPluginUpdateNotice(MiniMessage.miniMessage().deserialize(
            "<#ff0000>There is a new version of zAuctionHouse available ! " +
                "<gray>(<white>current: <#ff9900>4.0.1.4 <gray>| <white>latest: <#00ff00>4.1.0<gray>)",
        )) shouldBe true
        isPluginUpdateNotice(Component.text("§8(§bzAuctionHouse Redis§8) §c" + redis.substringAfter(") "))) shouldBe true
    }

    "matches only the known Redis and Nightbreak update sentences and exact download URLs" {
        listOf(redis, redisDownload, models, structures, "[FreeMinecraftModels] $models", "[BetterStructures] $structures")
            .forEach { isPluginUpdateNotice(Component.text(it)) shouldBe true }
    }

    "keeps unrelated warnings, shop output, spy observations and near-miss update notices" {
        listOf(
            "[zAuctionHouse] Failed to connect to Redis",
            "[BetterStructures] Structure generation failed: your version is outdated",
            "[FreeMinecraftModels] Your version of FreeMinecraftModels is outdated.",
            models.replace("[FreeMinecraftModels]", "[OtherPlugin]"),
            models.replace("/freeminecraftmodels/", "/betterstructures/"),
            redisDownload.replace("/210", "/211"),
            redisDownload.substringAfter(") "),
            "Текущая: 4.0.1.4; новая: 4.0.1.4",
            "[Shop] $models",
            "[SocialSpy] Admin: $english",
            "Player: $russian",
            "$english Please update now.",
            "Auction item sold for 210 coins",
        ).forEach { isPluginUpdateNotice(Component.text(it)) shouldBe false }
    }

    "packet adapter uses the UUID snapshot, excludes chat and overlays, and unregisters on close" {
        val previousApi = PacketEvents.getAPI()
        val api = mockk<PacketEventsAPI<Any>>(relaxed = true)
        every { api.settings } returns PacketEventsSettings()
        every { api.serverManager.version } returns ServerVersion.V_1_21_8
        PacketEvents.setAPI(api)
        val events = mockk<EventManager>(relaxed = true)
        val filter = PluginUpdateNotifications(events)
        val target = UUID.fromString("e3adaf48-cda5-40ef-9350-3fdbd149bc65")
        val other = UUID.fromString("c6dbd0d9-4e0b-4cba-b1f7-f73063c5c27d")
        fun checkPacket(
            recipient: UUID,
            expected: Boolean,
            overlay: Boolean = false,
            type: PacketType.Play.Server = PacketType.Play.Server.SYSTEM_CHAT_MESSAGE,
            text: String = redis,
        ) {
            val wrapper = WrapperPlayServerSystemChatMessage(overlay, Component.text(text))
            val event = mockk<PacketSendEvent>(relaxed = true)
            every { event.packetType } returns type
            every { event.user.uuid } returns recipient
            every { event.user.clientVersion } returns ServerVersion.V_1_21_8.toClientVersion()
            every { event.serverVersion } returns ServerVersion.V_1_21_8
            every { event.lastUsedWrapper } returns wrapper
            filter.onPacketSend(event)
            verify(exactly = if (expected) 1 else 0) { event.isCancelled = true }
        }
        try {
            checkPacket(target, expected = false)
            val configured = mutableListOf(target.toString())
            filter.reload(configured)
            configured.clear()
            checkPacket(target, expected = true)
            checkPacket(other, expected = false)
            checkPacket(target, expected = false, overlay = true)
            checkPacket(target, expected = false, type = PacketType.Play.Server.CHAT_MESSAGE)
            checkPacket(target, expected = false, type = PacketType.Play.Server.DISGUISED_CHAT)
            checkPacket(target, expected = false, type = PacketType.Play.Server.ACTION_BAR)
            checkPacket(target, expected = false, text = "[SocialSpy] $redis")
            filter.reload(listOf(other.toString()))
            checkPacket(target, expected = false)
            checkPacket(other, expected = true)
            filter.reload(emptyList())
            checkPacket(other, expected = false)
            filter.reload(listOf(target.toString()))
            filter.close()
            checkPacket(target, expected = false)
            verify(exactly = 1) { events.registerListener(filter) }
            verify(exactly = 1) { events.unregisterListener(filter) }
        } finally {
            PacketEvents.setAPI(previousApi)
        }
    }
})
