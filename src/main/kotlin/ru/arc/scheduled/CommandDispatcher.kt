package ru.arc.scheduled

/**
 * Dispatches a console command. Abstracted for tests.
 */
fun interface CommandDispatcher {
    fun dispatch(command: String)
}
