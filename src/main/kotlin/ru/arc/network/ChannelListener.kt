package ru.arc.network

fun interface ChannelListener {
    fun consume(channel: String, message: String, originServer: String)
}
