package com.videolms.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.videolms.android.data.model.CourseItem
import com.videolms.android.ui.screens.CourseContentScreen
import com.videolms.android.ui.screens.HomeScreen
import com.videolms.android.ui.screens.SettingsScreen
import com.videolms.android.ui.screens.VideoPlayerScreen
import com.videolms.android.ui.theme.VideoLMSTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            scanLocalVideos()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkAndRequestPermissions()
        
        setContent {
            VideoLMSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
            }
        }
    }
    
    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            scanLocalVideos()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    private fun scanLocalVideos() {
        lifecycleScope.launch {
            (application as VideoLMSApplication).repository.scanLocalVideoFiles()
        }
    }
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as VideoLMSApplication
    val repository = application.repository
    
    var courses by remember { mutableStateOf<List<com.videolms.android.data.model.Course>>(emptyList()) }
    var continueWatching by remember { mutableStateOf<List<CourseItem>>(emptyList()) }
    var currentCourseItems by remember { mutableStateOf<List<CourseItem>>(emptyList()) }
    var selectedVideo by remember { mutableStateOf<CourseItem?>(null) }
    
    // Collect courses
    LaunchedEffect(Unit) {
        launch {
            repository.courses.collectLatest { courses = it }
        }
        launch {
            repository.continueWatching.collectLatest { continueWatching = it }
        }
    }
    
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                courses = courses,
                continueWatching = continueWatching,
                onCourseClick = { course ->
                    navController.navigate("course/${course.id}")
                },
                onLocalVideosClick = {
                    // For local videos without server connection
                    navController.navigate("local-videos")
                },
                onVideoClick = { video ->
                    selectedVideo = video
                    navController.navigate("video/${video.id}?source=local")
                },
                onSettingsClick = {
                    navController.navigate("settings")
                }
            )
        }
        
        composable(
            route = "course/{courseId}",
            arguments = listOf(navArgument("courseId") { type = NavType.IntType })
        ) { backStackEntry ->
            val courseId = backStackEntry.arguments?.getInt("courseId") ?: return@composable
            
            LaunchedEffect(courseId) {
                repository.getRootItemsForCourse(courseId).collectLatest { items ->
                    currentCourseItems = items
                }
            }
            
            CourseContentScreen(
                courseName = courses.find { it.id == courseId }?.name ?: "Course",
                items = currentCourseItems,
                onFolderClick = { folder ->
                    // Navigate to subfolder or expand
                },
                onVideoClick = { video ->
                    selectedVideo = video
                    navController.navigate("video/${video.id}?source=server")
                }
            )
        }
        
        composable(
            route = "video/{videoId}?source={source}",
            arguments = listOf(
                navArgument("videoId") { type = NavType.IntType },
                navArgument("source") { defaultValue = "local" }
            )
        ) { backStackEntry ->
            val videoId = backStackEntry.arguments?.getInt("videoId") ?: return@composable
            val source = backStackEntry.arguments?.getString("source") ?: "local"
            
            val video = selectedVideo ?: return@composable
            
            val serverBaseUrl = if (source == "server") {
                VideoLMSApplication.getServerBaseUrl(LocalContext.current)
            } else null
            
            VideoPlayerScreen(
                videoItem = video,
                serverBaseUrl = serverBaseUrl,
                onProgressUpdate = { watchedSeconds, duration ->
                    lifecycleScope.launch {
                        // Save to both local DB and server
                        repository.saveProgressBoth(videoId, watchedSeconds, duration)
                    }
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable("local-videos") {
            // Local videos screen - can be implemented similar to course content
            CourseContentScreen(
                courseName = "Local Videos",
                items = emptyList(), // Would populate from scanned local files
                onFolderClick = { },
                onVideoClick = { video ->
                    selectedVideo = video
                    navController.navigate("video/${video.id}?source=local")
                }
            )
        }
        
        composable("settings") {
            SettingsScreen()
        }
    }
}
