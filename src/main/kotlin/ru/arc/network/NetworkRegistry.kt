package ru.arc.network

import ru.arc.ai.assistant.ToolMessanger
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.lands.LandsMessager
import ru.arc.hooks.zauction.AuctionMessager
import ru.arc.stock.HistoryManager
import ru.arc.stock.HistoryMessager
import ru.arc.xserver.playerlist.PlayerListMessager

class NetworkRegistry(private val redisManager: RedisManager) {

    private lateinit var playerListMessager: PlayerListMessager

    companion object {
        @JvmField
        var landsMessager: LandsMessager? = null
    }

    fun init() {
        playerListMessager = PlayerListMessager("arc.proxy_player_list")
        redisManager.registerChannelUnique(playerListMessager.channel, playerListMessager)

        landsMessager = LandsMessager(redisManager, "arc.lands_req", "arc.lands_response")
        landsMessager!!.init()
        redisManager.registerChannelUnique(landsMessager!!.respChannel, landsMessager!!)
        redisManager.registerChannelUnique(landsMessager!!.reqChannel, landsMessager!!)

        val historyMessager = HistoryMessager("arc.high_lows_update", redisManager)
        redisManager.registerChannelUnique(historyMessager.channel, historyMessager)
        HistoryManager.setMessager(historyMessager)

        if (HookRegistry.auctionHook != null) {
            val auctionMessager = AuctionMessager("arc.auction_items", "arc.auction_items_all", redisManager)
            redisManager.registerChannelUnique(auctionMessager.channel, auctionMessager)
            redisManager.registerChannelUnique(auctionMessager.channelAll, auctionMessager)
            HookRegistry.auctionHook!!.auctionMessager = auctionMessager
        }

        val toolMessanger = ToolMessanger()
        redisManager.registerChannelUnique(ToolMessanger.CHANNEL_REQUEST_TOOLS, toolMessanger)
    }
}
