package com.videolms.android.data.local

import androidx.room.*
import com.videolms.android.data.model.Course
import com.videolms.android.data.model.CourseItem
import com.videolms.android.data.model.Progress
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // Courses
    @Query("SELECT * FROM courses ORDER BY name")
    fun getAllCourses(): Flow<List<Course>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: Course): Long
    
    @Update
    suspend fun updateCourse(course: Course)
    
    @Query("SELECT * FROM courses WHERE id = :courseId")
    suspend fun getCourseById(courseId: Int): Course?
    
    // Course Items
    @Query("SELECT * FROM course_items WHERE courseId = :courseId AND parentId IS NULL ORDER BY sortOrder, name")
    fun getRootItemsForCourse(courseId: Int): Flow<List<CourseItem>>
    
    @Query("SELECT * FROM course_items WHERE parentId = :parentId ORDER BY sortOrder, name")
    fun getItemsByParentId(parentId: Int): Flow<List<CourseItem>>
    
    @Query("SELECT * FROM course_items WHERE id = :itemId")
    suspend fun getItemById(itemId: Int): CourseItem?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourseItem(item: CourseItem): Long
    
    @Update
    suspend fun updateCourseItem(item: CourseItem)
    
    @Query("SELECT * FROM course_items WHERE courseId = :courseId AND itemType = 'video' ORDER BY name")
    fun getAllVideosForCourse(courseId: Int): Flow<List<CourseItem>>
    
    // Progress
    @Query("SELECT * FROM progress WHERE itemId = :itemId")
    suspend fun getProgressByItemId(itemId: Int): Progress?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: Progress)
    
    @Update
    suspend fun updateProgress(progress: Progress)
    
    @Query("SELECT * FROM progress")
    fun getAllProgress(): Flow<List<Progress>>
    
    @Query("SELECT ci.*, COALESCE(p.watchedSeconds, 0) as watchedSeconds, COALESCE(p.duration, 0) as duration, COALESCE(p.isCompleted, 0) as isCompleted FROM course_items ci LEFT JOIN progress p ON ci.id = p.itemId WHERE ci.courseId = :courseId AND ci.itemType = 'video'")
    @RewriteQueriesToDropUnusedColumns
    fun getVideosWithProgress(courseId: Int): Flow<List<CourseItem>>
    
    @Query("SELECT ci.*, COALESCE(p.watchedSeconds, 0) as watchedSeconds, COALESCE(p.duration, 0) as duration, COALESCE(p.isCompleted, 0) as isCompleted FROM course_items ci JOIN courses c ON ci.courseId = c.id LEFT JOIN progress p ON ci.id = p.itemId WHERE ci.itemType = 'video' AND p.watchedSeconds > 0 AND p.isCompleted = 0 ORDER BY p.watchedSeconds DESC LIMIT 6")
    @RewriteQueriesToDropUnusedColumns
    fun getContinueWatching(): Flow<List<CourseItem>>
}
