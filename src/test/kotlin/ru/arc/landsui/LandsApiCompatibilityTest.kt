package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import me.angeschossen.lands.api.applicationframework.util.ULID
import me.angeschossen.lands.api.memberholder.MemberHolder
import me.angeschossen.lands.api.player.LandPlayer
import me.angeschossen.lands.api.player.OfflinePlayer

class LandsApiCompatibilityTest : StringSpec({
    "matches the production getLands binary contract" {
        LandPlayer::class.java.declaredMethods.any {
            it.name == "getLands" && it.parameterCount == 0
        } shouldBe false

        OfflinePlayer::class.java.getDeclaredMethod("getLands").returnType shouldBe Collection::class.java
        MemberHolder::class.java.getDeclaredMethod("getULID").returnType shouldBe ULID::class.java
    }
})
