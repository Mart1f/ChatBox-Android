package com.example.chatbox

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.UUID
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "chatbox_prefs"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_PUBLIC_MESSAGES = "public_messages"
        private const val KEY_DM_MESSAGES = "dm_messages"
        private const val KEY_STATIONS = "stations"
        private const val KEY_SELECTED_DM_PEER = "selected_dm_peer"

        private const val SERVICE_ID = "com.example.chatbox.nearby"
        private const val SYSTEM_NAME = "System"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var connectionsClient: ConnectionsClient

    private val strategy = Strategy.P2P_CLUSTER

    private var nickname by mutableStateOf("")
    private var isConnectedToNetwork by mutableStateOf(false)
    private var showNicknameDialog by mutableStateOf(false)
    private var selectedDmPeerId by mutableStateOf<String?>(null)
    private var proximityMeters by mutableFloatStateOf(15f)

    private val peers = mutableStateListOf<Peer>()
    private val publicMessages = mutableStateListOf<ChatMessage>()
    private val dmMessages = mutableStateMapOf<String, MutableList<ChatMessage>>()
    private val stations = mutableStateListOf<BikeStation>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                addSystemMsg("Permissions denied: $denied")
            } else {
                addSystemMsg("Permissions granted")
                connectToNetwork()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        connectionsClient = Nearby.getConnectionsClient(this)

        nickname = prefs.getString(KEY_NICKNAME, "") ?: ""
        selectedDmPeerId = prefs.getString(KEY_SELECTED_DM_PEER, null)

        loadPublicMessages()
        loadDmMessages()
        loadStations()

        if (stations.isEmpty()) {
            stations.addAll(defaultStations())
            saveStations()
        }

        if (nickname.isBlank()) {
            showNicknameDialog = true
        }

        setContent {
            MaterialTheme {
                AppShell(
                    nickname = nickname,
                    isConnected = isConnectedToNetwork,
                    peers = peers,
                    selectedDmPeerId = selectedDmPeerId,
                    publicMessages = publicMessages,
                    dmMessages = dmMessages,
                    stations = stations,
                    proximityMeters = proximityMeters,
                    onProximityChange = { newValue ->
                        proximityMeters = newValue
                        val updatedStations = stations.mapIndexed { index, st ->
                            val stationPosition = index * 10f
                            val near = abs(stationPosition - proximityMeters) < 5f
                            if (near) {
                                val delta = listOf(-1, 0, 1).random()
                                st.copy(available = (st.available + delta).coerceIn(0, st.capacity))
                            } else {
                                st
                            }
                        }
                        stations.clear()
                        stations.addAll(updatedStations)
                        saveStations()
                    },
                    showNicknameDialog = showNicknameDialog,
                    onRequestNickname = { showNicknameDialog = true },
                    onSaveNickname = { newName ->
                        val clean = newName.trim()
                        if (clean.isNotBlank()) {
                            val oldName = nickname
                            nickname = clean
                            prefs.edit().putString(KEY_NICKNAME, nickname).apply()
                            showNicknameDialog = false

                            if (isConnectedToNetwork && oldName != nickname) {
                                addSystemMsg("Nickname updated. Reconnecting to refresh your identity.")
                                disconnectFromNetwork(clearPeers = true, userInitiated = false)
                                connectToNetwork()
                            }
                        }
                    },
                    onDismissNicknameDialog = {
                        if (nickname.isNotBlank()) {
                            showNicknameDialog = false
                        }
                    },
                    onConnect = { requestAllNeededPermissions() },
                    onDisconnect = { disconnectFromNetwork(clearPeers = false, userInitiated = true) },
                    onClearLocalData = { clearLocalData() },
                    onSelectDmPeer = {
                        selectedDmPeerId = it
                        prefs.edit().putString(KEY_SELECTED_DM_PEER, it).apply()
                    },
                    onSendPublic = { sendPublic(it) },
                    onSendDm = { peerId, text -> sendDm(peerId, text) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromNetwork(clearPeers = false, userInitiated = false)
    }

    private fun defaultStations(): List<BikeStation> {
        return listOf(
            BikeStation("ST-001", "Gare Centrale", 18, 6),
            BikeStation("ST-002", "Campus IMT", 22, 14),
            BikeStation("ST-003", "Centre Ville", 12, 3)
        )
    }

    // ---------------- Permissions ----------------

    private fun requestAllNeededPermissions() {
        val perms = mutableListOf<String>()
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
        perms += Manifest.permission.ACCESS_FINE_LOCATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
        }

        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun hasAllNeededPermissions(): Boolean {
        val needed = mutableListOf<String>()
        needed += Manifest.permission.ACCESS_COARSE_LOCATION
        needed += Manifest.permission.ACCESS_FINE_LOCATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
        }

        return needed.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ---------------- Network ----------------

    private fun connectToNetwork() {
        if (nickname.isBlank()) {
            showNicknameDialog = true
            return
        }

        if (!hasAllNeededPermissions()) {
            addSystemMsg("Missing permissions")
            return
        }

        if (isConnectedToNetwork) {
            addSystemMsg("Already connected")
            return
        }

        isConnectedToNetwork = true
        addSystemMsg("Connecting to network as $nickname")

        val advOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(
            nickname,
            SERVICE_ID,
            connectionLifecycleCallback,
            advOptions
        ).addOnFailureListener {
            isConnectedToNetwork = false
            addSystemMsg("Advertising error: ${it.message}")
        }

        val discOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discOptions
        ).addOnFailureListener {
            isConnectedToNetwork = false
            addSystemMsg("Discovery error: ${it.message}")
        }
    }

    private fun disconnectFromNetwork(clearPeers: Boolean, userInitiated: Boolean) {
        if (!isConnectedToNetwork && !clearPeers) return

        isConnectedToNetwork = false
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        } catch (_: Exception) {
        }

        if (clearPeers) {
            peers.clear()
            selectedDmPeerId = null
            prefs.edit().remove(KEY_SELECTED_DM_PEER).apply()
        } else {
            val updated = peers.map { it.copy(connected = false) }
            peers.clear()
            peers.addAll(updated)
        }

        if (userInitiated) {
            addSystemMsg("Disconnected from network")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            upsertPeer(endpointId, connectionInfo.endpointName, connected = false)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                upsertPeer(endpointId, peerName(endpointId), connected = true)
                addSystemMsg("Connected with ${peerName(endpointId)}")
            } else {
                markDisconnected(endpointId)
                addSystemMsg("Connection failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            markDisconnected(endpointId)
            addSystemMsg("Peer disconnected: ${peerName(endpointId)}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val existing = peers.firstOrNull { it.id == endpointId }
            if (existing?.connected == true) return

            upsertPeer(endpointId, info.endpointName, connected = false)
            connectionsClient.requestConnection(
                nickname,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener {
                addSystemMsg("Request connection failed for ${info.endpointName}: ${it.message}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            markDisconnected(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return

            val raw = payload.asBytes()?.toString(Charset.defaultCharset()).orEmpty()
            val parts = raw.split("|", limit = 6)
            if (parts.size < 6) return

            val type = parts[0]
            val scope = parts[1]
            val target = parts[2]
            val msgId = parts[3]
            val senderName = parts[4]
            val text = parts[5]

            when (type) {
                "CHAT" -> {
                    if (scope == "PUBLIC") {
                        if (publicMessages.none { it.id == msgId }) {
                            publicMessages.add(
                                ChatMessage(
                                    id = msgId,
                                    from = senderName,
                                    text = text,
                                    mine = false
                                )
                            )
                            savePublicMessages()
                        }
                    } else if (scope == "DM") {
                        val peer = peers.firstOrNull { it.id == endpointId }
                        val intendedForMe = target == nickname || target == peer?.name || target == "*"
                        if (intendedForMe) {
                            val list = dmMessages.getOrPut(endpointId) { mutableListOf() }
                            if (list.none { it.id == msgId }) {
                                list.add(
                                    ChatMessage(
                                        id = msgId,
                                        from = senderName,
                                        text = text,
                                        mine = false
                                    )
                                )
                                saveDmMessages()
                            }
                        }
                    }
                }

                "STATION" -> {
                    val stationObj = parseStationPayload(text)
                    if (stationObj != null) {
                        upsertStation(stationObj)
                        saveStations()
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // ---------------- Messaging ----------------

    private fun sendPublic(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        val msgId = UUID.randomUUID().toString().take(8)
        val message = ChatMessage(
            id = msgId,
            from = nickname,
            text = clean,
            mine = true
        )
        publicMessages.add(message)
        savePublicMessages()

        val connectedIds = peers.filter { it.connected }.map { it.id }
        if (connectedIds.isEmpty()) {
            addSystemMsg("No peers connected")
            return
        }

        val payloadText = "CHAT|PUBLIC|*|$msgId|$nickname|$clean"
        connectionsClient.sendPayload(connectedIds, Payload.fromBytes(payloadText.toByteArray()))
            .addOnFailureListener { addSystemMsg("Public message send failed: ${it.message}") }
    }

    private fun sendDm(peerId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        val peer = peers.firstOrNull { it.id == peerId } ?: return
        if (!peer.connected) {
            addSystemMsg("That peer is not connected")
            return
        }

        val msgId = UUID.randomUUID().toString().take(8)
        val list = dmMessages.getOrPut(peerId) { mutableListOf() }
        list.add(
            ChatMessage(
                id = msgId,
                from = nickname,
                text = clean,
                mine = true
            )
        )
        saveDmMessages()

        val payloadText = "CHAT|DM|${peer.name}|$msgId|$nickname|$clean"
        connectionsClient.sendPayload(listOf(peerId), Payload.fromBytes(payloadText.toByteArray()))
            .addOnFailureListener { addSystemMsg("DM send failed: ${it.message}") }
    }

    // ---------------- Stations ----------------

    private fun parseStationPayload(raw: String): BikeStation? {
        return try {
            val map = raw.split(";")
                .mapNotNull {
                    val kv = it.split("=", limit = 2)
                    if (kv.size == 2) kv[0] to kv[1] else null
                }
                .toMap()

            BikeStation(
                id = map["stationId"] ?: return null,
                name = map["name"] ?: "Unknown Station",
                available = map["available"]?.toIntOrNull() ?: 0,
                capacity = map["capacity"]?.toIntOrNull() ?: 0
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun upsertStation(station: BikeStation) {
        val idx = stations.indexOfFirst { it.id == station.id }
        if (idx >= 0) {
            stations[idx] = station
        } else {
            stations.add(station)
        }
    }

    // ---------------- Persistence ----------------

    private fun loadPublicMessages() {
        publicMessages.clear()
        val raw = prefs.getString(KEY_PUBLIC_MESSAGES, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                publicMessages.add(
                    ChatMessage(
                        id = obj.getString("id"),
                        from = obj.getString("from"),
                        text = obj.getString("text"),
                        mine = obj.getBoolean("mine"),
                        isSystem = obj.optBoolean("isSystem", false)
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun savePublicMessages() {
        val arr = JSONArray()
        publicMessages.forEach { msg ->
            arr.put(
                JSONObject().apply {
                    put("id", msg.id)
                    put("from", msg.from)
                    put("text", msg.text)
                    put("mine", msg.mine)
                    put("isSystem", msg.isSystem)
                }
            )
        }
        prefs.edit().putString(KEY_PUBLIC_MESSAGES, arr.toString()).apply()
    }

    private fun loadDmMessages() {
        dmMessages.clear()
        val raw = prefs.getString(KEY_DM_MESSAGES, null) ?: return
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val peerId = keys.next()
                val arr = root.getJSONArray(peerId)
                val list = mutableListOf<ChatMessage>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        ChatMessage(
                            id = obj.getString("id"),
                            from = obj.getString("from"),
                            text = obj.getString("text"),
                            mine = obj.getBoolean("mine"),
                            isSystem = obj.optBoolean("isSystem", false)
                        )
                    )
                }
                dmMessages[peerId] = list
            }
        } catch (_: Exception) {
        }
    }

    private fun saveDmMessages() {
        val root = JSONObject()
        dmMessages.forEach { (peerId, messages) ->
            val arr = JSONArray()
            messages.forEach { msg ->
                arr.put(
                    JSONObject().apply {
                        put("id", msg.id)
                        put("from", msg.from)
                        put("text", msg.text)
                        put("mine", msg.mine)
                        put("isSystem", msg.isSystem)
                    }
                )
            }
            root.put(peerId, arr)
        }
        prefs.edit().putString(KEY_DM_MESSAGES, root.toString()).apply()
    }

    private fun loadStations() {
        stations.clear()
        val raw = prefs.getString(KEY_STATIONS, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                stations.add(
                    BikeStation(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        capacity = obj.getInt("capacity"),
                        available = obj.getInt("available")
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun saveStations() {
        val arr = JSONArray()
        stations.forEach { st ->
            arr.put(
                JSONObject().apply {
                    put("id", st.id)
                    put("name", st.name)
                    put("available", st.available)
                    put("capacity", st.capacity)
                }
            )
        }
        prefs.edit().putString(KEY_STATIONS, arr.toString()).apply()
    }

    private fun clearLocalData() {
        prefs.edit()
            .remove(KEY_PUBLIC_MESSAGES)
            .remove(KEY_DM_MESSAGES)
            .remove(KEY_STATIONS)
            .remove(KEY_SELECTED_DM_PEER)
            .apply()

        publicMessages.clear()
        dmMessages.clear()
        stations.clear()
        selectedDmPeerId = null

        stations.addAll(defaultStations())
        saveStations()
        addSystemMsg("Local chat history cleared")
    }

    // ---------------- Helpers ----------------

    private fun addSystemMsg(text: String) {
        publicMessages.add(
            ChatMessage(
                id = UUID.randomUUID().toString().take(8),
                from = SYSTEM_NAME,
                text = text,
                mine = false,
                isSystem = true
            )
        )
        savePublicMessages()
    }

    private fun peerName(endpointId: String): String {
        return peers.firstOrNull { it.id == endpointId }?.name ?: endpointId.take(8)
    }

    private fun upsertPeer(id: String, name: String, connected: Boolean) {
        val idx = peers.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val current = peers[idx]
            peers[idx] = current.copy(name = name, connected = connected)
        } else {
            peers.add(Peer(id = id, name = name, connected = connected))
        }
    }

    private fun markDisconnected(id: String) {
        val idx = peers.indexOfFirst { it.id == id }
        if (idx >= 0) {
            peers[idx] = peers[idx].copy(connected = false)
        }
    }
}

// ---------------- Models ----------------

data class Peer(
    val id: String,
    val name: String,
    val connected: Boolean
)

data class ChatMessage(
    val id: String,
    val from: String,
    val text: String,
    val mine: Boolean,
    val isSystem: Boolean = false
)

data class BikeStation(
    val id: String,
    val name: String,
    val capacity: Int,
    val available: Int
)

// ---------------- UI ----------------

@Composable
private fun AppShell(
    nickname: String,
    isConnected: Boolean,
    peers: List<Peer>,
    selectedDmPeerId: String?,
    publicMessages: List<ChatMessage>,
    dmMessages: Map<String, List<ChatMessage>>,
    stations: List<BikeStation>,
    proximityMeters: Float,
    onProximityChange: (Float) -> Unit,
    showNicknameDialog: Boolean,
    onRequestNickname: () -> Unit,
    onSaveNickname: (String) -> Unit,
    onDismissNicknameDialog: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClearLocalData: () -> Unit,
    onSelectDmPeer: (String) -> Unit,
    onSendPublic: (String) -> Unit,
    onSendDm: (String, String) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ChatBox", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (nickname.isBlank()) "Anonymous name not set" else "You are: $nickname",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = if (isConnected) "Network: connected" else "Network: disconnected",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                IconButton(onClick = onRequestNickname) {
                    Icon(Icons.Default.Person, contentDescription = "Nickname")
                }

                TextButton(onClick = {
                    if (isConnected) onDisconnect() else onConnect()
                }) {
                    Text(if (isConnected) "Disconnect" else "Connect")
                }

                IconButton(onClick = onClearLocalData) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear data")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TabButton("Public", tab == 0) { tab = 0 }
            TabButton("DM", tab == 1) { tab = 1 }
            TabButton("Stations", tab == 2) { tab = 2 }
        }

        Divider()

        when (tab) {
            0 -> PublicChat(
                modifier = Modifier.fillMaxSize(),
                peers = peers,
                messages = publicMessages,
                onSend = onSendPublic
            )

            1 -> DmChat(
                modifier = Modifier.fillMaxSize(),
                peers = peers,
                selectedPeerId = selectedDmPeerId,
                dmMessages = dmMessages,
                onSelectPeer = onSelectDmPeer,
                onSend = onSendDm
            )

            2 -> StationsView(
                modifier = Modifier.fillMaxSize(),
                stations = stations,
                proximityMeters = proximityMeters,
                onProximityChange = onProximityChange
            )
        }
    }

    if (showNicknameDialog) {
        NicknameDialog(
            initialValue = nickname,
            onSave = onSaveNickname,
            onDismiss = onDismissNicknameDialog
        )
    }
}

@Composable
private fun TabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg =
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant

    Surface(
        color = bg,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun NicknameDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onSave(text) }) {
                Text("Save")
            }
        },
        dismissButton = {
            if (initialValue.isNotBlank()) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
        title = { Text("Choose your anonymous name") },
        text = {
            Column {
                Text("This name is how other devices will see you inside the network.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("Anonymous name") }
                )
            }
        }
    )
}

@Composable
private fun PublicChat(
    modifier: Modifier,
    peers: List<Peer>,
    messages: List<ChatMessage>,
    onSend: (String) -> Unit
) {
    Column(modifier.padding(12.dp)) {
        Text(
            "Connected peers: ${peers.count { it.connected }}",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        ChatList(Modifier.weight(1f), messages)
        Spacer(Modifier.height(8.dp))
        ChatComposer(
            hint = "Write to the public network…",
            onSend = onSend
        )
    }
}

@Composable
private fun DmChat(
    modifier: Modifier,
    peers: List<Peer>,
    selectedPeerId: String?,
    dmMessages: Map<String, List<ChatMessage>>,
    onSelectPeer: (String) -> Unit,
    onSend: (String, String) -> Unit
) {
    val availablePeers = peers.filter { it.connected || dmMessages.containsKey(it.id) }
    val selectedPeer = availablePeers.firstOrNull { it.id == selectedPeerId }
    val list = dmMessages[selectedPeerId].orEmpty()

    Column(modifier.padding(12.dp)) {
        Text("Private messages", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 130.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
        ) {
            items(availablePeers.size) { idx ->
                val peer = availablePeers[idx]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectPeer(peer.id) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = peer.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (peer.connected) "Connected" else "Offline history",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (peer.id == selectedPeerId) Text("✓")
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (selectedPeer == null) {
            Text("Select a peer to open the DM history.")
            return
        }

        Text("DM with ${selectedPeer.name}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        ChatList(Modifier.weight(1f), list)
        Spacer(Modifier.height(8.dp))

        ChatComposer(
            hint = if (selectedPeer.connected) "Message ${selectedPeer.name}…" else "Peer offline",
            enabled = selectedPeer.connected,
            onSend = { txt ->
                onSend(selectedPeer.id, txt)
            }
        )
    }
}


@Composable
private fun StationsView(
    modifier: Modifier,
    stations: List<BikeStation>,
    proximityMeters: Float,
    onProximityChange: (Float) -> Unit
) {
    Column(modifier.padding(12.dp)) {
        Text("Nearby stations", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        Text("Simulated proximity: ${proximityMeters.toInt()} m")
        Slider(
            value = proximityMeters,
            onValueChange = onProximityChange,
            valueRange = 0f..30f
        )

        Spacer(Modifier.height(8.dp))

        if (stations.isEmpty()) {
            Text("No station broadcasts received yet.")
            return
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(stations.size) { idx ->
                val st = stations[idx]
                val stationPosition = idx * 10f
                val near = abs(stationPosition - proximityMeters) < 5f

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (near)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(st.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("ID: ${st.id}")
                        Text("Available: ${st.available}/${st.capacity}")
                        Text("Distance: ${abs(stationPosition - proximityMeters).toInt()} m")

                        if (st.capacity > 0) {
                            Spacer(Modifier.height(8.dp))
                            Slider(
                                value = st.available.toFloat(),
                                onValueChange = {},
                                valueRange = 0f..st.capacity.toFloat(),
                                enabled = false
                            )
                        }

                        if (near) {
                            Spacer(Modifier.height(6.dp))
                            Text("You are close to this station", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatList(
    modifier: Modifier,
    messages: List<ChatMessage>
) {
    val reversed = messages.asReversed()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(reversed.size) { idx ->
            val msg = reversed[idx]
            ChatBubble(msg)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val align = if (msg.mine) Alignment.End else Alignment.Start
    val bg = when {
        msg.isSystem -> MaterialTheme.colorScheme.surfaceVariant
        msg.mine -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 320.dp)
            ) {
                if (!msg.mine) {
                    Text(
                        msg.from,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(msg.text)
            }
        }
    }
}

@Composable
private fun ChatComposer(
    hint: String,
    enabled: Boolean = true,
    onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(hint) },
            singleLine = true,
            enabled = enabled
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = {
                val clean = text.trim()
                if (clean.isNotEmpty()) {
                    onSend(clean)
                    text = ""
                }
            },
            enabled = enabled
        ) {
            Icon(Icons.Default.Send, contentDescription = "Send")
        }
    }
}