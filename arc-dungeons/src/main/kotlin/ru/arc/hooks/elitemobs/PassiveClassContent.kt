package ru.arc.hooks.elitemobs

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.magmaguy.elitemobs.advancedcombat.content.BuiltInClassContent
import com.magmaguy.elitemobs.advancedcombat.passives.FixedPassiveRegistry
import com.magmaguy.elitemobs.advancedcombat.passives.PassiveMechanicTrait
import com.magmaguy.elitemobs.advancedcombat.passives.PassiveProfile
import com.magmaguy.elitemobs.advancedcombat.passives.PassiveTrait
import ru.arc.logging.ArcLogging as Logging
import java.security.MessageDigest

/**
 * The passive-only balance overlays known forms in the current native catalog.
 * UI and combat receive the same registry; additional native forms retain their own mechanics.
 */
internal object PassiveClassContent {
    const val BALANCE_SOURCE_VERSION = "10.9.8"
    const val STOCK_FINGERPRINT = "3368a7461ea59e2ca62ae7facca481263f5876c77f7000fdda83dd2f523c1ead"
    const val BALANCE_FINGERPRINT = "9d4de8eb00f8fc4afea8a4d3784ae5ebb73eb7679e919b19e423679805587929"
    private const val DOCUMENT_SHA256 = "d1745b505e98307aef3992faaa916061e5856847da19e4dc4f4af0a0128d0582"

    private val balanced by lazy { applyBalance(BuiltInClassContent.passiveRegistry()) }

    fun registry(): FixedPassiveRegistry = balanced

    internal fun applyBalance(stock: FixedPassiveRegistry, warn: (String) -> Unit = { Logging.warn(it) }): FixedPassiveRegistry {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/elitemobs/passive-classes-10.9.8.json"))
            .use { it.readBytes() }
        check(sha256(bytes) == DOCUMENT_SHA256) { "Bundled passive balance document failed its integrity check" }
        val document = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        check(document["schema"].asInt == 1 && document["nativeVersion"].asString == BALANCE_SOURCE_VERSION)
        check(document["stockFingerprint"].asString == STOCK_FINGERPRINT)
        check(document["balanceFingerprint"].asString == BALANCE_FINGERPRINT)
        val overrides = document.getAsJsonObject("forms")
        val knownStock = fingerprint(stock) == STOCK_FINGERPRINT
        val missing = overrides.keySet() - stock.mappedFormIds()
        if (!knownStock) warn("Native passive catalog changed; applying compatible balance overrides; " +
            "skipped ${missing.size} absent forms: ${missing.sorted().take(8).joinToString()}")
        val gson = Gson()
        val profiles = linkedMapOf<String, PassiveProfile>()
        val traits = linkedMapOf<String, List<PassiveTrait>>()
        val mechanics = linkedMapOf<String, List<PassiveMechanicTrait>>()
        for (id in stock.mappedFormIds().sorted()) {
            val override = overrides.getAsJsonObject(id)
            val profile = gson.toJsonTree(stock.require(id)).asJsonObject
            override?.getAsJsonObject("profile")?.entrySet()?.forEach { (key, value) ->
                check(profile.has(key)) { "Unknown passive profile field: $key" }
                profile.add(key, value)
            }
            profiles[id] = gson.fromJson(profile, PassiveProfile::class.java)
            traits[id] = override?.getAsJsonArray("traits")?.map { gson.fromJson(it, PassiveTrait::class.java) }
                ?: stock.traits(id)
            mechanics[id] = override?.getAsJsonArray("mechanics")?.map { gson.fromJson(it, PassiveMechanicTrait::class.java) }
                ?: stock.mechanicTraits(id)
        }
        return EliteMobsPassiveAccess.newRegistry(profiles, traits, mechanics).also {
            if (knownStock) check(fingerprint(it) == BALANCE_FINGERPRINT) { "Passive-only balance failed its complete catalog check" }
        }
    }

    internal fun fingerprint(registry: FixedPassiveRegistry): String {
        val lines = buildList {
            for (id in registry.mappedFormIds().sorted()) {
                add("$id|profile|${registry.require(id)}")
                addAll(registry.traits(id).map { trait ->
                    "$id|trait|${trait.conditions().map { it.name }.sorted().joinToString(",")}|${trait.profile()}"
                }.sorted())
                addAll(registry.mechanicTraits(id).map { trait ->
                    "$id|mechanic|${trait.conditions().map { it.name }.sorted().joinToString(",")}|${trait.authoredAtLevelThirty()}"
                }.sorted())
            }
        }
        return sha256(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
