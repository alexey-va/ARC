package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent

class LandsUiCommandRouteTest : StringSpec({
    "bare trust and all Lands root labels open dialog routes" {
        listOf("/trust", "/TrUsT", "/lands:trust", " /trust  ").forEach {
            landsUiCommandRoute(it) shouldBe LandsUiCommandRoute(LandsUiDestination.ADD_MEMBER)
        }
        listOf("/lands", "/land", "/lands:lands", "/lands:land").forEach {
            landsUiCommandRoute(it) shouldBe LandsUiCommandRoute(LandsUiDestination.ROOT)
        }
        landsUiCommandRoute("/untrust") shouldBe LandsUiCommandRoute(LandsUiDestination.MEMBERS)
    }

    "navigation and incomplete member actions open the matching task" {
        mapOf(
            "menu" to LandsUiDestination.OVERVIEW,
            "menu land roles" to LandsUiDestination.ROLES,
            "menu land options" to LandsUiDestination.SETTINGS,
            "menu lands" to LandsUiDestination.ROOT,
            "menu here" to LandsUiDestination.CURRENT,
            "invites" to LandsUiDestination.INVITES,
            "invites menu" to LandsUiDestination.INVITES,
            "menu personal invites" to LandsUiDestination.INVITES,
            "trust" to LandsUiDestination.ADD_MEMBER,
            "member trust" to LandsUiDestination.ADD_MEMBER,
            "member" to LandsUiDestination.MEMBERS,
            "member menu" to LandsUiDestination.MEMBERS,
            "member untrust" to LandsUiDestination.MEMBERS,
            "member setrole" to LandsUiDestination.MEMBERS,
            "roles" to LandsUiDestination.ROLES,
            "flags" to LandsUiDestination.RULES,
            "settings" to LandsUiDestination.SETTINGS,
            "rename" to LandsUiDestination.RENAME,
            "leave" to LandsUiDestination.LEAVE,
            "delete" to LandsUiDestination.DELETE,
            "create" to LandsUiDestination.CREATE,
            "help" to LandsUiDestination.GUIDE,
        ).forEach { (command, destination) ->
            landsUiCommandRoute("/lands $command") shouldBe LandsUiCommandRoute(destination)
        }
    }

    "area navigation preserves the exact requested area token" {
        landsUiCommandRoute("/lands area City_Centre menu") shouldBe
            LandsUiCommandRoute(LandsUiDestination.OVERVIEW, "City_Centre")
        landsUiCommandRoute("/lands area City_Centre member trust") shouldBe
            LandsUiCommandRoute(LandsUiDestination.ADD_MEMBER, "City_Centre")
    }

    "explicit actions and unrelated commands remain native" {
        listOf(
            "/trust Alex", "/lands trust Alex", "/lands untrust Alex", "/lands member trust Alex",
            "/lands area Centre member trust Alex", "/lands create Home", "/lands rename Home",
            "/lands invites accept Home", "/lands invites decline Home",
            "/lands delete confirm", "/lands leave confirm", "/lands claim", "/lands unclaim",
            "/lands view", "/lands spawn", "/lands storage", "/lands bank deposit 100",
            "/lands admin menu", "/worldedit:trust", "/another:lands menu", "/landscape", "/home",
        ).forEach { landsUiCommandRoute(it) shouldBe null }
    }

    "Lands dialog entrypoints remain enabled when retired shortcut aliases are disabled" {
        val player = mockk<Player>(relaxed = true)
        val open = mockk<(Player, LandsUiCommandRoute) -> Unit>(relaxed = true)
        val listener = ClaimMenuCommandListener({ true }, {}, open, { false }, { false })
        val trust = PlayerCommandPreprocessEvent(player, "/trust")
        listener.onPlayerCommand(trust)
        trust.isCancelled shouldBe true
        val wand = PlayerCommandPreprocessEvent(player, "/wand")
        listener.onPlayerCommand(wand)
        wand.isCancelled shouldBe false
        verify(exactly = 1) { open(player, LandsUiCommandRoute(LandsUiDestination.ADD_MEMBER)) }
    }

    "listener cancels a menu shortcut once but preserves actions and unavailable-module fallback" {
        val player = mockk<Player>(relaxed = true)
        var available = true
        val open = mockk<(Player, LandsUiCommandRoute) -> Unit>(relaxed = true)
        val listener = ClaimMenuCommandListener({ available }, {}, open, { false })
        val trust = PlayerCommandPreprocessEvent(player, "/trust")
        listener.onPlayerCommand(trust)
        trust.isCancelled shouldBe true
        verify(exactly = 1) { open(player, LandsUiCommandRoute(LandsUiDestination.ADD_MEMBER)) }

        val action = PlayerCommandPreprocessEvent(player, "/trust Alex")
        listener.onPlayerCommand(action)
        action.isCancelled shouldBe false
        available = false
        val fallback = PlayerCommandPreprocessEvent(player, "/trust")
        listener.onPlayerCommand(fallback)
        fallback.isCancelled shouldBe false
        verify(exactly = 1) { open(any(), any()) }
        verify(exactly = 0) { player.performCommand(any()) }
    }
})
