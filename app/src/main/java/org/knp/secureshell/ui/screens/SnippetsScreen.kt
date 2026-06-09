package org.knp.secureshell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.knp.secureshell.data.db.entity.GroupEntity
import org.knp.secureshell.data.db.entity.SnippetEntity
import org.knp.secureshell.data.repository.AppRepository
import org.knp.secureshell.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(repo: AppRepository) {
    val c = LocalAppColors.current
    val snippets by repo.snippets.collectAsState(initial = emptyList())
    val snippetFolders by repo.groupsByIcon("snippet-folder").collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSnippet by remember { mutableStateOf<SnippetEntity?>(null) }
    var showAddFolder by remember { mutableStateOf(false) }

    val byGroup = remember(snippets) { snippets.groupBy { it.groupId } }

    data class FolderWithCount(val group: GroupEntity, val count: Int)

    val grouped = remember(snippets, snippetFolders, byGroup) {
        val result = mutableListOf<Any>()
        for (folder in snippetFolders) {
            val items = byGroup[folder.id] ?: emptyList()
            result.add(FolderWithCount(folder, items.size))
            result.addAll(items)
        }
        val ungrouped = byGroup[null] ?: emptyList()
        if (ungrouped.isNotEmpty() && snippetFolders.isNotEmpty()) {
            result.add(FolderWithCount(GroupEntity(id = "__ungrouped__", name = "Unfiled"), ungrouped.size))
        }
        result.addAll(ungrouped)
        result
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Snippets", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showAddFolder = true }) {
                        Icon(Icons.Filled.CreateNewFolder, "Add folder")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, "Add snippet")
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
        if (snippets.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Code, null, Modifier.size(64.dp), tint = c.textMuted)
                    Spacer(Modifier.height(16.dp))
                    Text("No snippets yet", color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text("Save frequently used commands", color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = c.goldPrimary),
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add Snippet", color = c.bgApp)
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
                        is SnippetEntity -> item.id
                        else -> item.hashCode().toString()
                    }
                }) { item ->
                    when (item) {
                        is FolderWithCount -> {
                            val collapsed = collapsedFolders[item.group.id] ?: false
                            SnippetFolderSectionHeader(
                                name = item.group.name,
                                count = item.count,
                                collapsed = collapsed,
                                onToggle = { collapsedFolders[item.group.id] = !collapsed },
                                onDelete = if (item.group.id != "__ungrouped__") {
                                    { scope.launch { repo.deleteGroup(item.group.id) } }
                                } else null,
                            )
                        }
                        is SnippetEntity -> {
                            val parentId = item.groupId ?: "__ungrouped__"
                            val parentCollapsed = collapsedFolders[parentId] ?: false
                            AnimatedVisibility(visible = !parentCollapsed) {
                                SnippetCard(
                                    snippet = item,
                                    onEdit = { editingSnippet = item; showAddDialog = true },
                                    onDelete = { scope.launch { repo.deleteSnippet(item.id) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        SnippetEditDialog(
            initial = editingSnippet,
            folders = snippetFolders,
            onDismiss = { showAddDialog = false; editingSnippet = null },
            onSave = { snippet ->
                scope.launch { repo.saveSnippet(snippet) }
                showAddDialog = false
                editingSnippet = null
            },
        )
    }

    if (showAddFolder) {
        SnippetFolderCreateDialog(
            onDismiss = { showAddFolder = false },
            onSave = { name ->
                scope.launch {
                    repo.saveGroup(GroupEntity(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        icon = "snippet-folder",
                    ))
                }
                showAddFolder = false
            },
        )
    }
}

@Composable
private fun SnippetFolderSectionHeader(
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
            message = "Delete \"$name\"? Snippets inside will be moved to ungrouped.",
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
private fun SnippetCard(
    snippet: SnippetEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalAppColors.current
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        DeleteConfirmDialog(
            title = "Delete snippet",
            message = "Delete \"${snippet.name}\"?",
            onConfirm = { showConfirm = false; onDelete() },
            onDismiss = { showConfirm = false },
        )
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = c.bgCard),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Code, null, tint = c.goldPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    snippet.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Edit, "Edit", tint = c.textMuted, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Delete, "Delete", tint = Error.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                color = c.bgInput,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    snippet.command,
                    color = c.goldPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Show variable badges
            val vars = extractSnippetVars(snippet.command)
            if (vars.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    vars.take(5).forEach { v ->
                        Surface(
                            color = c.goldPrimary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                "{{$v}}",
                                color = c.goldPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            if (snippet.tags.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(snippet.tags, color = c.textMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetEditDialog(
    initial: SnippetEntity?,
    folders: List<GroupEntity>,
    onDismiss: () -> Unit,
    onSave: (SnippetEntity) -> Unit,
) {
    val c = LocalAppColors.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var commandField by remember {
        mutableStateOf(TextFieldValue(initial?.command ?: ""))
    }
    var tags by remember { mutableStateOf(initial?.tags ?: "") }
    var selectedGroupId by remember { mutableStateOf(initial?.groupId) }
    var folderExpanded by remember { mutableStateOf(false) }
    var varName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = {
            Text(
                if (initial == null) "Add Snippet" else "Edit Snippet",
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
                    colors = goldSnippetFieldColors(),
                )
                OutlinedTextField(
                    value = commandField,
                    onValueChange = { commandField = it },
                    label = { Text("Command") },
                    minLines = 3, maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    colors = goldSnippetFieldColors(),
                )

                // Insert variable row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = varName,
                        onValueChange = { varName = normalizeVarName(it) },
                        label = { Text("Variable name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = goldSnippetFieldColors(),
                    )
                    Button(
                        onClick = {
                            if (varName.isNotBlank()) {
                                val insert = "{{$varName}}"
                                val text = commandField.text
                                val cursor = commandField.selection.start
                                val newText = text.substring(0, cursor) + insert + text.substring(cursor)
                                val newCursor = cursor + insert.length
                                commandField = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(newCursor),
                                )
                                varName = ""
                            }
                        },
                        enabled = varName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = c.goldPrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("Insert", color = c.bgApp)
                    }
                }

                OutlinedTextField(
                    value = tags, onValueChange = { tags = it },
                    label = { Text("Tags (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = goldSnippetFieldColors(),
                )

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
                            colors = goldSnippetFieldColors(),
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
                    onSave(SnippetEntity(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        name = name.ifBlank { commandField.text.take(30) },
                        command = commandField.text,
                        tags = tags,
                        connectionIds = initial?.connectionIds ?: "[]",
                        groupId = selectedGroupId,
                        sortOrder = initial?.sortOrder ?: 0,
                    ))
                },
                enabled = commandField.text.isNotBlank(),
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
private fun SnippetFolderCreateDialog(
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
                colors = goldSnippetFieldColors(),
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

// ─── Snippet variable utilities ─────────────────────────────

private val SNIPPET_VAR_RE = Regex("""\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\}\}""")

fun extractSnippetVars(command: String): List<String> {
    val seen = mutableSetOf<String>()
    return SNIPPET_VAR_RE.findAll(command).mapNotNull { m ->
        val name = m.groupValues[1]
        if (seen.add(name)) name else null
    }.toList()
}

fun replaceSnippetVars(command: String, values: Map<String, String>): String =
    SNIPPET_VAR_RE.replace(command) { m -> values[m.groupValues[1]] ?: "" }

private fun normalizeVarName(input: String): String =
    input.trim()
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[^a-zA-Z0-9_]"), "")
        .replace(Regex("^[^a-zA-Z_]+"), "")

@Composable
private fun goldSnippetFieldColors(): TextFieldColors {
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
