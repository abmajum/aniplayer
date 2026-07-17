package com.videolms.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val path: String,
    val coverUrl: String?,
    val totalVideos: Int = 0,
    val completedVideos: Int = 0,
    val completionPercentage: Int = 0
)

@Entity(tableName = "course_items")
data class CourseItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val courseId: Int,
    val parentId: Int?,
    val name: String,
    val path: String,
    val itemType: String,
    val filename: String?,
    val sortOrder: Int = 0,
    val isCompleted: Boolean = false,
    val watchedSeconds: Float = 0f,
    val duration: Float = 0f
)

@Entity(tableName = "progress")
data class Progress(
    @PrimaryKey val itemId: Int,
    val watchedSeconds: Float = 0f,
    val duration: Float = 0f,
    val isCompleted: Boolean = false
)

data class ProgressUpdateRequest(
    val video_id: Int,
    val watched_seconds: Float,
    val duration: Float
)

data class ProgressUpdateResponse(
    val status: String
)

sealed class VideoSource {
    object Local : VideoSource()
    data class Server(val baseUrl: String, val itemId: Int) : VideoSource()
}

data class VideoItem(
    val id: Int,
    val name: String,
    val path: String,
    val itemType: String,
    val courseId: Int,
    val courseName: String,
    val source: VideoSource,
    val watchedSeconds: Float = 0f,
    val duration: Float = 0f,
    val isCompleted: Boolean = false
)
