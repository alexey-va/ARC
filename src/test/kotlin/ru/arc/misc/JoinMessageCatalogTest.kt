package ru.arc.misc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.util.Common
import java.nio.file.Files

class JoinMessageCatalogTest : FreeSpec({
    "Paper decodes the ProxyARC wire schema and preserves catalog order" {
        val catalog =
            Common.gson.fromJson(
                """
                {
                  "catalogId":"catalog",
                  "schemaVersion":1,
                  "revision":"abc123",
                  "updatedAt":42,
                  "join":[
                    {"id":"join-01","message":"one","displayName":"<italic:false>One","material":"APPLE","customModelData":0,"rank":"<italic:false>All"},
                    {"id":"join-02","message":"two","displayName":"<italic:false>Two","material":"COMPASS","customModelData":7,"permission":"rank.vip","rank":"<italic:false>VIP"}
                  ],
                  "leave":[
                    {"id":"leave-01","message":"bye","displayName":"<italic:false>Bye","material":"BARRIER","customModelData":0,"rank":"<italic:false>All"}
                  ]
                }
                """.trimIndent(),
                JoinMessageCatalog::class.java,
            )

        catalog.validate()
        catalog.entries(isJoin = true).map(JoinMessageCatalogEntry::id) shouldContainExactly
            listOf("join-01", "join-02")
        catalog.entries(isJoin = true)[1].customModelData shouldBe 7
    }

    "invalid Redis catalog is rejected but an unknown icon degrades to paper" {
        JoinMessageMaterial.resolve("NOT_A_MATERIAL") shouldBe Material.PAPER
        JoinMessageMaterial.resolve("AMETHYST_SHARD") shouldBe Material.AMETHYST_SHARD

        val invalid =
            JoinMessageCatalog(
                schemaVersion = 99,
                revision = "bad",
                join = listOf(JoinMessageCatalogEntry(id = "join-01", message = "text")),
            )
        shouldThrow<IllegalArgumentException> { invalid.validate() }
    }

    "Paper bundled misc config no longer contains a local phrase catalog" {
        val directory = Files.createTempDirectory("arc-misc-without-phrase-catalog-")
        Config.copyDefaultConfig("modules/misc.yml", directory, replace = false)
        val config = Config(directory, "modules/misc.yml")

        config.exists("join-message-gui.messages") shouldBe false
        config.exists("leave-message-gui.messages") shouldBe false
    }
})
