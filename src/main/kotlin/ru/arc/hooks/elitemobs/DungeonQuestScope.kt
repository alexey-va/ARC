package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfig
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfigFields
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import org.bukkit.entity.Player
import java.util.ArrayDeque

internal data class DungeonQuestScopePackage(
    val filename: String,
    val contentType: ContentPackagesConfigFields.ContentType?,
    val worldName: String?,
    val wormholeWorldName: String?,
    val containedPackages: List<String>,
)

internal fun currentDungeonQuestWorlds(player: Player): Set<String> {
    val world = player.world
    val scopePackages = ContentPackagesConfig.getDungeonPackages().orEmpty().values
        .mapNotNull(ContentPackagesConfigFields::toDungeonQuestScopePackage)
        .associateByTo(linkedMapOf(), DungeonQuestScopePackage::filename)

    val matchingInstances = DungeonInstance.getDungeonInstances().orEmpty()
        .filter { it.world.uid == world.uid }
    val instancePackage = matchingInstances.singleOrNull()
        ?.contentPackagesConfigFields
        ?.let(ContentPackagesConfigFields::toDungeonQuestScopePackage)
    if (instancePackage != null) scopePackages.putIfAbsent(instancePackage.filename, instancePackage)

    val currentPackage = instancePackage
        ?: scopePackages.values.singleOrNull { it.worldName == world.name }
        ?: scopePackages.values.singleOrNull { it.wormholeWorldName == world.name }

    return dungeonQuestWorldNames(world.name, currentPackage?.filename, scopePackages.values)
}

internal fun dungeonQuestWorldNames(
    currentWorldName: String?,
    currentPackageFilename: String?,
    packages: Collection<DungeonQuestScopePackage>,
): Set<String> {
    val worldNames = linkedSetOf<String>()
    currentWorldName?.takeIf(String::isNotBlank)?.let(worldNames::add)

    val packageByFilename = packages.asSequence()
        .filter { it.filename.isNotBlank() }
        .associateBy(DungeonQuestScopePackage::filename)
    val currentPackage = currentPackageFilename?.takeIf(String::isNotBlank)
        ?.let(packageByFilename::get)
        ?: return worldNames

    // Meta packages can be catalogs; adventure families have exactly one open-world anchor.
    val familyMembers = packageByFilename.values.asSequence()
        .filter { it.contentType == ContentPackagesConfigFields.ContentType.META_PACKAGE }
        .map { it.filename to containedPackageFilenames(it.filename, packageByFilename) }
        .filter { (_, members) ->
            currentPackage.filename in members && members.count { member ->
                packageByFilename[member]?.contentType == ContentPackagesConfigFields.ContentType.OPEN_DUNGEON
            } == 1
        }
        .flatMap { (_, members) -> members.asSequence() }
        .toSet()

    val relatedPackageFilenames = familyMembers.ifEmpty { setOf(currentPackage.filename) }
    for (filename in relatedPackageFilenames) {
        val packageScope = packageByFilename[filename] ?: continue
        packageScope.worldName?.takeIf(String::isNotBlank)?.let(worldNames::add)
        packageScope.wormholeWorldName?.takeIf(String::isNotBlank)?.let(worldNames::add)
    }
    return worldNames
}

private fun containedPackageFilenames(
    rootFilename: String,
    packageByFilename: Map<String, DungeonQuestScopePackage>,
): Set<String> {
    val contained = linkedSetOf<String>()
    val pending = ArrayDeque<String>()
    pending.add(rootFilename)
    while (pending.isNotEmpty()) {
        val filename = pending.removeLast()
        if (!contained.add(filename)) continue
        packageByFilename[filename]?.containedPackages.orEmpty()
            .filter(String::isNotBlank)
            .forEach(pending::addLast)
    }
    return contained
}

private fun ContentPackagesConfigFields.toDungeonQuestScopePackage(): DungeonQuestScopePackage? {
    val packageFilename = filename?.takeIf(String::isNotBlank) ?: return null
    return DungeonQuestScopePackage(
        filename = packageFilename,
        contentType = contentType,
        worldName = worldName?.takeIf(String::isNotBlank),
        wormholeWorldName = wormholeWorldName?.takeIf(String::isNotBlank),
        containedPackages = containedPackages.orEmpty().filter(String::isNotBlank),
    )
}
