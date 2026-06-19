package ru.arc.ops

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.security.MessageDigest

object OpsJson {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun toJson(value: Any?): String = gson.toJson(value)

    fun ok(data: Map<String, Any?> = emptyMap()): String =
        toJson(linkedMapOf("ok" to true) + data)

    fun error(status: Int, message: String, extra: Map<String, Any?> = emptyMap()): Pair<Int, String> {
        val body = linkedMapOf<String, Any?>("ok" to false, "error" to message, "status" to status)
        body.putAll(extra)
        return status to toJson(body)
    }
}

object OpsAuth {
    fun extractToken(headers: Map<String, String>): String? {
        val auth = headers["Authorization"] ?: headers["authorization"]
        if (auth != null) {
            val prefix = "Bearer "
            if (auth.startsWith(prefix, ignoreCase = true)) {
                return auth.substring(prefix.length).trim()
            }
        }
        return headers["X-ARC-Ops-Token"] ?: headers["x-arc-ops-token"]
    }

    fun isAuthorized(headers: Map<String, String>, expected: String): Boolean {
        if (expected.isBlank()) return false
        val provided = extractToken(headers) ?: return false
        return constantTimeEquals(provided, expected)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val da = MessageDigest.getInstance("SHA-256").digest(a.toByteArray())
        val db = MessageDigest.getInstance("SHA-256").digest(b.toByteArray())
        if (da.size != db.size) return false
        var result = 0
        for (i in da.indices) {
            result = result or (da[i].toInt() xor db[i].toInt())
        }
        return result == 0
    }
}
