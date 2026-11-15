package com.moonblink.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Единый формат сообщения для Mesh-сети Moonblink.
 *
 * Пока используется как отдельная модель.
 * Интеграцию с передачей сообщений добавим следующим этапом.
 */
data class MeshMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val recipientId: String,
    val chatId: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val ttl: Int = DEFAULT_TTL,
    val visitedNodes: List<String> = emptyList(),
    val type: String = TYPE_TEXT
) {

    companion object {
        const val DEFAULT_TTL = 8

        const val TYPE_TEXT = "text"
        const val TYPE_GROUP = "group_create"

        fun fromJson(json: JSONObject): MeshMessage? {
            return try {
                val senderId = json.optString("senderId")
                val chatId = json.optString("chatId")

                if (senderId.isBlank() || chatId.isBlank()) {
                    return null
                }

                val routeArray =
                    json.optJSONArray("visitedNodes") ?: JSONArray()

                val route = mutableListOf<String>()

                for (i in 0 until routeArray.length()) {
                    val node = routeArray.optString(i)
                    if (node.isNotBlank()) {
                        route.add(node)
                    }
                }

                MeshMessage(
                    messageId = json.optString(
                        "messageId",
                        UUID.randomUUID().toString()
                    ),
                    senderId = senderId,
                    senderName = json.optString(
                        "senderName",
                        "Пользователь"
                    ),
                    recipientId = json.optString("recipientId"),
                    chatId = chatId,
                    text = json.optString("text"),
                    createdAt = json.optLong(
                        "createdAt",
                        System.currentTimeMillis()
                    ),
                    ttl = json.optInt("ttl", DEFAULT_TTL),
                    visitedNodes = route,
                    type = json.optString("type", TYPE_TEXT)
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun toJson(): JSONObject {
        val route = JSONArray()

        visitedNodes.forEach { node ->
            route.put(node)
        }

        return JSONObject().apply {
            put("type", type)
            put("messageId", messageId)
            put("senderId", senderId)
            put("senderName", senderName)
            put("recipientId", recipientId)
            put("chatId", chatId)
            put("text", text)
            put("createdAt", createdAt)
            put("ttl", ttl)
            put("visitedNodes", route)
        }
    }

    /**
     * Подготовка сообщения к пересылке через текущий узел.
     * Возвращает null, если пересылка запрещена.
     */
    fun forwardedBy(nodeId: String): MeshMessage? {
        if (nodeId.isBlank()) return null
        if (ttl <= 0) return null
        if (visitedNodes.contains(nodeId)) return null

        return copy(
            ttl = ttl - 1,
            visitedNodes = visitedNodes + nodeId
        )
    }
}
