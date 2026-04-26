package ru.arc.core

/**
 * Base class for manager objects that follow the singleton facade pattern.
 * Provides lifecycle management (init, start, stop) for services.
 *
 * Usage:
 * ```kotlin
 * object FarmManager : ServiceManager<FarmService>() {
 *     override fun createService(): FarmService {
 *         val config = FarmModuleConfig.load(ARC.instance.dataPath)
 *         return FarmService(config, ...)
 *     }
 *
 *     // Add domain-specific methods
 *     @JvmStatic
 *     fun processEvent(event: BlockBreakEvent) {
 *         service?.processEvent(event)
 *     }
 * }
 * ```
 *
 * @param T The service type managed by this manager
 */
abstract class ServiceManager<T : Any> {
    /**
     * The service instance. Null if not initialized.
     */
    @Volatile
    @JvmField
    protected var service: T? = null

    /**
     * Initialize with default production dependencies.
     * Calls [createService] to instantiate the service.
     *
     * If the service implements [Lifecycle], calls [Lifecycle.start].
     *
     * Note: Subclasses should add @JvmStatic if they want Java compatibility.
     */
    open fun init() {
        stopService()
        service = createService()
        startService()
    }

    /**
     * Initialize with a custom service instance (for testing).
     * If the service implements [Lifecycle], stops the old one and starts the new one.
     *
     * @param customService The service instance to use
     *
     * Note: Subclasses should add @JvmStatic if they want Java compatibility.
     */
    open fun init(customService: T) {
        stopService()
        service = customService
        startService()
    }

    /**
     * Stop the service and clear the reference.
     * If the service implements [Lifecycle], calls [Lifecycle.stop].
     *
     * Note: Subclasses should add @JvmStatic if they want Java compatibility.
     */
    open fun cancel() {
        stopService()
        service = null
    }

    /**
     * Alias for [cancel] for backward compatibility.
     *
     * Note: Subclasses should add @JvmStatic if they want Java compatibility.
     */
    open fun clear() {
        cancel()
    }

    /**
     * Shutdown the service completely.
     * If the service implements [Lifecycle], calls [Lifecycle.shutdown] if available,
     * otherwise calls [Lifecycle.stop].
     *
     * Note: Subclasses should add @JvmStatic if they want Java compatibility.
     */
    open fun shutdown() {
        service?.let { svc ->
            when (svc) {
                is Lifecycle -> {
                    svc.shutdown()
                }

                else -> {
                    // Service doesn't implement Lifecycle, just null it
                }
            }
        }
        service = null
    }

    /**
     * Get the underlying service instance.
     * @return The service, or null if not initialized
     */
    open fun getService(): T? = service

    /**
     * Check if the service is initialized.
     */
    open fun isInitialized(): Boolean = service != null

    /**
     * Create the production service instance.
     * Called by [init] to instantiate the service with production dependencies.
     *
     * @return The service instance
     */
    protected abstract fun createService(): T

    /**
     * Start the service if it implements Lifecycle.
     */
    private fun startService() {
        (service as? Lifecycle)?.start()
    }

    /**
     * Stop the service if it implements Lifecycle.
     */
    private fun stopService() {
        (service as? Lifecycle)?.stop()
    }
}

/**
 * Interface for services that have a lifecycle.
 * Implement this interface in your service classes to enable automatic
 * lifecycle management by [ServiceManager].
 */
interface Lifecycle {
    /**
     * Start the service (begin background tasks, register listeners, etc.).
     */
    fun start()

    /**
     * Stop the service (cancel tasks, unregister listeners, but keep data).
     */
    fun stop()

    /**
     * Shutdown the service completely (stop + cleanup resources).
     * Default implementation just calls [stop].
     */
    fun shutdown() {
        stop()
    }
}
