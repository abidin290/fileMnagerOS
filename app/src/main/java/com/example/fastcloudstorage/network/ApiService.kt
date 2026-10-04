package com.example.fastcloudstorage.network

import com.example.fastcloudstorage.model.ApiResponse
import com.example.fastcloudstorage.model.FileItem
import com.example.fastcloudstorage.model.RenameFileRequest
import com.example.fastcloudstorage.model.UploadUrlRequest
import com.example.fastcloudstorage.model.UploadUrlResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {
    @GET("api/v1/files")
    suspend fun getFileList(): Response<ApiResponse<List<FileItem>>>

    @POST("api/v1/files/upload-url")
    suspend fun createUploadUrl(
        @Body request: UploadUrlRequest,
    ): Response<ApiResponse<UploadUrlResponse>>

    @GET("api/v1/files/{filename}")
    suspend fun getFileUrl(
        @Path(value = "filename", encoded = true) fileName: String,
    ): Response<ApiResponse<FileItem>>

    @DELETE("api/v1/files/{filename}")
    suspend fun deleteFile(
        @Path(value = "filename", encoded = true) fileName: String,
    ): Response<ApiResponse<Unit>>

    @POST("api/v1/files/rename")
    suspend fun renameFile(
        @Body request: RenameFileRequest,
    ): Response<ApiResponse<Unit>>
}
