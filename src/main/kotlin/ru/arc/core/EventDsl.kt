package ru.arc.core

import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

/**
 * Event DSL - A Kotlin DSL for registering Bukkit event listeners.
 *
 * Features:
 * - Type-safe event registration
 * - Fluent API with priority, cancellation, and filters
 * - One-time listeners (auto-unregister)
 * - Scoped listeners with lifecycle management
 * - Testable via EventBus abstraction
 *
 * Usage:
 * ```kotlin
 * // Simple listener
 * on<PlayerJoinEvent> { event ->
 *     event.player.sendMessage("Welcome!")
 * }
 *
 * // With priority and ignore cancelled
 * on<PlayerMoveEvent>(
 *     priority = EventPriority.HIGH,
 *     ignoreCancelled = true
 * ) { event ->
 *     // Handle movement
 * }
 *
 * // One-time listener (auto-unregisters)
 * once<PlayerJoinEvent> { event ->
 *     event.player.sendMessage("First join message!")
 * }
 *
 * // Filtered listener
 * on<PlayerJoinEvent>()
 *     .filter { it.player.hasPermission("vip") }
 *     .handler { player.sendMessage("VIP joined!") }
 *
 * // Scoped listeners (auto-cleanup)
 * val scope = eventScope()
 * scope.on<PlayerJoinEvent> { ... }
 * scope.on<PlayerQuitEvent> { ... }
 * // Later:
 * scope.unregisterAll()
 *
 * // Testing
 * val bus = TestEventBus()
 * bus.on<PlayerJoinEvent> { ... }
 * bus.fire(mockJoinEvent)
 * ```
 */

// ==================== Event Bus Interface ====================

/**
 * Abstraction over event handling for testability.
 */
interface EventBus {
    /**
     * Register a listener for an event type.
     */
    fun <T : Event> register(
        eventClass: KClass<T>,
        priority: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = false,
        handler: (T) -> Unit,
    ): EventRegistration

    /**
     * Unregister a specific listener.
     */
    fun unregister(registration: EventRegistration)

    /**
     * Unregister all listeners.
     */
    fun unregisterAll()
}

/**
 * Handle to a registered event listener.
 */
interface EventRegistration {
    val eventClass: KClass<out Event>
    val isRegistered: Boolean

    fun unregister()
}

// ==================== Bukkit Implementation ====================

/**
 * Production implementation using Bukkit event system.
 */
class BukkitEventBus(
    private val plugin: Plugin,
) : EventBus {
    private val registrations = CopyOnWriteArrayList<BukkitEventRegistration<*>>()

    override fun <T : Event> register(
        eventClass: KClass<T>,
        priority: EventPriority,
        ignoreCancelled: Boolean,
        handler: (T) -> Unit,
    ): EventRegistration {
        val listener = object : Listener {}

        @Suppress("UNCHECKED_CAST")
        val executor =
            EventExecutor { _: Listener, event: Event ->
                if (eventClass.isInstance(event)) {
                    handler(event as T)
                }
            }

        Bukkit.getPluginManager().registerEvent(
            eventClass.java,
            listener,
            priority,
            executor,
            plugin,
            ignoreCancelled,
        )

        val registration = BukkitEventRegistration(eventClass, listener, this)
        registrations.add(registration)
        return registration
    }

    override fun unregister(registration: EventRegistration) {
        if (registration is BukkitEventRegistration<*>) {
            HandlerList.unregisterAll(registration.listener)
            registrations.remove(registration)
        }
    }

    override fun unregisterAll() {
        registrations.forEach { HandlerList.unregisterAll(it.listener) }
        registrations.clear()
    }

    private class BukkitEventRegistration<T : Event>(
        override val eventClass: KClass<T>,
        val listener: Listener,
        private val bus: BukkitEventBus,
    ) : EventRegistration {
        private var registered = true

        override val isRegistered: Boolean get() = registered

        override fun unregister() {
            if (registered) {
                bus.unregister(this)
                registered = false
            }
        }
    }
}

// ==================== Test Implementation ====================

/**
 * Test implementation that stores handlers for manual event firing.
 */
class TestEventBus : EventBus {
    private val handlers = CopyOnWriteArrayList<TestHandler<*>>()

    override fun <T : Event> register(
        eventClass: KClass<T>,
        priority: EventPriority,
        ignoreCancelled: Boolean,
        handler: (T) -> Unit,
    ): EventRegistration {
        val testHandler = TestHandler(eventClass, priority, ignoreCancelled, handler)
        handlers.add(testHandler)
        return testHandler
    }

    override fun unregister(registration: EventRegistration) {
        handlers.remove(registration)
    }

    override fun unregisterAll() {
        handlers.clear()
    }

    /**
     * Fire an event to all registered handlers.
     * Returns number of handlers that received the event.
     */
    fun <T : Event> fire(event: T): Int {
        var count = 0
        handlers
            .filter { it.eventClass.isInstance(event) }
            .filter { it.isRegistered }
            .sortedBy { it.priority.ordinal }
            .forEach { handler ->
                @Suppress("UNCHECKED_CAST")
                val typedHandler = handler as TestHandler<T>

                // Check if should ignore cancelled
                if (typedHandler.ignoreCancelled && event is Cancellable && event.isCancelled) {
                    return@forEach
                }

                typedHandler.handler(event)
                count++
            }
        return count
    }

    /**
     * Check if any handler is registered for event type.
     */
    fun <T : Event> hasHandler(eventClass: KClass<T>): Boolean = handlers.any { it.eventClass == eventClass && it.isRegistered }

    /**
     * Get number of registered handlers.
     */
    fun handlerCount(): Int = handlers.count { it.isRegistered }

    /**
     * Get handlers for specific event type.
     */
    fun <T : Event> getHandlers(eventClass: KClass<T>): List<TestHandler<*>> =
        handlers.filter { it.eventClass == eventClass && it.isRegistered }

    class TestHandler<T : Event>(
        override val eventClass: KClass<T>,
        val priority: EventPriority,
        val ignoreCancelled: Boolean,
        val handler: (T) -> Unit,
    ) : EventRegistration {
        private var registered = true

        override val isRegistered: Boolean get() = registered

        override fun unregister() {
            registered = false
        }
    }
}

// ==================== Event Scope ====================

/**
 * A scope that manages multiple event registrations.
 * All listeners in a scope can be unregistered at once.
 */
class EventScope(
    @PublishedApi internal val bus: EventBus,
) {
    @PublishedApi
    internal val registrations = CopyOnWriteArrayList<EventRegistration>()

    /**
     * Register a listener in this scope.
     */
    inline fun <reified T : Event> on(
        priority: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = false,
        noinline handler: (T) -> Unit,
    ): EventRegistration {
        val registration = bus.register(T::class, priority, ignoreCancelled, handler)
        registrations.add(registration)
        return registration
    }

    /**
     * Register a one-time listener in this scope.
     */
    inline fun <reified T : Event> once(
        priority: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = false,
        noinline handler: (T) -> Unit,
    ): EventRegistration {
        var registrationRef: EventRegistration? = null
        // Use AtomicBoolean for thread-safe one-time handler
        val called =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val registration =
            bus.register(T::class, priority, ignoreCancelled) { event ->
                // Use compareAndSet to ensure only one thread executes the handler
                if (called.compareAndSet(false, true)) {
                    registrationRef?.let { reg ->
                        reg.unregister()
                        registrations.remove(reg)
                    }
                    handler(event)
                }
            }
        registrationRef = registration
        registrations.add(registration)
        return registration
    }

    /**
     * Unregister all listeners in this scope.
     */
    fun unregisterAll() {
        registrations.forEach { it.unregister() }
        registrations.clear()
    }

    /**
     * Number of active registrations.
     */
    fun count(): Int = registrations.count { it.isRegistered }
}

// ==================== Event Builder DSL ====================

/**
 * Builder for filtered event handlers.
 */
class EventHandlerBuilder<T : Event>(
    private val bus: EventBus,
    private val eventClass: KClass<T>,
) {
    private var priority: EventPriority = EventPriority.NORMAL
    private var ignoreCancelled: Boolean = false
    private var filters: MutableList<(T) -> Boolean> = mutableListOf()
    private var oneTime: Boolean = false
    private var timeout: Long? = null
    private var onTimeout: (() -> Unit)? = null

    /**
     * Set event priority.
     */
    fun priority(priority: EventPriority) = apply { this.priority = priority }

    /**
     * Ignore cancelled events.
     */
    fun ignoreCancelled(ignore: Boolean = true) = apply { this.ignoreCancelled = ignore }

    /**
     * Add a filter condition.
     */
    fun filter(predicate: (T) -> Boolean) = apply { filters.add(predicate) }

    /**
     * Make this a one-time listener (unregisters ONLY when filters pass).
     */
    fun once() = apply { this.oneTime = true }

    /**
     * Set timeout after which listener auto-unregisters (in ticks).
     * Useful for "wait for event with timeout" patterns.
     */
    fun timeout(
        ticks: Long,
        onTimeout: (() -> Unit)? = null,
    ) = apply {
        this.timeout = ticks
        this.onTimeout = onTimeout
    }

    /**
     * Register the handler.
     */
    fun handler(block: (T) -> Unit): EventRegistration {
        lateinit var registration: EventRegistration
        var timeoutTask: ScheduledTask? = null

        // Use AtomicBoolean for thread-safe one-time handler
        val called =
            if (oneTime) {
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            } else {
                null
            }

        val wrappedHandler: (T) -> Unit = { event ->
            // Check all filters - only proceed if ALL pass
            if (filters.all { it(event) }) {
                if (oneTime) {
                    // Use compareAndSet to ensure only one thread executes the handler
                    if (called!!.compareAndSet(false, true)) {
                        registration.unregister()
                        timeoutTask?.cancel()
                        block(event)
                    }
                } else {
                    block(event)
                }
            }
            // If filters don't pass - listener stays registered!
        }

        registration = bus.register(eventClass, priority, ignoreCancelled, wrappedHandler)

        // Setup timeout if specified
        timeout?.let { ticks ->
            timeoutTask =
                Tasks.scheduler.runLater(
                    ticks,
                    Runnable {
                        if (registration.isRegistered) {
                            registration.unregister()
                            onTimeout?.invoke()
                        }
                    },
                )
        }

        return registration
    }
}

// ==================== Awaitable Event DSL ====================

/**
 * Result of awaiting an event.
 */
sealed class AwaitResult<T> {
    data class Success<T>(
        val event: T,
    ) : AwaitResult<T>()

    class Timeout<T> : AwaitResult<T>()

    class Cancelled<T> : AwaitResult<T>()

    val isSuccess: Boolean get() = this is Success
    val isTimeout: Boolean get() = this is Timeout
    val isCancelled: Boolean get() = this is Cancelled

    fun getOrNull(): T? = (this as? Success)?.event

    inline fun onSuccess(block: (T) -> Unit): AwaitResult<T> {
        if (this is Success) block(event)
        return this
    }

    inline fun onTimeout(block: () -> Unit): AwaitResult<T> {
        if (this is Timeout) block()
        return this
    }
}

/**
 * Awaitable event handler - for "wait for specific event" patterns.
 */
class EventAwaiter<T : Event>(
    private val bus: EventBus,
    private val eventClass: KClass<T>,
) {
    private var priority: EventPriority = EventPriority.NORMAL
    private var ignoreCancelled: Boolean = false
    private var filters: MutableList<(T) -> Boolean> = mutableListOf()
    private var timeoutTicks: Long = 20 * 30 // Default 30 seconds

    fun priority(priority: EventPriority) = apply { this.priority = priority }

    fun ignoreCancelled(ignore: Boolean = true) = apply { this.ignoreCancelled = ignore }

    fun filter(predicate: (T) -> Boolean) = apply { filters.add(predicate) }

    fun timeout(ticks: Long) = apply { this.timeoutTicks = ticks }

    /**
     * Wait for event and execute callback when received (or timeout).
     * Returns registration that can be cancelled.
     */
    fun then(callback: (AwaitResult<T>) -> Unit): EventRegistration {
        lateinit var registration: EventRegistration
        // Use AtomicBoolean for thread-safe completed flag
        val completed =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        val timeoutTask =
            Tasks.scheduler.runLater(
                timeoutTicks,
                Runnable {
                    if (completed.compareAndSet(false, true) && registration.isRegistered) {
                        registration.unregister()
                        callback(AwaitResult.Timeout())
                    }
                },
            )

        registration =
            bus.register(eventClass, priority, ignoreCancelled) { event ->
                // Check filters BEFORE attempting to complete
                if (filters.all { it(event) } && completed.compareAndSet(false, true)) {
                    timeoutTask.cancel()
                    registration.unregister()
                    callback(AwaitResult.Success(event))
                }
            }

        return object : EventRegistration by registration {
            override fun unregister() {
                if (completed.compareAndSet(false, true)) {
                    timeoutTask.cancel()
                    registration.unregister()
                    callback(AwaitResult.Cancelled())
                }
            }
        }
    }
}

/**
 * Create an event awaiter - for "wait for specific event" patterns.
 *
 * Example:
 * ```kotlin
 * awaitEvent<PlayerInteractEvent>()
 *     .filter { it.player == targetPlayer }
 *     .filter { it.action == Action.RIGHT_CLICK_BLOCK }
 *     .timeout(600) // 30 seconds
 *     .then { result ->
 *         result.onSuccess { event ->
 *             player.sendMessage("You clicked!")
 *         }.onTimeout {
 *             player.sendMessage("Too slow!")
 *         }
 *     }
 * ```
 */
inline fun <reified T : Event> awaitEvent(): EventAwaiter<T> = EventAwaiter(Events.bus, T::class)

inline fun <reified T : Event> EventBus.awaitEvent(): EventAwaiter<T> = EventAwaiter(this, T::class)

// ==================== Global Event Functions ====================

/**
 * Default event bus accessor.
 * Override in tests by setting Events.bus.
 */
object Events {
    @Volatile
    @PublishedApi
    internal var bus: EventBus = LazyBukkitEventBus

    /**
     * Get current bus.
     */
    fun current(): EventBus = bus

    /**
     * Set custom bus.
     */
    fun use(eventBus: EventBus) {
        bus = eventBus
    }

    /**
     * Reset to default Bukkit event bus.
     */
    fun reset() {
        bus = LazyBukkitEventBus
    }

    /**
     * Use a test bus temporarily.
     */
    inline fun <T> withBus(
        testBus: EventBus,
        block: () -> T,
    ): T {
        val original = bus
        return try {
            bus = testBus
            block()
        } finally {
            bus = original
        }
    }

    /**
     * Create a new event scope.
     */
    fun scope(): EventScope = EventScope(bus)
}

/**
 * Lazy initializer for Bukkit event bus.
 */
private object LazyBukkitEventBus : EventBus {
    private val delegate: EventBus by lazy {
        BukkitEventBus(ARC.instance)
    }

    override fun <T : Event> register(
        eventClass: KClass<T>,
        priority: EventPriority,
        ignoreCancelled: Boolean,
        handler: (T) -> Unit,
    ) = delegate.register(eventClass, priority, ignoreCancelled, handler)

    override fun unregister(registration: EventRegistration) = delegate.unregister(registration)

    override fun unregisterAll() = delegate.unregisterAll()
}

// ==================== Global DSL Functions ====================

/**
 * Register an event listener.
 */
inline fun <reified T : Event> on(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    noinline handler: (T) -> Unit,
): EventRegistration = Events.bus.register(T::class, priority, ignoreCancelled, handler)

/**
 * Register a one-time event listener.
 */
inline fun <reified T : Event> once(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    noinline handler: (T) -> Unit,
): EventRegistration {
    var registrationRef: EventRegistration? = null
    val registration =
        Events.bus.register(T::class, priority, ignoreCancelled) { event ->
            registrationRef?.unregister()
            handler(event)
        }
    registrationRef = registration
    return registration
}

/**
 * Create an event handler builder for complex configurations.
 */
inline fun <reified T : Event> on(): EventHandlerBuilder<T> = EventHandlerBuilder(Events.bus, T::class)

/**
 * Create an event scope for managing multiple listeners.
 */
fun eventScope(): EventScope = Events.scope()

// ==================== EventBus Extension Functions ====================

/**
 * Register listener on a specific bus.
 */
inline fun <reified T : Event> EventBus.on(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    noinline handler: (T) -> Unit,
): EventRegistration = register(T::class, priority, ignoreCancelled, handler)

/**
 * Register one-time listener on a specific bus.
 */
inline fun <reified T : Event> EventBus.once(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    noinline handler: (T) -> Unit,
): EventRegistration {
    lateinit var registration: EventRegistration
    registration =
        register(T::class, priority, ignoreCancelled) { event ->
            registration.unregister()
            handler(event)
        }
    return registration
}

/**
 * Create builder for specific bus.
 */
inline fun <reified T : Event> EventBus.on(): EventHandlerBuilder<T> = EventHandlerBuilder(this, T::class)

/**
 * Create scope for specific bus.
 */
fun EventBus.scope(): EventScope = EventScope(this)

// ==================== Player Event Shortcuts ====================

/**
 * Shortcut for player events - automatically extracts player.
 */
inline fun <reified T : PlayerEvent> onPlayer(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    crossinline handler: T.(player: org.bukkit.entity.Player) -> Unit,
): EventRegistration =
    on<T>(priority, ignoreCancelled) { event ->
        event.handler(event.player)
    }

/**
 * Filter by player permission.
 */
fun <T : PlayerEvent> EventHandlerBuilder<T>.withPermission(permission: String) = filter { it.player.hasPermission(permission) }

/**
 * Filter by player world.
 */
fun <T : PlayerEvent> EventHandlerBuilder<T>.inWorld(worldName: String) = filter { it.player.world.name == worldName }
