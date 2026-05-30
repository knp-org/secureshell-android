package org.knp.secureshell.ui.screens

import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.knp.secureshell.data.db.entity.PeerEntity
import org.knp.secureshell.data.repository.AppRepository
import org.knp.secureshell.sync.DESKTOP_SYNC_LISTENER_PORT
import org.knp.secureshell.sync.DeviceIdentity
import org.knp.secureshell.sync.DiscoveryManager
import org.knp.secureshell.sync.LanSyncManager
import org.knp.secureshell.sync.PairingManager
import org.knp.secureshell.sync.PairingResult
import org.knp.secureshell.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

private fun NsdServiceInfo.hostAddress(): String? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        hostAddresses.firstOrNull()?.hostAddress
    } else {
        @Suppress("DEPRECATION")
        host?.hostAddress
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(repo: AppRepository, syncManager: LanSyncManager) {
    val c = LocalAppColors.current
    val peers by repo.peers.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val discoveryManager = remember { DiscoveryManager(context) }

    val discoveredPeers = remember { mutableStateMapOf<String, NsdServiceInfo>() }

    DisposableEffect(Unit) {
        discoveryManager.startDiscovery(object : DiscoveryManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                discoveredPeers[info.serviceName] = info
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                discoveredPeers.remove(info.serviceName)
            }
        })
        onDispose { discoveryManager.stopDiscovery() }
    }

    var isSyncing by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<String?>(null) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var showPairDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var pairingResult by remember { mutableStateOf<PairingResult?>(null) }
    var pairingManager by remember { mutableStateOf<PairingManager?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LAN Sync", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showManualDialog = true }) {
                        Icon(Icons.Filled.SettingsEthernet, "Manual sync")
                    }
                    IconButton(onClick = { showPairDialog = true }) {
                        Icon(Icons.Filled.QrCodeScanner, "Pair")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgPrimary, titleContentColor = c.textPrimary)
            )
        },
        containerColor = c.bgApp
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SyncStatusCard() }
            syncResult?.let { msg -> item { ResultBanner(msg, Success) } }
            syncError?.let { msg -> item { ResultBanner(msg, Error) } }

            if (peers.isNotEmpty()) {
                item { Text("PAIRED DEVICES", style = MaterialTheme.typography.labelSmall, color = c.textMuted) }
                items(peers) { peer ->
                    val discovered = discoveredPeers.values.find { it.serviceName.contains(peer.pkHex.take(8)) }
                    PeerCard(
                        peer = peer,
                        discovered = discovered,
                        isSyncing = isSyncing,
                        onSync = {
                            val host = discovered?.hostAddress() ?: peer.lastSyncHost
                            val port = DESKTOP_SYNC_LISTENER_PORT
                            if (host.isNullOrBlank()) {
                                syncError =
                                    "No address for this peer. Scan the desktop pairing QR again (with the app open), or use Manual sync with the desktop IP."
                                return@PeerCard
                            }
                            scope.launch {
                                isSyncing = true
                                syncResult = null
                                syncError = null
                                try {
                                    val devicePkHex = DeviceIdentity.getPkHex(context)
                                    val result = syncManager.syncWithPeer(
                                        host = host,
                                        port = port,
                                        ourPkHex = devicePkHex,
                                        peerPkHex = peer.pkHex
                                    )
                                    repo.updatePeerLastSyncEndpoint(peer.id, host, port)
                                    syncResult = "Synced: ↓${result.pulled} ↑${result.pushed}"
                                } catch (e: Exception) {
                                    syncError = e.message ?: "Sync failed"
                                } finally {
                                    isSyncing = false
                                }
                            }
                        },
                        onRemove = { scope.launch { repo.deletePeer(peer.id) } }
                    )
                }
            }
        }
    }

    if (showPairDialog) {
        ScannerView(
            onClose = { showPairDialog = false },
            onScan = { qrPayload ->
                showPairDialog = false
                scope.launch {
                    try {
                        val pm = PairingManager(context)
                        pairingManager = pm
                        val qr = pm.parseQr(qrPayload)
                        val result = pm.startPairing(qr)
                        pairingResult = result
                    } catch (e: Exception) {
                        syncError = "Pairing failed: ${e.message}"
                        pairingManager?.close()
                        pairingManager = null
                    }
                }
            }
        )
    }

    // SAS confirmation dialog
    pairingResult?.let { pr ->
        SasConfirmationDialog(
            sas = pr.sas,
            onConfirm = {
                val currentPm = pairingManager
                pairingResult = null
                scope.launch {
                    isSyncing = true
                    syncResult = null
                    syncError = null
                    try {
                        currentPm?.confirmPairing()

                        // Run initial sync over the pairing connection
                        val din = currentPm?.getDataIn()
                        val dout = currentPm?.getDataOut()
                        val deviceSecret = currentPm?.getDeviceSecret()
                        if (din != null && dout != null && deviceSecret != null) {
                            val result = syncManager.syncOverExistingConnection(din, dout, deviceSecret)
                            syncResult = "Paired & synced: ↓${result.pulled} ↑${result.pushed}"
                        } else {
                            syncResult = "Paired successfully"
                        }

                        // Store the desktop peer (pk = desktop's public key for mDNS)
                        val peerId = UUID.randomUUID().toString()
                        repo.upsertPeer(
                            PeerEntity(
                                id = peerId,
                                pkHex = pr.desktopPkHex,
                                label = "Desktop (${pr.ip})",
                                pairedAt = Instant.now().toString(),
                                lastSyncHost = pr.ip,
                                lastSyncPort = pr.syncPort,
                            )
                        )
                    } catch (e: Exception) {
                        syncError = "Sync after pairing failed: ${e.message}"
                    } finally {
                        isSyncing = false
                        currentPm?.close()
                        pairingManager = null
                    }
                }
            },
            onReject = {
                val currentPm = pairingManager
                pairingResult = null
                scope.launch {
                    currentPm?.rejectPairing()
                    pairingManager = null
                    syncError = "Pairing rejected — codes did not match"
                }
            }
        )
    }

    if (showManualDialog) {
        ManualSyncDialog(
            peers = peers,
            onDismiss = { showManualDialog = false },
            onSync = { host, port, pkHex, peerLabel ->
                showManualDialog = false
                scope.launch {
                    isSyncing = true
                    syncResult = null
                    syncError = null
                    try {
                        val existing = peers.firstOrNull { it.pkHex == pkHex }
                        val peerId = existing?.id ?: UUID.randomUUID().toString()
                        if (existing == null) {
                            repo.upsertPeer(
                                PeerEntity(
                                    id = peerId,
                                    pkHex = pkHex,
                                    label = peerLabel.ifBlank { "Desktop ($host)" },
                                    pairedAt = Instant.now().toString(),
                                    lastSyncHost = host,
                                    lastSyncPort = port,
                                )
                            )
                        }

                        val devicePkHex = DeviceIdentity.getPkHex(context)
                        val result = syncManager.syncWithPeer(
                            host = host,
                            port = port,
                            ourPkHex = devicePkHex,
                            peerPkHex = pkHex,
                        )
                        repo.updatePeerLastSyncEndpoint(peerId, host, port)
                        syncResult = "Synced: ↓${result.pulled} ↑${result.pushed}"
                    } catch (e: Exception) {
                        syncError = e.message ?: "Sync failed"
                    } finally {
                        isSyncing = false
                    }
                }
            },
        )
    }
}

@Composable
private fun SyncStatusCard() {
    val c = LocalAppColors.current
    Card(colors = CardDefaults.cardColors(containerColor = c.bgCard), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(c.goldPrimary, c.goldDark))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.SyncAlt, null, tint = c.bgApp, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("LAN Sync", style = MaterialTheme.typography.titleLarge, color = c.textPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Desktop listens on port $DESKTOP_SYNC_LISTENER_PORT. mDNS may also find it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultBanner(msg: String, color: androidx.compose.ui.graphics.Color) {
    val c = LocalAppColors.current
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(msg, color = color, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSyncDialog(
    peers: List<PeerEntity>,
    onDismiss: () -> Unit,
    onSync: (host: String, port: Int, pkHex: String, label: String) -> Unit,
) {
    val c = LocalAppColors.current
    var selectedPeer by remember(peers) { mutableStateOf(peers.firstOrNull()) }
    var host by remember { mutableStateOf(selectedPeer?.lastSyncHost ?: "") }
    var port by remember { mutableStateOf(DESKTOP_SYNC_LISTENER_PORT.toString()) }
    var pkHex by remember { mutableStateOf(selectedPeer?.pkHex ?: "") }
    var label by remember { mutableStateOf(selectedPeer?.label ?: "") }
    var peerMenuOpen by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedPeer) {
        selectedPeer?.let {
            host = it.lastSyncHost ?: host
            port = DESKTOP_SYNC_LISTENER_PORT.toString()
            pkHex = it.pkHex
            label = it.label
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text("Manual Sync", color = c.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Default sync port is $DESKTOP_SYNC_LISTENER_PORT (same as the desktop app). Leave Port blank to use it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )

                if (peers.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = peerMenuOpen,
                        onExpandedChange = { peerMenuOpen = !peerMenuOpen },
                    ) {
                        OutlinedTextField(
                            value = selectedPeer?.label ?: "Select peer",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Peer") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = peerMenuOpen) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            colors = goldTextFieldColors(),
                        )
                        ExposedDropdownMenu(
                            expanded = peerMenuOpen,
                            onDismissRequest = { peerMenuOpen = false },
                        ) {
                            peers.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.label) },
                                    onClick = {
                                        selectedPeer = p
                                        peerMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("Host / IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = goldTextFieldColors(),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port (default $DESKTOP_SYNC_LISTENER_PORT)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = goldTextFieldColors(),
                )

                if (peers.isEmpty()) {
                    OutlinedTextField(
                        value = pkHex, onValueChange = { pkHex = it.trim() },
                        label = { Text("Desktop public key (hex)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = goldTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = label, onValueChange = { label = it },
                        label = { Text("Label (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = goldTextFieldColors(),
                    )
                }

                validationError?.let { Text(it, color = Error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = port.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                        ?: DESKTOP_SYNC_LISTENER_PORT
                    when {
                        host.isBlank() -> validationError = "Host is required"
                        p !in 1..65535 -> validationError = "Port must be 1-65535"
                        pkHex.isBlank() -> validationError = "Desktop public key required (pair via QR first, or paste hex)"
                        pkHex.length != 64 || !pkHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ->
                            validationError = "Public key must be 64 hex chars"
                        else -> onSync(host.trim(), p, pkHex.lowercase(), label)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = c.goldPrimary),
            ) {
                Text("Sync now", color = c.bgApp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.textMuted) }
        },
    )
}

@Composable
private fun goldTextFieldColors(): TextFieldColors {
    val c = LocalAppColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.goldPrimary,
        unfocusedBorderColor = c.border,
        cursorColor = c.goldPrimary,
        focusedLabelColor = c.goldPrimary,
        unfocusedLabelColor = c.textMuted,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary,
    )
}

@Composable
private fun PeerCard(
    peer: PeerEntity,
    discovered: NsdServiceInfo?,
    isSyncing: Boolean,
    onSync: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = LocalAppColors.current
    var showConfirm by remember { mutableStateOf(false) }
    val hasStoredHost = !peer.lastSyncHost.isNullOrBlank()

    if (showConfirm) {
        DeleteConfirmDialog(
            title = "Remove device",
            message = "Remove \"${peer.label}\" from paired devices?",
            onConfirm = { showConfirm = false; onRemove() },
            onDismiss = { showConfirm = false },
        )
    }
    val canSync = discovered != null || hasStoredHost
    Card(colors = CardDefaults.cardColors(containerColor = c.bgCard), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Icon(Icons.Filled.Computer, null, tint = if (canSync) c.goldPrimary else c.textMuted)
                if (canSync) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Success).align(Alignment.BottomEnd))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.label, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                val status = when {
                    discovered != null -> "Online (mDNS)"
                    hasStoredHost -> "Saved IP — port ${peer.lastSyncPort?.takeIf { it > 0 } ?: DESKTOP_SYNC_LISTENER_PORT}"
                    else -> "Offline"
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (canSync) Success else c.textMuted)
            }
            IconButton(onClick = onSync, enabled = !isSyncing && canSync) {
                if (isSyncing) CircularProgressIndicator(Modifier.size(20.dp), c.goldPrimary, 2.dp)
                else Icon(Icons.Filled.Sync, "Sync", tint = if (canSync) c.goldPrimary else c.textMuted)
            }
            IconButton(onClick = { showConfirm = true }) { Icon(Icons.Filled.Delete, "Remove", tint = Error.copy(0.7f)) }
        }
    }
}

@Composable
private fun SasConfirmationDialog(
    sas: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = {},
        containerColor = c.bgSecondary,
        title = {
            Text(
                "Verify Pairing Code",
                color = c.textPrimary,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Confirm this code matches the one shown on your desktop:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                    textAlign = TextAlign.Center,
                )
                Surface(
                    color = c.bgPrimary,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = sas,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.goldPrimary,
                        textAlign = TextAlign.Center,
                        letterSpacing = 8.sp,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                Text(
                    "If the codes don't match, someone may be intercepting the connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Error.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = c.goldPrimary),
            ) {
                Text("Match — Pair", color = c.bgApp)
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("Don't match", color = Error)
            }
        },
    )
}
