package ru.arc.sync.base

import java.util.UUID

interface SyncData {
    fun timestamp(): Long
    fun server(): String
    fun uuid(): UUID?
    fun trash(): Boolean = false
}
