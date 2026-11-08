
package com.moonblink.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private val Bg = Color(0xFF080D18)
private val Panel = Color(0xFF111B2B)
private val Mint = Color(0xFF55E6D0)
private val Muted = Color(0xFF8B9BB4)

private const val SERVICE_ID = "com.moonblink.app"

data class NearbyPeer(
    val endpointId: String,
    val name: String
)

data class PendingRequest(
    val endpointId: String,
    val name: String,
    val digits: String
)

data class ChatLine(
    val text: String,
    val sender: String,
    val mine: Boolean
)

class MainActivity : ComponentActivity() {

    private lateinit var connections: ConnectionsClient
    private lateinit var prefs: android.content.SharedPreferences

    private var localId = ""
    private var localName = "Пользователь"

    private var online by mutableStateOf(false)
    private var status by mutableStateOf("Не в сети")

    private val peers = mutableStateListOf<NearbyPeer>()
    private val connected = mutableStateMapOf<String, String>()
    private val endpointNames = mutableMapOf<String, String>()

    private var pendingRequest by mutableStateOf<PendingRequest?>(null)

    private val messages = mutableStateListOf<ChatLine>()

    private lateinit var permissionLauncher:
        androidx.activity.result.ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("moonblink_local", Context.MODE_PRIVATE)

        localId = prefs.getString("id", null) ?: run {
            val newId = "MB-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .take(8)
                .uppercase()

            prefs.edit()
                .putString("id", newId)
                .putString("name", "Пользователь")
                .apply()

            newId
        }

        localName = prefs.getString("name", "Пользователь")
            ?: "Пользователь"

        connections = Nearby.getConnectionsClient(this)

        loadMessages()

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
                    primary = Mint,
                    background = Bg,
                    surface = Panel,
                    onSurface = Color.White
                )
            ) {
                MoonblinkScreen()
            }
        }
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

                if (json.optString("type") != "text") return

                val text = json.optString("text")
                val sender = json.optString("senderName", "Соседнее устройство")

                if (text.isNotBlank()) {
                    runOnUiThread {
                        messages.add(
                            ChatLine(
                                text = text,
                                sender = sender,
                                mine = false
                            )
                        )

                        saveMessages()
                    }
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
                    status = "Сообщение передано"
                }
            }
        }
    }

    private val connectionCallback =
        object : ConnectionLifecycleCallback() {

            override fun onConnectionInitiated(
                endpointId: String,
                info: ConnectionInfo
            ) {
                val endpointName = info.endpointName
                endpointNames[endpointId] = endpointName

                runOnUiThread {
                    pendingRequest = PendingRequest(
                        endpointId = endpointId,
                        name = endpointName,
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
                        val name = endpointNames[endpointId]
                            ?: "Moonblink"

                        connected[endpointId] = name

                        peers.removeAll {
                            it.endpointId == endpointId
                        }

                        status = "Устройство подключено: $name"
                    } else {
                        status = "Не удалось установить соединение"
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

    private val discoveryCallback =
        object : EndpointDiscoveryCallback() {

            override fun onEndpointFound(
                endpointId: String,
                info: DiscoveredEndpointInfo
            ) {
                runOnUiThread {
                    if (connected.containsKey(endpointId)) return@runOnUiThread

                    if (peers.none { it.endpointId == endpointId }) {
                        peers.add(
                            NearbyPeer(
                                endpointId,
                                info.endpointName
                            )
                        )
                    }
                }
            }

            override fun onEndpointLost(endpointId: String) {
                runOnUiThread {
                    peers.removeAll {
                        it.endpointId == endpointId
                    }
                }
            }
        }

    private fun startNearby() {
        if (online) return

        status = "Запускаем поиск устройств..."

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
            runOnUiThread {
                status = "Видимость включена"
            }
        }.addOnFailureListener { error ->
            runOnUiThread {
                status = "Ошибка видимости: ${error.localizedMessage}"
            }
        }

        connections.startDiscovery(
            SERVICE_ID,
            discoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            runOnUiThread {
                online = true
                status = "Ищем устройства поблизости"
            }
        }.addOnFailureListener { error ->
            runOnUiThread {
                status = "Ошибка поиска: ${error.localizedMessage}"
            }
        }
    }

    private fun stopNearby() {
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.stopAllEndpoints()

        online = false
        peers.clear()
        connected.clear()
        endpointNames.clear()
        pendingRequest = null

        status = "Не в сети"
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
                status = "Ошибка подключения: ${error.localizedMessage}"
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
                status = "Не удалось принять подключение: ${error.localizedMessage}"
            }
        }

        pendingRequest = null
    }

    private fun rejectPending() {
        val request = pendingRequest ?: return

        connections.rejectConnection(request.endpointId)

        pendingRequest = null
        status = "Запрос отклонён"
    }

    private fun sendMessage(text: String) {
        val cleanText = text.trim()

        if (cleanText.isEmpty()) return

        if (connected.isEmpty()) {
            status = "Сначала подключи соседнее устройство"
            return
        }

        val json = JSONObject()
            .put("type", "text")
            .put("senderId", localId)
            .put("senderName", localName)
            .put("text", cleanText)
            .put("messageId", UUID.randomUUID().toString())

        val payload = Payload.fromBytes(
            json.toString().toByteArray(StandardCharsets.UTF_8)
        )

        messages.add(
            ChatLine(
                text = cleanText,
                sender = "Ты",
                mine = true
            )
        )

        saveMessages()

        connected.keys.toList().forEach { endpointId ->
            connections.sendPayload(endpointId, payload)
                .addOnFailureListener { error ->
                    runOnUiThread {
                        status = "Ошибка отправки: ${error.localizedMessage}"
                    }
                }
        }
    }

    private fun loadMessages() {
        val array = try {
            JSONArray(prefs.getString("mesh_test_messages", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue

            messages.add(
                ChatLine(
                    item.optString("text"),
                    item.optString("sender"),
                    item.optBoolean("mine")
                )
            )
        }
    }

    private fun saveMessages() {
        val array = JSONArray()

        messages.takeLast(100).forEach {
            array.put(
                JSONObject()
                    .put("text", it.text)
                    .put("sender", it.sender)
                    .put("mine", it.mine)
            )
        }

        prefs.edit()
            .putString("mesh_test_messages", array.toString())
            .apply()
    }

    @Composable
    private fun MoonblinkScreen() {
        var draft by remember { mutableStateOf("") }
        var editedName by remember { mutableStateOf(localName) }

        BackHandler(pendingRequest != null) {
            rejectPending()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .padding(16.dp)
        ) {

            Text(
                "MOONBLINK",
                color = Mint,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )

            Text(
                "OFFLINE MESH MESSENGER",
                color = Muted,
                fontSize = 10.sp
            )

            Spacer(Modifier.height(18.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("ТВОЙ ПРОФИЛЬ", color = Muted, fontSize = 10.sp)

                    Spacer(Modifier.height(5.dp))

                    Text(
                        localId,
                        color = Mint,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text("Имя") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            localName = editedName.trim()
                                .ifBlank { "Пользователь" }

                            prefs.edit()
                                .putString("name", localName)
                                .apply()

                            status = "Имя сохранено"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сохранить имя")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (online) "● ONLINE" else "● OFFLINE",
                        color = if (online) Mint else Muted,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(5.dp))

                    Text(status, color = Color.White, fontSize = 12.sp)

                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (online) stopNearby()
                            else requestOnline()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (online) Color(0xFF8B4050) else Mint,
                            contentColor = if (online) Color.White else Bg
                        )
                    ) {
                        Text(
                            if (online) "Отключить поиск" else "Включить поиск устройств"
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "УСТРОЙСТВА ПОБЛИЗОСТИ",
                color = Mint,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )

            if (peers.isEmpty()) {
                Text(
                    "Пока никого не найдено",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            peers.forEach { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(peer.name, color = Color.White)
                        Text(
                            peer.endpointId.take(8),
                            color = Muted,
                            fontSize = 9.sp
                        )
                    }

                    Button(onClick = { connectTo(peer) }) {
                        Text("Связаться")
                    }
                }

                Spacer(Modifier.height(6.dp))
            }

            Text(
                "ПОДКЛЮЧЕНО: ${connected.size}",
                color = Mint,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )

            connected.values.forEach {
                Text("● $it", color = Color.White, fontSize = 11.sp)
            }

            Spacer(Modifier.height(10.dp))

            Text(
                "СООБЩЕНИЯ",
                color = Mint,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages) { message ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (message.mine) Color(0xFF17483F) else Panel,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            message.sender,
                            color = Mint,
                            fontSize = 10.sp
                        )

                        Text(
                            message.text,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Напиши сообщение") },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    )
                )

                Spacer(Modifier.width(7.dp))

                Button(
                    onClick = {
                        sendMessage(draft)
                        draft = ""
                    }
                ) {
                    Text("➤")
                }
            }

            Text(
                "ТЕСТОВАЯ ПРЯМАЯ СВЯЗЬ • E2EE ЕЩЁ НЕ ПОДКЛЮЧЕНО",
                color = Muted,
                fontSize = 8.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        val request = pendingRequest

        if (request != null) {
            AlertDialog(
                onDismissRequest = { rejectPending() },
                title = { Text("Запрос на подключение") },
                text = {
                    Column {
                        Text("Устройство: ${request.name}")
                        Spacer(Modifier.height(10.dp))
                        Text("Код подтверждения:")
                        Text(
                            request.digits,
                            color = Mint,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Сравни код на обоих устройствах. При несовпадении отклони запрос.",
                            color = Muted
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { acceptPending() }) {
                        Text("Подтвердить", color = Mint)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { rejectPending() }) {
                        Text("Отклонить")
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        if (::connections.isInitialized) {
            connections.stopAdvertising()
            connections.stopDiscovery()
            connections.stopAllEndpoints()
        }

        super.onDestroy()
    }
}
