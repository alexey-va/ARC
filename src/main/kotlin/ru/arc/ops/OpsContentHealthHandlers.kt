package ru.arc.ops

import ru.arc.ARC

/**
 * Read-only overview for the content catalogs managed through ops HTTP.
 *
 * Component failures are isolated so one unavailable integration (for example
 * CMI during startup) does not hide health information from native ARC
 * catalogs.
 */
object OpsContentHealthHandlers {
    fun health(cfg: OpsHttpConfig): Map<String, Any?> =
        OpsBukkitSync.call {
            collect(cfg, ContentCatalogReaders())
        }

    internal fun collect(
        cfg: OpsHttpConfig,
        readers: ContentCatalogReaders,
    ): Map<String, Any?> {
        val components =
            linkedMapOf(
                "itemPresets" to
                    component(cfg.itemPresetsReadEnabled, readers.itemPresets) {
                        countOnly(it)
                    },
                "cmiKits" to
                    component(cfg.itemsReadEnabled, readers.cmiKits) {
                        enabledCatalog(it, "kits")
                    },
                "scheduledCommands" to
                    component(cfg.scheduledCommandsReadEnabled, readers.scheduledCommands) {
                        enabledCatalog(it, "entries")
                    },
                "locationPools" to
                    component(cfg.locationPoolsReadEnabled, readers.locationPools) {
                        locationPools(it)
                    },
                "treasurePools" to
                    component(cfg.treasurePoolsReadEnabled, readers.treasurePools) {
                        treasurePools(it)
                    },
            )

        val unavailable = components.filterValues { it["available"] != true }.keys.toList()
        val unhealthy =
            components
                .filterValues { it["available"] == true && it["healthy"] != true }
                .keys
                .toList()
        val issueCount = components.values.sumOf { (it["issueCount"] as? Number)?.toInt() ?: 0 }
        return linkedMapOf(
            "source" to "arc-content-health",
            "server" to (ARC.serverName ?: "unknown"),
            "complete" to unavailable.isEmpty(),
            "healthy" to (unavailable.isEmpty() && unhealthy.isEmpty()),
            "issueCount" to issueCount,
            "unavailableComponents" to unavailable,
            "unhealthyComponents" to unhealthy,
            "components" to components,
        )
    }

    private fun component(
        enabled: Boolean,
        reader: () -> Map<String, Any?>,
        analyzer: (Map<String, Any?>) -> Map<String, Any?>,
    ): Map<String, Any?> {
        if (!enabled) {
            return mapOf(
                "available" to false,
                "healthy" to false,
                "issueCount" to 1,
                "reason" to "read-disabled",
            )
        }
        return try {
            analyzer(reader())
        } catch (error: Exception) {
            mapOf(
                "available" to false,
                "healthy" to false,
                "issueCount" to 1,
                "reason" to "read-failed",
                "error" to (error.message ?: error::class.simpleName.orEmpty()).take(MAX_ERROR_LENGTH),
            )
        }
    }

    private fun countOnly(data: Map<String, Any?>): Map<String, Any?> =
        mapOf(
            "available" to true,
            "healthy" to true,
            "issueCount" to 0,
            "count" to count(data),
        )

    private fun enabledCatalog(
        data: Map<String, Any?>,
        field: String,
    ): Map<String, Any?> {
        val entries = maps(data[field])
        val disabled = entries.filter { it["enabled"] == false }.mapNotNull { it["name"] ?: it["id"] }
        return mapOf(
            "available" to true,
            "healthy" to true,
            "issueCount" to 0,
            "count" to count(data),
            "enabledCount" to (entries.size - disabled.size),
            "disabledCount" to disabled.size,
            "disabled" to disabled,
        )
    }

    private fun locationPools(data: Map<String, Any?>): Map<String, Any?> {
        val issues =
            maps(data["pools"])
                .filter { it["healthyForCurrentServer"] != true }
                .map {
                    mapOf(
                        "id" to it["id"],
                        "localCount" to it["localCount"],
                        "localUsable" to it["localUsable"],
                    )
                }
        return mapOf(
            "available" to true,
            "healthy" to issues.isEmpty(),
            "issueCount" to issues.size,
            "count" to count(data),
            "issues" to issues,
        )
    }

    private fun treasurePools(data: Map<String, Any?>): Map<String, Any?> {
        val issues =
            maps(data["pools"])
                .filter { it["healthy"] != true }
                .map {
                    mapOf(
                        "id" to it["id"],
                        "totalWeight" to it["totalWeight"],
                        "cyclic" to it["cyclic"],
                        "missingSubPools" to it["missingSubPools"],
                        "unusableSubPools" to it["unusableSubPools"],
                    )
                }
        return mapOf(
            "available" to true,
            "healthy" to issues.isEmpty(),
            "issueCount" to issues.size,
            "count" to count(data),
            "issues" to issues,
        )
    }

    private fun count(data: Map<String, Any?>): Int = (data["count"] as? Number)?.toInt() ?: 0

    @Suppress("UNCHECKED_CAST")
    private fun maps(value: Any?): List<Map<String, Any?>> =
        (value as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    private const val MAX_ERROR_LENGTH = 240
}

internal data class ContentCatalogReaders(
    val itemPresets: () -> Map<String, Any?> = { OpsItemPresetHandlers.list() },
    val cmiKits: () -> Map<String, Any?> = { OpsCmiKitHandlers.listKits() },
    val scheduledCommands: () -> Map<String, Any?> = { OpsScheduledCommandHandlers.list() },
    val locationPools: () -> Map<String, Any?> = { OpsLocationPoolHandlers.list() },
    val treasurePools: () -> Map<String, Any?> = { OpsTreasurePoolHandlers.list() },
)
