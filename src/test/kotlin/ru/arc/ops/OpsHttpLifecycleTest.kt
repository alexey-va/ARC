package ru.arc.ops

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.config.ConfigManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpsHttpLifecycleTest : TestBase() {
    @Test
    fun `maintenance reload waits for accepted lifecycle work before publishing readiness`() {
        createConfigFile(plugin.dataFolder, "modules/ops-http.yml", "enabled: false\n")
        ConfigManager.clear()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reloadEntered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val previous = System.getProperty("arc.test.unit")
        mockkObject(OpsStartupLogTap)
        every { OpsStartupLogTap.uninstall() } answers {
            callOriginal()
            reloadEntered.countDown()
        }
        val blocker = plugin.submitBackground {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val observer = CompletableFuture.runAsync {
            try {
                assertTrue(reloadEntered.await(5, TimeUnit.SECONDS))
                assertFalse(OpsHttpModule.readiness().isDone)
                assertFalse(returned.await(50, TimeUnit.MILLISECONDS))
                assertFalse(blocker.isDone)
            } finally {
                release.countDown()
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            System.clearProperty("arc.test.unit")
            OpsHttpModule.reload()
            returned.countDown()
            observer.get(5, TimeUnit.SECONDS)
            assertTrue(blocker.isDone)
            val ready = OpsHttpModule.readiness()
            assertTrue(ready.isDone)
            ready.get(5, TimeUnit.SECONDS)
        } finally {
            if (previous == null) System.clearProperty("arc.test.unit")
            else System.setProperty("arc.test.unit", previous)
            release.countDown()
            try {
                blocker.get(5, TimeUnit.SECONDS)
                observer.get(5, TimeUnit.SECONDS)
            } finally {
                unmockkObject(OpsStartupLogTap)
            }
        }
    }

    @Test
    fun `shutdown does not wait for a blocked lifecycle worker`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = plugin.submitBackground {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            OpsHttpModule.shutdown()
            assertFalse(blocker.isDone)
        } finally {
            release.countDown()
            blocker.get(5, TimeUnit.SECONDS)
        }
    }
}
