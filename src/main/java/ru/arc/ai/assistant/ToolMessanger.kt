package ru.arc.ai.assistant

import com.google.gson.Gson
import ru.arc.ARC
import ru.arc.ai.assistant.Tools.getTool
import ru.arc.network.ChannelListener
import ru.arc.util.Logging
import ru.arc.util.Logging.info

class ToolMessanger : ChannelListener {
    var gson: Gson = Gson()

    override fun consume(channel: String?, message: String?, originServer: String?) {
        val toolMessageRaw = gson.fromJson(message, ToolMessageRaw::class.java)
        val toolClass = getTool(toolMessageRaw.toolName)
        val toolDto: Tool = gson.fromJson(toolMessageRaw.toolDto, toolClass)

        val execute = toolDto.execute()
        val toolResponse = ToolResponse(
            uuid = toolMessageRaw.uuid,
            result = gson.toJson(execute),
            serverName = ARC.serverName,
        )
        info(
            "Tool {} executed for uuid {}: {}",
            toolMessageRaw.toolName,
            toolMessageRaw.uuid,
            toolResponse
        )

        ARC.redisManager.publish(CHANNEL_RESPONSE_TOOLS, gson.toJson(toolResponse))
    }

    companion object {
        const val CHANNEL_REQUEST_TOOLS: String = "arc.ai_tools_req"
        const val CHANNEL_RESPONSE_TOOLS: String = "arc.ai_tools_res"
    }
}
