
package com.moonblink.app

import android.content.Context
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Bg = Color(0xFF080D18)
private val Panel = Color(0xFF111B2B)
private val Mint = Color(0xFF55E6D0)
private val Muted = Color(0xFF8B9BB4)

data class Contact(val id: String, val name: String)
data class Message(val text: String, val mine: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("moonblink_local", Context.MODE_PRIVATE)

        if (prefs.getString("id", null) == null) {
            val newId = "MB-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .take(8)
                .uppercase()

            prefs.edit()
                .putString("id", newId)
                .putString("name", "Пользователь")
                .apply()
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
                MoonblinkHome(prefs)
            }
        }
    }
}

private fun loadContacts(p: android.content.SharedPreferences): List<Contact> {
    val array = try {
        JSONArray(p.getString("contacts", "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val id = item.optString("id")
        if (id.isBlank()) null
        else Contact(id, item.optString("name", "Контакт"))
    }
}

private fun saveContacts(
    p: android.content.SharedPreferences,
    contacts: List<Contact>
) {
    val array = JSONArray()

    contacts.forEach {
        array.put(
            JSONObject()
                .put("id", it.id)
                .put("name", it.name)
        )
    }

    p.edit().putString("contacts", array.toString()).apply()
}

private fun loadMessages(
    p: android.content.SharedPreferences,
    id: String
): List<Message> {
    val array = try {
        JSONArray(p.getString("messages_$id", "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        Message(
            item.optString("text"),
            item.optBoolean("mine")
        )
    }
}

private fun saveMessages(
    p: android.content.SharedPreferences,
    id: String,
    messages: List<Message>
) {
    val array = JSONArray()

    messages.forEach {
        array.put(
            JSONObject()
                .put("text", it.text)
                .put("mine", it.mine)
        )
    }

    p.edit().putString("messages_$id", array.toString()).apply()
}

@Composable
fun MoonblinkHome(prefs: android.content.SharedPreferences) {
    val id = prefs.getString("id", "MB-UNKNOWN") ?: "MB-UNKNOWN"

    var name by remember {
        mutableStateOf(prefs.getString("name", "Пользователь") ?: "Пользователь")
    }

    val contacts = remember {
        mutableStateListOf<Contact>().also {
            it.addAll(loadContacts(prefs))
        }
    }

    var tab by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<Contact?>(null) }
    var addDialog by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf(false) }

    BackHandler(current != null) {
        current = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF10243A), Bg, Bg)
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            if (current != null) {
                ChatView(prefs, current!!) {
                    current = null
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(Mint.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "M",
                            color = Mint,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            "MOONBLINK",
                            color = Color.White,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            "PRIVATE LOCAL MESSENGER",
                            color = Muted,
                            fontSize = 9.sp
                        )
                    }

                    Text(
                        "● OFFLINE",
                        color = Mint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(14.dp))
                        .padding(4.dp)
                ) {
                    listOf("Чаты", "Профиль").forEachIndexed { index, label ->
                        Box(
                            Modifier
                                .weight(1f)
                                .background(
                                    if (tab == index) Mint.copy(alpha = 0.14f)
                                    else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { tab = index }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (tab == index) Mint else Muted,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (tab == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Твои чаты",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Локальные контакты и история",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = { addDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Mint,
                                contentColor = Bg
                            )
                        ) {
                            Text("+ Контакт")
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    if (contacts.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Panel, RoundedCornerShape(18.dp))
                                .padding(22.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("✉", color = Mint, fontSize = 42.sp)
                                Text(
                                    "Пока нет чатов",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Добавь контакт по его Moonblink ID. Передача между устройствами будет подключена отдельно.",
                                    color = Muted,
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp
                                )
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = { addDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Mint,
                                        contentColor = Bg
                                    )
                                ) {
                                    Text("Добавить контакт")
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(contacts, key = { it.id }) { contact ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Panel, RoundedCornerShape(15.dp))
                                        .clickable { current = contact }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .size(42.dp)
                                            .background(Mint.copy(alpha = 0.14f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            contact.name.take(1).uppercase(),
                                            color = Mint,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            contact.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            contact.id,
                                            color = Muted,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Text("›", color = Mint, fontSize = 26.sp)
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Panel, RoundedCornerShape(18.dp))
                            .padding(20.dp)
                    ) {
                        Text(
                            "ТВОЙ ПРОФИЛЬ",
                            color = Muted,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp
                        )

                        Spacer(Modifier.height(16.dp))

                        Text(
                            name,
                            color = Color.White,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Локальный профиль Moonblink",
                            color = Muted,
                            fontSize = 12.sp
                        )

                        Spacer(Modifier.height(20.dp))

                        Text(
                            "ТВОЙ УНИКАЛЬНЫЙ ID",
                            color = Muted,
                            fontSize = 10.sp
                        )

                        Text(
                            id,
                            color = Mint,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { nameDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Изменить имя")
                        }

                        val context = LocalContext.current

                        TextButton(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as ClipboardManager

                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Moonblink ID", id)
                                )
                            }
                        ) {
                            Text("Скопировать ID", color = Mint)
                        }

                        Text(
                            "Профиль и контакты сохраняются на этом устройстве.",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(Modifier.weight(1f, fill = false))

                Text(
                    "MOONBLINK • OFFLINE MESSENGER",
                    color = Color(0xFF53647B),
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }

    if (addDialog) {
        var newId by remember { mutableStateOf("") }
        var newName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Добавить контакт") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        newId,
                        { newId = it.uppercase() },
                        label = { Text("Moonblink ID") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        newName,
                        { newName = it },
                        label = { Text("Имя") },
                        singleLine = true
                    )

                    Text(
                        "Добавление пока локальное: второе устройство ещё не подключено.",
                        color = Muted,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = newId.trim()

                        if (
                            value.startsWith("MB-") &&
                            value.length >= 7 &&
                            contacts.none { it.id == value }
                        ) {
                            contacts.add(
                                Contact(
                                    value,
                                    newName.trim().ifBlank { "Контакт" }
                                )
                            )

                            saveContacts(prefs, contacts)
                            addDialog = false
                        }
                    }
                ) {
                    Text("Сохранить", color = Mint)
                }
            },
            dismissButton = {
                TextButton(onClick = { addDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (nameDialog) {
        var edited by remember { mutableStateOf(name) }

        AlertDialog(
            onDismissRequest = { nameDialog = false },
            title = { Text("Имя профиля") },
            text = {
                OutlinedTextField(
                    edited,
                    { edited = it },
                    label = { Text("Твоё имя") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        name = edited.trim().ifBlank { "Пользователь" }
                        prefs.edit().putString("name", name).apply()
                        nameDialog = false
                    }
                ) {
                    Text("Сохранить", color = Mint)
                }
            },
            dismissButton = {
                TextButton(onClick = { nameDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
fun ChatView(
    prefs: android.content.SharedPreferences,
    contact: Contact,
    back: () -> Unit
) {
    var messages by remember(contact.id) {
        mutableStateOf(loadMessages(prefs, contact.id))
    }

    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) {
                Text("‹ Назад", color = Mint)
            }

            Column(Modifier.weight(1f)) {
                Text(
                    contact.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(contact.id, color = Muted, fontSize = 10.sp)
            }

            Text("ЛОКАЛЬНО", color = Mint, fontSize = 9.sp)
        }

        Spacer(Modifier.height(12.dp))

        if (messages.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Начало истории чата.\nСообщения пока сохраняются только на этом устройстве.",
                    color = Muted,
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.mine)
                            Arrangement.End else Arrangement.Start
                    ) {
                        Text(
                            message.text,
                            color = Color.White,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .background(
                                    if (message.mine) Color(0xFF17483F) else Panel,
                                    RoundedCornerShape(13.dp)
                                )
                                .padding(12.dp)
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                draft,
                { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение…") },
                maxLines = 3
            )

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = {
                    if (draft.isNotBlank()) {
                        messages = messages + Message(draft.trim(), true)
                        saveMessages(prefs, contact.id, messages)
                        draft = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Mint,
                    contentColor = Bg
                )
            ) {
                Text("➤")
            }
        }
    }
}
