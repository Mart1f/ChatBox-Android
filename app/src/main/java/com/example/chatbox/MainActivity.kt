package com.example.chatbox

import android.Manifest
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import java.nio.charset.Charset
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private lateinit var connectionsClient: ConnectionsClient
    private val serviceId = "com.example.chatbox.nearby"
    private val myName = "Node-" + Build.MODEL.replace(" ", "_") + "-" + (1000..9999).random()
    private val strategy = Strategy.P2P_CLUSTER

    private val peers = mutableStateListOf<Peer>()
    private val publicMessages = mutableStateListOf<ChatMessage>()
    private val dmMessages = mutableStateMapOf<String, MutableList<ChatMessage>>()
    private val stations = mutableStateListOf<BikeStation>()

    private var selectedDmPeerId by mutableStateOf<String?>(null)
    private var simulationMode by mutableStateOf(true)
    private var runningRealNetwork by mutableStateOf(false)

    private var proximityMeters by mutableFloatStateOf(15f)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var simJob: Job? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                addSystemMsg("⚠️ Permisos denegados: $denied")
            } else {
                addSystemMsg("✅ Permisos OK")
                if (!simulationMode) startAutoNetwork()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionsClient = Nearby.getConnectionsClient(this)

        stations.addAll(
            listOf(
                BikeStation("ST-001", "Gare Centrale", 18, 6, 0f),
                BikeStation("ST-002", "Campus IMT", 22, 14, 12f),
                BikeStation("ST-003", "Centre Ville", 12, 3, 25f)
            )
        )

        startSimulation()

        setContent {
            MaterialTheme {
                AppShell(
                    myName = myName,
                    simulationMode = simulationMode,
                    runningReal = runningRealNetwork,
                    peers = peers,
                    selectedDmPeerId = selectedDmPeerId,
                    publicMessages = publicMessages,
                    dmMessages = dmMessages,
                    stations = stations,
                    proximityMeters = proximityMeters,
                    onProximityChange = { proximityMeters = it },
                    onToggleMode = { sim ->
                        simulationMode = sim
                        if (simulationMode) {
                            stopRealNetwork()
                            startSimulation()
                        } else {
                            stopSimulation()
                            requestAllNeededPermissions()
                        }
                    },
                    onSelectDmPeer = { selectedDmPeerId = it },
                    onSendPublic = { sendPublic(it) },
                    onSendDm = { peerId, txt -> sendDm(peerId, txt) },
                    onStartReal = { startAutoNetwork() },
                    onStopReal = { stopRealNetwork() },
                    onRequestPerms = { requestAllNeededPermissions() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        stopRealNetwork()
    }

    // ---------- Permissions ----------
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

    // ---------- Real network ----------
    private fun startAutoNetwork() {
        if (!hasAllNeededPermissions()) {
            addSystemMsg("⚠️ Falta permisos. Dale al botón de permisos.")
            return
        }

        stopRealNetwork()
        simulationMode = false
        runningRealNetwork = true
        addSystemMsg("🟢 Auto-network: advertising + discovery")

        val adv = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(myName, serviceId, connectionLifecycleCallback, adv)
            .addOnFailureListener { addSystemMsg("❌ Advertising error: ${it.message}") }

        val disc = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, disc)
            .addOnFailureListener { addSystemMsg("❌ Discovery error: ${it.message}") }
    }

    private fun stopRealNetwork() {
        runningRealNetwork = false
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        } catch (_: Exception) {}
        addSystemMsg("⏹️ Real network stopped")
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            upsertPeer(endpointId, info.endpointName, connected = false, simulated = false)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                upsertPeer(endpointId, peerName(endpointId), connected = true, simulated = false)
                addSystemMsg("✅ Connected: ${peerName(endpointId)}")
            } else {
                addSystemMsg("❌ Conn failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            markDisconnected(endpointId)
            addSystemMsg("🔌 Disconnected: ${peerName(endpointId)}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            upsertPeer(endpointId, info.endpointName, connected = false, simulated = false)
            connectionsClient.requestConnection(myName, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            markDisconnected(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val raw = payload.asBytes()?.toString(Charset.defaultCharset()).orEmpty()

            val parts = raw.split("|", limit = 5)
            if (parts.size < 5) return

            val type = parts[0]
            val scope = parts[1]
            val msgId = parts[3]
            val text = parts[4]
            val fromName = peerName(endpointId)

            if (type == "CHAT" && scope == "PUBLIC") {
                publicMessages.add(ChatMessage(msgId, fromName, text, mine = false))
            } else if (type == "CHAT" && scope == "DM") {
                val list = dmMessages.getOrPut(endpointId) { mutableListOf() }
                list.add(ChatMessage(msgId, fromName, text, mine = false))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // ---------- Send ----------
    private fun sendPublic(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return

        val msgId = UUID.randomUUID().toString().take(8)
        publicMessages.add(ChatMessage(msgId, "Yo", t, mine = true))

        if (simulationMode) {
            simulateIncomingPublic()
            return
        }

        val ids = peers.filter { it.connected && !it.isSimulated }.map { it.id }
        if (ids.isEmpty()) {
            addSystemMsg("⚠️ No hay peers conectados")
            return
        }

        val payload = Payload.fromBytes("CHAT|PUBLIC|*|$msgId|$t".toByteArray())
        connectionsClient.sendPayload(ids, payload)
    }

    private fun sendDm(peerId: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return

        val peer = peers.firstOrNull { it.id == peerId } ?: return
        val msgId = UUID.randomUUID().toString().take(8)
        val list = dmMessages.getOrPut(peerId) { mutableListOf() }
        list.add(ChatMessage(msgId, "Yo", t, mine = true))

        if (simulationMode) {
            simulateIncomingDm(peerId, peer.name)
            return
        }

        if (!peer.connected) {
            addSystemMsg("⚠️ Peer no conectado")
            return
        }

        val payload = Payload.fromBytes("CHAT|DM|$peerId|$msgId|$t".toByteArray())
        connectionsClient.sendPayload(listOf(peerId), payload)
    }

    // ---------- Simulation ----------
    private fun startSimulation() {
        if (!simulationMode) return
        if (peers.none { it.isSimulated }) {
            peers.addAll(
                listOf(
                    Peer("SIM-A", "Laura", true, true),
                    Peer("SIM-B", "Nico", true, true),
                    Peer("SIM-C", "Sara", true, true)
                )
            )
            addSystemMsg("🧪 Simulation ON")
        }

        simJob?.cancel()
        simJob = scope.launch {
            while (isActive && simulationMode) {
                delay(2500)
                stations.forEach { st ->
                    val dist = abs(st.x - proximityMeters)
                    if (dist < 5f) {
                        val delta = Random.nextInt(-2, 3)
                        st.available = (st.available + delta).coerceIn(0, st.capacity)
                    }
                }
            }
        }
    }

    private fun stopSimulation() {
        simJob?.cancel()
        simJob = null
        peers.removeAll { it.isSimulated }
        addSystemMsg("🧪 Simulation OFF")
    }

    private fun simulateIncomingPublic() {
        val p = peers.filter { it.isSimulated }.randomOrNull() ?: return
        val msgId = UUID.randomUUID().toString().take(8)
        scope.launch {
            delay(700)
            publicMessages.add(ChatMessage(msgId, p.name, listOf("ok!", "jajaja", "probando 😄", "hola!", "nice").random(), false))
        }
    }

    private fun simulateIncomingDm(peerId: String, peerName: String) {
        val msgId = UUID.randomUUID().toString().take(8)
        val list = dmMessages.getOrPut(peerId) { mutableListOf() }
        scope.launch {
            delay(600)
            list.add(ChatMessage(msgId, peerName, listOf("dale", "te leo", "ok", "funciona").random(), false))
        }
    }

    // ---------- Helpers ----------
    private fun addSystemMsg(text: String) {
        publicMessages.add(ChatMessage(UUID.randomUUID().toString().take(8), "Sistema", text, mine = false, isSystem = true))
    }

    private fun peerName(endpointId: String): String =
        peers.firstOrNull { it.id == endpointId }?.name ?: endpointId.take(8)

    private fun upsertPeer(id: String, name: String, connected: Boolean, simulated: Boolean) {
        val idx = peers.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val old = peers[idx]
            peers[idx] = old.copy(name = name, connected = connected, isSimulated = simulated)
        } else {
            peers.add(Peer(id, name, connected, simulated))
        }
    }

    private fun markDisconnected(id: String) {
        val idx = peers.indexOfFirst { it.id == id }
        if (idx >= 0) peers[idx] = peers[idx].copy(connected = false)
    }
}

// ---------- Models ----------
data class Peer(val id: String, val name: String, val connected: Boolean, val isSimulated: Boolean)
data class ChatMessage(val id: String, val from: String, val text: String, val mine: Boolean, val isSystem: Boolean = false)
data class BikeStation(val id: String, val name: String, val capacity: Int, var available: Int, val x: Float)

// ---------- UI ----------
@Composable
private fun AppShell(
    myName: String,
    simulationMode: Boolean,
    runningReal: Boolean,
    peers: List<Peer>,
    selectedDmPeerId: String?,
    publicMessages: List<ChatMessage>,
    dmMessages: Map<String, List<ChatMessage>>,
    stations: List<BikeStation>,
    proximityMeters: Float,
    onProximityChange: (Float) -> Unit,
    onToggleMode: (Boolean) -> Unit,
    onSelectDmPeer: (String) -> Unit,
    onSendPublic: (String) -> Unit,
    onSendDm: (String, String) -> Unit,
    onStartReal: () -> Unit,
    onStopReal: () -> Unit,
    onRequestPerms: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) } // 0 public, 1 dm, 2 stations

    Column(Modifier.fillMaxSize()) {

        // ---- Top bar (stable) ----
        Surface(tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ChatBox", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = when {
                            simulationMode -> "Simulación · $myName"
                            runningReal -> "Real (auto) · $myName"
                            else -> "Real · $myName"
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Botones pequeños
                TextButton(onClick = onRequestPerms) { Text("Perms") }

                if (!simulationMode) {
                    IconButton(onClick = onStartReal) { Icon(Icons.Default.PlayArrow, null) }
                    IconButton(onClick = onStopReal) { Icon(Icons.Default.Close, null) }
                }

                TextButton(onClick = { onToggleMode(!simulationMode) }) {
                    Text(if (simulationMode) "REAL" else "SIM")
                }
            }
        }

        // ---- Tabs (stable) ----
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabButton(text = "Public", selected = tab == 0) { tab = 0 }
            TabButton(text = "DM", selected = tab == 1) { tab = 1 }
            TabButton(text = "Stations", selected = tab == 2) { tab = 2 }
        }

        Divider()

        // ---- Content ----
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
}

@Composable
private fun TabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        color = bg,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun PublicChat(
    modifier: Modifier,
    peers: List<Peer>,
    messages: List<ChatMessage>,
    onSend: (String) -> Unit
) {
    Column(modifier.padding(12.dp)) {
        Text("Peers conectados: ${peers.count { it.connected }}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        ChatList(Modifier.weight(1f), messages)
        Spacer(Modifier.height(8.dp))
        ChatComposer("Mensaje al chat público…", onSend)
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
    val connectedPeers = peers.filter { it.connected }
    val selected = connectedPeers.firstOrNull { it.id == selectedPeerId }
    val list = dmMessages[selectedPeerId].orEmpty()

    Column(modifier.padding(12.dp)) {
        Text("Chats privados", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        // selector
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 120.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
        ) {
            items(count = connectedPeers.size) { idx ->
                val p = connectedPeers[idx]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectPeer(p.id) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(p.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.id == selectedPeerId) Text("✓")
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (selected == null) {
            Text("Selecciona un peer conectado para abrir un DM.")
            return
        }

        Text("DM con ${selected.name}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        ChatList(Modifier.weight(1f), list)
        Spacer(Modifier.height(8.dp))
        ChatComposer("Mensaje a ${selected.name}…") { txt -> onSend(selected.id, txt) }
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
        Text("Proximidad simulada: ${proximityMeters.toInt()}m", style = MaterialTheme.typography.titleMedium)
        Slider(value = proximityMeters, onValueChange = onProximityChange, valueRange = 0f..30f)
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(count = stations.size) { idx ->
                val st = stations[idx]
                val near = kotlin.math.abs(st.x - proximityMeters) < 5f
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (near) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(st.name, style = MaterialTheme.typography.titleMedium)
                        Text("Disponibles: ${st.available}/${st.capacity}")
                        if (near) Text("📡 Cerca: recibes updates", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatList(modifier: Modifier, messages: List<ChatMessage>) {
    val reversed = messages.asReversed()
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(count = reversed.size) { idx ->
            val m = reversed[idx]
            ChatBubble(m)
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

    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Surface(color = bg, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 320.dp)) {
                if (!msg.mine) {
                    Text(msg.from, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(2.dp))
                }
                Text(msg.text)
            }
        }
    }
}

@Composable
private fun ChatComposer(hint: String, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(hint) },
            singleLine = true
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { onSend(text); text = "" },
            modifier = Modifier.size(44.dp)
        ) {
            Icon(Icons.Default.Send, contentDescription = "Send")
        }
    }
}