package ru.arc.itemcatalog

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CompletionException

class SharedPhysicalRewardArchiveIntegrationTest : FreeSpec({
    lateinit var mysql: MySqlTestService
    lateinit var connection: SqlConnectionConfig

    beforeSpec {
        mysql = MySqlTestService.start(MySqlTestSettings(database = "arc_physical_archive_test"))
        connection = SqlConnectionConfig(
            host = mysql.endpoint.host,
            port = mysql.endpoint.port,
            database = mysql.endpoint.database,
            username = mysql.endpoint.username,
            password = mysql.endpoint.password,
            sslMode = SqlSslMode.DISABLED,
            minimumIdle = 0,
            maximumPoolSize = 2,
        )
    }

    afterSpec { mysql.close() }

    fun record(): FrozenPhysicalRewardRecord = MockBukkitTestRuntime.open().use {
        val root = Files.createTempDirectory("arc-shared-physical-reward")
        try {
            checkNotNull(
                FrozenPhysicalRewards(root).capture(
                    "travel-anchors:1",
                    FrozenPhysicalRecipe(type = "travel-anchors", travelAnchorAmount = 1),
                    ItemStack(Material.PAPER),
                ),
            ).record
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "two nodes read one immutable recipe; missing fingerprint stays distinct from repository failure" {
        val fixture = record()
        val first = MySqlSharedPhysicalRewardArchiveRepository.open(connection)
        val second = MySqlSharedPhysicalRewardArchiveRepository.open(connection)
        try {
            first.publishBatch(listOf(fixture)).join()
            second.publishBatch(listOf(fixture)).join()
            second.fetch(fixture.fingerprint).join() shouldBe Gson().toJson(fixture).toByteArray(Charsets.UTF_8)

            val missing = "0".repeat(64).takeUnless { it == fixture.fingerprint } ?: "1".repeat(64)
            second.fetch(missing).join() shouldBe null

            val corrupt = "{\"changed\":true}".toByteArray(Charsets.UTF_8)
            mysql.endpoint.connect().use { database ->
                database.prepareStatement(
                    "UPDATE arc_catalog_physical_reward_archive SET payload_hash = ?, payload = ? WHERE fingerprint = ?",
                ).use { statement ->
                    statement.setString(1, corrupt.sha256())
                    statement.setBytes(2, corrupt)
                    statement.setString(3, fixture.fingerprint)
                    statement.executeUpdate() shouldBe 1
                }
            }
            shouldThrow<CompletionException> { first.publishBatch(listOf(fixture)).join() }
        } finally {
            first.close()
            second.close()
        }
    }
})

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }
