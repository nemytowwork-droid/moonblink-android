package com.moonblink.app

/**
 * Результат обработки входящего Mesh-сообщения.
 */
data class MeshRoutingResult(
    val accepted: Boolean,
    val deliverToLocal: Boolean,
    val forwardMessage: MeshMessage?,
    val reason: String
)

/**
 * Локальный маршрутизатор Moonblink.
 *
 * Не зависит от транспорта:
 * Nearby Connections, Bluetooth или Wi-Fi Direct.
 */
class MeshRouter(
    private val maxRememberedMessages: Int = 2000
) {
    private val seenMessages =
        object : LinkedHashMap<String, Boolean>(
            maxRememberedMessages + 1,
            0.75f,
            true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Boolean>?
            ): Boolean {
                return size > maxRememberedMessages
            }
        }

    @Synchronized
    fun prepareOutgoing(
        message: MeshMessage,
        localNodeId: String
    ): MeshMessage? {
        if (localNodeId.isBlank()) return null
        if (message.messageId.isBlank()) return null
        if (message.senderId != localNodeId) return null
        if (message.chatId.isBlank()) return null
        if (message.ttl <= 0) return null

        val route = if (message.visitedNodes.contains(localNodeId)) {
            message.visitedNodes
        } else {
            message.visitedNodes + localNodeId
        }

        remember(message.messageId)

        return message.copy(visitedNodes = route)
    }

    @Synchronized
    fun processIncoming(
        message: MeshMessage,
        localNodeId: String
    ): MeshRoutingResult {
        if (localNodeId.isBlank()) {
            return rejected("Неизвестный локальный ID")
        }

        if (message.messageId.isBlank()) {
            return rejected("Отсутствует ID сообщения")
        }

        if (message.senderId.isBlank() || message.chatId.isBlank()) {
            return rejected("Некорректные данные сообщения")
        }

        if (message.ttl <= 0) {
            return rejected("Истёк срок пересылки сообщения")
        }

        if (message.senderId == localNodeId) {
            return rejected("Собственное сообщение")
        }

        if (seenMessages.containsKey(message.messageId)) {
            return rejected("Сообщение уже обработано")
        }

        if (message.visitedNodes.contains(localNodeId)) {
            remember(message.messageId)
            return rejected("Обнаружен повторный маршрут")
        }

        remember(message.messageId)

        val isDirectMessage =
            message.recipientId == localNodeId

        val isGroupMessage =
            message.recipientId.isBlank() &&
            !message.chatId.startsWith("dm:")

        val forwardMessage = message.forwardedBy(localNodeId)

        return MeshRoutingResult(
            accepted = true,
            deliverToLocal = isDirectMessage || isGroupMessage,
            forwardMessage = forwardMessage,
            reason = "Сообщение принято"
        )
    }

    @Synchronized
    fun hasSeen(messageId: String): Boolean {
        return seenMessages.containsKey(messageId)
    }

    @Synchronized
    fun clearHistory() {
        seenMessages.clear()
    }

    private fun remember(messageId: String) {
        seenMessages[messageId] = true
    }

    private fun rejected(reason: String) = MeshRoutingResult(
        accepted = false,
        deliverToLocal = false,
        forwardMessage = null,
        reason = reason
    )
}
