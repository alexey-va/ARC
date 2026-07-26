package ru.arc.xserver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.arc.core.TestTaskScheduler
import ru.arc.redis.InMemoryRedis
import ru.arc.util.Common

class XActionMessagerTest {
    @Test
    fun `register is idempotent and close unregisters the listener`() {
        val redis = InMemoryRedis()
        val messager = XActionMessager(redis, TestTaskScheduler())

        messager.register()
        messager.register()
        assertEquals(1, redis.listenerCount(XActionMessager.CHANNEL))

        messager.close()
        messager.close()
        assertEquals(0, redis.listenerCount(XActionMessager.CHANNEL))
    }

    @Test
    fun `publish uses the managed scheduler and is discarded after close`() {
        val redis = InMemoryRedis()
        val scheduler = TestTaskScheduler()
        val messager = XActionMessager(redis, scheduler)
        val action = XMessage(type = XMessage.Type.CHAT, serializedMessage = "hello")
        messager.register()

        messager.send(action)
        assertEquals(0, redis.getPublishedMessages().size)

        scheduler.executeImmediate()
        assertEquals(1, redis.getPublishedMessages().size)

        messager.send(action)
        messager.close()
        scheduler.executeImmediate()
        assertEquals(1, redis.getPublishedMessages().size)
    }

    @Test
    fun `title message survives the polymorphic Redis wire format`() {
        val titleData =
            XMessage.TitleData(
                subtitle = "<gray>Found",
                fadeInTicks = 5,
                stayTicks = 40,
                fadeOutTicks = 10,
            )
        val action: XAction =
            XMessage(
                type = XMessage.Type.TITLE,
                serializedMessage = "<gold>Treasure",
                serializationType = XMessage.SerializationType.MINI_MESSAGE,
                titleData = titleData,
            )

        val json = Common.gson.toJson(action, XAction::class.java)
        val restored = Common.gson.fromJson(json, XAction::class.java) as XMessage

        assertEquals(XMessage.Type.TITLE, restored.type)
        assertEquals(titleData, restored.titleData)
    }
}
