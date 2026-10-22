package com.abmajum.aniplayer

import android.content.Intent
import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.abmajum.aniplayer.data.AniplayerUiState
import com.abmajum.aniplayer.data.AniplayerViewModel
import com.abmajum.aniplayer.data.Course
import com.abmajum.aniplayer.data.ImportedSubtitle
import com.abmajum.aniplayer.data.Lesson
import com.abmajum.aniplayer.data.PomodoroState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private val AppBackground = Color(0xFF121212)
private val AppSurface = Color(0xFF1C1C1C)
private val AppCard = Color(0xFF252525)
private val AppAccent = Color(0xFF1ED760)
private val AppSecondary = Color(0xFFB3B3B3)

class MainActivity : ComponentActivity() {
    private val viewModel: AniplayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AniplayerTheme {
                AniplayerApp(viewModel) { fullScreen ->
                    this@MainActivity.requestedOrientation = if (fullScreen) {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }
    }
}

@Composable
private fun AniplayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppAccent,
            onPrimary = Color.Black,
            background = AppBackground,
            surface = AppSurface,
            onSurface = Color.White,
            secondary = AppAccent
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AniplayerApp(
    viewModel: AniplayerViewModel,
    onFullScreenChanged: (Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf("home") }
    var timerExpanded by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val view = LocalView.current
    val window = context.findActivity()?.window
    DisposableEffect(fullScreen, view, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.let {
            if (fullScreen) {
                it.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                it.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(fullScreen) {
        onFullScreenChanged(fullScreen)
    }
    val snackbarScope = rememberCoroutineScope()
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                viewModel.selectRoot(uri)
            } catch (error: SecurityException) {
                Toast.makeText(context, "Could not keep access to that folder.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(state.pomodoro.running) {
        while (state.pomodoro.running) {
            delay(1000)
            viewModel.tickPomodoro()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarScope.launch {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(state.selectedLesson != null || state.selectedCourse != null) {
        if (fullScreen) {
            fullScreen = false
        } else if (state.selectedLesson != null) {
            viewModel.closeLesson()
        } else {
            viewModel.goHome()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            if (!fullScreen) TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(AppAccent)
                                .padding(5.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Aniplayer", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    if (state.selectedLesson != null || state.selectedCourse != null) {
                        IconButton(onClick = {
                            if (state.selectedLesson != null) viewModel.closeLesson()
                            else viewModel.goHome()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { timerExpanded = true }) {
                        PomodoroIcon(state.pomodoro)
                    }
                    DropdownMenu(
                        expanded = timerExpanded,
                        onDismissRequest = { timerExpanded = false }
                    ) {
                        PomodoroMenu(
                            state = state.pomodoro,
                            viewModel = viewModel,
                            onClose = { timerExpanded = false },
                            onToggle = {
                                if (
                                    !state.pomodoro.running &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                viewModel.togglePomodoro()
                                timerExpanded = false
                            }
                        )
                    }
                    if (state.rootUri != null && state.selectedLesson == null) {
                        IconButton(
                            onClick = { viewModel.refreshLibrary() },
                            enabled = !state.scanning
                        ) {
                            if (state.scanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Rescan courses")
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.selectedLesson == null && state.selectedCourse == null) {
                NavigationBar(containerColor = AppSurface) {
                    NavigationBarItem(
                        selected = selectedTab == "home",
                        onClick = { selectedTab = "home" },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Courses") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == "search",
                        onClick = { selectedTab = "search" },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("Search") }
                    )
                }
            }
        }
    ) { insets ->
        when {
            state.selectedLesson != null -> PlayerScreen(
                state = state,
                viewModel = viewModel,
                fullScreen = fullScreen,
                onToggleFullScreen = { fullScreen = !fullScreen },
                modifier = Modifier.padding(insets)
            )
            state.selectedCourse != null -> CourseScreen(
                state = state,
                onOpenLesson = viewModel::openLesson,
                onResetCompletion = viewModel::resetCompletion,
                onMarkComplete = viewModel::markComplete,
                onDeleteCourse = viewModel::deleteCourse,
                modifier = Modifier.padding(insets)
            )
            selectedTab == "search" -> SearchScreen(
                state = state,
                onQuery = viewModel::setSearchQuery,
                onOpenLesson = viewModel::openLesson,
                onResetCompletion = viewModel::resetCompletion,
                onMarkComplete = viewModel::markComplete,
                modifier = Modifier.padding(insets)
            )
            else -> DashboardScreen(
                state = state,
                onChooseFolder = { folderPicker.launch(null) },
                onOpenCourse = viewModel::selectCourse,
                onOpenLesson = viewModel::openLesson,
                modifier = Modifier.padding(insets)
            )
        }
    }
}

@Composable
private fun DashboardScreen(
    state: AniplayerUiState,
    onChooseFolder: () -> Unit,
    onOpenCourse: (Course) -> Unit,
    onOpenLesson: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(AppBackground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Your courses", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Pick up where you left off.", color = AppSecondary)
        }
        if (state.rootUri == null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppSurface)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = AppAccent, modifier = Modifier.size(40.dp))
                        Text("Choose your courses folder", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Select a folder that contains one folder per course. Videos stay where they are; Aniplayer only saves their index and progress on this phone.",
                            color = AppSecondary
                        )
                        Button(onClick = onChooseFolder, colors = ButtonDefaults.buttonColors(containerColor = AppAccent)) {
                            Text("Select course folder")
                        }
                    }
                }
            }
        } else if (state.courses.isEmpty() && !state.scanning) {
            item {
                EmptyPanel(
                    title = "No courses found",
                    detail = "Choose a folder containing course folders, or rescan after adding videos."
                )
            }
        }
        if (state.error != null) {
            item { ErrorPanel(state.error) }
        }
        state.courses.forEach { course ->
            item(key = course.uri) {
                CourseCard(
                    course = course,
                    onClick = { onOpenCourse(course) },
                    onContinue = { course.lastLessonUri?.let(onOpenLesson) }
                )
            }
        }
        item {
            OutlinedButton(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.rootUri == null) "Select course folder" else "Change course folder")
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun CourseCard(course: Course, onClick: () -> Unit, onContinue: () -> Unit) {
    val completion = if (course.lessonCount == 0) 0f else course.completedCount.toFloat() / course.lessonCount
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF303030)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = AppAccent, modifier = Modifier.size(42.dp))
            }
            Text(course.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val completionPercent = if (course.lessonCount == 0) 0 else {
                (course.completedCount * 100f / course.lessonCount).roundToInt()
            }
            Text(
                "${course.lessonCount} video lessons · ${course.completedCount} completed · $completionPercent%",
                color = AppSecondary
            )
            androidx.compose.material3.LinearProgressIndicator(
                progress = { completion },
                modifier = Modifier.fillMaxWidth(),
                color = AppAccent,
                trackColor = AppCard
            )
            if (course.lastLessonUri != null) {
                TextButton(onClick = onContinue, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Continue watching")
                }
            }
        }
    }
}

@Composable
private fun CourseScreen(
    state: AniplayerUiState,
    onOpenLesson: (String) -> Unit,
    onResetCompletion: (String) -> Unit,
    onMarkComplete: (String) -> Unit,
    onDeleteCourse: suspend (Course) -> Result<Unit>,
    modifier: Modifier = Modifier
) {
    val course = state.selectedCourse ?: return
    val highlightedLessonUri = state.selectedLesson?.uri ?: course.lastLessonUri
    var deleteConfirmationOpen by remember(course.uri) { mutableStateOf(false) }
    var deleting by remember(course.uri) { mutableStateOf(false) }
    var deleteError by remember(course.uri) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(course.uri, highlightedLessonUri) {
        val lessonIndex = courseLessonListIndex(state.lessons, highlightedLessonUri)
        if (lessonIndex >= 0) listState.animateScrollToItem(lessonIndex + 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(AppBackground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    course.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { deleteConfirmationOpen = true }) {
                    Text("Delete course", color = Color(0xFFFF8A80))
                }
            }
            Text("${state.lessons.size} video lessons", color = AppSecondary)
            deleteError?.let { ErrorPanel(it) }
        }
        var lastFolder = ""
        state.lessons.forEach { lesson ->
            if (lesson.folderPath != lastFolder) {
                lastFolder = lesson.folderPath
                if (lastFolder.isNotBlank()) {
                    item(key = "folder:${lesson.folderPath}") {
                        Text(lesson.folderPath, color = AppAccent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                }
            }
            item(key = lesson.uri) {
                LessonRow(
                    lesson = lesson,
                    active = highlightedLessonUri == lesson.uri,
                    onClick = { onOpenLesson(lesson.uri) },
                    onReset = { onResetCompletion(lesson.uri) },
                    onMarkComplete = { onMarkComplete(lesson.uri) }
                )
            }
        }
        if (state.lessons.isEmpty()) item { EmptyPanel("No videos in this course", "Add supported video files and use Rescan.") }
    }
    if (deleteConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteConfirmationOpen = false },
            title = { Text("Delete ${course.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This permanently deletes the course folder and all its files, including videos and subtitles. Watch progress and imported subtitle copies will also be deleted.")
                    deleteError?.let { Text(it, color = Color(0xFFFF8A80)) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        coroutineScope.launch {
                            deleting = true
                            val result = onDeleteCourse(course)
                            deleting = false
                            result.onSuccess {
                                deleteConfirmationOpen = false
                            }.onFailure { error ->
                                deleteError = error.message ?: "Could not delete the course."
                            }
                        }
                    }
                ) {
                    Text(if (deleting) "Deleting…" else "Delete permanently", color = Color(0xFFFF8A80))
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { deleteConfirmationOpen = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun LessonRow(
    lesson: Lesson,
    active: Boolean,
    onClick: () -> Unit,
    onReset: () -> Unit,
    onMarkComplete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = if (active) BorderStroke(1.dp, AppAccent) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (active) Color(0xFF183724) else AppCard
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = if (active || lesson.completed) AppAccent else Color.White)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    lesson.name,
                    color = if (active) AppAccent else Color.White,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (lesson.positionMs > 0 && !lesson.completed) {
                    Text("Resume at ${formatTime(lesson.positionMs)}", color = AppSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (active || lesson.completed) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = if (active) "Currently playing" else "Completed",
                    tint = AppAccent,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (lesson.completed) {
                TextButton(onClick = onReset) { Text("Reset") }
            } else {
                TextButton(onClick = onMarkComplete) { Text("Mark complete") }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    state: AniplayerUiState,
    onQuery: (String) -> Unit,
    onOpenLesson: (String) -> Unit,
    onResetCompletion: (String) -> Unit,
    onMarkComplete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().background(AppBackground).padding(16.dp)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search courses and videos") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
        )
        Spacer(Modifier.height(12.dp))
        if (state.searchQuery.isNotBlank() && state.searchResults.isEmpty()) {
            EmptyPanel("No results", "Try a different course or video title.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.searchResults, key = Lesson::uri) { lesson ->
                    LessonRow(
                        lesson = lesson,
                        active = false,
                        onClick = { onOpenLesson(lesson.uri) },
                        onReset = { onResetCompletion(lesson.uri) },
                        onMarkComplete = { onMarkComplete(lesson.uri) }
                    )
                }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun PlayerScreen(
    state: AniplayerUiState,
    viewModel: AniplayerViewModel,
    fullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lesson = state.selectedLesson ?: return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context.findActivity()
    var playlistOpen by remember { mutableStateOf(false) }
    var subtitlePickerOpen by remember { mutableStateOf(false) }
    var subtitleLabel by remember { mutableStateOf("") }
    var subtitleLanguage by remember { mutableStateOf("und") }
    var subtitles by remember(lesson.uri) { mutableStateOf(viewModel.getSubtitles(lesson.uri)) }
    var subtitleError by remember { mutableStateOf<String?>(null) }
    var playbackError by remember(lesson.uri) { mutableStateOf<String?>(null) }
    val playlistListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val result = viewModel.importSubtitle(lesson.uri, uri, subtitleLabel, subtitleLanguage)
                result.onSuccess {
                    subtitles = viewModel.getSubtitles(lesson.uri)
                    subtitleError = null
                    subtitleLabel = ""
                    Toast.makeText(context, "Subtitle imported.", Toast.LENGTH_LONG).show()
                }.onFailure { subtitleError = it.message ?: "Subtitle import failed." }
            }
        }
    }

    val trackSelector = remember { DefaultTrackSelector(context) }
    val player = remember {
        ExoPlayer.Builder(context).setTrackSelector(trackSelector).build()
    }
    LaunchedEffect(player, lesson.uri, subtitles.map(ImportedSubtitle::id)) {
        val previousUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (previousUri != null && previousUri != lesson.uri) {
            viewModel.saveProgress(
                previousUri,
                player.currentPosition,
                player.duration.coerceAtLeast(0)
            )
        }
        val position = if (previousUri == lesson.uri) player.currentPosition else lesson.positionMs
        playbackError = null
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(buildMediaItem(lesson, subtitles), position)
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(player, lifecycleOwner, activity) {
        var shouldResumeAfterBackground = false
        fun updateKeepScreenOn() {
            val window = activity?.window ?: return
            if (player.isPlaying) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
            if (event == Lifecycle.Event.ON_PAUSE) {
                shouldResumeAfterBackground = player.playWhenReady
                if (currentUri != null) {
                    viewModel.saveProgress(currentUri, player.currentPosition, player.duration.coerceAtLeast(0))
                }
                player.pause()
                updateKeepScreenOn()
            }
            if (event == Lifecycle.Event.ON_RESUME && shouldResumeAfterBackground) {
                player.playWhenReady = true
            }
        }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val detail = error.cause?.localizedMessage ?: error.errorCodeName
                playbackError = "This video could not be played by this device ($detail). It may use an unsupported codec or container."
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.currentMediaItem?.localConfiguration?.uri?.toString()?.let { currentUri ->
                        viewModel.saveProgress(currentUri, player.duration, player.duration)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateKeepScreenOn()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        player.addListener(listener)
        updateKeepScreenOn()
        onDispose {
            player.currentMediaItem?.localConfiguration?.uri?.toString()?.let { currentUri ->
                viewModel.saveProgress(currentUri, player.currentPosition, player.duration.coerceAtLeast(0))
            }
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
            val duration = player.duration
            if (currentUri != null && duration > 0) {
                viewModel.saveProgress(currentUri, player.currentPosition, duration)
            }
        }
    }

    if (fullScreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerVideoView(player, Modifier.fillMaxSize())
            IconButton(
                onClick = onToggleFullScreen,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .clip(CircleShape).background(Color(0x99000000))
            ) {
                Icon(Icons.Default.FullscreenExit, contentDescription = "Exit full screen")
            }
        }
        return
    }

    val currentIndex = state.lessons.indexOfFirst { it.uri == lesson.uri }
    val previous = state.lessons.getOrNull(currentIndex - 1)
    val next = state.lessons.getOrNull(currentIndex + 1)

    Column(
        modifier.fillMaxSize().background(AppBackground).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { playlistOpen = true }) {
                Icon(Icons.Default.Book, contentDescription = "Open course content", tint = AppAccent)
            }
            Column(Modifier.weight(1f)) {
                Text(lesson.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(lesson.courseName, color = AppSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onToggleFullScreen) {
                Icon(Icons.Default.Fullscreen, contentDescription = "Enter full screen")
            }
        }
        PlayerVideoView(
            player,
            Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)
        )
        playbackError?.let { ErrorPanel(it) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { previous?.let { viewModel.openLesson(it.uri) } }, enabled = previous != null, modifier = Modifier.weight(1f)) {
                Text("← Previous", maxLines = 1)
            }
            Button(onClick = { next?.let { viewModel.openLesson(it.uri) } }, enabled = next != null, modifier = Modifier.weight(1f)) {
                Text("Next →", maxLines = 1)
            }
        }
        OutlinedButton(onClick = { subtitlePickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Import subtitle")
        }
        subtitles.forEach { subtitle ->
            Card(colors = CardDefaults.cardColors(containerColor = AppSurface)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(subtitle.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle.language, color = AppSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        viewModel.deleteSubtitle(subtitle)
                        subtitles = subtitles.filterNot { it.id == subtitle.id }
                    }) {
                        Text("Delete", color = Color(0xFFFF8A80))
                    }
                }
            }
        }
        if (subtitlePickerOpen) {
            AlertDialog(
                onDismissRequest = { subtitlePickerOpen = false },
                title = { Text("Import subtitle") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            subtitlePickerOpen = false
                            subtitlePicker.launch(arrayOf("application/x-subrip", "text/vtt", "application/octet-stream"))
                        }) { Text("Choose .srt or .vtt file") }
                        OutlinedTextField(subtitleLabel, { subtitleLabel = it }, label = { Text("Track name (optional)") }, singleLine = true)
                        OutlinedTextField(subtitleLanguage, { subtitleLanguage = it }, label = { Text("Language") }, singleLine = true)
                        subtitleError?.let { Text(it, color = Color(0xFFFF8A80)) }
                    }
                },
                confirmButton = { TextButton(onClick = { subtitlePickerOpen = false }) { Text("Close") } }
            )
        }
    }
    if (playlistOpen) {
        LaunchedEffect(lesson.uri) {
            val lessonIndex = courseLessonListIndex(state.lessons, lesson.uri)
            if (lessonIndex >= 0) playlistListState.animateScrollToItem(lessonIndex)
        }
        AlertDialog(
            onDismissRequest = { playlistOpen = false },
            title = {
                Column {
                    Text("Course content")
                    Text(lesson.name, color = AppSecondary, style = MaterialTheme.typography.bodySmall)
                }
            },
            text = {
                LazyColumn(
                    state = playlistListState,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    var lastFolder = ""
                    state.lessons.forEach { item ->
                        if (item.folderPath != lastFolder) {
                            lastFolder = item.folderPath
                            if (lastFolder.isNotBlank()) {
                                item(key = "playlist-folder:${item.folderPath}") {
                                    Text(
                                        item.folderPath,
                                        color = AppAccent,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                    )
                                }
                            }
                        }
                        item(key = "playlist-lesson:${item.uri}") {
                            LessonRow(
                                lesson = item,
                                active = item.uri == lesson.uri,
                                onClick = {
                                    playlistOpen = false
                                    viewModel.openLesson(item.uri)
                                },
                                onReset = { viewModel.resetCompletion(item.uri) },
                                onMarkComplete = { viewModel.markComplete(item.uri) }
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { playlistOpen = false }) { Text("Close") } }
        )
    }
}

private fun courseLessonListIndex(lessons: List<Lesson>, targetUri: String?): Int {
    if (targetUri == null) return -1
    var listIndex = 0
    var previousFolder = ""
    lessons.forEach { lesson ->
        if (lesson.folderPath != previousFolder) {
            previousFolder = lesson.folderPath
            if (previousFolder.isNotBlank()) listIndex++
        }
        if (lesson.uri == targetUri) return listIndex
        listIndex++
    }
    return -1
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun PlayerVideoView(player: Player, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                setShowSubtitleButton(true)
            }
        },
        update = { it.player = player }
    )
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun buildMediaItem(lesson: Lesson, subtitles: List<ImportedSubtitle>): MediaItem {
    val mediaItem = MediaItem.Builder().setUri(Uri.parse(lesson.uri))
    val configurations = subtitles.map { subtitle ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.uri))
            .setMimeType(subtitle.mimeType)
            .setLanguage(subtitle.language)
            .setLabel(subtitle.label)
            .build()
    }
    if (configurations.isNotEmpty()) mediaItem.setSubtitleConfigurations(configurations)
    return mediaItem.build()
}

@Composable
private fun PomodoroIcon(state: PomodoroState) {
    val duration = if (state.mode == "break") 5 * 60 * 1000L else 25 * 60 * 1000L
    val fraction = (state.remainingMs.toFloat() / duration).coerceIn(0f, 1f)
    val color = if (state.mode == "break") Color(0xFFFF5B5B) else AppAccent
    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            drawArc(Color.DarkGray, -90f, 360f, false, style = Stroke(stroke))
            drawArc(color, -90f, 360f * fraction, false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("🍅", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PomodoroMenu(
    state: PomodoroState,
    viewModel: AniplayerViewModel,
    onClose: () -> Unit,
    onToggle: () -> Unit
) {
    val remainingSeconds = (state.remainingMs + 999) / 1000
    val time = "%02d:%02d".format(Locale.ROOT, remainingSeconds / 60, remainingSeconds % 60)
    Column(Modifier.width(250.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pomodoro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(if (state.running) "${state.mode.replaceFirstChar(Char::uppercase)} session in progress" else "Ready when you are", color = AppSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { viewModel.setPomodoroMode("focus") }) { Text("Focus") }
            TextButton(onClick = { viewModel.setPomodoroMode("break") }) { Text("Break", color = Color(0xFFFF5B5B)) }
        }
        Text(time, modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onToggle, modifier = Modifier.weight(1f)) {
                Text(if (state.running) "Pause" else if (state.remainingMs == 0L) "Start again" else "Start")
            }
            OutlinedButton(onClick = { viewModel.resetPomodoro(); onClose() }, modifier = Modifier.weight(1f)) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun EmptyPanel(title: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = AppSurface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = AppSecondary)
        }
    }
}

@Composable
private fun ErrorPanel(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1D1D))) {
        Text(message, modifier = Modifier.padding(14.dp), color = Color(0xFFFF8A80))
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.ROOT, totalSeconds / 60, totalSeconds % 60)
}

private tailrec fun android.content.Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
