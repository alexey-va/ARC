package ru.arc.ai

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

class ChatHistory(val playerUuid: UUID, val maxLength: Int) {

    private val deque = ConcurrentLinkedDeque<Entry>()

    fun addPlayerMessage(message: String) {
        deque.add(Entry(message, isPlayer = true, timestamp = System.currentTimeMillis()))
    }

    fun addBotMessage(message: String) {
        deque.add(Entry(message, isPlayer = false, timestamp = System.currentTimeMillis()))
    }

    fun entries(): Collection<Entry> = deque

    fun clean(olderThan: Long) {
        while (!deque.isEmpty() && (deque.peek().timestamp < olderThan || deque.size > maxLength)) {
            deque.poll()
        }
    }

    data class Entry(val text: String, val isPlayer: Boolean, val timestamp: Long)
}
