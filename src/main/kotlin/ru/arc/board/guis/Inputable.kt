package ru.arc.board.guis

import net.kyori.adventure.text.Component

interface Inputable {
    fun setParameter(n: Int, s: String)
    fun proceed()
    fun satisfy(input: String, id: Int): Boolean
    fun denyMessage(input: String, id: Int): Component
    fun startMessage(id: Int): Component

    /** When true, input is aborted and [onInputCancel] is called instead of [setParameter]. */
    fun isCancelInput(
        input: String,
        id: Int,
    ): Boolean = false

    fun onInputCancel(id: Int) {}
}
