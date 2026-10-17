package ru.arc.hooks

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Modules.Afk.AfkManager
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.UUID

class CmiAfkProviderBridgeTest : FreeSpec({
    beforeTest { mockkStatic(CMI::class) }
    afterTest {
        HookRegistry.cmiHook = null
        unmockkStatic(CMI::class)
    }

    "uses CMI's current AFK flag and stops reading the retired hook" {
        val playerId = UUID.randomUUID()
        val afk = mockk<AfkManager>()
        val cmi = mockk<CMI>()
        every { CMI.getInstance() } returns cmi
        every { cmi.isEnabled } returns true
        every { cmi.afkManager } returns afk
        every { afk.isAfk(playerId) } returns true
        HookRegistry.cmiHook = CMIHook()

        CmiAfkProviderBridge.isAfk(playerId).shouldBeTrue()
        every { afk.isAfk(playerId) } returns false
        CmiAfkProviderBridge.isAfk(playerId).shouldBeFalse()

        HookRegistry().close()
        CmiAfkProviderBridge.isAfk(playerId).shouldBeFalse()
        verify(exactly = 2) { afk.isAfk(playerId) }
    }

    "missing hook, unready CMI and disabled CMI have no AFK viewers" {
        val playerId = UUID.randomUUID()
        HookRegistry.cmiHook = null
        CmiAfkProviderBridge.isAfk(playerId).shouldBeFalse()

        HookRegistry.cmiHook = CMIHook()
        every { CMI.getInstance() } returns null
        CmiAfkProviderBridge.isAfk(playerId).shouldBeFalse()

        val cmi = mockk<CMI>()
        every { CMI.getInstance() } returns cmi
        every { cmi.isEnabled } returns false
        CmiAfkProviderBridge.isAfk(playerId).shouldBeFalse()
        verify(exactly = 0) { cmi.afkManager }

        every { cmi.isEnabled } returns true
        every { cmi.afkManager } returns null
        CmiAfkProviderBridge.isAfk(playerId).shouldBeFalse()
    }
})
