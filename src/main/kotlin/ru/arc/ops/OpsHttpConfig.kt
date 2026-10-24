package ru.arc.ops

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

/**
 * Configuration for the ops HTTP API ([OpsHttpModule]).
 */
open class OpsHttpConfig(private val config: Config? = null) {
    private fun bool(path: String, default: Boolean) = config?.bool(path, default) ?: default
    private fun string(path: String, default: String) = config?.string(path, default) ?: default
    private fun integer(path: String, default: Int) = config?.integer(path, default) ?: default
    private fun stringList(path: String) = config?.stringList(path).orEmpty()


    open val enabled: Boolean
        get() = bool("enabled", false)

    open val token: String
        get() = string("token", "")

    open val bindHost: String
        get() = string("bind-host", "127.0.0.1")

    open val bindPort: Int
        get() = integer("bind-port", 25823)

    open val consoleEnabled: Boolean
        get() = bool("console-enabled", false)

    open val staffCommandsEnabled: Boolean
        get() = bool("staff-commands-enabled", false)

    open val staffCommandBindHost: String
        get() = string("staff-command-bind-host", "")

    open val staffCommandBindPort: Int
        get() = integer("staff-command-bind-port", 25833)

    open val staffCommandAllowedAddress: String
        get() = string("staff-command-allowed-address", "")

    open val staffCommandAdminGroups: Set<String>
        get() = (config?.stringList("staff-command-admin-groups", listOf("admin")) ?: listOf("admin"))
            .map { it.trim().lowercase(java.util.Locale.ROOT) }
            .filter { it.matches(Regex("[a-z0-9_.-]{1,64}")) }
            .toSet()

    open val messagesEnabled: Boolean
        get() = bool("messages-enabled", true)

    open val effectsEnabled: Boolean
        get() = bool("effects-enabled", true)

    open val reloadEnabled: Boolean
        get() = bool("reload-enabled", true)

    open val runAsEnabled: Boolean
        get() = bool("run-as-enabled", false)

    open val economyAuditReadEnabled: Boolean
        get() = bool("economy-audit-read-enabled", true)

    open val productInterestReadEnabled: Boolean
        get() = bool("product-interest-read-enabled", true)

    open val playerTelemetryReadEnabled: Boolean
        get() = bool("player-telemetry-read-enabled", true)

    open val contractReconciliationReadEnabled: Boolean
        get() = bool("contract-reconciliation-read-enabled", true)

    open val contractReconciliationWriteEnabled: Boolean
        get() = bool("contract-reconciliation-write-enabled", false)

    open val itemsReadEnabled: Boolean
        get() = bool("items-read-enabled", true)

    open val itemsGiveEnabled: Boolean
        get() = bool("items-give-enabled", false)

    open val itemsGiveMaxStack: Int
        get() = integer("items-give-max-stack", 64).coerceIn(1, 6400)

    open val itemPresetsReadEnabled: Boolean
        get() = bool("item-presets-read-enabled", true)

    open val itemPresetsWriteEnabled: Boolean
        get() = bool("item-presets-write-enabled", false)

    open val cmiKitsWriteEnabled: Boolean
        get() = bool("cmi-kits-write-enabled", false)

    open val cmiHologramsReadEnabled: Boolean
        get() = bool("cmi-holograms-read-enabled", true)

    open val cmiHologramsWriteEnabled: Boolean
        get() = bool("cmi-holograms-write-enabled", false)

    open val scheduledCommandsReadEnabled: Boolean
        get() = bool("scheduled-commands-read-enabled", true)

    open val scheduledCommandsWriteEnabled: Boolean
        get() = bool("scheduled-commands-write-enabled", false)

    open val locationPoolsReadEnabled: Boolean
        get() = bool("location-pools-read-enabled", true)

    open val locationPoolsWriteEnabled: Boolean
        get() = bool("location-pools-write-enabled", false)

    open val worldScenesReadEnabled: Boolean
        get() = bool("world-scenes-read-enabled", true)

    open val worldScenesWriteEnabled: Boolean
        get() = bool("world-scenes-write-enabled", false)

    open val treasurePoolsReadEnabled: Boolean
        get() = bool("treasure-pools-read-enabled", true)

    open val treasurePoolsWriteEnabled: Boolean
        get() = bool("treasure-pools-write-enabled", false)

    open val npcsReadEnabled: Boolean
        get() = bool("npcs-read-enabled", true)

    open val npcsWriteEnabled: Boolean
        get() = bool("npcs-write-enabled", true)

    open val npcsLoadChunksEnabled: Boolean
        get() = bool("npcs-load-chunks-enabled", true)

    open val qaWorldReadEnabled: Boolean
        get() = bool("qa-world-read-enabled", false)

    open val qaWorldWriteEnabled: Boolean
        get() = bool("qa-world-write-enabled", false)

    open val qaWorldAllowedPlayers: Set<String>
        get() = stringList("qa-world-allowed-players").map { it.lowercase() }.toSet()

    open val luckpermsGroupsReadEnabled: Boolean
        get() = bool("luckperms-groups-read-enabled", true)

    open val luckpermsGroupsWriteEnabled: Boolean
        get() = bool("luckperms-groups-write-enabled", false)

    open val luckpermsUsersReadEnabled: Boolean
        get() = bool("luckperms-users-read-enabled", true)

    open val luckpermsUsersWriteEnabled: Boolean
        get() = bool("luckperms-users-write-enabled", false)

    open val luckpermsMigrationsEnabled: Boolean
        get() = bool("luckperms-migrations-enabled", false)

    open val errorBufferSize: Int
        get() = integer("error-buffer-size", 200).coerceIn(50, 2000)

    companion object {
        @Volatile
        private var instance: OpsHttpConfig = OpsHttpConfig()

        fun current(): OpsHttpConfig = instance

        fun reload(root: Path) {
            val cfg = ConfigManager.ofModule(root, "ops-http.yml")
            instance = OpsHttpConfig(cfg)
            OpsLogBuffer.resize(instance.errorBufferSize)
        }

        fun loadForTest(test: OpsHttpConfig) {
            instance = test
            OpsLogBuffer.resize(test.errorBufferSize)
        }
    }
}

/** Test overrides without YAML. */
class TestOpsHttpConfig(
    override val enabled: Boolean = true,
    override val token: String = "test-token",
    override val bindHost: String = "127.0.0.1",
    override val bindPort: Int = 0,
    override val consoleEnabled: Boolean = false,
    override val staffCommandsEnabled: Boolean = false,
    override val staffCommandBindHost: String = "",
    override val staffCommandBindPort: Int = 0,
    override val staffCommandAllowedAddress: String = "",
    override val staffCommandAdminGroups: Set<String> = setOf("admin"),
    override val messagesEnabled: Boolean = true,
    override val effectsEnabled: Boolean = true,
    override val reloadEnabled: Boolean = true,
    override val runAsEnabled: Boolean = false,
    override val economyAuditReadEnabled: Boolean = true,
    override val productInterestReadEnabled: Boolean = true,
    override val playerTelemetryReadEnabled: Boolean = true,
    override val contractReconciliationReadEnabled: Boolean = true,
    override val contractReconciliationWriteEnabled: Boolean = false,
    override val itemsReadEnabled: Boolean = true,
    override val itemsGiveEnabled: Boolean = true,
    override val itemsGiveMaxStack: Int = 64,
    override val itemPresetsReadEnabled: Boolean = true,
    override val itemPresetsWriteEnabled: Boolean = false,
    override val cmiKitsWriteEnabled: Boolean = false,
    override val cmiHologramsReadEnabled: Boolean = true,
    override val cmiHologramsWriteEnabled: Boolean = false,
    override val scheduledCommandsReadEnabled: Boolean = true,
    override val scheduledCommandsWriteEnabled: Boolean = false,
    override val locationPoolsReadEnabled: Boolean = true,
    override val locationPoolsWriteEnabled: Boolean = false,
    override val worldScenesReadEnabled: Boolean = true,
    override val worldScenesWriteEnabled: Boolean = false,
    override val treasurePoolsReadEnabled: Boolean = true,
    override val treasurePoolsWriteEnabled: Boolean = false,
    override val npcsReadEnabled: Boolean = true,
    override val npcsWriteEnabled: Boolean = false,
    override val npcsLoadChunksEnabled: Boolean = false,
    override val qaWorldReadEnabled: Boolean = true,
    override val qaWorldWriteEnabled: Boolean = false,
    override val qaWorldAllowedPlayers: Set<String> = setOf("codexqa_728"),
    override val luckpermsGroupsReadEnabled: Boolean = true,
    override val luckpermsGroupsWriteEnabled: Boolean = false,
    override val luckpermsUsersReadEnabled: Boolean = true,
    override val luckpermsUsersWriteEnabled: Boolean = false,
    override val luckpermsMigrationsEnabled: Boolean = false,
    override val errorBufferSize: Int = 100,
) : OpsHttpConfig() {
    fun copy(
        consoleEnabled: Boolean = this.consoleEnabled,
        staffCommandsEnabled: Boolean = this.staffCommandsEnabled,
        staffCommandAdminGroups: Set<String> = this.staffCommandAdminGroups,
        messagesEnabled: Boolean = this.messagesEnabled,
        runAsEnabled: Boolean = this.runAsEnabled,
        economyAuditReadEnabled: Boolean = this.economyAuditReadEnabled,
        productInterestReadEnabled: Boolean = this.productInterestReadEnabled,
        playerTelemetryReadEnabled: Boolean = this.playerTelemetryReadEnabled,
        contractReconciliationReadEnabled: Boolean = this.contractReconciliationReadEnabled,
        contractReconciliationWriteEnabled: Boolean = this.contractReconciliationWriteEnabled,
        itemsGiveEnabled: Boolean = this.itemsGiveEnabled,
        itemPresetsReadEnabled: Boolean = this.itemPresetsReadEnabled,
        itemPresetsWriteEnabled: Boolean = this.itemPresetsWriteEnabled,
        cmiKitsWriteEnabled: Boolean = this.cmiKitsWriteEnabled,
        cmiHologramsReadEnabled: Boolean = this.cmiHologramsReadEnabled,
        cmiHologramsWriteEnabled: Boolean = this.cmiHologramsWriteEnabled,
        scheduledCommandsReadEnabled: Boolean = this.scheduledCommandsReadEnabled,
        scheduledCommandsWriteEnabled: Boolean = this.scheduledCommandsWriteEnabled,
        locationPoolsReadEnabled: Boolean = this.locationPoolsReadEnabled,
        locationPoolsWriteEnabled: Boolean = this.locationPoolsWriteEnabled,
        worldScenesReadEnabled: Boolean = this.worldScenesReadEnabled,
        worldScenesWriteEnabled: Boolean = this.worldScenesWriteEnabled,
        treasurePoolsReadEnabled: Boolean = this.treasurePoolsReadEnabled,
        treasurePoolsWriteEnabled: Boolean = this.treasurePoolsWriteEnabled,
        npcsReadEnabled: Boolean = this.npcsReadEnabled,
        npcsWriteEnabled: Boolean = this.npcsWriteEnabled,
        npcsLoadChunksEnabled: Boolean = this.npcsLoadChunksEnabled,
        qaWorldReadEnabled: Boolean = this.qaWorldReadEnabled,
        qaWorldWriteEnabled: Boolean = this.qaWorldWriteEnabled,
        qaWorldAllowedPlayers: Set<String> = this.qaWorldAllowedPlayers,
        luckpermsGroupsReadEnabled: Boolean = this.luckpermsGroupsReadEnabled,
        luckpermsGroupsWriteEnabled: Boolean = this.luckpermsGroupsWriteEnabled,
        luckpermsUsersReadEnabled: Boolean = this.luckpermsUsersReadEnabled,
        luckpermsUsersWriteEnabled: Boolean = this.luckpermsUsersWriteEnabled,
        luckpermsMigrationsEnabled: Boolean = this.luckpermsMigrationsEnabled,
    ): TestOpsHttpConfig =
        TestOpsHttpConfig(
            enabled = enabled,
            token = token,
            bindHost = bindHost,
            bindPort = bindPort,
            consoleEnabled = consoleEnabled,
            staffCommandsEnabled = staffCommandsEnabled,
            staffCommandBindHost = staffCommandBindHost,
            staffCommandBindPort = staffCommandBindPort,
            staffCommandAllowedAddress = staffCommandAllowedAddress,
            staffCommandAdminGroups = staffCommandAdminGroups,
            messagesEnabled = messagesEnabled,
            effectsEnabled = effectsEnabled,
            reloadEnabled = reloadEnabled,
            runAsEnabled = runAsEnabled,
            economyAuditReadEnabled = economyAuditReadEnabled,
            productInterestReadEnabled = productInterestReadEnabled,
            playerTelemetryReadEnabled = playerTelemetryReadEnabled,
            contractReconciliationReadEnabled = contractReconciliationReadEnabled,
            contractReconciliationWriteEnabled = contractReconciliationWriteEnabled,
            itemsReadEnabled = itemsReadEnabled,
            itemsGiveEnabled = itemsGiveEnabled,
            itemsGiveMaxStack = itemsGiveMaxStack,
            itemPresetsReadEnabled = itemPresetsReadEnabled,
            itemPresetsWriteEnabled = itemPresetsWriteEnabled,
            cmiKitsWriteEnabled = cmiKitsWriteEnabled,
            cmiHologramsReadEnabled = cmiHologramsReadEnabled,
            cmiHologramsWriteEnabled = cmiHologramsWriteEnabled,
            scheduledCommandsReadEnabled = scheduledCommandsReadEnabled,
            scheduledCommandsWriteEnabled = scheduledCommandsWriteEnabled,
            locationPoolsReadEnabled = locationPoolsReadEnabled,
            locationPoolsWriteEnabled = locationPoolsWriteEnabled,
            worldScenesReadEnabled = worldScenesReadEnabled,
            worldScenesWriteEnabled = worldScenesWriteEnabled,
            treasurePoolsReadEnabled = treasurePoolsReadEnabled,
            treasurePoolsWriteEnabled = treasurePoolsWriteEnabled,
            npcsReadEnabled = npcsReadEnabled,
            npcsWriteEnabled = npcsWriteEnabled,
            npcsLoadChunksEnabled = npcsLoadChunksEnabled,
            qaWorldReadEnabled = qaWorldReadEnabled,
            qaWorldWriteEnabled = qaWorldWriteEnabled,
            qaWorldAllowedPlayers = qaWorldAllowedPlayers,
            luckpermsGroupsReadEnabled = luckpermsGroupsReadEnabled,
            luckpermsGroupsWriteEnabled = luckpermsGroupsWriteEnabled,
            luckpermsUsersReadEnabled = luckpermsUsersReadEnabled,
            luckpermsUsersWriteEnabled = luckpermsUsersWriteEnabled,
            luckpermsMigrationsEnabled = luckpermsMigrationsEnabled,
            errorBufferSize = errorBufferSize,
        )
}
