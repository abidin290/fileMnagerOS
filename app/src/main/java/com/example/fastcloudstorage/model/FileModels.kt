package com.example.fastcloudstorage.model

import com.google.gson.annotations.SerializedName

data class FileItem(
    @SerializedName("name") val name: String,
    @SerializedName("size") val size: Long,
    @SerializedName("last_modified") val lastModified: String,
    @SerializedName("content_type") val contentType: String? = null,
    @SerializedName("download_url") val downloadUrl: String? = null,
)

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: T?,
    @SerializedName("message") val message: String?,
)

data class UploadUrlRequest(
    @SerializedName("name") val name: String,
    @SerializedName("content_type") val contentType: String,
    @SerializedName("size") val size: Long,
)

data class UploadUrlResponse(
    @SerializedName("object_key") val objectKey: String,
    @SerializedName("upload_url") val uploadUrl: String,
    @SerializedName("public_url") val publicUrl: String?,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("headers") val headers: Map<String, String> = emptyMap(),
)

data class RenameFileRequest(
    @SerializedName("old_name") val oldName: String,
    @SerializedName("new_name") val newName: String,
)
