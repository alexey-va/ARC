package ru.arc.metrics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis

class MeasurementResetControlTest : StringSpec({
    "stale confirmation cannot create another period and corrupt control is never overwritten" {
        val redis = InMemoryRedis()
        val control = MeasurementResetControl(redis)
        control.request(expectedGeneration = 0).join().generation shouldBe 1L
        runCatching { control.request(expectedGeneration = 0).join() }.isFailure shouldBe true
        control.read().join()?.generation shouldBe 1L
        redis.setHash(MeasurementResetControl.HASH_KEY, mapOf(MeasurementResetControl.FIELD to "{broken"))
        runCatching { control.request().join() }.isFailure shouldBe true
        redis.getHash(MeasurementResetControl.HASH_KEY)[MeasurementResetControl.FIELD] shouldBe "{broken"
    }

    "shares one monotonic boundary and survives a reopened reader" {
        val redis = InMemoryRedis()
        var now = 1_700_000_000_000L
        val writer = MeasurementResetControl(redis, clock = { now })
        val reader = MeasurementResetControl(redis, clock = { now })

        writer.request() .join().boundaryAt shouldBe now
        reader.read().join()?.generation shouldBe 1L
        now += 10_000
        writer.request().join().boundaryAt shouldBe now
        reader.read().join()?.generation shouldBe 2L
        reader.read().join()?.boundaryAt shouldBe now
    }
})
