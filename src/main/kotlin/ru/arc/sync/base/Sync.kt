package ru.arc.sync.base

import org.bukkit.event.Event
import java.util.UUID

interface Sync {
    fun forceSave(uuid: UUID) {}
    fun playerJoin(uuid: UUID) {}
    fun playerQuit(uuid: UUID) {}
    fun processEvent(event: Event) {}
}
