package ru.arc.ops

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Modules.Holograms.CMIHologram
import com.Zrips.CMI.Modules.Holograms.CMIHologramType
import com.Zrips.CMI.Modules.Holograms.HologramManager
import com.google.gson.JsonObject
import net.Zrips.CMILib.Colors.CMIChatColor
import net.Zrips.CMILib.Container.CMIVector2D
import net.Zrips.CMILib.Container.CMIVector3D
import net.Zrips.CMILib.Effects.CMIEffect
import net.Zrips.CMILib.Effects.CMIEffectManager.CMIParticle
import net.Zrips.CMILib.Items.CMIMaterial
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import kotlin.math.roundToInt

/**
 * Presence-aware CMI hologram administration through CMI's Java API.
 *
 * ARC never edits CMI/Saves/Holograms.yml directly. CMI owns its runtime
 * registry, client-side entities, and persistence.
 */
object OpsCmiHologramHandlers {
    fun list(
        name: String? = null,
        worldName: String? = null,
        limit: Int = 200,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val manager = manager()
            val selected =
                if (name.isNullOrBlank()) {
                    manager.holograms.values
                        .asSequence()
                        .filter {
                            worldName.isNullOrBlank() ||
                                it.worldName.equals(worldName.trim(), ignoreCase = true)
                        }
                        .sortedBy { it.name.lowercase() }
                        .take(limit.coerceIn(1, 500))
                        .toList()
                } else {
                    listOf(find(manager, validateName(name)))
                }
            mapOf(
                "provider" to "CMI",
                "source" to "cmi-api",
                "count" to selected.size,
                "holograms" to selected.map(::summary),
            )
        }

    fun preview(
        name: String,
        body: JsonObject,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val safeName = validateName(name)
            val manager = manager()
            val existing = manager.getByName(safeName)
            val spec = OpsCmiHologramSpec.parse(body)
            if (existing == null) spec.requireCreateFields()
            val location = resolveLocation(existing, spec.location)
            validateNativeFields(spec)

            mapOf(
                "provider" to "CMI",
                "source" to "cmi-api",
                "preview" to true,
                "persisted" to false,
                "action" to if (existing == null) "create" else "update",
                "name" to safeName,
                "changedFields" to spec.changedFields,
                "resolvedLocation" to locationSummary(location),
                "current" to existing?.let(::summary),
            )
        }

    fun upsert(
        name: String,
        body: JsonObject,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val safeName = validateName(name)
            val manager = manager()
            val existing = manager.getByName(safeName)
            val spec = OpsCmiHologramSpec.parse(body)
            if (existing == null) spec.requireCreateFields()
            val location = resolveLocation(existing, spec.location)
            validateNativeFields(spec)
            val hologram = existing ?: CMIHologram(safeName, location)

            if (existing == null) {
                hologram.settings.setSaveToFile(true)
                manager.add(hologram)
            } else if (spec.location is CmiHologramPatch.Set) {
                manager.removeChunkRecords(hologram)
                hologram.setLocation(location)
                manager.recalculateChunks(hologram)
            }
            apply(hologram, spec)
            hologram.saveToFile()
            check(manager.saveHolograms() != false) { "CMI failed to persist holograms" }
            hologram.requestFullUpdate()

            mapOf(
                "provider" to "CMI",
                "source" to "cmi-api",
                "created" to (existing == null),
                "saved" to true,
                "changedFields" to spec.changedFields,
                "hologram" to summary(hologram),
            )
        }

    fun delete(name: String): Map<String, Any?> =
        OpsBukkitSync.call {
            val manager = manager()
            val hologram = find(manager, validateName(name))
            val deleted = summary(hologram)
            hologram.delete()
            if (manager.getByName(hologram.name) != null) {
                manager.remove(hologram)
            }
            check(manager.saveHolograms() != false) { "CMI failed to persist holograms" }
            mapOf(
                "provider" to "CMI",
                "source" to "cmi-api",
                "deleted" to true,
                "hologram" to deleted,
            )
        }

    internal fun validateName(raw: String): String {
        val name = raw.trim()
        require(name.isNotEmpty()) { "hologram name must not be blank" }
        require(name.length <= 64) { "hologram name must be at most 64 characters" }
        require(name.none(Char::isISOControl)) { "hologram name contains control characters" }
        require('/' !in name && '\\' !in name) { "hologram name must not contain path separators" }
        return name
    }

    private fun apply(
        hologram: CMIHologram,
        spec: OpsCmiHologramSpec,
    ) {
        val settings = hologram.settings
        val interaction = hologram.interactionSettings
        val pageSettings = hologram.pageSettings
        val textSettings = hologram.textSettings
        val iconSettings = hologram.iconSettings
        val boardSettings = hologram.boardSettings
        val animationSettings = hologram.animationSettings

        spec.lines.applyIfSet(hologram.pages::setLines)
        spec.enabled.applyIfSet(hologram::setEnabled)
        spec.showRange.applyIfSet(settings::setVisibilityRange)
        spec.updateRange.applyIfSet(settings::setUpdateRange)
        spec.updateIntervalSeconds.applyIfSet {
            settings.setUpdateIntervalTicks((it * 20.0).roundToInt())
        }
        spec.pageChangeIntervalSeconds.applyIfSet {
            pageSettings.setPageChangeIntervalTicks((it * 20.0).roundToInt())
        }
        spec.spacing.applyIfSet(hologram::setSpacing)
        spec.iconSpacing.applyIfSet(hologram::setIconSpacing)
        spec.downOrder.applyIfSet(hologram::setDownOrder)
        spec.interactable.applyIfSet(interaction::setInteractable)
        spec.commands.applyIfSet(interaction::setCommands)
        spec.permissionRequired.applyIfSet(settings::setPermissionRequirement)
        spec.checkLineOfSight.applyIfSet(settings::setCheckLineOfSight)
        spec.sticky.applyIfSet(hologram::setSticky)
        spec.type.applyIfSet(hologram::setType)
        spec.group.applyIfSet { settings.setGroup(it.takeIf(String::isNotBlank)) }
        spec.autoPagination.applyIfSet(pageSettings::setAutoPagination)
        (spec.display as? CmiHologramPatch.Set)?.value?.let { display ->
            display.billboard.applyIfSet(settings::setBillboard)
            display.yaw.applyIfSet { settings.setYaw(it.toDouble()) }
            display.pitch.applyIfSet { settings.setPitch(it.toDouble()) }
            display.alignment.applyIfSet(textSettings::setTextAlignment)
            display.backgroundAlpha.applyIfSet(textSettings::setBackgroundAlpha)
            display.textAlpha.applyIfSet(textSettings::setTextAlpha)
            display.doubleSided.applyIfSet(textSettings::setDoubleSided)
            display.shadowed.applyIfSet(textSettings::setShadowed)
            val scale = settings.scale
            val scaleWidth = display.scaleWidth.valueOrNull() ?: scale.x
            val scaleHeight = display.scaleHeight.valueOrNull() ?: scale.y
            if (display.scaleWidth is CmiHologramPatch.Set || display.scaleHeight is CmiHologramPatch.Set) {
                settings.setScale(CMIVector2D(scaleWidth, scaleHeight))
            }
            display.seeThrough.applyIfSet(textSettings::setSeeThrough)
            display.lineWidth.applyIfSet(textSettings::setLineWidth)
            display.fillerAmount.applyIfSet(textSettings::setFillerAmount)
            display.backgroundColor.applyIfSet { textSettings.setBackgroundColor(resolveColor(it)) }
            display.direction.applyIfSet { settings.setDirection(it.toNative()) }
            display.offset.applyIfSet { settings.setOffset(it.toNative()) }
            if (display.skyLight is CmiHologramPatch.Set || display.blockLight is CmiHologramPatch.Set) {
                val light = settings.lightLevel
                settings.setLightLevel(
                    CMIVector2D(
                        display.skyLight.valueOrNull()?.toDouble() ?: light?.x ?: -1.0,
                        display.blockLight.valueOrNull()?.toDouble() ?: light?.y ?: -1.0,
                    ),
                )
            }
        }
        (spec.icon as? CmiHologramPatch.Set)?.value?.let { icon ->
            icon.billboard.applyIfSet(iconSettings::setBillboard)
            icon.offset.applyIfSet { iconSettings.setOffset(it.toNative()) }
            icon.scale.applyIfSet { iconSettings.setScale(it.toNative()) }
            icon.direction.applyIfSet { iconSettings.setDirection(it.toNative()) }
            icon.yaw.applyIfSet(iconSettings::setYaw)
            icon.pitch.applyIfSet(iconSettings::setPitch)
            icon.roll.applyIfSet(iconSettings::setRoll)
        }
        (spec.board as? CmiHologramPatch.Set)?.value?.let { board ->
            board.enabled.applyIfSet(boardSettings::setEnabled)
            board.material.applyIfSet { boardSettings.setMaterial(resolveBoardMaterial(it)) }
            board.dimensions.applyIfSet { boardSettings.setExtraDimensions(it.toNative()) }
            board.offset.applyIfSet { boardSettings.setOffset(it.toNative()) }
            board.direction.applyIfSet { boardSettings.setDirection(it.toNative()) }
        }
        (spec.interaction as? CmiHologramPatch.Set)?.value?.let { details ->
            details.dimensions.applyIfSet { interaction.setDimensions(it.toNative()) }
            details.offset.applyIfSet { interaction.setOffset(it.toNative()) }
            details.particleDimensions.applyIfSet { interaction.setParticleDimensions(it.toNative()) }
            details.particleOffset.applyIfSet { interaction.setParticleOffset(it.toNative()) }
            details.particlePosition.applyIfSet { interaction.setParticlePosition(it.toShort()) }
            details.particleSpacing.applyIfSet(interaction::setParticleSpacing)
            details.particleCount.applyIfSet(interaction::setParticleCount)
            details.effect.applyIfSet { interaction.setHoverEffect(buildEffect(it)) }
            details.showHoverParticle.applyIfSet(interaction::setShowHoverParticle)
            details.showClickParticle.applyIfSet(interaction::setShowClickParticle)
            details.basePrefix.applyIfSet(interaction::setBasePrefix)
            details.hoverPrefix.applyIfSet(interaction::setHoverPrefix)
        }
        (spec.animation as? CmiHologramPatch.Set)?.value?.let { animation ->
            animation.fadeInTicks.applyIfSet(animationSettings::setFadeInAnimation)
            animation.fadeOutTicks.applyIfSet(animationSettings::setFadeOutAnimation)
            animation.autoRotateDegreesPerTick.applyIfSet(animationSettings::setAutoRotate)
        }
    }

    private fun validateNativeFields(spec: OpsCmiHologramSpec) {
        (spec.display as? CmiHologramPatch.Set)
            ?.value
            ?.backgroundColor
            ?.valueOrNull()
            ?.let(::resolveColor)
        (spec.board as? CmiHologramPatch.Set)
            ?.value
            ?.material
            ?.valueOrNull()
            ?.let(::resolveBoardMaterial)
        (spec.interaction as? CmiHologramPatch.Set)
            ?.value
            ?.effect
            ?.valueOrNull()
            ?.let(::buildEffect)
    }

    private fun resolveColor(raw: String): CMIChatColor =
        CMIChatColor.getColor(raw)
            ?.takeIf(CMIChatColor::isColor)
            ?: throw IllegalArgumentException("Unsupported CMI color: $raw")

    private fun resolveBoardMaterial(raw: String): CMIMaterial {
        val material = CMIMaterial.get(raw)
        require(material != null && !material.isNone && material.material?.isBlock == true) {
            "Unsupported board block material: $raw"
        }
        return material
    }

    private fun buildEffect(spec: CmiHologramParticleSpec): CMIEffect {
        val particle =
            CMIParticle.get(spec.particle)
                ?: throw IllegalArgumentException("Unsupported CMI particle: ${spec.particle}")
        val effect = CMIEffect(particle)
        spec.color?.let { effect.color = parseBukkitColor(it, "interaction.effect.color") }
        spec.colorFrom?.let {
            effect.colorFrom = parseBukkitColor(it, "interaction.effect.colorFrom")
        }
        spec.colorTo?.let {
            effect.colorTo = parseBukkitColor(it, "interaction.effect.colorTo")
        }
        spec.offset?.let { effect.offset = org.bukkit.util.Vector(it.x, it.y, it.z) }
        spec.amount?.let { effect.amount = it }
        spec.speed?.let { effect.speed = it.toFloat() }
        spec.size?.let { effect.size = it }
        spec.material?.let {
            val material = CMIMaterial.get(it)
            require(material != null && !material.isNone) {
                "Unsupported particle material: $it"
            }
            effect.material = material
        }
        spec.duration?.let { effect.duration = it }
        return effect
    }

    private fun parseBukkitColor(
        raw: String,
        path: String,
    ): Color {
        require(raw.startsWith("#") && raw.length == 7) {
            "$path must use #RRGGBB"
        }
        return runCatching { Color.fromRGB(raw.substring(1).toInt(16)) }
            .getOrElse { throw IllegalArgumentException("$path must use #RRGGBB") }
    }

    private fun resolveLocation(
        existing: CMIHologram?,
        patch: CmiHologramPatch<CmiHologramLocationSpec>,
    ): Location {
        val current = existing?.location
        val locationPatch = (patch as? CmiHologramPatch.Set)?.value
        val worldName = locationPatch?.world.valueOrNull() ?: existing?.worldName
        require(!worldName.isNullOrBlank()) { "location.world required when creating a CMI hologram" }
        val world = Bukkit.getWorld(worldName)
            ?: throw IllegalArgumentException("World is not loaded: $worldName")
        val x = locationPatch?.x.valueOrNull() ?: current?.x
        val y = locationPatch?.y.valueOrNull() ?: current?.y
        val z = locationPatch?.z.valueOrNull() ?: current?.z
        require(x != null && y != null && z != null) {
            "location.x, location.y, and location.z required when creating a CMI hologram"
        }
        val yaw = locationPatch?.yaw.valueOrNull() ?: current?.yaw ?: 0f
        val pitch = locationPatch?.pitch.valueOrNull() ?: current?.pitch ?: 0f
        return Location(world, x, y, z, yaw, pitch)
    }

    private fun summary(hologram: CMIHologram): Map<String, Any?> {
        val settings = hologram.settings
        val interaction = hologram.interactionSettings
        val pageSettings = hologram.pageSettings
        val textSettings = hologram.textSettings
        val iconSettings = hologram.iconSettings
        val boardSettings = hologram.boardSettings
        val animationSettings = hologram.animationSettings
        val scale = settings.scale
        val light = settings.lightLevel
        return linkedMapOf(
            "name" to hologram.name,
            "location" to locationSummary(hologram.location),
            "type" to hologramTypeName(hologram.type),
            "lines" to hologram.pages.lines,
            "pageCount" to hologram.pages.pageCount,
            "enabled" to hologram.isEnabled,
            "showRange" to settings.visibilityRange,
            "updateRange" to settings.updateRange,
            "updateIntervalSeconds" to settings.updateIntervalTicks / 20.0,
            "pageChangeIntervalSeconds" to pageSettings.pageChangeIntervalTicks / 20.0,
            "autoPagination" to pageSettings.isAutoPagination,
            "spacing" to hologram.spacing,
            "iconSpacing" to hologram.iconSpacing,
            "downOrder" to hologram.isDownOrder,
            "interactable" to interaction.isInteractable,
            "commands" to interaction.commands,
            "permissionRequired" to settings.isRequiresPermission,
            "checkLineOfSight" to settings.isCheckLineOfSight,
            "sticky" to hologram.isSticky,
            "group" to settings.group,
            "display" to
                linkedMapOf(
                    "billboard" to settings.billboard?.name?.lowercase(),
                    "yaw" to settings.yaw,
                    "pitch" to settings.pitch,
                    "alignment" to textSettings.textAlignment?.name?.lowercase(),
                    "backgroundColor" to textSettings.backgroundColor?.let(::colorName),
                    "backgroundAlpha" to textSettings.backgroundAlpha,
                    "textAlpha" to textSettings.textAlpha,
                    "doubleSided" to textSettings.isDoubleSided,
                    "shadowed" to textSettings.isShadowed,
                    "scaleWidth" to scale?.x,
                    "scaleHeight" to scale?.y,
                    "seeThrough" to textSettings.isSeeThrough,
                    "lineWidth" to textSettings.lineWidth,
                    "fillerAmount" to textSettings.fillerAmount,
                    "direction" to vectorSummary(settings.direction),
                    "offset" to vectorSummary(settings.offset),
                    "skyLight" to light?.x?.roundToInt(),
                    "blockLight" to light?.y?.roundToInt(),
                ),
            "icon" to
                linkedMapOf(
                    "billboard" to iconSettings.billboard?.name?.lowercase(),
                    "offset" to vectorSummary(iconSettings.offset),
                    "scale" to vectorSummary(iconSettings.scale),
                    "direction" to vectorSummary(iconSettings.direction),
                    "yaw" to iconSettings.yaw,
                    "pitch" to iconSettings.pitch,
                    "roll" to iconSettings.roll,
                ),
            "board" to
                linkedMapOf(
                    "enabled" to boardSettings.isEnabled,
                    "material" to boardSettings.material?.name?.lowercase(),
                    "dimensions" to vectorSummary(boardSettings.extraDimensions),
                    "offset" to vectorSummary(boardSettings.offset),
                    "direction" to vectorSummary(boardSettings.direction),
                ),
            "interaction" to
                linkedMapOf(
                    "dimensions" to vectorSummary(interaction.dimensions),
                    "offset" to vectorSummary(interaction.offset),
                    "particleDimensions" to vectorSummary(interaction.particleDimensions),
                    "particleOffset" to vectorSummary(interaction.particleOffset),
                    "particlePosition" to interaction.particlePosition.toInt(),
                    "particleSpacing" to interaction.particleSpacing,
                    "particleCount" to interaction.particleCount,
                    "effect" to effectSummary(interaction.hoverEffect),
                    "showHoverParticle" to interaction.isShowHoverParticle,
                    "showClickParticle" to interaction.isShowClickParticle,
                    "basePrefix" to interaction.basePrefix,
                    "hoverPrefix" to interaction.hoverPrefix,
                ),
            "animation" to
                linkedMapOf(
                    "fadeInTicks" to animationSettings.fadeInAnimationSpeed,
                    "fadeOutTicks" to animationSettings.fadeOutAnimationSpeed,
                    "autoRotateDegreesPerTick" to animationSettings.autoRotate,
                ),
        )
    }

    private fun hologramTypeName(type: CMIHologramType): String =
        when (type) {
            CMIHologramType.TextDisplay -> "text_display"
            CMIHologramType.ArmorStand -> "armor_stand"
            CMIHologramType.Auto -> "auto"
        }

    private fun colorName(color: CMIChatColor): String =
        "#%02x%02x%02x".format(color.red, color.green, color.blue)

    private fun colorName(color: Color): String =
        "#%02x%02x%02x".format(color.red, color.green, color.blue)

    private fun effectSummary(effect: CMIEffect?): Map<String, Any?>? =
        effect?.let {
            linkedMapOf(
                "particle" to it.particle?.name?.lowercase(),
                "color" to it.color?.let(::colorName),
                "colorFrom" to it.colorFrom?.let(::colorName),
                "colorTo" to it.colorTo?.let(::colorName),
                "offset" to vectorSummary(it.offset),
                "amount" to it.amount,
                "speed" to it.speed,
                "size" to it.size,
                "material" to it.material?.takeUnless(CMIMaterial::isNone)?.name?.lowercase(),
                "duration" to it.duration,
            )
        }

    private fun vectorSummary(vector: CMIVector2D?): Map<String, Double>? =
        vector?.let { linkedMapOf("x" to it.x, "y" to it.y) }

    private fun vectorSummary(vector: CMIVector3D?): Map<String, Double>? =
        vector?.let { linkedMapOf("x" to it.x, "y" to it.y, "z" to it.z) }

    private fun vectorSummary(vector: org.bukkit.util.Vector?): Map<String, Double>? =
        vector?.let { linkedMapOf("x" to it.x, "y" to it.y, "z" to it.z) }

    private fun locationSummary(location: Location): Map<String, Any?> =
        linkedMapOf(
            "world" to location.world?.name,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
            "yaw" to location.yaw,
            "pitch" to location.pitch,
        )

    private fun manager(): HologramManager {
        val plugin = Bukkit.getPluginManager().getPlugin("CMI")
        check(plugin?.isEnabled == true) { "CMI plugin is not enabled" }
        return CMI.getInstance().hologramManager
            ?: throw IllegalStateException("CMI hologram manager is unavailable")
    }

    private fun find(
        manager: HologramManager,
        name: String,
    ): CMIHologram =
        manager.getByName(name)
            ?: throw NoSuchElementException("CMI hologram not found: $name")
}

private inline fun <T> CmiHologramPatch<T>.applyIfSet(block: (T) -> Unit) {
    if (this is CmiHologramPatch.Set) block(value)
}

private fun <T> CmiHologramPatch<T>?.valueOrNull(): T? =
    (this as? CmiHologramPatch.Set)?.value

private fun CmiHologramVector2Spec.toNative(): CMIVector2D = CMIVector2D(x, y)

private fun CmiHologramVector3Spec.toNative(): CMIVector3D = CMIVector3D(x, y, z)
