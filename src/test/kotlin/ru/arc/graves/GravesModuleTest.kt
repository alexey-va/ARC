package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.PluginDescriptionFile
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture

class GravesModuleTest : FunSpec({
    test("only the latest administrator request may open and its loading dismissal cannot cancel its replacement") {
        val f = AdminRequestFixture()
        f.requests.use { requests ->
            var opened = 0
            fun pending(): Pair<GravesAdminRequests.Request, CompletableFuture<Unit>> {
                val request = requireNotNull(requests.begin(f.player))
                val reply = CompletableFuture<Unit>()
                reply.thenRun { if (requests.complete(request)) opened++ }
                return request to reply
            }
            val old = pending()
            val current = pending()
            (current.first.nonce > old.first.nonce) shouldBe true
            requests.dismiss(old.first)
            old.second.complete(Unit)
            opened shouldBe 0
            current.second.complete(Unit)
            opened shouldBe 1
            requests.complete(current.first) shouldBe false
            val dismissed = pending()
            requests.dismiss(dismissed.first)
            dismissed.second.complete(Unit)
            opened shouldBe 1
        }
    }

    test("administrator return rechecks permission identity world online and death without reviving a retired request") {
        val f = AdminRequestFixture()
        f.requests.use { requests ->
            val cases: List<() -> Unit> = listOf(
                { f.allowed = false }, { f.currentPlayer = mockk(relaxed = true) },
                { f.worldId = UUID(9, 9) }, { f.online = false }, { f.dead = true },
            )
            cases.forEach { invalidate ->
                f.reset()
                val request = requireNotNull(requests.begin(f.player))
                invalidate()
                requests.complete(request) shouldBe false
                f.reset()
                requests.complete(request) shouldBe false
            }
            f.allowed = false
            requests.begin(f.player) shouldBe null
            f.reset()
            val pending = requireNotNull(requests.begin(f.player))
            requests.close()
            requests.complete(pending) shouldBe false
            requests.begin(f.player) shouldBe null
        }
    }

    test("actual command inventory world and quit events retire pending administrator work") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("GravesAdminRequests")
                val player = paper.addPlayer("Administrator")
                player.addAttachment(plugin, GRAVES_ADMIN_PERMISSION, true)
                GravesAdminRequests().use { requests ->
                    Bukkit.getPluginManager().registerEvents(requests, plugin)
                    var pending = requireNotNull(requests.begin(player))
                    paper.callEvent(PlayerCommandPreprocessEvent(player, "/spawn").also { it.isCancelled = true })
                    requests.complete(pending) shouldBe false

                    pending = requireNotNull(requests.begin(player))
                    player.openInventory(Bukkit.createInventory(null, 9))
                    requests.complete(pending) shouldBe false

                    pending = requireNotNull(requests.begin(player))
                    paper.callEvent(PlayerChangedWorldEvent(player, player.world))
                    requests.complete(pending) shouldBe false

                    pending = requireNotNull(requests.begin(player))
                    paper.callEvent(PlayerQuitEvent(player, ""))
                    requests.complete(pending) shouldBe false

                    pending = requireNotNull(requests.begin(player))
                    requests.complete(pending) shouldBe true
                    player.openInventory(Bukkit.createInventory(null, 18))
                    requests.complete(pending) shouldBe false
                }
            }
        }
    }

    test("administrator inspection permission defaults to operators") {
        val loader = GravesModuleTest::class.java.classLoader
        val description = requireNotNull(loader.getResourceAsStream("plugin.yml")).bufferedReader().use(::PluginDescriptionFile)
        description.permissions.single { it.name == GRAVES_ADMIN_PERMISSION }.default shouldBe PermissionDefault.OP
    }
})

private class AdminRequestFixture {
    val player = mockk<Player>(relaxed = true)
    var currentPlayer: Player? = player
    var allowed = true
    var online = true
    var dead = false
    var worldId = UUID(1, 1)
    private val world = mockk<World>()
    val requests = GravesAdminRequests { currentPlayer }

    init {
        every { player.uniqueId } returns UUID(2, 2)
        every { player.world } returns world
        every { world.uid } answers { worldId }
        every { player.isOnline } answers { online }
        every { player.isDead } answers { dead }
        every { player.hasPermission(GRAVES_ADMIN_PERMISSION) } answers { allowed }
    }

    fun reset() { currentPlayer = player; allowed = true; online = true; dead = false; worldId = UUID(1, 1) }
}
