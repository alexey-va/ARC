package ru.arc.ops

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpsHttpLifecycleTest : TestBase() {
    @Test
    fun `reload and shutdown do not wait for a blocked lifecycle worker`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = plugin.submitBackground {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val previous = System.getProperty("arc.test.unit")
        try {
            System.clearProperty("arc.test.unit")
            OpsHttpModule.reload()
            val starting = OpsHttpModule.readiness()
            assertFalse(starting.isDone)
            OpsHttpModule.shutdown()
            assertTrue(starting.isCompletedExceptionally)
            assertFalse(blocker.isDone)
        } finally {
            if (previous == null) System.clearProperty("arc.test.unit")
            else System.setProperty("arc.test.unit", previous)
            release.countDown()
            blocker.get(5, TimeUnit.SECONDS)
        }
    }
}
