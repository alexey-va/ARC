package ru.arc.hooks.elitemobs

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.magmaguy.elitemobs.advancedcombat.content.BuiltInClassContent
import com.magmaguy.elitemobs.advancedcombat.passives.FixedPassiveRegistry
import com.magmaguy.elitemobs.advancedcombat.passives.PassiveMechanicTrait
import com.magmaguy.elitemobs.advancedcombat.passives.PassiveProfile
import com.magmaguy.elitemobs.advancedcombat.passives.PassiveTrait
import java.security.MessageDigest

/**
 * The passive-only balance is data over the pinned native catalog, evaluated by native mechanics.
 * UI and combat receive the same registry. Both fingerprints deliberately reject upstream drift:
 * changing the EliteMobs dependency requires reviewing this small compatibility boundary too.
 */
internal object PassiveClassContent {
    const val NATIVE_VERSION = "10.9.8"
    const val STOCK_FINGERPRINT = "3368a7461ea59e2ca62ae7facca481263f5876c77f7000fdda83dd2f523c1ead"
    const val BALANCE_FINGERPRINT = "9d4de8eb00f8fc4afea8a4d3784ae5ebb73eb7679e919b19e423679805587929"

    private val balanced by lazy(::load)

    fun registry(): FixedPassiveRegistry = balanced

    private fun load(): FixedPassiveRegistry {
        val stock = BuiltInClassContent.passiveRegistry()
        check(fingerprint(stock) == STOCK_FINGERPRINT) { "EliteMobs $NATIVE_VERSION passive catalog differs from the supported stock catalog" }
        val document = requireNotNull(javaClass.getResourceAsStream("/elitemobs/passive-classes-10.9.8.json"))
            .bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        check(document["schema"].asInt == 1 && document["nativeVersion"].asString == NATIVE_VERSION)
        check(document["stockFingerprint"].asString == STOCK_FINGERPRINT)
        check(document["balanceFingerprint"].asString == BALANCE_FINGERPRINT)
        val overrides = document.getAsJsonObject("forms")
        check(stock.mappedFormIds().containsAll(overrides.keySet())) { "Passive balance contains an unknown native form" }
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
        return EliteMobs1098Access.newRegistry(profiles, traits, mechanics).also {
            check(fingerprint(it) == BALANCE_FINGERPRINT) { "Passive-only balance failed its complete catalog check" }
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
        return MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
