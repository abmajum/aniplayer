package com.videolms.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videolms.android.VideoLMSApplication
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val application = context.applicationContext as VideoLMSApplication
    val scope = rememberCoroutineScope()
    
    var serverUrl by remember { mutableStateOf(TextFieldValue(application.getServerBaseUrl())) }
    var isSaving by remember { mutableStateOf(false) }
    var showSuccessMessage by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Server Configuration",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Text(
                        text = "Enter your FastAPI server URL. This should include the protocol (http/https) and port number.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("http://192.168.1.100:8000") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = serverUrl.text.isNotEmpty() && !isValidUrl(serverUrl.text)
                    )
                    
                    if (serverUrl.text.isNotEmpty() && !isValidUrl(serverUrl.text)) {
                        Text(
                            text = "Please enter a valid URL (e.g., http://192.168.1.100:8000)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    
                    Button(
                        onClick = {
                            if (isValidUrl(serverUrl.text)) {
                                isSaving = true
                                scope.launch {
                                    try {
                                        application.updateServerUrl(serverUrl.text)
                                        viewModel.saveServerUrl(serverUrl.text)
                                        isSaving = false
                                        showSuccessMessage = true
                                        Toast.makeText(
                                            context,
                                            "Server URL updated successfully!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } catch (e: Exception) {
                                        isSaving = false
                                        Toast.makeText(
                                            context,
                                            "Error updating server URL: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    "Please enter a valid URL",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving && serverUrl.text.isNotEmpty() && isValidUrl(serverUrl.text)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Save Server URL")
                        }
                    }
                    
                    if (showSuccessMessage) {
                        Text(
                            text = "✓ Server URL updated! The app will now connect to: ${serverUrl.text}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Connection Tips",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Text(
                        text = "• For local development, use your computer's IP address (not localhost)\n" +
                               "• Make sure your phone and computer are on the same WiFi network\n" +
                               "• Ensure the FastAPI server is running and accessible\n" +
                               "• Check firewall settings if connection fails",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            Text(
                text = "Current default: ${application.getServerBaseUrl()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun isValidUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
}
