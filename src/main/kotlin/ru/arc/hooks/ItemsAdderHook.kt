package ru.arc.hooks

import dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.redis.RedisModuleConfig
import ru.arc.redis.RedisWire
import ru.arc.redis.resourcepack.ResourcePackPublication
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ItemsAdderHook internal constructor(
    private val scheduler: TaskScheduler,
    private val generatedPack: GeneratedPackPublicationGate,
    private val publishResourcePack: () -> Boolean,
) : Listener {
    internal constructor(
        resourcePackZip: Path,
        syncScript: Path,
        config: ResourcePackSyncConfig,
    ) : this(
        scheduler = Tasks.scheduler,
        generatedPack = GeneratedPackPublicationGate(resourcePackZip),
        publishResourcePack = {
            if (!config.enabled) {
                debug("ItemsAdder resource pack publication is disabled")
                false
            } else {
                ResourcePackSyncScript(syncScript, config::processEnvironment).publish(resourcePackZip)
            }
        },
    )

    private val publishing = AtomicBoolean(false)

    @EventHandler
    fun onPackCompressed(@Suppress("UNUSED_PARAMETER") event: ItemsAdderPackCompressedEvent) {
        requestPublish()
    }

    internal fun requestPublish() {
        if (!publishing.compareAndSet(false, true)) {
            info("ItemsAdder resource pack publication is already running; duplicate event skipped")
            return
        }

        val attempt = generatedPack.beginAttempt()
        info("Waiting for ItemsAdder to replace generated.zip before publication")
        val scheduled = AtomicReference<ru.arc.core.ScheduledTask?>()
        try {
            scheduled.set(
                scheduler.runTimerAsync(
                    generatedPack.pollIntervalTicks,
                    generatedPack.pollIntervalTicks,
                ) {
                    when (generatedPack.poll(attempt)) {
                        GeneratedPackPoll.WAITING -> Unit
                        GeneratedPackPoll.READY -> {
                            scheduled.get()?.cancel()
                            publishAndFinish()
                        }
                        GeneratedPackPoll.TIMED_OUT -> {
                            scheduled.get()?.cancel()
                            warn(
                                "ItemsAdder did not replace generated.zip after its finalization event; publication skipped",
                            )
                            publishing.set(false)
                        }
                    }
                },
            )
        } catch (failure: Throwable) {
            scheduled.get()?.cancel()
            publishing.set(false)
            error("Unable to schedule ItemsAdder generated.zip readiness check", failure)
        }
    }

    private fun publishAndFinish() {
        try {
            publishResourcePack()
        } catch (failure: Throwable) {
            error("Unexpected error publishing the ItemsAdder resource pack", failure)
        } finally {
            publishing.set(false)
        }
    }
}

internal enum class GeneratedPackPoll {
    WAITING,
    READY,
    TIMED_OUT,
}

internal class GeneratedPackPublicationGate(
    private val path: Path,
    val pollIntervalTicks: Long = 20L,
    private val requiredStablePolls: Int = 2,
    private val maximumPolls: Int = 120,
) {
    init {
        require(pollIntervalTicks > 0) { "pollIntervalTicks must be positive" }
        require(requiredStablePolls > 0) { "requiredStablePolls must be positive" }
        require(maximumPolls >= requiredStablePolls) { "maximumPolls must cover the stability window" }
    }

    fun beginAttempt(): Attempt = Attempt(baseline = snapshot())

    fun poll(attempt: Attempt): GeneratedPackPoll {
        attempt.polls++
        val current = snapshot()
        if (current != null && current != attempt.baseline) {
            if (current == attempt.lastChangedSnapshot) {
                attempt.stablePolls++
            } else {
                attempt.lastChangedSnapshot = current
                attempt.stablePolls = 1
            }
            if (attempt.stablePolls >= requiredStablePolls) {
                return GeneratedPackPoll.READY
            }
        } else {
            attempt.lastChangedSnapshot = null
            attempt.stablePolls = 0
        }
        return if (attempt.polls >= maximumPolls) GeneratedPackPoll.TIMED_OUT else GeneratedPackPoll.WAITING
    }

    internal class Attempt internal constructor(
        internal val baseline: Snapshot?,
        internal var lastChangedSnapshot: Snapshot? = null,
        internal var stablePolls: Int = 0,
        internal var polls: Int = 0,
    )

    internal data class Snapshot(
        val size: Long,
        val modifiedMillis: Long,
        val fileKey: String?,
    )

    private fun snapshot(): Snapshot? =
        try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (!attributes.isRegularFile) {
                null
            } else {
                Snapshot(
                    size = attributes.size(),
                    modifiedMillis = attributes.lastModifiedTime().toMillis(),
                    fileKey = attributes.fileKey()?.toString(),
                )
            }
        } catch (_: IOException) {
            null
        }
}

internal class ResourcePackSyncScript(
    private val script: Path,
    private val processEnvironment: () -> Map<String, String>,
) {
    fun publish(resourcePackZip: Path): Boolean {
        if (!Files.isRegularFile(resourcePackZip)) {
            warn("ItemsAdder resource pack does not exist: {}", resourcePackZip)
            return false
        }
        if (!Files.isRegularFile(script)) {
            warn("Resource pack sync script does not exist: {}", script)
            return false
        }
        if (!Files.isExecutable(script)) {
            warn("Resource pack sync script is not executable: {}", script)
            return false
        }

        val environment = processEnvironment()
        val missingCredentials =
            REQUIRED_CREDENTIALS.filter { key ->
                environment[key].isNullOrBlank() || environment[key] == "CHANGE_ME"
            }
        if (missingCredentials.isNotEmpty()) {
            warn(
                "ItemsAdder resource pack publication is missing config values: {}",
                missingCredentials.joinToString(),
            )
            return false
        }

        info("Publishing ItemsAdder resource pack via {}", script)
        return try {
            val processBuilder =
                ProcessBuilder(script.toString())
                    .redirectErrorStream(true)
            processBuilder.environment().putAll(environment)
            processBuilder.environment()["RP_SOURCE"] = resourcePackZip.toAbsolutePath().normalize().toString()

            val process = processBuilder.start()
            process.inputStream.bufferedReader().useLines { output ->
                output.forEach { line -> info("resourcepack_sync: {}", line) }
            }

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                info("ItemsAdder resource pack publication finished")
                true
            } else {
                error("ItemsAdder resource pack publication failed with exit code {}", exitCode)
                false
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            warn("ItemsAdder resource pack publication was interrupted")
            false
        } catch (failure: IOException) {
            error("Unable to start resource pack sync script {}", script, failure)
            false
        }
    }

    companion object {
        private val REQUIRED_CREDENTIALS = listOf("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
    }
}

internal class ResourcePackSyncConfig(
    private val config: Config,
    private val redisConfig: RedisModuleConfig,
) {
    val enabled: Boolean
        get() = config.bool("enabled", true)

    fun processEnvironment(): Map<String, String> =
        mapOf(
            "AWS_ACCESS_KEY_ID" to config.string("aws.access-key-id", ""),
            "AWS_SECRET_ACCESS_KEY" to config.string("aws.secret-access-key", ""),
            "AWS_DEFAULT_REGION" to config.string("aws.default-region", "ru-central1"),
            "AWS_CLI" to config.string("aws.cli", "aws"),
            "S3_ENDPOINT" to config.string("s3.endpoint", "https://storage.yandexcloud.net"),
            "S3_BUCKET" to config.string("s3.bucket", "ruscraftinresources"),
            "RP_UPLOAD_NAME" to config.string("s3.upload-name", "RusCraftingResource.zip"),
            "S3_RP_KEY" to config.string("s3.key", "RusCraftingResource.zip"),
            "S3_RP_MANIFEST_KEY" to config.string("s3.manifest-key", "RusCraftingResource.zip.sha256"),
            "S3_RP_ARCHIVE_PREFIX" to config.string("s3.archive-prefix", "archive"),
            "FORCE_UPLOAD" to if (config.bool("force-upload", false)) "1" else "0",
            "RP_NOTIFY_ENABLED" to
                if (config.bool("publish-notification.enabled", true) && redisConfig.enabled) "1" else "0",
            "REDIS_CLI" to config.string("publish-notification.redis-cli", "redis-cli"),
            "REDIS_HOST" to redisConfig.host,
            "REDIS_PORT" to redisConfig.port.toString(),
            "REDIS_USERNAME" to redisConfig.username,
            "REDISCLI_AUTH" to redisConfig.password,
            "REDIS_SERVER_NAME" to redisConfig.serverName,
            "REDIS_WIRE_DELIMITER" to RedisWire.SERVER_DELIMITER,
            "RP_PUBLISHED_CHANNEL" to ResourcePackPublication.CHANNEL,
            "RP_PUBLISHED_ACK_KEY" to ResourcePackPublication.ACK_KEY,
        )

    companion object {
        const val FILE_NAME = "resourcepack-sync.yml"

        fun load(dataFolder: Path): ResourcePackSyncConfig =
            ResourcePackSyncConfig(
                ConfigManager.ofModule(dataFolder, FILE_NAME),
                RedisModuleConfig.load(dataFolder),
            )
    }
}

internal object BundledResourcePackSyncScript {
    const val RESOURCE_PATH = "scripts/resourcepack_sync.sh"

    fun install(
        dataFolder: Path,
        resourceLoader: (String) -> InputStream? = {
            ItemsAdderHook::class.java.classLoader.getResourceAsStream(it)
        },
    ): Path {
        val target = dataFolder.resolve(RESOURCE_PATH)
        Files.createDirectories(target.parent)
        val resource =
            checkNotNull(resourceLoader(RESOURCE_PATH)) {
                "Missing bundled resource $RESOURCE_PATH"
            }
        resource.use {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
        }
        check(target.toFile().setExecutable(true, true) || Files.isExecutable(target)) {
            "Unable to make bundled resource pack sync script executable: $target"
        }
        return target
    }
}
