package ru.arc.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import ru.arc.paper.api.ArcSidebarPriorities
import ru.arc.paper.api.ArcSidebarSelectionSnapshot
import ru.arc.paper.api.ArcSidebarService
import ru.arc.paper.testing.MockBukkitTestRuntime

class SectionedSidebarServiceTest : StringSpec({
    "activity composition follows Core selection and preserves names before restoring original frames" {
        MockBukkitTestRuntime.open().use { runtime ->
            val host = runtime.createSimplePlugin("ARC")
            val farmPlugin = runtime.createSimplePlugin("ArcFarms")
            val eventPlugin = runtime.createSimplePlugin("ArcEvents")
            val player = runtime.addPlayer("Viewer")
            // MockBukkit does not implement Score.customName; test the existing typed service port.
            val native = mockk<ArcSidebarService>()
            var selected = ArcSidebarSelectionSnapshot("ARC", "base", 0)
            var rendered: ArcSidebarFrame? = null
            every { native.active(any()) } answers { selected }
            every { native.register(any(), any(), any()) } answers {
                mockk<ArcSidebarHandle>(relaxed = true).also { handle ->
                    every { handle.show(any(), any()) } answers { rendered = secondArg() }
                }
            }
            val service = SectionedSidebarService(host, native)
            val base = service.register(host, "base", ArcSidebarPriorities.BASE)
            val farm = service.register(farmPlugin, "worksite", ArcSidebarPriorities.ACTIVITY)
            val event = service.register(eventPlugin, "event", ArcSidebarPriorities.EVENT)
            var merged = false
            service.refreshPlayer = { viewer ->
                val rows = composeSidebarSections({ it == SidebarSection.RANK || it == SidebarSection.ACTIVITY && merged }, {
                    if (it == SidebarSection.RANK) listOf(Component.text("Rank")) else service.activity(viewer.uniqueId)?.rows.orEmpty()
                }, Component.empty()) { it == Component.empty() }
                val frame = ArcSidebarFrame(Component.text("Base"), rows)
                base.show(viewer, frame)
                service.present(viewer, frame, merged)
            }
            service.refreshPlayer!!(player)
            selected = ArcSidebarSelectionSnapshot("ArcFarms", "worksite", 100)
            farm.show(player, ArcSidebarFrame(Component.text("Farm"), listOf(Component.text("Harvest"))))
            rendered!!.title shouldBe Component.text("Farm")
            merged = true
            service.refreshPlayer!!(player)
            rendered!!.rows shouldContainExactly listOf(Component.text("Rank"), Component.empty(), Component.text("Harvest"))
            selected = ArcSidebarSelectionSnapshot("ArcEvents", "event", 300)
            event.show(player, ArcSidebarFrame(Component.text("Event"), listOf(Component.text("Goal")), setOf("Alpha")))
            service.active(player.uniqueId)!!.owner shouldBe "ArcEvents"
            rendered!!.rows.last() shouldBe Component.text("Goal")
            rendered!!.hiddenNameEntries shouldBe setOf("Alpha")
            selected = ArcSidebarSelectionSnapshot("ArcFarms", "worksite", 100)
            event.hide(player)
            rendered!!.rows.last() shouldBe Component.text("Harvest")
            rendered!!.hiddenNameEntries shouldBe emptySet()
            merged = false
            service.refreshPlayer!!(player)
            rendered!!.title shouldBe Component.text("Farm")
            selected = ArcSidebarSelectionSnapshot("ARC", "base", 0)
            farm.close()
            rendered!!.rows shouldContainExactly listOf(Component.text("Rank"))
            service.close()
        }
    }
})
