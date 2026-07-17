package com.videolms.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    
    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl
    
    fun saveServerUrl(url: String) {
        viewModelScope.launch {
            _serverUrl.value = url
            // The actual saving to DataStore is done in VideoLMSApplication
        }
    }
}
