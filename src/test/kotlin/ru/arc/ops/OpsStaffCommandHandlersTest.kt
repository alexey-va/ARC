package ru.arc.ops

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.luckperms.api.LuckPerms
import net.luckperms.api.model.group.Group
import net.luckperms.api.model.user.User
import net.luckperms.api.query.QueryOptions
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.spy.CrossServerSpyModule
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OpsStaffCommandHandlersTest : FreeSpec({
    val actor = UUID.fromString("10000000-0000-0000-0000-000000000001")
    val requestId = UUID.fromString("20000000-0000-0000-0000-000000000002")
    fun body(command: String = "list", id: UUID = requestId) =
        OpsJson.toJson(mapOf("requestId" to id.toString(), "actorUuid" to actor.toString(), "command" to command))

    "strict schema rejects malformed, ambiguous and injected input without reflecting it" {
        val malformed = listOf(
            "[]", body() + "{}", body().dropLast(1) + ",\"extra\":true}",
            body().replace("\"command\":\"list\"", "\"command\":3"),
            body().replace("\"command\":\"list\"", "\"command\":\"list\",\"command\":\"stop\""),
            body("list\nstop"), body("x".repeat(513)), body("/"),
            body().replace(requestId.toString(), "2-0-0-0-2"),
            body().replace(actor.toString(), "10000000-0000-0000-0000-00000000000A"),
        )
        malformed.forEach { raw ->
            val failure = shouldThrow<StaffCommandFailure> { OpsStaffCommandHandlers.parse(raw) }
            failure.status shouldBe 400
            failure.message shouldBe "Invalid staff command request"
        }
        OpsStaffCommandHandlers.parse(body("x".repeat(512))).command.length shouldBe 512
        OpsHttpConfig().staffCommandsEnabled shouldBe false
        OpsHttpConfig().staffCommandAdminGroups shouldBe setOf("admin")
    }

    "direct sender feedback is literal, stripped and bounded, and late feedback is ignored" {
        val output = StaffCommandOutput()
        output.accept(Component.text("§aReady\u001B[31m\u0000 <red>literal</red>\nsecond"))
        repeat(30) { output.accept(Component.text("x".repeat(200))) }
        val lines = output.snapshot()
        lines.first() shouldBe "Ready <red>literal</red>"
        lines[1] shouldBe "second"
        (lines.size <= 20) shouldBe true
        lines.sumOf(String::length) shouldBe 2000
        output.accept(Component.text("late"))
        output.snapshot() shouldBe lines
        val lineLimited = StaffCommandOutput()
        repeat(30) { lineLimited.accept(Component.text("line")) }
        lineLimited.snapshot().size shouldBe 20
    }

    "fresh inherited admin authorizes off main and duplicate concurrent requests dispatch only once on main" {
        val mainThread = Thread.currentThread()
        val api = mockk<LuckPerms>(relaxed = true)
        val userManager = api.userManager
        val user = mockk<User>()
        val options = mockk<QueryOptions>()
        val group = mockk<Group> { every { name } returns "admin" }
        var authorizationThread: Thread? = null
        every { api.userManager.loadUser(actor) } answers {
            authorizationThread = Thread.currentThread()
            CompletableFuture.completedFuture(user)
        }
        every { api.contextManager.staticQueryOptions } returns options
        every { user.uniqueId } returns actor
        every { user.getInheritedGroups(options) } returns listOf(group)
        mockkStatic(Bukkit::class)
        mockkObject(CrossServerSpyModule)
        try {
            every { Bukkit.isPrimaryThread() } answers { Thread.currentThread() === mainThread }
            every { CrossServerSpyModule.staffCommandAllowed("list") } returns true
            every { CrossServerSpyModule.publishStaffCommand(any(), any(), any()) } returns Unit
            val sender = mockk<CommandSender>()
            var feedback: Consumer<in Component>? = null
            every { Bukkit.createCommandSender(any()) } answers {
                (Thread.currentThread() === mainThread) shouldBe true
                feedback = firstArg()
                sender
            }
            every { Bukkit.dispatchCommand(sender, "list") } answers {
                (Thread.currentThread() === mainThread) shouldBe true
                feedback!!.accept(Component.text("There are 2 players"))
                true
            }
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                val config = TestOpsHttpConfig(staffCommandsEnabled = true)
                val handler = OpsStaffCommandHandlers({ api }, configProvider = { config })
                val first = CompletableFuture.supplyAsync { handler.execute(body(), config) }
                val duplicate = CompletableFuture.supplyAsync { handler.execute(body(), config) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (scheduler.pendingCount() == 0 && !first.isDone && System.nanoTime() < deadline) Thread.sleep(1)
                scheduler.pendingCount() shouldBe 1
                scheduler.executeImmediate()
                val result = first.get(3, TimeUnit.SECONDS)
                duplicate.get(3, TimeUnit.SECONDS) shouldBe result
                result shouldBe mapOf("requestId" to requestId.toString(), "dispatched" to true, "output" to listOf("There are 2 players"))
                (authorizationThread !== mainThread) shouldBe true
                val conflict = shouldThrow<ExecutionException> {
                    CompletableFuture.supplyAsync { handler.execute(body("stop"), config) }.get(3, TimeUnit.SECONDS)
                }
                (conflict.cause as StaffCommandFailure).status shouldBe 409
                verify(exactly = 1) { Bukkit.dispatchCommand(sender, "list") }
                verify(exactly = 1) { CrossServerSpyModule.publishStaffCommand(requestId, actor, "list") }
                verify(exactly = 3) { userManager.loadUser(actor) }
            }
        } finally {
            unmockkObject(CrossServerSpyModule)
            unmockkStatic(Bukkit::class)
        }
    }

    listOf("revoked role", "disabled config", "stopped listener").forEach { scenario ->
        "queued command rejects $scenario at the final sink and preserves its replay reservation" {
            val mainThread = Thread.currentThread()
            val api = mockk<LuckPerms>(relaxed = true)
            val manager = api.userManager
            val user = mockk<User>()
            val options = mockk<QueryOptions>()
            val group = mockk<Group> { every { name } returns "admin" }
            val admin = AtomicBoolean(true)
            val cleanupOnMain = AtomicBoolean(false)
            val initialConfig = TestOpsHttpConfig(staffCommandsEnabled = true)
            val config = AtomicReference(initialConfig)
            every { manager.loadUser(actor) } returns CompletableFuture.completedFuture(user)
            every { api.contextManager.staticQueryOptions } returns options
            every { user.uniqueId } returns actor
            every { user.getInheritedGroups(options) } answers { if (admin.get()) listOf(group) else emptyList() }
            every { manager.cleanupUser(user) } answers { cleanupOnMain.set(Thread.currentThread() === mainThread) }
            mockkStatic(Bukkit::class)
            mockkObject(CrossServerSpyModule)
            val handler = OpsStaffCommandHandlers({ api }, configProvider = config::get)
            val server = OpsHttpServer(configProvider = config::get, staffCommandHandlers = handler)
            try {
                every { Bukkit.isPrimaryThread() } answers { Thread.currentThread() === mainThread }
                every { Bukkit.createCommandSender(any()) } returns mockk()
                every { CrossServerSpyModule.staffCommandAllowed("list") } returns true
                every { CrossServerSpyModule.publishStaffCommand(any(), any(), any()) } returns Unit
                val scheduler = TestTaskScheduler()
                Tasks.withScheduler(scheduler) {
                    server.start()
                    val result = CompletableFuture.supplyAsync { handler.execute(body(), config.get()) }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (scheduler.pendingCount() == 0 && !result.isDone && System.nanoTime() < deadline) Thread.sleep(1)
                    scheduler.pendingCount() shouldBe 1
                    verify(exactly = 0) { manager.cleanupUser(user) }
                    when (scenario) {
                        "revoked role" -> admin.set(false)
                        "disabled config" -> config.set(initialConfig.copy(staffCommandsEnabled = false))
                        else -> server.stop()
                    }
                    scheduler.executeImmediate()
                    val failure = shouldThrow<ExecutionException> { result.get(3, TimeUnit.SECONDS) }
                    (failure.cause as StaffCommandFailure).status shouldBe if (scenario == "revoked role") 403 else 503
                    cleanupOnMain.get() shouldBe false
                    verify(exactly = 1) { manager.cleanupUser(user) }
                    verify(exactly = 0) { Bukkit.dispatchCommand(any(), any()) }
                    verify(exactly = 0) { CrossServerSpyModule.publishStaffCommand(any(), any(), any()) }
                    admin.set(true)
                    config.set(initialConfig)
                    handler.activate()
                    shouldThrow<ExecutionException> {
                        CompletableFuture.supplyAsync { handler.execute(body(), config.get()) }.get(3, TimeUnit.SECONDS)
                    }.cause!!.javaClass shouldBe StaffCommandFailure::class.java
                    scheduler.pendingCount() shouldBe 0
                    verify(exactly = 0) { Bukkit.dispatchCommand(any(), any()) }
                }
            } finally {
                server.stop()
                unmockkObject(CrossServerSpyModule)
                unmockkStatic(Bukkit::class)
            }
        }
    }

    "non-admin and wrong loaded identity fail closed before scheduling or dispatch" {
        val api = mockk<LuckPerms>(relaxed = true)
        val userManager = api.userManager
        val user = mockk<User>()
        val options = mockk<QueryOptions>()
        every { api.userManager.loadUser(actor) } returns CompletableFuture.completedFuture(user)
        every { api.contextManager.staticQueryOptions } returns options
        every { user.uniqueId } returns actor
        every { user.getInheritedGroups(options) } returns emptyList()
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.isPrimaryThread() } returns false
            val handler = OpsStaffCommandHandlers({ api })
            val config = TestOpsHttpConfig(staffCommandsEnabled = true)
            shouldThrow<StaffCommandFailure> { handler.execute(body(), config) }.status shouldBe 403
            every { user.uniqueId } returns UUID.randomUUID()
            shouldThrow<StaffCommandFailure> { handler.execute(body(), config) }.status shouldBe 403
            listOf("login private-value", "authme:register private-value", "cmi:cmi cmi:usermeta name private-value").forEach {
                shouldThrow<StaffCommandFailure> { handler.execute(body(it), config) }.status shouldBe 403
            }
            shouldThrow<StaffCommandFailure> {
                OpsStaffCommandHandlers({ throw NoClassDefFoundError("provider unavailable") }).execute(body(), config)
            }.status shouldBe 503
            verify(exactly = 0) { Bukkit.dispatchCommand(any(), any()) }
            verify(exactly = 2) { userManager.loadUser(actor) }
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }
})
