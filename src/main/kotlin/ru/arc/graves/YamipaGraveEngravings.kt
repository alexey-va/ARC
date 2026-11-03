package ru.arc.graves

import io.josemmo.bukkit.plugin.renderer.FakeImage
import io.josemmo.bukkit.plugin.storage.ImageFile
import org.bukkit.Location
import org.bukkit.Rotation
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Main-thread, per-viewer 1x2 images. Assets/cache belong to ARC, never Yamipa's player library.
 * The caller owns visibility, received-chunk checks and the matching client collision mask.
 * A support location is the TOP tile: Yamipa places its frame one block toward [BlockFace].
 * No world entities, placements, player quotas, inventory or grave transactions are changed here.
 *
 * Exact .9 boundary: its public API cannot register an ephemeral shutdown owner or expose frame IDs,
 * and ARC's compile-only 1.3 has a different destroy descriptor. Resolve those bindings once below.
 * Yamipa must remain loaded until closeAsync completes. Its own disable finalizer retains unfinished
 * registrations; acknowledgement means packet sending ran, not that a Minecraft client rendered it.
 */
internal class YamipaGraveEngravings internal constructor(
    private val plugin: Plugin,
    private val backend: Backend?,
    initialError: String? = null,
    private val onFrameIds: (Handle, Set<Int>) -> Unit = { _, _ -> },
) : Listener, AutoCloseable {
    data class Health(val available: Boolean, val detail: String?, val retainedImages: Int)

    /** Test seam at the exact third-party boundary; no Yamipa types escape into the lifecycle owner. */
    internal interface Backend {
        val provider: Plugin
        fun create(player: Player, variant: Int, location: Location, face: BlockFace): Any
        fun register(image: Any)
        fun unregister(image: Any)
        fun onLoaded(image: Any, callback: Runnable)
        fun frameIds(image: Any): Set<Int>
        fun spawn(image: Any, player: Player)
        fun destroy(image: Any, player: Player): CompletableFuture<Void>
    }

    inner class Handle internal constructor(
        val player: Player,
        val grave: UUID,
        internal val image: Any,
    ) {
        @Volatile var ids: Set<Int> = emptySet()
            private set
        @Volatile private var retirement: CompletableFuture<Void>? = null
        val isValid: Boolean get() = !closed && !providerStopped && retirement == null

        internal fun loaded() {
            if (!isValid) return // A late decoder completion must not reinstall interaction IDs.
            try {
                val ready = checkNotNull(backend).frameIds(image).toSet()
                check(ready.size == 2 && ready.all { it > 0 }) { "Expected exactly two Yamipa frame IDs" }
                check(ready.none { idOwners[it]?.let { owner -> owner !== this } == true }) { "Duplicate Yamipa frame ID" }
                ids = ready
                ready.forEach { idOwners[it] = this }
                onFrameIds(this, ready)
            } catch (failure: Throwable) {
                report("Could not bind grave image interaction", failure)
                removeAsync()
            }
        }

        /** Detach IDs immediately, then retain native ownership until the ordered cleanup succeeds. */
        fun removeAsync(): CompletableFuture<Void> {
            retirement?.let { return it.copy() }
            val result = CompletableFuture<Void>()
            retirement = result
            ids.forEach { idOwners.remove(it, this) }
            val hadIds = ids.isNotEmpty()
            ids = emptySet()
            if (hadIds) runCatching { onFrameIds(this, emptySet()) }
                .onFailure { report("Could not retire grave image interaction", it) }
            try {
                val owner = checkNotNull(backend)
                // Clearing a callback must not prevent the actual native destroy attempt.
                runCatching { owner.onLoaded(image, Runnable {}) }
                    .onFailure { report("Could not clear grave image load callback", it) }
                owner.destroy(image, player).whenComplete { _, failure ->
                    if (failure != null) {
                        // Yamipa.stop must still see this image, including staged/in-flight frame IDs.
                        report("Grave image cleanup failed; retained for Yamipa shutdown", failure)
                        result.completeExceptionally(failure)
                    } else {
                        try {
                            owner.unregister(image)
                            retained.remove(this)
                            result.complete(null)
                        } catch (error: Throwable) {
                            report("Could not release grave image shutdown ownership", error)
                            result.completeExceptionally(error)
                        }
                    }
                }
            } catch (failure: Throwable) {
                report("Grave image cleanup failed; retained for Yamipa shutdown", failure)
                result.completeExceptionally(failure)
            }
            return result.copy()
        }
    }

    private val retained = ConcurrentHashMap.newKeySet<Handle>()
    private val idOwners = ConcurrentHashMap<Int, Handle>()
    @Volatile private var error: String? = initialError
    @Volatile private var closed = false
    @Volatile private var providerStopped = false
    private var completion: CompletableFuture<Void>? = null

    init {
        if (backend != null) plugin.server.pluginManager.registerEvents(this, plugin)
        initialError?.let { plugin.logger.warning("Grave images unavailable: $it") }
    }

    fun health(): Health = Health(backend != null && !closed && !providerStopped, error, retained.size)

    /** One retained slot per visible grave, including unfinished cleanup, bounds churn under failure. */
    fun spawn(player: Player, grave: UUID, variant: Int, location: Location, face: BlockFace): Handle? {
        val owner = backend ?: return null
        if (closed || providerStopped || !player.isOnline || player.isDead || location.world?.uid != player.world.uid) return null
        require(variant in 0..1) { "Grave image variant must be 0 or 1" }
        require(face in CARDINALS) { "Grave images require a cardinal face" }
        if (retained.count { it.player.uniqueId == player.uniqueId } >= MAX_RETAINED_PER_VIEWER) {
            report("Grave image viewer limit reached while cleanup is pending", null)
            return null
        }
        var handle: Handle? = null
        return try {
            val image = owner.create(player, variant, location.clone(), face)
            val created = Handle(player, grave, image)
            handle = created
            // Register BEFORE any asynchronous load/send, without ImageRenderer.addImage.
            owner.register(image)
            retained.add(created)
            owner.onLoaded(image, Runnable { created.loaded() })
            owner.spawn(image, player)
            created
        } catch (failure: Throwable) {
            report("Could not create grave image", failure)
            if (handle != null && handle in retained) handle.removeAsync()
            null
        }
    }

    /** Exact owned ID AND connection identity; never match a global Yamipa ID range. */
    fun graveForFrame(player: Player, id: Int): UUID? = idOwners[id]
        ?.takeIf { it.isValid && it.player === player && id in it.ids }?.grave

    @EventHandler(priority = EventPriority.MONITOR)
    fun providerDisabled(event: PluginDisableEvent) {
        if (event.plugin !== backend?.provider || closed || providerStopped) return
        providerStopped = true
        error = "Yamipa stopped; recreate the image owner after its next ready generation"
        retained.toList().forEach { it.removeAsync() }
        // Do not clear retained registrations: Yamipa's terminal worker owns unfinished cleanup.
    }

    fun closeAsync(): CompletableFuture<Void> {
        completion?.let { return it.copy() }
        closed = true
        HandlerList.unregisterAll(this)
        return CompletableFuture.allOf(*retained.toList().map { it.removeAsync() }.toTypedArray())
            .also { completion = it }.copy()
    }

    override fun close() { closeAsync() }

    private fun report(message: String, failure: Throwable?) {
        val cause = if (failure is InvocationTargetException) failure.targetException else failure
        val detail = if (cause == null) message else "$message: ${cause.javaClass.simpleName}: ${cause.message}"
        if (error == detail) return
        error = detail
        plugin.logger.log(Level.WARNING, detail, cause)
    }

    companion object {
        private const val VERSION = "1.4.2-ruscrafting.9"
        private const val MAX_RETAINED_PER_VIEWER = 8
        private val CARDINALS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

        fun variant(grave: UUID): Int = (UUID.nameUUIDFromBytes("arc:grave:image:$grave".toByteArray(Charsets.UTF_8))
            .mostSignificantBits and 1L).toInt()

        fun create(plugin: Plugin, assets: List<Path>, onFrameIds: (YamipaGraveEngravings.Handle, Set<Int>) -> Unit = { _, _ -> }): YamipaGraveEngravings {
            val provider = plugin.server.pluginManager.getPlugin("YamipaPlugin")
            if (provider == null || !provider.isEnabled) return YamipaGraveEngravings(plugin, null, "YamipaPlugin is not enabled", onFrameIds)
            if (provider.description.version != VERSION) return YamipaGraveEngravings(plugin, null,
                "Unsupported Yamipa version ${provider.description.version}; expected $VERSION", onFrameIds)
            return try {
                // Guard optional classes without initializing them. The typed subclass loads only here.
                Class.forName("io.josemmo.bukkit.plugin.renderer.FakeImage", false, provider.javaClass.classLoader)
                YamipaGraveEngravings(plugin, NativeBackend.bind(plugin, provider, assets), onFrameIds = onFrameIds)
            } catch (failure: Throwable) {
                val cause = if (failure is InvocationTargetException) failure.targetException else failure
                YamipaGraveEngravings(plugin, null, "Yamipa .9 API binding failed: ${cause.javaClass.simpleName}: ${cause.message}", onFrameIds)
            }
        }
    }

    /** All reflective lookup is startup-only and restricted to the exact deployed third-party ABI. */
    private class NativeBackend private constructor(
        override val provider: Plugin,
        private val renderer: Any,
        private val cleanup: MutableSet<Any>,
        private val sources: List<ImageFile>,
        private val loaded: java.lang.reflect.Method,
        private val show: java.lang.reflect.Method,
        private val destroy: java.lang.reflect.Method,
        private val frames: java.lang.reflect.Field,
        private val frameId: java.lang.reflect.Method,
    ) : Backend {
        override fun create(player: Player, variant: Int, location: Location, face: BlockFace): Any =
            NativePicture(sources[variant], player, location, face)
        override fun register(image: Any) { check(provider.isEnabled) { "Yamipa stopped" }; check(cleanup.add(image)) }
        override fun unregister(image: Any) { cleanup.remove(image) }
        override fun onLoaded(image: Any, callback: Runnable) { loaded.invoke(image, callback) }
        override fun frameIds(image: Any): Set<Int> = (frames.get(image) as? Array<*>)
            ?.map { frameId.invoke(checkNotNull(it)) as Int }?.toSet() ?: emptySet()
        override fun spawn(image: Any, player: Player) { show.invoke(image, player) }
        @Suppress("UNCHECKED_CAST")
        override fun destroy(image: Any, player: Player): CompletableFuture<Void> = destroy.invoke(image, player) as CompletableFuture<Void>

        companion object {
            fun bind(plugin: Plugin, provider: Plugin, assets: List<Path>): Backend {
                require(assets.size == 2) { "Exactly two private ARC image assets are required" }
                val root = plugin.dataFolder.toPath().toAbsolutePath().normalize()
                val paths = assets.map { it.toAbsolutePath().normalize().also { path ->
                    require(path.startsWith(root) && path != root) { "Grave image assets must be inside ARC's data directory" }
                } }
                val loader = provider.javaClass.classLoader
                val image = Class.forName("io.josemmo.bukkit.plugin.renderer.FakeImage", false, loader)
                val file = Class.forName("io.josemmo.bukkit.plugin.storage.ImageFile", false, loader)
                val frame = Class.forName("io.josemmo.bukkit.plugin.renderer.FakeItemFrame", false, loader)
                check(NativePicture::class.java.superclass === image && ImageFile::class.java === file) { "Yamipa API classloader identity differs" }
                val renderer = provider.javaClass.getMethod("getRenderer").invoke(provider)
                val cleanupField = renderer.javaClass.getDeclaredField("cleanupImages").apply {
                    check(type == Set::class.java) { "Unexpected Yamipa cleanup owner type" }; isAccessible = true
                }
                @Suppress("UNCHECKED_CAST")
                val cleanup = cleanupField.get(renderer) as MutableSet<Any>
                check(cleanup is ConcurrentHashMap.KeySetView<*, *>) { "Yamipa cleanup owner is not its expected concurrent set" }
                val frames = image.getDeclaredField("frames").apply {
                    check(type.isArray && type.componentType === frame) { "Unexpected Yamipa frames type" }; isAccessible = true
                }
                val destroy = image.getMethod("destroy", Player::class.java).also {
                    check(it.returnType == CompletableFuture::class.java) { "Yamipa destroy does not acknowledge cleanup" }
                }
                val constructor = file.getDeclaredConstructor(String::class.java, Path::class.java, Path::class.java).apply { isAccessible = true }
                val sources = paths.map { path ->
                    // The caller extracts content-addressed assets; Yamipa keys its disk cache by this name.
                    constructor.newInstance(path.fileName.toString(), path, root.resolve("cache/graves-yamipa")) as ImageFile
                }
                return NativeBackend(provider, renderer, cleanup, sources,
                    image.getMethod("setOnLoadedListener", Runnable::class.java), image.getMethod("spawn", Player::class.java),
                    destroy, frames, frame.getMethod("getId").also { check(it.returnType == Int::class.javaPrimitiveType) })
            }
        }
    }

    /** Kept out of always-loaded descriptors; 1.3 and .9 share only this constructor/getFile ABI. */
    private class NativePicture(private val source: ImageFile, player: Player, location: Location, face: BlockFace) :
        FakeImage(source.filename, location, face, Rotation.NONE, 1, 2, null, player, 0) {
        override fun getFile(): ImageFile = source
    }
}
