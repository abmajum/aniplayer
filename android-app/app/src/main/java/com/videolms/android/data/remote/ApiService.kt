package com.videolms.android.data.remote

import com.videolms.android.data.model.ProgressUpdateRequest
import com.videolms.android.data.model.ProgressUpdateResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming

interface ApiService {
    @GET("api/courses")
    suspend fun getCourses(): List<Map<String, Any>>
    
    @GET("api/course/{courseId}")
    suspend fun getCourseDetail(@Path("courseId") courseId: Int): Map<String, Any>
    
    @GET("stream/{itemId}")
    @Streaming
    suspend fun streamVideo(@Path("itemId") itemId: Int): okhttp3.ResponseBody
    
    @POST("api/progress")
    suspend fun updateProgress(@Body progress: ProgressUpdateRequest): ProgressUpdateResponse
    
    @GET("video/{itemId}")
    suspend fun getVideoInfo(@Path("itemId") itemId: Int): Map<String, Any>
}
