package com.llamaaistudio.app.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.data.db.FolderEntity
import com.llamaaistudio.app.data.db.NoteEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(private val app: LlamaAiStudioApp) : ViewModel() {
    private val repo = app.notesRepository

    val folders: StateFlow<List<FolderEntity>> =
        repo.folders.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedFolderId = MutableStateFlow<Long?>(null)
    val selectedFolderId: StateFlow<Long?> = _selectedFolderId

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedNoteId = MutableStateFlow<Long?>(null)
    val selectedNoteId: StateFlow<Long?> = _selectedNoteId

    private val _summaryError = MutableStateFlow<String?>(null)
    val summaryError: StateFlow<String?> = _summaryError

    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing

    fun selectFolder(id: Long?) { _selectedFolderId.value = id }
    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun selectNote(id: Long?) { _selectedNoteId.value = id }

    fun createNote(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repo.createNote("Untitled note", _selectedFolderId.value)
            onCreated(id)
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch { repo.createFolder(name) }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch { repo.deleteFolder(folder) }
    }

    fun save(note: NoteEntity, title: String, content: String) {
        viewModelScope.launch { repo.saveContent(note, title, content) }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch {
            repo.deleteNote(note)
            if (_selectedNoteId.value == note.id) _selectedNoteId.value = null
        }
    }

    fun moveNote(note: NoteEntity, folderId: Long?) {
        viewModelScope.launch { repo.moveNote(note, folderId) }
    }

    fun sendToChat(note: NoteEntity) {
        com.llamaaistudio.app.util.WorkspaceBridge.sendToChat(
            "Regarding this note \"${note.title}\":\n\n${note.content}"
        )
    }

    fun summarize(note: NoteEntity) {
        viewModelScope.launch {
            _isSummarizing.value = true
            _summaryError.value = null
            val result = repo.summarizeWithAi(note, app.llamaEngine)
            _isSummarizing.value = false
            result.onFailure { _summaryError.value = it.message }
        }
    }

    fun observeNote(id: Long) = repo.observeNote(id)
    fun notesIn(folderId: Long?) = if (_searchQuery.value.isBlank()) repo.notes(folderId) else repo.search(_searchQuery.value)

    class Factory(private val app: LlamaAiStudioApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NotesViewModel(app) as T
    }
}
