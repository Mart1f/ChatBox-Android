package com.example.chatbox

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.UUID
import kotlin.math.abs

// ─── Theme Colors ───────────────────────────────────────────────────────────
val DarkBg        = Color(0xFF0D0D0D)
val DarkSurface   = Color(0xFF1A1A1A)
val DarkCard      = Color(0xFF222222)
val DarkHeader    = Color(0xFF111111)
val AccentPurple  = Color(0xFF9B8FE0)
val AccentPurple2 = Color(0xFF7B6FCA)
val TextPrimary   = Color(0xFFEEEEEE)
val TextSecondary = Color(0xFF888888)
val GreenOnline   = Color(0xFF4CAF50)
val BubbleMine    = Color(0xFF2A2060)
val BubbleOther   = Color(0xFF2A2A2A)

@Composable
fun ChatBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary            = AccentPurple,
            onPrimary          = Color.White,
            primaryContainer   = BubbleMine,
            onPrimaryContainer = TextPrimary,
            secondary          = AccentPurple2,
            surface            = DarkSurface,
            onSurface          = TextPrimary,
            surfaceVariant     = DarkCard,
            onSurfaceVariant   = TextSecondary,
            background         = DarkBg,
            onBackground       = TextPrimary,
        ),
        content = content
    )
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "chatbox_prefs"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_PUBLIC_MESSAGES = "public_messages"
        private const val KEY_DM_MESSAGES = "dm_messages"
        private const val KEY_STATIONS = "stations"
        private const val KEY_SELECTED_DM_PEER = "selected_dm_peer"
        private const val SERVICE_ID = "com.example.chatbox.nearby"
        private const val SPAM_COOLDOWN_MS = 30_000L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var connectionsClient: ConnectionsClient
    private val strategy = Strategy.P2P_CLUSTER
    private val localId = UUID.randomUUID().toString().replace("-", "").take(8)

    private var nickname by mutableStateOf("")
    private var isConnectedToNetwork by mutableStateOf(false)
    private var isConnecting by mutableStateOf(false)
    private var showNicknameDialog by mutableStateOf(false)
    private var selectedDmPeerId by mutableStateOf<String?>(null)
    private var proximityMeters by mutableFloatStateOf(15f)

    private var transientNotification by mutableStateOf<String?>(null)
    private val handler = Handler(Looper.getMainLooper())

    // ─── Anti-spam ────────────────────────────────────────────────────────
    private var lastPublicSentAt by mutableLongStateOf(0L)
    private var lastDmSentAt    by mutableLongStateOf(0L)
    // ─── Cooldown countdown (seconds remaining) shown in the UI ──────────
    private var publicCooldownLeft by mutableIntStateOf(0)
    private var dmCooldownLeft     by mutableIntStateOf(0)
    private val cooldownHandler = Handler(Looper.getMainLooper())

    private val peers = mutableStateListOf<Peer>()
    private val publicMessages = mutableStateListOf<ChatMessage>()
    private val dmMessages = mutableStateMapOf<String, SnapshotStateList<ChatMessage>>()
    private val unreadDms = mutableStateListOf<String>()
    private val stations = mutableStateListOf<BikeStation>()

    private val pendingConnections = mutableSetOf<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) connectToNetwork()
        else showTransient("Permissions denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Respect system status bar (WiFi/signal bar) — draws edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
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

        if (nickname.isBlank()) showNicknameDialog = true

        setContent {
            ChatBoxTheme {
                AppShell(
                    nickname = nickname,
                    isConnected = isConnectedToNetwork,
                    isConnecting = isConnecting,
                    transientNotification = transientNotification,
                    peers = peers,
                    selectedDmPeerId = selectedDmPeerId,
                    publicMessages = publicMessages,
                    dmMessages = dmMessages,
                    unreadDms = unreadDms,
                    stations = stations,
                    proximityMeters = proximityMeters,
                    publicCooldownLeft = publicCooldownLeft,
                    dmCooldownLeft = dmCooldownLeft,
                    onProximityChange = { newValue ->
                        proximityMeters = newValue
                        val updated = stations.mapIndexed { i, st ->
                            if (abs(i * 10f - proximityMeters) < 5f) {
                                st.copy(available = (st.available + listOf(-1, 0, 1).random()).coerceIn(0, st.capacity))
                            } else st
                        }
                        stations.clear(); stations.addAll(updated); saveStations()
                    },
                    showNicknameDialog = showNicknameDialog,
                    onRequestNickname = { showNicknameDialog = true },
                    onSaveNickname = {
                        val clean = it.trim()
                        if (clean.isNotBlank()) {
                            nickname = clean
                            prefs.edit().putString(KEY_NICKNAME, nickname).apply()
                            showNicknameDialog = false
                            if (isConnectedToNetwork) {
                                disconnectFromNetwork(clearPeers = true, userInitiated = false)
                                connectToNetwork()
                            }
                        }
                    },
                    onDismissNicknameDialog = { if (nickname.isNotBlank()) showNicknameDialog = false },
                    onConnect = { requestNeededPermissions() },
                    onDisconnect = { disconnectFromNetwork(false, true) },
                    onClearLocalData = { clearLocalData() },
                    onSelectDmPeer = { id ->
                        selectedDmPeerId = id
                        unreadDms.remove(id)
                        prefs.edit().putString(KEY_SELECTED_DM_PEER, id).apply()
                    },
                    onSendPublic = { sendPublic(it) },
                    onSendDm = { id, txt -> sendDm(id, txt) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cooldownHandler.removeCallbacksAndMessages(null)
        disconnectFromNetwork(false, false)
    }

    // ─── Anti-spam helpers ────────────────────────────────────────────────

    private fun startCooldownTick(isPublic: Boolean) {
        val updateRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - if (isPublic) lastPublicSentAt else lastDmSentAt
                val remaining = ((SPAM_COOLDOWN_MS - elapsed) / 1000).toInt().coerceAtLeast(0)
                if (isPublic) publicCooldownLeft = remaining else dmCooldownLeft = remaining
                if (remaining > 0) cooldownHandler.postDelayed(this, 1000)
            }
        }
        cooldownHandler.post(updateRunnable)
    }

    private fun canSendPublic(): Boolean {
        val elapsed = System.currentTimeMillis() - lastPublicSentAt
        return elapsed >= SPAM_COOLDOWN_MS
    }

    private fun canSendDm(): Boolean {
        val elapsed = System.currentTimeMillis() - lastDmSentAt
        return elapsed >= SPAM_COOLDOWN_MS
    }

    // ─── Network ──────────────────────────────────────────────────────────

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun connectToNetwork() {
        if (nickname.isBlank() || isConnectedToNetwork) return
        isConnecting = true
        peers.clear()
        pendingConnections.clear()
        showTransient("Conectando...")

        val myName = "$nickname|$localId"

        connectionsClient.startAdvertising(
            myName, SERVICE_ID, connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener {
            isConnectedToNetwork = true
            isConnecting = false
            showTransient("Network active ✓")
        }.addOnFailureListener { e ->
            isConnecting = false
            showTransient("Advertising error: ${e.message?.take(50)}")
        }

        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener { e ->
            showTransient("Discovery error: ${e.message?.take(50)}")
        }
    }

    private fun disconnectFromNetwork(clearPeers: Boolean, userInitiated: Boolean) {
        isConnectedToNetwork = false
        isConnecting = false
        pendingConnections.clear()
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        } catch (_: Exception) {}

        if (clearPeers) {
            peers.clear()
            unreadDms.clear()
            selectedDmPeerId = null
        } else {
            for (i in peers.indices) peers[i] = peers[i].copy(connected = false)
        }
        if (userInitiated) showTransient("Desconectado")
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val parts = info.endpointName.split("|")
            val remoteId = parts.getOrNull(1) ?: ""
            if (remoteId == localId) { connectionsClient.rejectConnection(endpointId); return }
            upsertPeer(endpointId, parts[0], false)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingConnections.remove(endpointId)
            if (result.status.isSuccess) {
                upsertPeer(endpointId, peerName(endpointId), true)
                showTransient("Connected to ${peerName(endpointId)}")
            } else {
                markDisconnected(endpointId)
                val code = result.status.statusCode
                if (code != 8012) showTransient("Connection failed (${peerName(endpointId)}) code: $code")
            }
        }
        override fun onDisconnected(endpointId: String) {
            pendingConnections.remove(endpointId)
            markDisconnected(endpointId)
            showTransient("${peerName(endpointId)} disconnected")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val parts = info.endpointName.split("|")
            val remoteId = parts.getOrNull(1) ?: ""
            if (remoteId == localId) return
            val existingPeer = peers.firstOrNull { it.id == endpointId }
            if (existingPeer?.connected == true) return

            upsertPeer(endpointId, parts[0], false)

            if (localId < remoteId) {
                if (pendingConnections.contains(endpointId)) return
                pendingConnections.add(endpointId)
                connectionsClient.requestConnection("$nickname|$localId", endpointId, connectionLifecycleCallback)
                    .addOnFailureListener { e ->
                        pendingConnections.remove(endpointId)
                        val msg = e.message ?: ""
                        if (!msg.contains("8012") && !msg.contains("already", ignoreCase = true)) {
                            showTransient("Connect error: ${msg.take(40)}")
                        }
                    }
            }
        }
        override fun onEndpointLost(endpointId: String) {
            pendingConnections.remove(endpointId)
            markDisconnected(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val raw = payload.asBytes()?.toString(Charset.defaultCharset()) ?: return
            val parts = raw.split("|", limit = 6)
            if (parts.size < 6) return

            val type   = parts[0]
            val scope  = parts[1]
            val target = parts[2]
            val msgId  = parts[3]
            val sender = parts[4]
            val text   = parts[5]

            when (type) {
                "CHAT" -> {
                    if (scope == "PUBLIC") {
                        if (publicMessages.none { it.id == msgId }) {
                            publicMessages.add(ChatMessage(msgId, sender, text, false))
                            savePublicMessages()
                        }
                    } else if (scope == "DM" && (target == nickname || target == "*")) {
                        val list = dmMessages.getOrPut(endpointId) { mutableStateListOf() }
                        if (list.none { it.id == msgId }) {
                            list.add(ChatMessage(msgId, sender, text, false))
                            if (selectedDmPeerId != endpointId && !unreadDms.contains(endpointId)) unreadDms.add(endpointId)
                            saveDmMessages()
                        }
                    }
                }
                "STATION" -> parseStation(text)?.let { upsertStation(it); saveStations() }
            }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }

    // ─── Messaging ────────────────────────────────────────────────────────

    private fun sendPublic(txt: String) {
        if (!canSendPublic()) {
            showTransient("⏳ Wait ${publicCooldownLeft}s before sending again")
            return
        }
        lastPublicSentAt = System.currentTimeMillis()
        publicCooldownLeft = (SPAM_COOLDOWN_MS / 1000).toInt()
        startCooldownTick(isPublic = true)

        val msgId = UUID.randomUUID().toString().take(8)
        publicMessages.add(ChatMessage(msgId, nickname, txt, true))
        savePublicMessages()
        val ids = peers.filter { it.connected }.map { it.id }
        if (ids.isNotEmpty()) {
            val payloadText = "CHAT|PUBLIC|*|$msgId|$nickname|$txt"
            connectionsClient.sendPayload(ids, Payload.fromBytes(payloadText.toByteArray()))
        } else showTransient("Waiting for peers...")
    }

    private fun sendDm(id: String, txt: String) {
        val peer = peers.firstOrNull { it.id == id } ?: return
        if (!peer.connected) { showTransient("Peer offline"); return }
        if (!canSendDm()) {
            showTransient("⏳ Wait ${dmCooldownLeft}s before sending another DM")
            return
        }
        lastDmSentAt = System.currentTimeMillis()
        dmCooldownLeft = (SPAM_COOLDOWN_MS / 1000).toInt()
        startCooldownTick(isPublic = false)

        val msgId = UUID.randomUUID().toString().take(8)
        dmMessages.getOrPut(id) { mutableStateListOf() }.add(ChatMessage(msgId, nickname, txt, true))
        saveDmMessages()
        val payloadText = "CHAT|DM|${peer.name}|$msgId|$nickname|$txt"
        connectionsClient.sendPayload(listOf(id), Payload.fromBytes(payloadText.toByteArray()))
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun showTransient(text: String) {
        transientNotification = text
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ transientNotification = null }, 3000)
    }

    private fun peerName(id: String) = peers.firstOrNull { it.id == id }?.name ?: id.take(8)

    private fun upsertPeer(id: String, name: String, connected: Boolean) {
        val idx = peers.indexOfFirst { it.id == id }
        if (idx >= 0) peers[idx] = peers[idx].copy(name = name, connected = connected)
        else peers.add(Peer(id, name, connected))
    }

    private fun markDisconnected(id: String) {
        val idx = peers.indexOfFirst { it.id == id }
        if (idx >= 0) peers[idx] = peers[idx].copy(connected = false)
    }

    private fun defaultStations() = listOf(
        BikeStation("ST-001", "Gare Centrale", 18, 6),
        BikeStation("ST-002", "Campus IMT", 22, 14),
        BikeStation("ST-003", "Centre Ville", 12, 3)
    )

    private fun parseStation(raw: String) = try {
        val map = raw.split(";").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        BikeStation(map["stationId"]!!, map["name"] ?: "Unknown", map["capacity"]?.toInt() ?: 0, map["available"]?.toInt() ?: 0)
    } catch (_: Exception) { null }

    private fun upsertStation(s: BikeStation) {
        val idx = stations.indexOfFirst { it.id == s.id }
        if (idx >= 0) stations[idx] = s else stations.add(s)
    }

    private fun loadPublicMessages() {
        publicMessages.clear()
        prefs.getString(KEY_PUBLIC_MESSAGES, null)?.let { raw ->
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    publicMessages.add(ChatMessage(obj.getString("id"), obj.getString("from"), obj.getString("text"), obj.getBoolean("mine"), obj.optBoolean("isSystem", false)))
                }
            } catch (_: Exception) {}
        }
    }

    private fun savePublicMessages() {
        val arr = JSONArray()
        publicMessages.forEach { msg ->
            arr.put(JSONObject().apply { put("id", msg.id); put("from", msg.from); put("text", msg.text); put("mine", msg.mine); put("isSystem", msg.isSystem) })
        }
        prefs.edit().putString(KEY_PUBLIC_MESSAGES, arr.toString()).apply()
    }

    private fun loadDmMessages() {
        dmMessages.clear()
        prefs.getString(KEY_DM_MESSAGES, null)?.let { raw ->
            try {
                val root = JSONObject(raw)
                root.keys().forEach { peerId ->
                    val arr = root.getJSONArray(peerId)
                    val list = mutableStateListOf<ChatMessage>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(ChatMessage(obj.getString("id"), obj.getString("from"), obj.getString("text"), obj.getBoolean("mine"), obj.optBoolean("isSystem", false)))
                    }
                    dmMessages[peerId] = list
                }
            } catch (_: Exception) {}
        }
    }

    private fun saveDmMessages() {
        val root = JSONObject()
        dmMessages.forEach { (id, msgs) ->
            val arr = JSONArray()
            msgs.forEach { msg ->
                arr.put(JSONObject().apply { put("id", msg.id); put("from", msg.from); put("text", msg.text); put("mine", msg.mine); put("isSystem", msg.isSystem) })
            }
            root.put(id, arr)
        }
        prefs.edit().putString(KEY_DM_MESSAGES, root.toString()).apply()
    }

    private fun loadStations() {
        stations.clear()
        prefs.getString(KEY_STATIONS, null)?.let { raw ->
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    stations.add(BikeStation(obj.getString("id"), obj.getString("name"), obj.getInt("capacity"), obj.getInt("available")))
                }
            } catch (_: Exception) {}
        }
    }

    private fun saveStations() {
        val arr = JSONArray()
        stations.forEach { st ->
            arr.put(JSONObject().apply { put("id", st.id); put("name", st.name); put("available", st.available); put("capacity", st.capacity) })
        }
        prefs.edit().putString(KEY_STATIONS, arr.toString()).apply()
    }

    private fun clearLocalData() {
        prefs.edit().remove(KEY_PUBLIC_MESSAGES).remove(KEY_DM_MESSAGES).remove(KEY_STATIONS).remove(KEY_SELECTED_DM_PEER).apply()
        publicMessages.clear(); dmMessages.clear(); unreadDms.clear(); stations.clear()
        selectedDmPeerId = null
        stations.addAll(defaultStations()); saveStations()
        showTransient("History cleared")
    }
}

// ─── Models ─────────────────────────────────────────────────────────────────

data class Peer(val id: String, val name: String, val connected: Boolean)
data class ChatMessage(val id: String, val from: String, val text: String, val mine: Boolean, val isSystem: Boolean = false)
data class BikeStation(val id: String, val name: String, val capacity: Int, val available: Int)

// ─── UI ─────────────────────────────────────────────────────────────────────

@Composable
private fun AppShell(
    nickname: String, isConnected: Boolean, isConnecting: Boolean, transientNotification: String?,
    peers: List<Peer>, selectedDmPeerId: String?, publicMessages: List<ChatMessage>,
    dmMessages: Map<String, List<ChatMessage>>, unreadDms: List<String>,
    stations: List<BikeStation>, proximityMeters: Float, onProximityChange: (Float) -> Unit,
    publicCooldownLeft: Int, dmCooldownLeft: Int,
    showNicknameDialog: Boolean, onRequestNickname: () -> Unit, onSaveNickname: (String) -> Unit,
    onDismissNicknameDialog: () -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit,
    onClearLocalData: () -> Unit, onSelectDmPeer: (String) -> Unit,
    onSendPublic: (String) -> Unit, onSendDm: (String, String) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }

    Box(
        Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        Column(Modifier.fillMaxSize()) {

            // ─── Header (respects status bar) ───────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF000000), DarkHeader))
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Logo (vector drawable — transparent bg, purple+white)
                    Image(
                        painter = painterResource(id = R.drawable.chatbox_logo),
                        contentDescription = "ChatBox",
                        modifier = Modifier
                            .height(44.dp)
                            .weight(1f),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                    // Status column
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (nickname.isBlank()) "No identity" else nickname,
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentPurple,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                isConnecting -> "Searching..."
                                isConnected  -> "Online · ${peers.count { it.connected }} peers"
                                else         -> "Offline"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isConnected) GreenOnline else TextSecondary
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onRequestNickname) {
                        Icon(Icons.Default.Person, null, tint = TextSecondary)
                    }
                    TextButton(onClick = { if (isConnected || isConnecting) onDisconnect() else onConnect() }) {
                        if (isConnecting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentPurple)
                        else Text(if (isConnected) "Disconnect" else "Connect", color = AccentPurple, fontSize = 13.sp)
                    }
                    IconButton(onClick = onClearLocalData) {
                        Icon(Icons.Default.Delete, null, tint = TextSecondary)
                    }
                }
            }

            // ─── Tab bar ────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabButton("Public",   tab == 0, false)              { tab = 0 }
                TabButton("DMs",      tab == 1, unreadDms.isNotEmpty()) { tab = 1 }
                TabButton("Stations", tab == 2, false)              { tab = 2 }
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))

            // ─── Content ────────────────────────────────────────────────
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> PublicChat(Modifier.fillMaxSize(), publicMessages, publicCooldownLeft, onSendPublic)
                    1 -> DmChat(Modifier.fillMaxSize(), peers, selectedDmPeerId, dmMessages, unreadDms, dmCooldownLeft, onSelectDmPeer, onSendDm)
                    2 -> StationsView(Modifier.fillMaxSize(), stations, proximityMeters, onProximityChange)
                }
            }
        }

        // ─── Toast ──────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = transientNotification != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit  = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
        ) {
            Surface(
                color = AccentPurple.copy(alpha = 0.92f),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp
            ) {
                Text(
                    transientNotification ?: "",
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
    if (showNicknameDialog) NicknameDialog(nickname, onSaveNickname, onDismissNicknameDialog)
}

@Composable
private fun TabButton(text: String, selected: Boolean, hasNotification: Boolean, onClick: () -> Unit) {
    val bgColor = if (selected) AccentPurple.copy(alpha = 0.25f) else DarkCard
    val textColor = if (selected) AccentPurple else TextSecondary
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = textColor, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            if (hasNotification) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(7.dp).background(Color.Red, CircleShape))
            }
        }
    }
}

@Composable
private fun NicknameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        confirmButton = {
            Button(onClick = { onSave(text) }, colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)) {
                Text("Save")
            }
        },
        title = { Text("Your identity", color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Nickname") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPurple,
                    unfocusedBorderColor = TextSecondary,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
        }
    )
}

@Composable
private fun PublicChat(modifier: Modifier, messages: List<ChatMessage>, cooldownLeft: Int, onSend: (String) -> Unit) {
    Column(modifier.padding(12.dp)) {
        ChatList(Modifier.weight(1f), messages)
        ChatComposer("Broadcast...", cooldownLeft = cooldownLeft, onSend = onSend)
    }
}

@Composable
private fun DmChat(
    modifier: Modifier, peers: List<Peer>, selectedId: String?,
    dmMessages: Map<String, List<ChatMessage>>, unread: List<String>,
    cooldownLeft: Int, onSelect: (String) -> Unit, onSend: (String, String) -> Unit
) {
    val available = peers.filter { it.connected || dmMessages.containsKey(it.id) }
    val selected  = available.firstOrNull { it.id == selectedId }
    Column(modifier.padding(12.dp)) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DarkCard)
        ) {
            items(available.size) { idx ->
                val p = available[idx]; val isSel = p.id == selectedId; val hasUn = unread.contains(p.id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(p.id) }
                        .background(if (isSel) AccentPurple.copy(0.15f) else Color.Transparent)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).background(if (p.connected) GreenOnline else TextSecondary, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Text(p.name, Modifier.weight(1f), color = TextPrimary, fontWeight = if (hasUn) FontWeight.Bold else FontWeight.Normal)
                    if (hasUn) Box(Modifier.size(8.dp).background(Color.Red, CircleShape))
                }
                if (idx < available.size - 1) HorizontalDivider(color = Color(0xFF2A2A2A))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (selected == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Select someone to chat", color = TextSecondary)
            }
        } else {
            Text("DM · ${selected.name}", color = getColorForName(selected.name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            ChatList(Modifier.weight(1f), dmMessages[selectedId].orEmpty())
            ChatComposer("Message...", selected.connected, cooldownLeft, onSend = { onSend(selected.id, it) })
        }
    }
}

@Composable
private fun StationsView(modifier: Modifier, stations: List<BikeStation>, proximity: Float, onChange: (Float) -> Unit) {
    Column(modifier.padding(12.dp)) {
        Text("Proximity: ${proximity.toInt()}m", color = TextPrimary, fontWeight = FontWeight.Medium)
        Slider(
            value = proximity, onValueChange = onChange, valueRange = 0f..30f,
            colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(stations.size) { idx ->
                val s = stations[idx]; val dist = abs(idx * 10f - proximity).toInt(); val near = dist < 5
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (near) AccentPurple.copy(0.15f) else DarkCard),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium, color = if (near) AccentPurple else TextPrimary, fontWeight = FontWeight.Bold)
                        Text("Available: ${s.available}/${s.capacity} · ${dist}m", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        LinearProgressIndicator(
                            progress = { if (s.capacity > 0) s.available.toFloat() / s.capacity else 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(4.dp)),
                            color = if (near) AccentPurple else Color(0xFF4A4A6A),
                            trackColor = Color(0xFF2A2A3A)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatList(modifier: Modifier, messages: List<ChatMessage>) {
    LazyColumn(modifier, reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp)) {
        val rev = messages.asReversed()
        items(rev.size) { idx -> ChatBubble(rev[idx]); Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    if (msg.isSystem) {
        Text(msg.text, Modifier.fillMaxWidth().padding(4.dp), TextSecondary, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        return
    }
    val align = if (msg.mine) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Surface(
            color = if (msg.mine) BubbleMine else BubbleOther,
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (msg.mine) 14.dp else 4.dp,
                bottomEnd   = if (msg.mine) 4.dp  else 14.dp
            ),
            shadowElevation = 2.dp
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 280.dp)) {
                if (!msg.mine) Text(msg.from, color = getColorForName(msg.from), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
        }
    }
}

@Composable
private fun ChatComposer(hint: String, enabled: Boolean = true, cooldownLeft: Int = 0, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val blocked = cooldownLeft > 0
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    if (blocked) "Wait ${cooldownLeft}s..." else hint,
                    color = if (blocked) Color(0xFFE57373) else TextSecondary
                )
            },
            enabled = enabled && !blocked,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = AccentPurple,
                unfocusedBorderColor = Color(0xFF3A3A3A),
                focusedTextColor     = TextPrimary,
                unfocusedTextColor   = TextPrimary,
                disabledBorderColor  = Color(0xFF3A3A3A),
                disabledTextColor    = TextSecondary,
                disabledContainerColor = DarkCard
            )
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = {
                if (text.isNotBlank()) { onSend(text.trim()); text = "" }
            },
            enabled = enabled && !blocked && text.isNotBlank(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = AccentPurple,
                disabledContainerColor = Color(0xFF3A3A4A)
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White)
        }
    }
}

private fun getColorForName(name: String): Color {
    val h = abs(name.hashCode())
    val hue = (h % 360).toFloat()
    return Color(
        android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.90f))
    )
}
