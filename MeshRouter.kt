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
 * Не зависит от конкретного транспорта:
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

    /**
     * Подготовка собственного сообщения к отправке.
     * Запоминаем ID, чтобы не принять собственную копию обратно.
     */
    @Synchronized
    fun prepareOutgoing(
        message: MeshMessage,
        localNodeId: String
    ): MeshMessage? {
        if (localNodeId.isBlank()) return null
        if (message.messageId.isBlank()) return null
        if (message.senderId != localNodeId) return null
        if (message.chatId.isBlank()) return null

        val route = if (message.visitedNodes.contains(localNodeId)) {
            message.visitedNodes
        } else {
            message.visitedNodes + localNodeId
        }

        remember(message.messageId)

        return message.copy(
            visitedNodes = route
        )
    }

    /**
     * Обработка сообщения, полученного от соседнего устройства.
     *
     * Если сообщение новое:
     * - определяем, предназначено ли оно этому устройству;
     * - готовим копию для дальнейшей пересылки;
     * - исключаем повторную обработку.
     */
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

        val deliverToLocal =
            isDirectMessage || isGroupMessage

        val forwardMessage =
            message.forwardedBy(localNodeId)

        return MeshRoutingResult(
            accepted = true,
            deliverToLocal = deliverToLocal,
            forwardMessage = forwardMessage,
            reason = if (forwardMessage != null) {
                "Сообщение принято и подготовлено к пересылке"
            } else {
                "Сообщение принято, дальнейшая пересылка невозможна"
            }
        )
    }

    /**
     * Проверка, встречалось ли сообщение ранее.
     */
    @Synchronized
    fun hasSeen(messageId: String): Boolean {
        return seenMessages.containsKey(messageId)
    }

    /**
     * Очистка локального списка обработанных сообщений.
     */
    @Synchronized
    fun clearHistory() {
        seenMessages.clear()
    }

    private fun remember(messageId: String) {
        seenMessages[messageId] = true
    }

    private fun rejected(reason: String): MeshRoutingResult {
        return MeshRoutingResult(
            accepted = false,
            deliverToLocal = false,
            forwardMessage = null,
            reason = reason
        )
    }
}
