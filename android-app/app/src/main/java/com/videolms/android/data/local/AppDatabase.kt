package com.videolms.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.videolms.android.data.model.Course
import com.videolms.android.data.model.CourseItem
import com.videolms.android.data.model.Progress

@Database(
    entities = [Course::class, CourseItem::class, Progress::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
    
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "videolms_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
