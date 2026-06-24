package ru.arc.util

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.ThreadContext
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.impl.ContextDataFactory
import org.apache.logging.log4j.core.impl.Log4jLogEvent
import org.apache.logging.log4j.message.SimpleMessage
import ru.arc.config.EmptyConfig

class LoggingStructuredTest : FreeSpec({

    afterEach {
        ThreadContext.clearAll()
    }

    "withContext" - {
        "should set and clear MDC keys" {
            Logging.withContext("board", "Steve", "announce") {
                ThreadContext.get("module") shouldBe "board"
                ThreadContext.get("player") shouldBe "Steve"
                ThreadContext.get("action") shouldBe "announce"
            }
            ThreadContext.get("module").shouldBeNull()
            ThreadContext.get("player").shouldBeNull()
            ThreadContext.get("action").shouldBeNull()
        }
    }

    "buildLokiLayout" - {
        "should emit JSON with core fields and MDC contextMap" {
            val context = LogManager.getContext(false) as LoggerContext
            val layout = Logging.buildLokiLayout(EmptyConfig, context.configuration)
            val contextData = ContextDataFactory.createContextData()
            contextData.putValue("module", "xaction")
            contextData.putValue("action", "publish")
            val event =
                Log4jLogEvent.newBuilder()
                    .setLoggerName("ru.arc.test.Sample")
                    .setLevel(org.apache.logging.log4j.Level.INFO)
                    .setMessage(SimpleMessage("hello structured"))
                    .setContextData(contextData)
                    .build()
            val line = layout.toSerializable(event)
            line shouldContain "\"level\":\"INFO\""
            line shouldContain "\"logger\":\"ru.arc.test.Sample\""
            line shouldContain "\"message\":\"hello structured\""
            line shouldContain "\"contextMap\":{"
            line shouldContain "\"module\":\"xaction\""
            line shouldContain "\"action\":\"publish\""
        }
    }

    "resolveCallerLogger" - {
        "should skip Logging frames" {
            CallerProbe.loggerName() shouldBe "ru.arc.util.CallerProbe"
        }
    }
})

private object CallerProbe {
    fun loggerName(): String = Logging.resolveCallerLogger().name
}
