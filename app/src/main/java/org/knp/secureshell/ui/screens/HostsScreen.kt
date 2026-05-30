package org.knp.secureshell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.knp.secureshell.data.db.entity.ConnectionEntity
import org.knp.secureshell.data.db.entity.GroupEntity
import org.knp.secureshell.data.db.entity.SshKeyEntity
import org.knp.secureshell.data.repository.AppRepository
import org.knp.secureshell.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    repo: AppRepository,
    onConnect: (ConnectionEntity) -> Unit,
) {
    val c = LocalAppColors.current
    val connections by repo.connections.collectAsState(initial = emptyList())
    val sshKeys by repo.sshKeys.collectAsState(initial = emptyList())
    val hostFolders by repo.groupsByIcon("host-folder").collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingConn by remember { mutableStateOf<ConnectionEntity?>(null) }
    var showAddFolder by remember { mutableStateOf(false) }

    val byGroup = remember(connections) { connections.groupBy { it.groupId } }

    data class FolderWithCount(val group: GroupEntity, val count: Int)

    val grouped = remember(connections, hostFolders, byGroup) {
        val result = mutableListOf<Any>()
        for (folder in hostFolders) {
            val items = byGroup[folder.id] ?: emptyList()
            result.add(FolderWithCount(folder, items.size))
            result.addAll(items)
        }
        val ungrouped = byGroup[null] ?: emptyList()
        if (ungrouped.isNotEmpty() && hostFolders.isNotEmpty()) {
            result.add(FolderWithCount(GroupEntity(id = "__ungrouped__", name = "Unfiled"), ungrouped.size))
        }
        result.addAll(ungrouped)
        result
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hosts", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showAddFolder = true }) {
                        Icon(Icons.Filled.CreateNewFolder, "Add folder")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, "Add host")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.bgPrimary,
                    titleContentColor = c.textPrimary,
                ),
            )
        },
        containerColor = c.bgApp,
    ) { padding ->
        if (connections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Dns, null, Modifier.size(64.dp), tint = c.textMuted)
                    Spacer(Modifier.height(16.dp))
                    Text("No hosts yet", color = c.textSecondary, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Add a host or sync from desktop", color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = c.goldPrimary),
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add Host", color = c.bgApp)
                    }
                }
            }
        } else {
            val collapsedFolders = remember { mutableStateMapOf<String, Boolean>() }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(grouped, key = { item ->
                    when (item) {
                        is FolderWithCount -> "folder-${item.group.id}"
                        is ConnectionEntity -> item.id
                        else -> item.hashCode().toString()
                    }
                }) { item ->
                    when (item) {
                        is FolderWithCount -> {
                            val collapsed = collapsedFolders[item.group.id] ?: false
                            FolderSectionHeader(
                                name = item.group.name,
                                count = item.count,
                                collapsed = collapsed,
                                onToggle = { collapsedFolders[item.group.id] = !collapsed },
                                onDelete = if (item.group.id != "__ungrouped__") {
                                    { scope.launch { repo.deleteGroup(item.group.id) } }
                                } else null,
                            )
                        }
                        is ConnectionEntity -> {
                            val parentId = item.groupId ?: "__ungrouped__"
                            val parentCollapsed = collapsedFolders[parentId] ?: false
                            AnimatedVisibility(visible = !parentCollapsed) {
                                HostCard(
                                    conn = item,
                                    onClick = { onConnect(item) },
                                    onEdit = { editingConn = item; showAddDialog = true },
                                    onDelete = { scope.launch { repo.deleteConnection(item.id) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        HostEditDialog(
            initial = editingConn,
            folders = hostFolders,
            sshKeys = sshKeys,
            onDismiss = { showAddDialog = false; editingConn = null },
            onSave = { conn ->
                scope.launch { repo.saveConnection(conn) }
                showAddDialog = false
                editingConn = null
            },
        )
    }

    if (showAddFolder) {
        FolderCreateDialog(
            onDismiss = { showAddFolder = false },
            onSave = { name ->
                scope.launch {
                    repo.saveGroup(GroupEntity(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        icon = "host-folder",
                    ))
                }
                showAddFolder = false
            },
        )
    }
}

@Composable
private fun FolderSectionHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val c = LocalAppColors.current
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm && onDelete != null) {
        DeleteConfirmDialog(
            title = "Delete folder",
            message = "Delete \"$name\"? Connections inside will be moved to ungrouped.",
            onConfirm = { showConfirm = false; onDelete() },
            onDismiss = { showConfirm = false },
        )
    }

    Surface(
        color = c.bgSecondary,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (collapsed) Icons.Filled.Folder else Icons.Filled.FolderOpen,
                null,
                tint = c.goldPrimary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                name,
                color = c.textPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$count",
                color = c.textMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 4.dp),
            )
            if (onDelete != null) {
                IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Delete, "Delete folder", tint = Error.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                }
            }
            Icon(
                if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                null,
                tint = c.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun HostCard(
    conn: ConnectionEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalAppColors.current
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        DeleteConfirmDialog(
            title = "Delete connection",
            message = "Delete \"${conn.name}\"?",
            onConfirm = { showConfirm = false; onDelete() },
            onDismiss = { showConfirm = false },
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = c.bgCard),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(c.goldPrimary, c.goldDark))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Dns, null, tint = c.bgApp, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conn.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${conn.username}@${conn.host}:${conn.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conn.authType != "password") {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Key,
                            contentDescription = "Key auth",
                            tint = c.goldPrimary.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Edit, "Edit", tint = c.textMuted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, "Delete", tint = Error.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEditDialog(
    initial: ConnectionEntity?,
    folders: List<GroupEntity>,
    sshKeys: List<SshKeyEntity>,
    onDismiss: () -> Unit,
    onSave: (ConnectionEntity) -> Unit,
) {
    val c = LocalAppColors.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var authType by remember { mutableStateOf(initial?.authType ?: "password") }
    var selectedKeyId by remember { mutableStateOf(initial?.keyId) }
    var selectedGroupId by remember { mutableStateOf(initial?.groupId) }
    var authExpanded by remember { mutableStateOf(false) }
    var keyExpanded by remember { mutableStateOf(false) }
    var folderExpanded by remember { mutableStateOf(false) }

    val authOptions = listOf(
        "password" to "Password",
        "key" to "SSH Key",
        "key_passphrase" to "SSH Key + Passphrase",
    )
    val showPassword = authType == "password" || authType == "key_passphrase"
    val showKeyPicker = authType == "key" || authType == "key_passphrase"
    val passwordLabel = if (authType == "key_passphrase") "Key Passphrase" else "Password"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = {
            Text(
                if (initial == null) "Add Host" else "Edit Host",
                color = c.textPrimary,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = goldTextFieldColors(),
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("Host / IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = goldTextFieldColors(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = port, onValueChange = { port = it },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = goldTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = username, onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                        colors = goldTextFieldColors(),
                    )
                }

                // Auth method dropdown
                ExposedDropdownMenuBox(
                    expanded = authExpanded,
                    onExpandedChange = { authExpanded = it },
                ) {
                    OutlinedTextField(
                        value = authOptions.find { it.first == authType }?.second ?: "Password",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Authentication") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(authExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        colors = goldTextFieldColors(),
                    )
                    ExposedDropdownMenu(
                        expanded = authExpanded,
                        onDismissRequest = { authExpanded = false },
                    ) {
                        authOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { authType = value; authExpanded = false },
                                leadingIcon = {
                                    Icon(
                                        when (value) {
                                            "password" -> Icons.Filled.Password
                                            else -> Icons.Filled.Key
                                        },
                                        null,
                                        tint = c.goldPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                    }
                }

                // SSH Key picker
                if (showKeyPicker) {
                    if (sshKeys.isEmpty()) {
                        Surface(
                            color = c.bgCard,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Info, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "No SSH keys available. Sync keys from desktop first.",
                                    color = c.textMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    } else {
                        ExposedDropdownMenuBox(
                            expanded = keyExpanded,
                            onExpandedChange = { keyExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = sshKeys.find { it.id == selectedKeyId }?.name ?: "Select a key",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("SSH Key") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(keyExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                colors = goldTextFieldColors(),
                            )
                            ExposedDropdownMenu(
                                expanded = keyExpanded,
                                onDismissRequest = { keyExpanded = false },
                            ) {
                                sshKeys.forEach { key ->
                                    DropdownMenuItem(
                                        text = { Text(key.name) },
                                        onClick = { selectedKeyId = key.id; keyExpanded = false },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Key, null, tint = c.goldPrimary, modifier = Modifier.size(18.dp))
                                        },
                                        trailingIcon = {
                                            Text(
                                                key.keyType.uppercase(),
                                                color = c.textMuted,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Password / passphrase field
                if (showPassword) {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text(passwordLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = goldTextFieldColors(),
                    )
                }

                if (folders.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = folderExpanded,
                        onExpandedChange = { folderExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = folders.find { it.id == selectedGroupId }?.name ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Folder") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(folderExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            colors = goldTextFieldColors(),
                        )
                        ExposedDropdownMenu(
                            expanded = folderExpanded,
                            onDismissRequest = { folderExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("None") },
                                onClick = { selectedGroupId = null; folderExpanded = false },
                            )
                            folders.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder.name) },
                                    onClick = { selectedGroupId = folder.id; folderExpanded = false },
                                    leadingIcon = { Icon(Icons.Filled.Folder, null, tint = c.goldPrimary, modifier = Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        ConnectionEntity(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.ifBlank { host },
                            host = host,
                            port = port.toIntOrNull() ?: 22,
                            username = username,
                            password = if (showPassword) password else "",
                            authType = authType,
                            keyId = if (showKeyPicker) selectedKeyId else null,
                            groupId = selectedGroupId,
                        )
                    )
                },
                enabled = host.isNotBlank() && (!showKeyPicker || selectedKeyId != null || sshKeys.isEmpty()),
                colors = ButtonDefaults.buttonColors(containerColor = c.goldPrimary),
            ) {
                Text("Save", color = c.bgApp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.textMuted) }
        },
    )
}

@Composable
private fun FolderCreateDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val c = LocalAppColors.current
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text("New Folder", color = c.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = goldTextFieldColors(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(name) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = c.goldPrimary),
            ) { Text("Create", color = c.bgApp) }
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
internal fun DeleteConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text(title, color = c.textPrimary) },
        text = { Text(message, color = c.textSecondary) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Error),
            ) { Text("Delete", color = androidx.compose.ui.graphics.Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = c.textMuted)
            }
        },
    )
}
