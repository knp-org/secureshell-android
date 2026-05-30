package org.knp.secureshell.ui.screens

import android.annotation.SuppressLint
import android.util.Base64
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.knp.secureshell.data.db.entity.ConnectionEntity
import org.knp.secureshell.data.db.entity.GroupEntity
import org.knp.secureshell.data.db.entity.SnippetEntity
import org.knp.secureshell.data.repository.AppRepository
import org.knp.secureshell.data.crypto.VaultManager
import org.knp.secureshell.ssh.SshSessionManager
import org.knp.secureshell.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    repo: AppRepository,
    sshManager: SshSessionManager,
    initialConnection: ConnectionEntity? = null,
    terminalFontSize: Int = 14,
) {
    val c = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val snippets by repo.snippets.collectAsState(initial = emptyList())
    val snippetFolders by repo.groupsByIcon("snippet-folder").collectAsState(initial = emptyList())

    val connection by remember { mutableStateOf(initialConnection) }

    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sessionId by remember { mutableStateOf("") }
    var sessionLabel by remember { mutableStateOf<String?>(null) }
    var showSnippets by remember { mutableStateOf(false) }
    var needsReplay by remember { mutableStateOf(false) }
    var varPromptSnippet by remember { mutableStateOf<SnippetEntity?>(null) }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var termReady by remember { mutableStateOf(false) }
    val pendingOutput = remember { mutableListOf<ByteArray>() }

    var showVaultDialog by remember { mutableStateOf(false) }
    var vaultPassword by remember { mutableStateOf("") }
    val isVaultUnlocked by VaultManager.isUnlocked.collectAsState()

    // Reattach to an existing session if we have no initialConnection
    LaunchedEffect(Unit) {
        if (connection == null && sessionId.isBlank()) {
            val ids = sshManager.activeSessionIds()
            if (ids.isNotEmpty()) {
                val existingId = ids.first()
                if (sshManager.isConnected(existingId)) {
                    sessionId = existingId
                    sessionLabel = sshManager.sessionLabel(existingId)
                    isConnected = true
                    needsReplay = true
                }
            }
        }
    }

    // Auto-connect if initial connection provided
    LaunchedEffect(connection, isVaultUnlocked) {
        if (connection != null && !isConnected && !isConnecting) {
            // Check if we need to unlock the vault
            val conn = connection!!
            val needsUnlock = (VaultManager.isEnvelope(conn.password) || 
                              (!conn.keyId.isNullOrBlank() && 
                               repo.getKey(conn.keyId)?.let { VaultManager.isEnvelope(it.privateKey) } == true)) &&
                              !isVaultUnlocked

            if (needsUnlock) {
                showVaultDialog = true
                return@LaunchedEffect
            }

            isConnecting = true
            errorMessage = null
            sessionId = UUID.randomUUID().toString()
            try {
                var privateKey: String? = null
                var passphrase: String? = null
                if (!conn.keyId.isNullOrBlank()) {
                    val key = repo.getKey(conn.keyId)
                    if (key != null) {
                        privateKey = if (isVaultUnlocked) {
                            VaultManager.decrypt(key.privateKey, key.id) ?: key.privateKey
                        } else key.privateKey
                        
                        passphrase = if (isVaultUnlocked && !key.passphrase.isNullOrBlank()) {
                            VaultManager.decrypt(key.passphrase, key.id) ?: key.passphrase
                        } else key.passphrase
                    }
                }

                val decryptedPassword = if (isVaultUnlocked && !conn.password.isNullOrBlank()) {
                    VaultManager.decrypt(conn.password, conn.id) ?: conn.password
                } else conn.password

                sshManager.connect(
                    sessionId = sessionId,
                    host = conn.host,
                    port = conn.port,
                    username = conn.username,
                    password = decryptedPassword.ifBlank { null },
                    privateKey = privateKey,
                    passphrase = passphrase?.ifBlank { null }
                )
                isConnected = true
                isConnecting = false

                sshManager.startReading(
                    sessionId = sessionId,
                    onData = { data ->
                        val bytes = data.toByteArray(Charsets.UTF_8)
                        if (termReady) {
                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            webView?.evaluateJavascript("writeToTerminalB64('$b64')", null)
                        } else {
                            pendingOutput.add(bytes)
                        }
                    },
                    onClosed = {
                        isConnected = false
                    },
                )
            } catch (e: Exception) {
                errorMessage = e.message ?: "Connection failed"
                isConnecting = false
            }
        }
    }

    // When reattaching to an existing session, re-register the reader
    // callbacks so live output flows into the new WebView instance.
    LaunchedEffect(needsReplay, isConnected, sessionId) {
        if (needsReplay && isConnected && sessionId.isNotBlank()) {
            sshManager.startReading(
                sessionId = sessionId,
                onData = { data ->
                    val bytes = data.toByteArray(Charsets.UTF_8)
                    if (termReady) {
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        webView?.evaluateJavascript("writeToTerminalB64('$b64')", null)
                    } else {
                        pendingOutput.add(bytes)
                    }
                },
                onClosed = { isConnected = false },
            )
        }
    }

    if (showVaultDialog) {
        AlertDialog(
            onDismissRequest = { showVaultDialog = false },
            title = { Text("Vault Locked") },
            text = {
                Column {
                    Text("This connection is encrypted. Please enter your master password to unlock.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vaultPassword,
                        onValueChange = { vaultPassword = it },
                        label = { Text("Master Password") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        when (repo.unlockVault(vaultPassword)) {
                            AppRepository.VaultUnlockResult.Success,
                            AppRepository.VaultUnlockResult.SuccessAfterRotation,
                            AppRepository.VaultUnlockResult.SuccessWithNewPassword -> {
                                showVaultDialog = false
                                vaultPassword = ""
                            }
                            AppRepository.VaultUnlockResult.WrongPassword ->
                                errorMessage = "Incorrect master password"
                            AppRepository.VaultUnlockResult.NotInitialized ->
                                errorMessage = "Vault not initialized on this device"
                            AppRepository.VaultUnlockResult.RotationAdoptFailed ->
                                errorMessage =
                                    "Password accepted but key update failed. " +
                                    "Sync with the desktop app again, then unlock with your old password."
                        }
                    }
                }) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = { showVaultDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Flush pending output + replay snapshot when xterm is ready.
    LaunchedEffect(termReady) {
        if (termReady) {
            // On reattach, replay the ring-buffer snapshot first so the user
            // sees recent output from while the UI was detached.
            if (needsReplay && sessionId.isNotBlank()) {
                val snap = sshManager.snapshot(sessionId)
                if (snap.isNotEmpty()) {
                    val b64 = Base64.encodeToString(snap, Base64.NO_WRAP)
                    webView?.evaluateJavascript("writeToTerminalB64('$b64')", null)
                }
                needsReplay = false
            }

            if (pendingOutput.isNotEmpty()) {
                val merged = ByteArray(pendingOutput.sumOf { it.size })
                var offset = 0
                for (chunk in pendingOutput) {
                    System.arraycopy(chunk, 0, merged, offset, chunk.size)
                    offset += chunk.size
                }
                pendingOutput.clear()
                val b64 = Base64.encodeToString(merged, Base64.NO_WRAP)
                webView?.evaluateJavascript("writeToTerminalB64('$b64')", null)
            }
        }
    }

    // Keep the SSH session alive across activity pauses (app minimized,
    // screen off, etc.). Only disconnect when the activity is finishing
    // (user pressed back / navigated away), not just paused/minimized.
    // Also check if the connection dropped while we were in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(sessionId, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && sessionId.isNotBlank()) {
                if (!sshManager.isConnected(sessionId)) {
                    isConnected = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.terminalBg),
    ) {
        // Top bar with connection info + snippets toggle
        Surface(
            color = c.bgSecondary,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = connection?.let { "${it.username}@${it.host}" } ?: sessionLabel
                if (label != null) {
                    Icon(Icons.Filled.Terminal, null, tint = c.goldPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        color = c.textPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        "Terminal",
                        color = c.textPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = c.goldPrimary,
                        strokeWidth = 2.dp,
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Snippets toggle
                IconButton(onClick = { showSnippets = !showSnippets }) {
                    Icon(
                        Icons.Filled.Code,
                        "Snippets",
                        tint = if (showSnippets) c.goldPrimary else c.textMuted,
                    )
                }
            }
        }

        // Error banner
        errorMessage?.let { msg ->
            Surface(
                color = Error.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    msg,
                    color = Error,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Main content: terminal + optional snippets panel
        Row(modifier = Modifier.weight(1f)) {
            // Terminal WebView
            Box(modifier = Modifier.weight(1f)) {
                if (isConnected || isConnecting) {
                    TerminalWebView(
                        onWebViewReady = { wv -> webView = wv },
                        onTermReady = { termReady = true },
                        onInput = { data ->
                            if (isConnected) {
                                scope.launch { sshManager.write(sessionId, data) }
                            }
                        },
                        onResize = { cols, rows ->
                            if (isConnected) {
                                sshManager.resize(sessionId, cols, rows)
                            }
                        },
                        fontSize = terminalFontSize,
                        isDark = c.isDark,
                    )
                } else {
                    // No connection — show prompt
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.Terminal,
                                null,
                                modifier = Modifier.size(64.dp),
                                tint = c.textMuted,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("No active session", color = c.textSecondary)
                            Spacer(Modifier.height(8.dp))
                            Text("Tap a host to connect", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Snippets sidebar
            if (showSnippets) {
                Surface(
                    modifier = Modifier
                        .width(200.dp)
                        .fillMaxHeight(),
                    color = c.bgSecondary,
                ) {
                    Column {
                        Text(
                            "Snippets",
                            style = MaterialTheme.typography.labelLarge,
                            color = c.goldPrimary,
                            modifier = Modifier.padding(12.dp),
                        )
                        HorizontalDivider(color = c.border)
                        if (snippets.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("No snippets", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            val byGroup = snippets.groupBy { it.groupId }
                            val collapsedSidebar = remember { mutableStateMapOf<String, Boolean>() }

                            val sidebarItems = remember(snippets, snippetFolders) {
                                val result = mutableListOf<Any>()
                                for (folder in snippetFolders) {
                                    val items = byGroup[folder.id] ?: emptyList()
                                    result.add(folder to items.size)
                                    result.addAll(items)
                                }
                                val ungrouped = byGroup[null] ?: emptyList()
                                if (ungrouped.isNotEmpty() && snippetFolders.isNotEmpty()) {
                                    result.add(GroupEntity(id = "__ungrouped__", name = "Unfiled") to ungrouped.size)
                                }
                                result.addAll(ungrouped)
                                result
                            }

                            LazyColumn {
                                items(sidebarItems, key = { item ->
                                    when (item) {
                                        is Pair<*, *> -> "sf-${(item.first as GroupEntity).id}"
                                        is SnippetEntity -> "ss-${item.id}"
                                        else -> item.hashCode().toString()
                                    }
                                }) { item ->
                                    when (item) {
                                        is Pair<*, *> -> {
                                            val group = item.first as GroupEntity
                                            val count = item.second as Int
                                            val collapsed = collapsedSidebar[group.id] ?: false
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { collapsedSidebar[group.id] = !collapsed }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    if (collapsed) Icons.Filled.Folder else Icons.Filled.FolderOpen,
                                                    null,
                                                    tint = c.goldPrimary,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    group.name,
                                                    color = c.textMuted,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text("$count", color = c.textMuted, style = MaterialTheme.typography.labelSmall)
                                                Spacer(Modifier.width(4.dp))
                                                Icon(
                                                    if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                                    null,
                                                    tint = c.textMuted,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                        }
                                        is SnippetEntity -> {
                                            val parentId = item.groupId ?: "__ungrouped__"
                                            val parentCollapsed = collapsedSidebar[parentId] ?: false
                                            if (!parentCollapsed) {
                                                SnippetItem(item) {
                                                    if (isConnected) {
                                                        val vars = extractSnippetVars(item.command)
                                                        if (vars.isNotEmpty()) {
                                                            varPromptSnippet = item
                                                        } else {
                                                            scope.launch {
                                                                sshManager.write(sessionId, item.command + "\n")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Special-keys toolbar for terminal control keys
        if (isConnected) {
            TerminalToolbar(
                onKey = { seq ->
                    scope.launch { sshManager.write(sessionId, seq) }
                },
            )
        }
    }

    // Variable prompt dialog for snippets with {{variables}}
    varPromptSnippet?.let { snippet ->
        val vars = extractSnippetVars(snippet.command)
        val values = remember(snippet.id) {
            mutableStateMapOf<String, String>().apply {
                vars.forEach { put(it, "") }
            }
        }

        AlertDialog(
            onDismissRequest = { varPromptSnippet = null },
            containerColor = c.bgSecondary,
            title = { Text("Fill variables", color = c.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        snippet.name,
                        color = c.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    vars.forEach { v ->
                        OutlinedTextField(
                            value = values[v] ?: "",
                            onValueChange = { values[v] = it },
                            label = { Text(v) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = c.goldPrimary,
                                unfocusedBorderColor = c.border,
                                cursorColor = c.goldPrimary,
                                focusedLabelColor = c.goldPrimary,
                                unfocusedLabelColor = c.textMuted,
                                focusedTextColor = c.textPrimary,
                                unfocusedTextColor = c.textPrimary,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalCmd = replaceSnippetVars(snippet.command, values)
                        scope.launch { sshManager.write(sessionId, finalCmd + "\n") }
                        varPromptSnippet = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.goldPrimary),
                ) {
                    Text("Run", color = c.bgApp)
                }
            },
            dismissButton = {
                TextButton(onClick = { varPromptSnippet = null }) {
                    Text("Cancel", color = c.textMuted)
                }
            },
        )
    }
}

@Composable
private fun SnippetItem(snippet: SnippetEntity, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            snippet.name,
            color = c.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            snippet.command,
            color = c.textMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TerminalToolbar(onKey: (String) -> Unit) {
    val c = LocalAppColors.current
    var ctrlActive by remember { mutableStateOf(false) }

    Surface(
        color = c.bgSecondary,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Esc
            ToolbarKey("ESC") { onKey("\u001B") }

            // Ctrl toggle
            ToolbarKey(
                label = "CTRL",
                highlight = ctrlActive,
            ) { ctrlActive = !ctrlActive }

            // Common Ctrl combos
            ToolbarKey("C") {
                if (ctrlActive) { onKey("\u0003"); ctrlActive = false }
                else onKey("c")
            }
            ToolbarKey("D") {
                if (ctrlActive) { onKey("\u0004"); ctrlActive = false }
                else onKey("d")
            }
            ToolbarKey("Z") {
                if (ctrlActive) { onKey("\u001A"); ctrlActive = false }
                else onKey("z")
            }
            ToolbarKey("A") {
                if (ctrlActive) { onKey("\u0001"); ctrlActive = false }
                else onKey("a")
            }
            ToolbarKey("L") {
                if (ctrlActive) { onKey("\u000C"); ctrlActive = false }
                else onKey("l")
            }

            Spacer(Modifier.width(4.dp))

            // Tab
            ToolbarKey("TAB") { onKey("\t") }

            Spacer(Modifier.width(4.dp))

            // Arrow keys (ANSI escape sequences)
            ToolbarKey("\u2191") { onKey("\u001B[A") }
            ToolbarKey("\u2193") { onKey("\u001B[B") }
            ToolbarKey("\u2190") { onKey("\u001B[D") }
            ToolbarKey("\u2192") { onKey("\u001B[C") }

            Spacer(Modifier.width(4.dp))

            // Home / End
            ToolbarKey("HOME") { onKey("\u001B[H") }
            ToolbarKey("END")  { onKey("\u001B[F") }

            // PgUp / PgDn
            ToolbarKey("PgUp") { onKey("\u001B[5~") }
            ToolbarKey("PgDn") { onKey("\u001B[6~") }
        }
    }
}

@Composable
private fun ToolbarKey(
    label: String,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val c = LocalAppColors.current
    val bg = if (highlight) c.goldPrimary.copy(alpha = 0.25f) else c.bgCard
    val fg = if (highlight) c.goldPrimary else c.textSecondary
    val borderColor = if (highlight) c.goldPrimary.copy(alpha = 0.6f) else c.border

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(0.5.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalWebView(
    onWebViewReady: (WebView) -> Unit,
    onTermReady: () -> Unit,
    onInput: (String) -> Unit,
    onResize: (Int, Int) -> Unit,
    fontSize: Int = 14,
    isDark: Boolean = true,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView.setWebContentsDebuggingEnabled(true)
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = true
                setBackgroundColor(if (isDark) 0xFF0A0A0C.toInt() else 0xFFFFFFFF.toInt())

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun sendInput(data: String) = onInput(data)

                    @JavascriptInterface
                    fun onTermResize(cols: Int, rows: Int) = onResize(cols, rows)

                    @JavascriptInterface
                    fun onReady() {
                        post { onTermReady() }
                    }
                }, "Android")

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        android.util.Log.d(
                            "TerminalWebView",
                            "${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}"
                        )
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onWebViewReady(this@apply)
                    }
                }

                loadUrl("file:///android_asset/xterm/index.html?fontSize=$fontSize&theme=${if (isDark) "dark" else "light"}")
            }
        },
        update = { wv ->
            wv.evaluateJavascript("if(window.applySettings)applySettings($fontSize,${isDark})", null)
        },
    )
}
