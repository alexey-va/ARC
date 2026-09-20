package ru.arc.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ArcRuntimeProfileTest : FreeSpec({
    afterTest { ConfigManager.clear() }

    "an existing server without a runtime profile keeps the full composition" {
        val root = Files.createTempDirectory("arc-profile-default")
        try {
            ArcRuntimeProfile.load(root) shouldBe ArcRuntimeProfile.FULL
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "an explicit isolated profile is loaded from its own configuration" {
        val root = Files.createTempDirectory("arc-profile-isolated")
        try {
            Files.createDirectories(root.resolve("modules"))
            Files.writeString(root.resolve("modules/runtime.yml"), "profile: isolated\n")
            ArcRuntimeProfile.load(root) shouldBe ArcRuntimeProfile.ISOLATED
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "a misspelled profile cannot silently enable full gameplay and synchronization" {
        shouldThrow<IllegalArgumentException> { ArcRuntimeProfile.parse("isloated") }
        ArcRuntimeProfile.parse(" FULL ") shouldBe ArcRuntimeProfile.FULL
    }
})
