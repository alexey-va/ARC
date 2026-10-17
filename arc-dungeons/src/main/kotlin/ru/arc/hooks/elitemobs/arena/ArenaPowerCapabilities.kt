package ru.arc.hooks.elitemobs.arena

import com.magmaguy.elitemobs.config.luapowers.premade.*
import com.magmaguy.elitemobs.config.powers.LuaPowerConfigFields
import com.magmaguy.elitemobs.config.powers.PowersConfigFields
import com.magmaguy.elitemobs.powers.scripts.caching.EliteScriptBlueprint

/** Audited stock 10.9.8 powers used by the league arenas, none of which creates another elite actor. */
internal object ArenaPowerCapabilities {
    // Compare the loaded source, not just the filename: admins may replace a premade Lua file.
    // Projectile/particle/temporary vanilla entities in these powers remain native effects.
    private val nonSummoningSources by lazy {
        listOf(
            ArrowFireworksLuaConfig(), ArrowRainLuaConfig(), AttackArrowLuaConfig(), AttackBlindingLuaConfig(),
            AttackConfusingLuaConfig(), AttackFireLuaConfig(), AttackFireballLuaConfig(), AttackFreezeLuaConfig(),
            AttackGravityLuaConfig(), AttackLightningLuaConfig(), AttackPoisonLuaConfig(), AttackPushLuaConfig(),
            AttackVacuumLuaConfig(), AttackWeaknessLuaConfig(), AttackWebLuaConfig(), AttackWitherLuaConfig(),
            BulletHellLuaConfig(), ChannelHealingLuaConfig(), CorpseLuaConfig(), DeathSliceLuaConfig(),
            FirestormLuaConfig(), FireworksBarrageLuaConfig(), FlamePyreLuaConfig(), FlamethrowerLuaConfig(),
            FrostConeLuaConfig(), GoldExplosionLuaConfig(), GoldShotgunLuaConfig(), GroundPoundLuaConfig(),
            ImplosionLuaConfig(), InvisibilityLuaConfig(), InvulnerabilityArrowLuaConfig(),
            InvulnerabilityFallDamageLuaConfig(), InvulnerabilityFireLuaConfig(), InvulnerabilityKnockbackLuaConfig(),
            LightningBoltsLuaConfig(), MeteorShowerLuaConfig(), MovementSpeedLuaConfig(), PhotonRayLuaConfig(),
            PlasmaBlasterLuaConfig(), ShieldWallLuaConfig(), SkeletonTrackingArrowLuaConfig(), TazeLuaConfig(),
            ThunderstormLuaConfig(), ZombieBloatLuaConfig(),
        ).associate { it.filename to it.source }
    }

    fun scripts(power: PowersConfigFields): List<EliteScriptBlueprint> {
        if (power is LuaPowerConfigFields) {
            val source = nonSummoningSources[power.filename]
            require(source != null && source == power.luaPowerDefinition.source) {
                "Unverified Lua power ${power.filename}"
            }
            return emptyList()
        }
        val scripts = power.eliteScriptBlueprints.orEmpty()
        require(scripts.isNotEmpty()) { "Unverified native power ${power.filename}" }
        return scripts
    }
}
