package com.llamaaistudio.app.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.NoteEntity
import com.llamaaistudio.app.ui.theme.StudioOrange
import com.llamaaistudio.app.ui.theme.StudioPanel
import com.llamaaistudio.app.ui.theme.StudioTextMuted
import kotlinx.coroutines.delay

@Composable
fun NotesScreen(app: LlamaAiStudioApp) {
    val viewModel: NotesViewModel = viewModel(factory = NotesViewModel.Factory(app))
    val selectedFolderId by viewModel.selectedFolderId.collectAsStateWithLifecycle()
    val selectedNoteId by viewModel.selectedNoteId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val notesFlow = remember(selectedFolderId, searchQuery) { viewModel.notesIn(selectedFolderId) }
    val notes by notesFlow.collectAsStateWithLifecycle(emptyList())
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth > 700.dp
        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                NotesListPane(
                    viewModel = viewModel,
                    notes = notes,
                    folders = folders,
                    selectedFolderId = selectedFolderId,
                    selectedNoteId = selectedNoteId,
                    searchQuery = searchQuery,
                    modifier = Modifier.width(320.dp).fillMaxHeight(),
                )
                Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
                if (selectedNoteId != null) {
                    NoteEditorPane(app, viewModel, selectedNoteId!!, modifier = Modifier.weight(1f))
                } else {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(48.dp))
                        Text("Select a note, or create a new one", color = StudioTextMuted)
                    }
                }
            }
        } else {
            if (selectedNoteId == null) {
                NotesListPane(
                    viewModel = viewModel,
                    notes = notes,
                    folders = folders,
                    selectedFolderId = selectedFolderId,
                    selectedNoteId = selectedNoteId,
                    searchQuery = searchQuery,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                NoteEditorPane(app, viewModel, selectedNoteId!!, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun NotesListPane(
    viewModel: NotesViewModel,
    notes: List<NoteEntity>,
    folders: List<com.llamaaistudio.app.data.db.FolderEntity>,
    selectedFolderId: Long?,
    selectedNoteId: Long?,
    searchQuery: String,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.createNote { viewModel.selectNote(it) } }) {
                Icon(Icons.Filled.Add, contentDescription = "New note")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(8.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setSearchQuery,
                placeholder = { Text("Search notes…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FolderChipsRow(folders, selectedFolderId, viewModel::selectFolder, viewModel::createFolder)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(notes, key = { it.id }) { note ->
                    NoteListItem(
                        note = note,
                        selected = note.id == selectedNoteId,
                        onClick = { viewModel.selectNote(note.id) },
                        onDelete = { viewModel.deleteNote(note) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderChipsRow(
    folders: List<com.llamaaistudio.app.data.db.FolderEntity>,
    selectedFolderId: Long?,
    onSelect: (Long?) -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    var newFolderName by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }

    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            FilterChip(selected = selectedFolderId == null, onClick = { onSelect(null) }, label = { Text("All") })
        }
        items(folders, key = { it.id }) { folder ->
            FilterChip(
                selected = selectedFolderId == folder.id,
                onClick = { onSelect(folder.id) },
                label = { Text(folder.name) },
            )
        }
        item {
            if (showAdd) {
                TextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    modifier = Modifier.width(140.dp),
                    colors = TextFieldDefaults.colors(),
                    placeholder = { Text("Folder name") },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (newFolderName.isNotBlank()) {
                                onCreateFolder(newFolderName)
                                newFolderName = ""
                                showAdd = false
                            }
                        }) { Icon(Icons.Filled.Add, contentDescription = "Create") }
                    },
                )
            } else {
                FilterChip(selected = false, onClick = { showAdd = true }, label = { Text("+ Folder") })
            }
        }
    }
}

@Composable
private fun NoteListItem(note: NoteEntity, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onClick,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) StudioOrange.copy(alpha = 0.15f) else StudioPanel,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(note.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.bodyLarge)
                Text(
                    note.content.take(80).replace("\n", " "),
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = StudioTextMuted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorPane(
    app: LlamaAiStudioApp,
    viewModel: NotesViewModel,
    noteId: Long,
    modifier: Modifier = Modifier,
) {
    val note by viewModel.observeNote(noteId).collectAsStateWithLifecycle(initialValue = null)
    val isSummarizing by viewModel.isSummarizing.collectAsStateWithLifecycle()
    val summaryError by viewModel.summaryError.collectAsStateWithLifecycle()

    val current = note ?: return

    var title by remember(current.id) { mutableStateOf(current.title) }
    var content by remember(current.id) { mutableStateOf(current.content) }
    var showPreview by remember { mutableStateOf(false) }

    // Debounced autosave: coalesce rapid keystrokes into one write ~500ms after the user pauses.
    LaunchedEffect(title, content) {
        delay(500)
        viewModel.save(current, title, content)
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.selectNote(null) }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { viewModel.sendToChat(current) }) {
                Icon(Icons.Filled.Send, contentDescription = "Send to chat", tint = StudioOrange)
            }
            IconButton(onClick = { viewModel.summarize(current) }, enabled = !isSummarizing) {
                if (isSummarizing) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = "AI summary", tint = StudioOrange)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            FilterChip(selected = !showPreview, onClick = { showPreview = false }, label = { Text("Edit") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = showPreview, onClick = { showPreview = true }, label = { Text("Preview") })
        }

        current.aiSummary?.let { summary ->
            Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StudioPanel)) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("AI summary", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        summaryError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }

        if (showPreview) {
            Text(
                renderMarkdownPreview(content),
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
        } else {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxSize(),
                placeholder = { Text("Write in Markdown…") },
            )
        }
    }
}
