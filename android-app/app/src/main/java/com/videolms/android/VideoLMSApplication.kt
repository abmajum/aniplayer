package com.videolms.android

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.videolms.android.data.local.AppDatabase
import com.videolms.android.data.remote.ApiService
import com.videolms.android.data.repository.VideoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

// DataStore instance
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class VideoLMSApplication : Application() {
    
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }
    
    private var _apiService: ApiService? = null
    private var _repository: VideoRepository? = null
    
    val repository: VideoRepository
        get() {
            if (_repository == null) {
                _repository = VideoRepository(
                    appDao = database.appDao(),
                    apiService = getApiService(),
                    context = this
                )
            }
            return _repository!!
        }
    
    private fun getApiService(): ApiService? {
        return _apiService ?: createApiService(getServerBaseUrl())
    }
    
    private fun createApiService(baseUrl: String): ApiService? {
        return try {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            
            val client = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            
            val retrofit = Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            
            retrofit.create(ApiService::class.java)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get server base URL from DataStore preferences
     */
    fun getServerBaseUrl(): String {
        // Default value for emulator; will be overridden by saved preference
        return "http://192.168.1.100:8000"
    }
    
    /**
     * Flow of server URL from preferences
     */
    val serverUrlFlow: Flow<String> = applicationContext.dataStore.data
        .map { preferences ->
            preferences[SERVER_URL_KEY] ?: getServerBaseUrl()
        }
    
    /**
     * Update server URL in preferences and reinitialize API service
     */
    suspend fun updateServerUrl(newUrl: String) {
        applicationContext.dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = newUrl
        }
        // Reinitialize API service with new URL
        _apiService = createApiService(newUrl)
        _repository = null // Force recreation of repository
    }
    
    companion object {
        lateinit var instance: VideoLMSApplication
            private set
        
        fun getServerBaseUrl(context: Context): String {
            // Default fallback
            return "http://192.168.1.100:8000"
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize with default or saved URL
        _apiService = createApiService(getServerBaseUrl())
    }
}
