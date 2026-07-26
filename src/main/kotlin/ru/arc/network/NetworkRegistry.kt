package ru.arc.network

import ru.arc.hooks.HookRegistry
import ru.arc.hooks.lands.LandsMessager
import ru.arc.hooks.zauction.AuctionMessager
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.arc.stock.HistoryManager
import ru.arc.stock.HistoryMessager
import ru.arc.xserver.playerlist.PlayerListMessager

class NetworkRegistry(
    private val redis: RedisOperations,
) : AutoCloseable {
    private val registrations = mutableListOf<Pair<String, ChannelListener>>()
    private var landBridge: LandsMessager? = null
    private var historyBridge: HistoryMessager? = null
    private var auctionBridge: AuctionMessager? = null

    companion object {
        @JvmField
        var landsMessager: LandsMessager? = null
    }

    @Synchronized
    fun init() {
        close()
        try {
            val playerList = PlayerListMessager("arc.proxy_player_list")
            register(playerList.channel, playerList)

            val lands = LandsMessager(redis, "arc.lands_req", "arc.lands_response")
            lands.init()
            landBridge = lands
            landsMessager = lands
            register(lands.respChannel, lands)
            register(lands.reqChannel, lands)

            val history = HistoryMessager("arc.high_lows_update", redis)
            historyBridge = history
            register(history.channel, history)
            HistoryManager.setMessager(history)

            HookRegistry.auctionHook?.let { hook ->
                val auction = AuctionMessager("arc.auction_items", "arc.auction_items_all", redis)
                auctionBridge = auction
                register(auction.channel, auction)
                register(auction.channelAll, auction)
                hook.auctionMessager = auction
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        registrations.asReversed().forEach { (channel, listener) ->
            redis.unregisterChannel(channel, listener)
        }
        registrations.clear()

        landBridge?.close()
        if (landsMessager === landBridge) landsMessager = null
        landBridge = null

        historyBridge?.let(HistoryManager::clearMessager)
        historyBridge = null

        auctionBridge?.let { bridge ->
            HookRegistry.auctionHook?.let { hook ->
                if (hook.auctionMessager === bridge) hook.auctionMessager = null
            }
        }
        auctionBridge = null
    }

    fun shutdown() = close()

    private fun register(channel: String, listener: ChannelListener) {
        redis.registerChannelUnique(channel, listener)
        registrations += channel to listener
    }
}
