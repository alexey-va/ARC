package ru.arc.core

/**
 * Abstraction over time source for testability.
 *
 * In production, uses system time. In tests, can be mocked
 * to control time-dependent behavior.
 */
interface TimeProvider {
    /**
     * Current time in milliseconds.
     */
    fun currentTimeMillis(): Long

    /**
     * Current time in nanoseconds (for high-precision timing).
     */
    fun nanoTime(): Long
}

/**
 * Production implementation using system time.
 */
object SystemTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun nanoTime(): Long = System.nanoTime()
}

/**
 * Test implementation with controllable time.
 */
class TestTimeProvider(private var currentTime: Long = 0L) : TimeProvider {
    private var nanoOffset = 0L

    override fun currentTimeMillis(): Long = currentTime
    override fun nanoTime(): Long = currentTime * 1_000_000 + nanoOffset

    /**
     * Set current time.
     */
    fun setTime(millis: Long) {
        currentTime = millis
    }

    /**
     * Advance time by specified milliseconds.
     */
    fun advance(millis: Long) {
        currentTime += millis
    }

    /**
     * Advance time by specified ticks (1 tick = 50ms).
     */
    fun advanceTicks(ticks: Long) {
        currentTime += ticks * 50
    }
}

