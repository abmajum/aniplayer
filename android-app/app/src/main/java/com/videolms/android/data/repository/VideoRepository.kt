package com.videolms.android.data.repository

import android.content.Context
import android.os.Environment
import com.videolms.android.data.local.AppDao
import com.videolms.android.data.local.AppDatabase
import com.videolms.android.data.model.Course
import com.videolms.android.data.model.CourseItem
import com.videolms.android.data.model.Progress
import com.videolms.android.data.model.VideoSource
import com.videolms.android.data.model.VideoItem
import com.videolms.android.data.remote.ApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VideoRepository(
    private val appDao: AppDao,
    private val apiService: ApiService? = null,
    private val context: Context
) {
    val courses: Flow<List<Course>> = appDao.getAllCourses()
    val continueWatching: Flow<List<CourseItem>> = appDao.getContinueWatching()
    
    fun getRootItemsForCourse(courseId: Int): Flow<List<CourseItem>> {
        return appDao.getRootItemsForCourse(courseId)
    }
    
    fun getItemsByParentId(parentId: Int): Flow<List<CourseItem>> {
        return appDao.getItemsByParentId(parentId)
    }
    
    fun getVideosWithProgress(courseId: Int): Flow<List<CourseItem>> {
        return appDao.getVideosWithProgress(courseId)
    }
    
    suspend fun getProgressByItemId(itemId: Int): Progress? {
        return withContext(Dispatchers.IO) {
            appDao.getProgressByItemId(itemId)
        }
    }
    
    suspend fun saveProgressLocal(itemId: Int, watchedSeconds: Float, duration: Float) {
        withContext(Dispatchers.IO) {
            val isCompleted = watchedSeconds >= (duration * 0.95f)
            val progress = Progress(
                itemId = itemId,
                watchedSeconds = watchedSeconds,
                duration = duration,
                isCompleted = isCompleted
            )
            
            val existing = appDao.getProgressByItemId(itemId)
            if (existing != null) {
                val updated = progress.copy(
                    watchedSeconds = maxOf(existing.watchedSeconds, watchedSeconds),
                    isCompleted = existing.isCompleted || isCompleted
                )
                appDao.updateProgress(updated)
            } else {
                appDao.insertProgress(progress)
            }
        }
    }
    
    suspend fun saveProgressServer(itemId: Int, watchedSeconds: Float, duration: Float): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (apiService == null) {
                    return@withContext Result.failure(Exception("API service not available"))
                }
                
                val request = com.videolms.android.data.model.ProgressUpdateRequest(
                    video_id = itemId,
                    watched_seconds = watchedSeconds,
                    duration = duration
                )
                
                apiService.updateProgress(request)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    suspend fun saveProgressBoth(itemId: Int, watchedSeconds: Float, duration: Float) {
        saveProgressLocal(itemId, watchedSeconds, duration)
        saveProgressServer(itemId, watchedSeconds, duration)
    }
    
    suspend fun scanLocalVideoFiles(): List<CourseItem> {
        return withContext(Dispatchers.IO) {
            val videoItems = mutableListOf<CourseItem>()
            val videoExtensions = setOf(".mp4", ".mkv", ".webm", ".avi", ".mov")
            
            val directoriesToScan = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                File(Environment.getExternalStorageDirectory(), "Videos"),
                File(Environment.getExternalStorageDirectory(), "Courses")
            )
            
            for (baseDir in directoriesToScan) {
                if (baseDir.exists() && baseDir.isDirectory) {
                    scanDirectory(baseDir, null, videoItems, videoExtensions)
                }
            }
            
            videoItems
        }
    }
    
    private fun scanDirectory(
        dir: File,
        parentId: Int?,
        videoItems: MutableList<CourseItem>,
        videoExtensions: Set<String>,
        courseId: Int = -1
    ) {
        val files = dir.listFiles() ?: return
        
        for (file in files.sortedBy { it.name.lowercase() }) {
            if (file.isDirectory) {
                val folderItem = CourseItem(
                    name = file.name,
                    path = file.absolutePath,
                    itemType = "folder",
                    filename = null,
                    courseId = courseId,
                    parentId = parentId,
                    sortOrder = 0
                )
                // Note: This needs to be called from a coroutine context
                // For now, we'll skip inserting folders during scan
                // folderId handling would require refactoring to be fully async
            } else {
                val extension = file.extension.lowercase()
                if (extension in videoExtensions) {
                    val item = CourseItem(
                        name = file.nameWithoutExtension,
                        path = file.absolutePath,
                        itemType = "video",
                        filename = file.name,
                        courseId = courseId,
                        parentId = parentId,
                        sortOrder = 1
                    )
                    videoItems.add(item)
                }
            }
        }
    }
    
    fun getVideoStreamUrl(serverBaseUrl: String, itemId: Int): String {
        return "$serverBaseUrl/stream/$itemId"
    }
}
