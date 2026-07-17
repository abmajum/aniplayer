package com.videolms.android.ui.screens

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.media3.ui.PlayerView
import com.videolms.android.data.model.CourseItem
import java.io.File

@Composable
fun VideoPlayerScreen(
    videoItem: CourseItem,
    serverBaseUrl: String?,
    onProgressUpdate: (Float, Float) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    
    LaunchedEffect(videoItem.id) {
        // Initialize player when video changes
        player = ExoPlayer.Builder(context).build().apply {
            val mediaItem = when {
                serverBaseUrl != null && videoItem.itemType == "server" -> {
                    MediaItem.fromUri("$serverBaseUrl/stream/${videoItem.id}")
                }
                videoItem.path.startsWith("http") -> {
                    MediaItem.fromUri(videoItem.path)
                }
                else -> {
                    // Local file
                    val file = File(videoItem.path)
                    val fileUri = Uri.fromFile(file)
                    MediaItem.fromUri(fileUri)
                }
            }
            setMediaItem(mediaItem)
            
            // Resume from saved position
            if (videoItem.watchedSeconds > 0) {
                seekTo((videoItem.watchedSeconds * 1000).toLong())
            }
            
            prepare()
            playWhenReady = true
            
            // Add progress listener
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && videoItem.watchedSeconds > 0) {
                        // Already handled in seekTo above
                    }
                }
            })
        }
    }
    
    // Periodic progress saving
    LaunchedEffect(player) {
        while (player != null && player?.isPlaying == true) {
            kotlinx.coroutines.delay(5000) // Save every 5 seconds
            player?.currentPosition?.let { position ->
                player?.duration?.let { duration ->
                    onProgressUpdate(position / 1000f, duration / 1000f)
                }
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }
    
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(videoItem.name) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )
        
        player?.let { exoPlayer ->
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Progress info
        player?.let { exoPlayer ->
            val currentPosition = exoPlayer.currentPosition / 1000f
            val duration = exoPlayer.duration / 1000f
            val progress = if (duration > 0) (currentPosition / duration * 100).toInt() else 0
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                LinearProgressIndicator(
                    progress = currentPosition / (duration.takeIf { it > 0 } ?: 1f),
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(currentPosition),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$progress% complete",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTime(duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatTime(seconds: Float): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%d:%02d", minutes, secs)
    }
}
