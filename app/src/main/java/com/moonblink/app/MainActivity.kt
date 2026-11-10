package com.moonblink.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

private val Bg = Color(0xFF080B16)
private val Panel = Color(0xFF11182A)
private val Panel2 = Color(0xFF19233A)
private val Neon = Color(0xFF55FFE0)
private val Purple = Color(0xFFB56CFF)
private val Blue = Color(0xFF559BFF)
private val Muted = Color(0xFF8795B0)
private val Danger = Color(0xFFFF5478)

private const val SERVICE_ID = "com.moonblink.app"
private const val MAX_MESSAGES = 500

private enum class AppTab {
    CHATS, CONTACTS, CONNECTION, PROFILE
}

data class NearbyPeer(
    val endpointId: String,
    val stableId: String,
    val name: String
)

data class ConnectedPeer(
    val stableId: String,
    val name: String
)

data class PendingRequest(
    val endpointId: String,
    val stableId: String,
    val name: String,
    val digits: String
)

data class MoonContact(
    val stableId: String,
    val name: String
)

data class ChatRoom(
    val id: String,
    val title: String,
    val isGroup: Boolean,
    val peerId: String,
    val members: List<String>,
    val lastMessage: String = "",
    val lastAt: Long = 0L
)

data class StoredMessage(
    val id: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val mine: Boolean,
    val time: Long
)

class MainActivity : ComponentActivity() {

    private lateinit var connections: ConnectionsClient
    private lateinit var prefs: android.content.SharedPreferences

    private var localId = ""
    private var localName = "Пользователь"

    private var online by mutableStateOf(false)
    private var startingNearby by mutableStateOf(false)
    private var status by mutableStateOf("Не в сети")

    private var advertisingReady = false
    private var discoveryReady = false
    private var startAttempt = 0

    private val peers = mutableStateListOf<NearbyPeer>()
    private val connected = mutableStateMapOf<String, ConnectedPeer>()
    private val endpointNames = mutableMapOf<String, String>()

    private val contacts = mutableStateListOf<MoonContact>()
    private val rooms = mutableStateListOf<ChatRoom>()
    private val messages = mutableStateListOf<StoredMessage>()

    private var pendingRequest by mutableStateOf<PendingRequest?>(null)

    private lateinit var permissionLauncher:
        androidx.activity.result.ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("moonblink_local", Context.MODE_PRIVATE)

        localId = prefs.getString("id", null) ?: run {
            val id = "MB-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .take(8)
                .uppercase()

            prefs.edit()
                .putString("id", id)
                .putString("name", "Пользователь")
                .apply()

            id
        }

        localName = prefs.getString("name", "Пользователь")
            ?: "Пользователь"

        connections = Nearby.getConnectionsClient(this)

        loadData()

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.values.all { it }) {
                startNearby()
            } else {
                status = "Необходимы разрешения для поиска устройств"
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Neon,
                    secondary = Purple,
                    background = Bg,
                    surface = Panel,
                    onSurface = Color.White
                )
            ) {
                MoonblinkApp()
            }
        }

        requestOnline()
    }

    private fun requiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )

            Build.VERSION.SDK_INT >= 31 -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )

            Build.VERSION.SDK_INT >= 29 -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )

            else -> arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestOnline() {
        if (hasPermissions()) {
            startNearby()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    private fun parseIdentity(raw: String): Pair<String, String> {
        val parts = raw.split("|", limit = 2)

        if (parts.size == 2 && parts[0].isNotBlank()) {
            return Pair(
                parts[0],
                parts[1].ifBlank { "Пользователь Moonblink" }
            )
        }

        return Pair(
            raw.ifBlank { "UNKNOWN" },
            "Устройство Moonblink"
        )
    }

    private fun dmChatId(first: String, second: String): String {
        val sorted = listOf(first, second).sorted()
        return "dm:${sorted[0]}:${sorted[1]}"
    }

    private fun updateNearbyState() {
        if (advertisingReady && discoveryReady) {
            online = true
            startingNearby = false
            status = "Поиск устройств поблизости"
        } else {
            online = false
            status = "Запускаем поиск устройств..."
        }
    }

    private fun failNearby(message: String, attempt: Int) {
        if (attempt != startAttempt) return

        startAttempt++

        connections.stopAdvertising()
        connections.stopDiscovery()

        advertisingReady = false
        discoveryReady = false
        startingNearby = false
        online = false

        status = message
    }

    private fun startNearby() {
        if (online || startingNearby) return

        if (!hasPermissions()) {
            requestOnline()
            return
        }

        startingNearby = true
        online = false

        advertisingReady = false
        discoveryReady = false

        val attempt = ++startAttempt

        status = "Запускаем поиск..."

        val strategy = Strategy.P2P_CLUSTER

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        connections.startAdvertising(
            "$localId|$localName",
            SERVICE_ID,
            connectionCallback,
            advertisingOptions
        ).addOnSuccessListener {
            if (attempt != startAttempt) return@addOnSuccessListener

            advertisingReady = true
            updateNearbyState()

        }.addOnFailureListener { error ->
            failNearby(
                "Ошибка видимости: ${error.localizedMessage}",
                attempt
            )
        }

        connections.startDiscovery(
            SERVICE_ID,
            discoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            if (attempt != startAttempt) return@addOnSuccessListener

            discoveryReady = true
            updateNearbyState()

        }.addOnFailureListener { error ->
            failNearby(
                "Ошибка поиска: ${error.localizedMessage}",
                attempt
            )
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {

        override fun onEndpointFound(
            endpointId: String,
            info: DiscoveredEndpointInfo
        ) {
            val identity = parseIdentity(info.endpointName)

            runOnUiThread {
                if (connected.containsKey(endpointId)) return@runOnUiThread

                if (identity.first == localId) return@runOnUiThread

                if (peers.none { it.endpointId == endpointId }) {
                    peers.add(
                        NearbyPeer(
                            endpointId,
                            identity.first,
                            identity.second
                        )
                    )
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            runOnUiThread {
                peers.removeAll { it.endpointId == endpointId }
            }
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(
            endpointId: String,
            info: ConnectionInfo
        ) {
            val identity = parseIdentity(info.endpointName)

            endpointNames[endpointId] = identity.second

            runOnUiThread {
                pendingRequest = PendingRequest(
                    endpointId = endpointId,
                    stableId = identity.first,
                    name = identity.second,
                    digits = info.authenticationDigits
                )
            }
        }

        override fun onConnectionResult(
            endpointId: String,
            result: ConnectionResolution
        ) {
            runOnUiThread {
                if (result.status.isSuccess) {
                    val identity = endpointNames[endpointId]
                        ?: "Устройство Moonblink"

                    val discovered = peers.firstOrNull {
                        it.endpointId == endpointId
                    }

                    val stableId = discovered?.stableId
                        ?: pendingRequest?.takeIf {
                            it.endpointId == endpointId
                        }?.stableId
                        ?: "UNKNOWN-$endpointId"

                    connected[endpointId] = ConnectedPeer(
                        stableId,
                        identity
                    )

                    saveContact(stableId, identity)

                    peers.removeAll {
                        it.endpointId == endpointId
                    }

                    status = "Подключено: $identity"
                } else {
                    status = "Не удалось установить соединение"
                }

                if (pendingRequest?.endpointId == endpointId) {
                    pendingRequest = null
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            runOnUiThread {
                connected.remove(endpointId)
                endpointNames.remove(endpointId)
                status = "Соединение потеряно"
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(
            endpointId: String,
            payload: Payload
        ) {
            if (payload.type != Payload.Type.BYTES) return

            val bytes = payload.asBytes() ?: return

            try {
                val json = JSONObject(
                    String(bytes, StandardCharsets.UTF_8)
                )

                when (json.optString("type")) {
                    "text" -> handleIncomingMessage(json)
                    "group_create" -> handleIncomingGroup(json)
                }

            } catch (_: Exception) {
                runOnUiThread {
                    status = "Получены неизвестные данные"
                }
            }
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate
        ) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                runOnUiThread {
                    status = "Передача завершена"
                }
            }
        }
    }

    private fun handleIncomingGroup(json: JSONObject) {
        val groupId = json.optString("chatId")
        val title = json.optString("title", "Новая группа")
        val membersArray = json.optJSONArray("members") ?: JSONArray()

        val members = mutableListOf<String>()

        for (i in 0 until membersArray.length()) {
            members.add(membersArray.optString(i))
        }

        if (groupId.isBlank() || !members.contains(localId)) return

        runOnUiThread {
            val existing = rooms.indexOfFirst { it.id == groupId }

            val room = ChatRoom(
                id = groupId,
                title = title,
                isGroup = true,
                peerId = "",
                members = members
            )

            if (existing >= 0) {
                rooms[existing] = room
            } else {
                rooms.add(room)
            }

            saveData()
            status = "Добавлена группа: $title"
        }
    }

    private fun handleIncomingMessage(json: JSONObject) {
        val senderId = json.optString("senderId")
        val senderName = json.optString("senderName", "Собеседник")
        val recipientId = json.optString("recipientId")
        val chatId = json.optString("chatId")
        val text = json.optString("text")
        val messageId = json.optString("messageId")

        if (senderId.isBlank() || text.isBlank() || chatId.isBlank()) return
        if (senderId == localId) return

        runOnUiThread {
            if (messageId.isNotBlank() && messages.any {
                    it.id == messageId
                }) {
                return@runOnUiThread
            }

            val room: ChatRoom

            if (chatId.startsWith("dm:")) {
                if (recipientId != localId) return@runOnUiThread

                val expectedId = dmChatId(localId, senderId)
                if (chatId != expectedId) return@runOnUiThread

                room = rooms.firstOrNull { it.id == chatId }
                    ?: ChatRoom(
                        id = chatId,
                        title = senderName,
                        isGroup = false,
                        peerId = senderId,
                        members = listOf(localId, senderId)
                    )
            } else {
                val existing = rooms.firstOrNull { it.id == chatId }
                    ?: return@runOnUiThread

                if (!existing.isGroup) return@runOnUiThread
                if (!existing.members.contains(senderId)) return@runOnUiThread

                room = existing
            }

            if (rooms.none { it.id == room.id }) {
                rooms.add(room)
            }

            val message = StoredMessage(
                id = messageId.ifBlank { UUID.randomUUID().toString() },
                chatId = chatId,
                senderId = senderId,
                senderName = senderName,
                text = text,
                mine = false,
                time = System.currentTimeMillis()
            )

            messages.add(message)
            updateRoomPreview(chatId, text)
            saveData()

            status = "Новое сообщение от $senderName"
        }
    }

    private fun connectTo(peer: NearbyPeer) {
        if (!hasPermissions()) {
            requestOnline()
            return
        }

        status = "Запрашиваем подключение..."

        connections.requestConnection(
            "$localId|$localName",
            peer.endpointId,
            connectionCallback
        ).addOnFailureListener { error ->
            runOnUiThread {
                status = "Ошибка: ${error.localizedMessage}"
            }
        }
    }

    private fun acceptPending() {
        val request = pendingRequest ?: return

        connections.acceptConnection(
            request.endpointId,
            payloadCallback
        ).addOnFailureListener { error ->
            runOnUiThread {
                status = "Ошибка принятия: ${error.localizedMessage}"
            }
        }.addOnSuccessListener {
            runOnUiThread {
                status = "Запрос принят, соединяемся..."
                if (pendingRequest?.endpointId == request.endpointId) {
                    pendingRequest = null
                }
            }
        }
    }

    private fun rejectPending() {
        val request = pendingRequest ?: return

        connections.rejectConnection(request.endpointId)

        pendingRequest = null
        status = "Запрос отклонён"
    }

    private fun stopNearby() {
        startAttempt++

        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.stopAllEndpoints()

        advertisingReady = false
        discoveryReady = false

        startingNearby = false
        online = false

        peers.clear()
        connected.clear()
        endpointNames.clear()
        pendingRequest = null

        status = "Не в сети"
    }

    private fun saveContact(id: String, name: String) {
        if (id.isBlank() || id.startsWith("UNKNOWN")) return

        val index = contacts.indexOfFirst {
            it.stableId == id
        }

        if (index >= 0) {
            contacts[index] = MoonContact(id, name)
        } else {
            contacts.add(MoonContact(id, name))
        }

        saveData()
    }

    private fun updateRoomPreview(chatId: String, text: String) {
        val index = rooms.indexOfFirst { it.id == chatId }

        if (index >= 0) {
            val old = rooms[index]

            rooms[index] = old.copy(
                lastMessage = text,
                lastAt = System.currentTimeMillis()
            )
        }
    }

    private fun sendToEndpoint(
        endpointId: String,
        json: JSONObject
    ) {
        val payload = Payload.fromBytes(
            json.toString().toByteArray(StandardCharsets.UTF_8)
        )

        connections.sendPayload(endpointId, payload)
            .addOnFailureListener { error ->
                runOnUiThread {
                    status = "Ошибка отправки: ${error.localizedMessage}"
                }
            }
    }

    private fun sendMessage(chatId: String, text: String): Boolean {
        val clean = text.trim()

        if (clean.isEmpty()) return false

        val room = rooms.firstOrNull { it.id == chatId } ?: return false

        val targets = if (room.isGroup) {
            connected.filterValues {
                room.members.contains(it.stableId) &&
                    it.stableId != localId
            }.keys.toList()
        } else {
            connected.filterValues {
                it.stableId == room.peerId
            }.keys.toList()
        }

        if (targets.isEmpty()) {
            status = "Собеседник сейчас не подключён"
            return false
        }

        val messageId = UUID.randomUUID().toString()

        val json = JSONObject()
            .put("type", "text")
            .put("senderId", localId)
            .put("senderName", localName)
            .put("recipientId", if (room.isGroup) "" else room.peerId)
            .put("chatId", room.id)
            .put("text", clean)
            .put("messageId", messageId)

        targets.forEach { endpointId ->
            sendToEndpoint(endpointId, json)
        }

        messages.add(
            StoredMessage(
                id = messageId,
                chatId = room.id,
                senderId = localId,
                senderName = localName,
                text = clean,
                mine = true,
                time = System.currentTimeMillis()
            )
        )

        updateRoomPreview(room.id, clean)
        saveData()

        status = "Сообщение отправляется..."
        return true
    }

    private fun createGroup(title: String, memberIds: List<String>): Boolean {
        val cleanTitle = title.trim()

        if (cleanTitle.isEmpty() || memberIds.isEmpty()) return false

        val groupId = "group:${UUID.randomUUID()}"

        val members = (memberIds + localId).distinct()

        val room = ChatRoom(
            id = groupId,
            title = cleanTitle,
            isGroup = true,
            peerId = "",
            members = members
        )

        rooms.add(room)
        saveData()

        val array = JSONArray()
        members.forEach { array.put(it) }

        val json = JSONObject()
            .put("type", "group_create")
            .put("chatId", groupId)
            .put("title", cleanTitle)
            .put("members", array)
            .put("senderId", localId)

        val targets = connected.filterValues {
            members.contains(it.stableId) && it.stableId != localId
        }.keys.toList()

        targets.forEach { endpointId ->
            sendToEndpoint(endpointId, json)
        }

        status = "Группа создана"
        return true
    }

    private fun loadData() {
        contacts.clear()
        rooms.clear()
        messages.clear()

        try {
            val contactArray = JSONArray(
                prefs.getString("moon_contacts", "[]")
            )

            for (i in 0 until contactArray.length()) {
                val item = contactArray.optJSONObject(i) ?: continue

                contacts.add(
                    MoonContact(
                        item.optString("id"),
                        item.optString("name")
                    )
                )
            }

            val roomArray = JSONArray(
                prefs.getString("moon_rooms", "[]")
            )

            for (i in 0 until roomArray.length()) {
                val item = roomArray.optJSONObject(i) ?: continue
                val memberArray = item.optJSONArray("members") ?: JSONArray()

                val members = mutableListOf<String>()

                for (j in 0 until memberArray.length()) {
                    members.add(memberArray.optString(j))
                }

                rooms.add(
                    ChatRoom(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        isGroup = item.optBoolean("group"),
                        peerId = item.optString("peerId"),
                        members = members,
                        lastMessage = item.optString("lastMessage"),
                        lastAt = item.optLong("lastAt")
                    )
                )
            }

            val messageArray = JSONArray(
                prefs.getString("moon_messages", "[]")
            )

            val start = maxOf(0, messageArray.length() - MAX_MESSAGES)

            for (i in start until messageArray.length()) {
                val item = messageArray.optJSONObject(i) ?: continue

                messages.add(
                    StoredMessage(
                        id = item.optString("id"),
                        chatId = item.optString("chatId"),
                        senderId = item.optString("senderId"),
                        senderName = item.optString("senderName"),
                        text = item.optString("text"),
                        mine = item.optBoolean("mine"),
                        time = item.optLong("time")
                    )
                )
            }

        } catch (_: Exception) {
            // Повреждённая история не должна мешать запуску приложения.
        }
    }

    private fun saveData() {
        val contactArray = JSONArray()

        contacts.forEach {
            contactArray.put(
                JSONObject()
                    .put("id", it.stableId)
                    .put("name", it.name)
            )
        }

        val roomArray = JSONArray()

        rooms.forEach { room ->
            val members = JSONArray()
            room.members.forEach { members.put(it) }

            roomArray.put(
                JSONObject()
                    .put("id", room.id)
                    .put("title", room.title)
                    .put("group", room.isGroup)
                    .put("peerId", room.peerId)
                    .put("members", members)
                    .put("lastMessage", room.lastMessage)
                    .put("lastAt", room.lastAt)
            )
        }

        val messageArray = JSONArray()

        messages.takeLast(MAX_MESSAGES).forEach {
            messageArray.put(
                JSONObject()
                    .put("id", it.id)
                    .put("chatId", it.chatId)
                    .put("senderId", it.senderId)
                    .put("senderName", it.senderName)
                    .put("text", it.text)
                    .put("mine", it.mine)
                    .put("time", it.time)
            )
        }

        prefs.edit()
            .putString("moon_contacts", contactArray.toString())
            .putString("moon_rooms", roomArray.toString())
            .putString("moon_messages", messageArray.toString())
            .apply()
    }

    @Composable
    private fun MoonblinkApp() {
        var tab by remember { mutableStateOf(AppTab.CHATS) }
        var selectedChatId by remember { mutableStateOf<String?>(null) }
        var showGroupDialog by remember { mutableStateOf(false) }

        BackHandler(selectedChatId != null) {
            selectedChatId = null
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
        ) {
            if (selectedChatId == null) {
                AppHeader()

                Box(Modifier.weight(1f)) {
                    when (tab) {
                        AppTab.CHATS -> ChatList(
                            onOpen = { selectedChatId = it },
                            onCreateGroup = { showGroupDialog = true }
                        )

                        AppTab.CONTACTS -> ContactsPage(
                            onOpen = { contact ->
                                val id = dmChatId(localId, contact.stableId)

                                if (rooms.none { it.id == id }) {
                                    rooms.add(
                                        ChatRoom(
                                            id = id,
                                            title = contact.name,
                                            isGroup = false,
                                            peerId = contact.stableId,
                                            members = listOf(localId, contact.stableId)
                                        )
                                    )
                                    saveData()
                                }

                                selectedChatId = id
                            }
                        )

                        AppTab.CONNECTION -> ConnectionPage()

                        AppTab.PROFILE -> ProfilePage()
                    }
                }

                BottomNavigation(
                    selected = tab,
                    onSelect = { tab = it }
                )

            } else {
                val room = rooms.firstOrNull {
                    it.id == selectedChatId
                }

                if (room != null) {
                    ChatPage(
                        room = room,
                        onBack = { selectedChatId = null }
                    )
                } else {
                    selectedChatId = null
                }
            }
        }

        val request = pendingRequest

        if (request != null) {
            AlertDialog(
                onDismissRequest = { rejectPending() },
                containerColor = Panel,
                title = {
                    Text("Запрос на подключение", color = Neon)
                },
                text = {
                    Column {
                        Text("Устройство: ${request.name}")
                        Text("ID: ${request.stableId}", color = Muted)

                        Spacer(Modifier.height(12.dp))

                        Text("Код подтверждения:")
                        Text(
                            request.digits,
                            color = Neon,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Сравни код на обоих устройствах.",
                            color = Muted
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { acceptPending() }) {
                        Text("Подтвердить", color = Neon)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { rejectPending() }) {
                        Text("Отклонить", color = Danger)
                    }
                }
            )
        }

        if (showGroupDialog) {
            CreateGroupDialog(
                available = connected.values.distinctBy { it.stableId },
                onDismiss = { showGroupDialog = false },
                onCreate = { title, ids ->
                    if (createGroup(title, ids)) {
                        showGroupDialog = false
                        tab = AppTab.CHATS
                    }
                }
            )
        }
    }

    @Composable
    private fun AppHeader() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(horizontal = 18.dp, vertical = 15.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "MOON",
                    color = Neon,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp
                )

                Text(
                    "BLINK",
                    color = Purple,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp
                )

                Spacer(Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .background(
                            if (online) Color(0xFF123B38) else Panel2,
                            CircleShape
                        )
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (online) "● ONLINE" else "● OFFLINE",
                        color = if (online) Neon else Muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                status,
                color = Muted,
                fontSize = 11.sp
            )
        }
    }

    @Composable
    private fun BottomNavigation(
        selected: AppTab,
        onSelect: (AppTab) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            NavItem("Чаты", "▣", AppTab.CHATS, selected, onSelect)
            NavItem("Контакты", "♙", AppTab.CONTACTS, selected, onSelect)
            NavItem("Связь", "⌁", AppTab.CONNECTION, selected, onSelect)
            NavItem("Профиль", "◉", AppTab.PROFILE, selected, onSelect)
        }
    }

    @Composable
    private fun NavItem(
        title: String,
        symbol: String,
        tab: AppTab,
        selected: AppTab,
        onSelect: (AppTab) -> Unit
    ) {
        val active = tab == selected

        Column(
            modifier = Modifier
                .clickable { onSelect(tab) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                symbol,
                color = if (active) Neon else Muted,
                fontSize = 23.sp
            )

            Text(
                title,
                color = if (active) Neon else Muted,
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
            )
        }
    }

    @Composable
    private fun PageTitle(
        title: String,
        subtitle: String = ""
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        }
    }

    @Composable
    private fun ChatList(
        onOpen: (String) -> Unit,
        onCreateGroup: () -> Unit
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    PageTitle(
                        "Твои чаты",
                        "Личные переписки и группы"
                    )
                }

                Button(
                    onClick = onCreateGroup,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Purple
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("+ Группа", color = Color.White)
                }
            }

            if (rooms.isEmpty()) {
                EmptyState(
                    "Пока нет чатов",
                    "Подключи устройство или создай группу"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = 14.dp,
                        vertical = 6.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rooms.sortedByDescending { it.lastAt }) { room ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(room.id) },
                            colors = CardDefaults.cardColors(
                                containerColor = Panel
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(
                                    title = room.title,
                                    group = room.isGroup
                                )

                                Spacer(Modifier.width(12.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        room.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        room.lastMessage.ifBlank {
                                            if (room.isGroup) {
                                                "Групповой чат"
                                            } else {
                                                "Личная переписка"
                                            }
                                        },
                                        color = Muted,
                                        fontSize = 12.sp,
                                        maxLines = 1
                                    )
                                }

                                Text(
                                    if (room.isGroup) "ГРУППА" else "ЛИЧНЫЙ",
                                    color = if (room.isGroup) Purple else Neon,
                                    fontSize = 8.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ContactsPage(
        onOpen: (MoonContact) -> Unit
    ) {
        Column(Modifier.fillMaxSize()) {
            PageTitle(
                "Контакты",
                "Сохранённые устройства Moonblink"
            )

            if (contacts.isEmpty()) {
                EmptyState(
                    "Контактов пока нет",
                    "Найди устройство на странице подключения"
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(contacts) { contact ->
                        val isOnline = connected.values.any {
                            it.stableId == contact.stableId
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Panel
                            ),
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(contact.name, false)

                                Spacer(Modifier.width(10.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        contact.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        contact.stableId,
                                        color = Muted,
                                        fontSize = 10.sp
                                    )

                                    Text(
                                        if (isOnline) "● В сети" else "● Не в сети",
                                        color = if (isOnline) Neon else Muted,
                                        fontSize = 10.sp
                                    )
                                }

                                Button(
                                    onClick = { onOpen(contact) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Neon
                                    )
                                ) {
                                    Text("Чат", color = Bg)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ConnectionPage() {
        Column(Modifier.fillMaxSize()) {
            PageTitle(
                "Подключение",
                "Поиск устройств поблизости"
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(15.dp)) {
                    Text(
                        if (online) "ПОИСК АКТИВЕН" else "ПОИСК ОТКЛЮЧЁН",
                        color = if (online) Neon else Muted,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(5.dp))

                    Text(status, color = Muted, fontSize = 11.sp)

                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (online || startingNearby) {
                                stopNearby()
                            } else {
                                requestOnline()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (online || startingNearby) {
                                Danger
                            } else {
                                Neon
                            },
                            contentColor = Bg
                        )
                    ) {
                        Text(
                            when {
                                online -> "Остановить поиск"
                                startingNearby -> "Остановка..."
                                else -> "Начать поиск устройств"
                            }
                        )
                    }
                }
            }

            PageTitle(
                "Найденные устройства",
                "Выбери устройство для отправки запроса"
            )

            if (peers.isEmpty()) {
                EmptyState(
                    "Никого не найдено",
                    "Убедись, что на другом телефоне включён поиск"
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(peers) { peer ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Panel
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(peer.name, false)

                                Spacer(Modifier.width(10.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(peer.name, color = Color.White)
                                    Text(
                                        peer.stableId,
                                        color = Muted,
                                        fontSize = 10.sp
                                    )
                                }

                                Button(
                                    onClick = { connectTo(peer) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Neon
                                    )
                                ) {
                                    Text("Связаться", color = Bg)
                                }
                            }
                        }
                    }
                }
            }

            PageTitle(
                "Активные соединения",
                "${connected.size} устройств подключено"
            )

            connected.values.distinctBy { it.stableId }.forEach { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("●", color = Neon)
                    Spacer(Modifier.width(8.dp))
                    Text(peer.name, color = Color.White)
                }
            }
        }
    }

    @Composable
    private fun ProfilePage() {
        var editedName by remember { mutableStateOf(localName) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            PageTitle(
                "Профиль",
                "Твоя личность в сети Moonblink"
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(82.dp)
                            .background(Panel2, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "MB",
                            color = Neon,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        localId,
                        color = Neon,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Твой уникальный ID Moonblink",
                        color = Muted,
                        fontSize = 10.sp
                    )

                    Spacer(Modifier.height(20.dp))

                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text("Твоё имя") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = {
                            localName = editedName.trim()
                                .ifBlank { "Пользователь" }

                            prefs.edit()
                                .putString("name", localName)
                                .apply()

                            status = "Имя сохранено. Новое имя будет передаваться при следующем запуске поиска."
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Neon
                        )
                    ) {
                        Text("Сохранить имя", color = Bg)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "MOONBLINK",
                color = Purple,
                fontWeight = FontWeight.Bold
            )

            Text(
                "Offline mesh messenger",
                color = Muted,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "Личная история хранится на устройстве. Сквозное шифрование пока не подключено.",
                color = Muted,
                fontSize = 11.sp
            )
        }
    }

    @Composable
    private fun ChatPage(
        room: ChatRoom,
        onBack: () -> Unit
    ) {
        var draft by remember { mutableStateOf("") }

        val chatMessages = messages.filter {
            it.chatId == room.id
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("‹ Назад", color = Neon)
                }

                Avatar(room.title, room.isGroup)

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        room.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        if (room.isGroup) {
                            "${room.members.size} участников"
                        } else {
                            if (connected.values.any {
                                    it.stableId == room.peerId
                                }) "В сети" else "Не в сети"
                        },
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chatMessages) { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.mine) {
                            Arrangement.End
                        } else {
                            Arrangement.Start
                        }
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .background(
                                    if (message.mine) {
                                        Color(0xFF123F3B)
                                    } else {
                                        Panel
                                    },
                                    RoundedCornerShape(15.dp)
                                )
                                .padding(11.dp)
                        ) {
                            if (!message.mine) {
                                Text(
                                    message.senderName,
                                    color = Purple,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Text(
                                message.text,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("Сообщение...", color = Muted)
                    },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    shape = RoundedCornerShape(18.dp)
                )

                Spacer(Modifier.width(7.dp))

                Button(
                    onClick = {
                        if (sendMessage(room.id, draft)) {
                            draft = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Neon
                    ),
                    contentPadding = PaddingValues(
                        horizontal = 15.dp,
                        vertical = 15.dp
                    )
                ) {
                    Text("➤", color = Bg, fontSize = 18.sp)
                }
            }
        }
    }

    @Composable
    private fun CreateGroupDialog(
        available: List<ConnectedPeer>,
        onDismiss: () -> Unit,
        onCreate: (String, List<String>) -> Unit
    ) {
        var title by remember { mutableStateOf("") }
        val selected = remember { mutableStateListOf<String>() }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Panel,
            title = {
                Text("Новая группа", color = Neon)
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название группы") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Выбери участников",
                        color = Muted
                    )

                    if (available.isEmpty()) {
                        Text(
                            "Сначала подключи другие устройства",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }

                    available.forEach { peer ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected.contains(peer.stableId),
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (!selected.contains(peer.stableId)) {
                                            selected.add(peer.stableId)
                                        }
                                    } else {
                                        selected.remove(peer.stableId)
                                    }
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Neon
                                )
                            )

                            Text(peer.name, color = Color.White)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreate(title, selected.toList())
                    },
                    enabled = title.isNotBlank() && selected.isNotEmpty()
                ) {
                    Text("Создать", color = Neon)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Отмена", color = Muted)
                }
            }
        )
    }

    @Composable
    private fun Avatar(
        title: String,
        group: Boolean
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(
                    if (group) Color(0xFF38244F) else Color(0xFF123C40),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (group) "✦" else title.take(1).uppercase(),
                color = if (group) Purple else Neon,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun EmptyState(
        title: String,
        subtitle: String
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "⌁",
                color = Neon,
                fontSize = 42.sp
            )

            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Text(
                subtitle,
                color = Muted,
                fontSize = 11.sp
            )
        }
    }

    override fun onDestroy() {
        startAttempt++

        if (::connections.isInitialized) {
            connections.stopAdvertising()
            connections.stopDiscovery()
            connections.stopAllEndpoints()
        }

        super.onDestroy()
    }
}
