# Product Requirement Document (PRD)

## 1. Document Overview
* **Nama Produk:** FastCloud Storage Lite
* **Platform Mobile:** Android (Kotlin)
* **Platform Backend:** Rust (Fastly Compute@Edge)
* **Storage Provider:** S3-Compatible Object Storage (e.g., AWS S3 / Cloudflare R2 / MinIO)
* **Versi Document:** 1.0.0
* **Status:** Ready for Development

---

## 2. Product Summary
Aplikasi Android ringan yang memungkinkan pengguna untuk mengunggah (*upload*) dan membaca/mengunduh (*download*) file secara langsung melalui edge backend yang dibangun menggunakan **Rust** dan berjalan di atas serverless platform **Fastly Compute@Edge**. Backend tidak menggunakan database (stateless); seluruh metadata dan manajemen file bergantung pada **Object Storage** dan Fastly Edge KV Store/Secret Store untuk konfigurasi.

---

## 3. Goals & Objectives
1. **Performa Tinggi & Latensi Rendah:** Memanfaatkan eksekusi Fastly Compute@Edge (Wasm/Rust) untuk memproses permintaan di lokasi edge terdekat pengguna.
2. **Stateless & Database-less:** Mengurangi kompleksitas dan biaya infrastruktur tanpa menggunakan RDBMS/NoSQL.
3. **Aplikasi Android Ringan:** Menggunakan Kotlin native dengan library minimalis agar ukuran APK kecil dan hemat memori/baterai.
4. **Keamanan Direct Access:** Backend bertindak sebagai proxy aman atau pembuat Presigned URL untuk akses file langsung ke Object Storage.

---

## 4. System Architecture Overview

```
+------------------+         HTTP/HTTPS          +----------------------------+
|  Android App     | <-------------------------> | Fastly Compute@Edge (Rust) |
|  (Kotlin Native) |                             +----------------------------+
+------------------+                                           |
                                                               | AWS SDK / S3 API
                                                               v
                                                 +----------------------------+
                                                 | Object Storage             |
                                                 | (S3 / R2 / MinIO)          |
                                                 +----------------------------+
```

---

## 5. User Stories & Functional Requirements

### 5.1 Android Application (Frontend)

| ID | Feature | User Story | Requirement Detail |
|---|---|---|---|
| **FR-M01** | Pilih File | Sebagai pengguna, saya ingin memilih file dari penyimpanan lokal HP. | Menggunakan Android Storage Access Framework (SAF) / File Picker. |
| **FR-M02** | Upload File | Sebagai pengguna, saya ingin mengunggah file ke cloud. | Menampilkan progress bar (*determinate*) saat proses pengunggahan berlangsung. |
| **FR-M03** | Lihat Daftar File | Sebagai pengguna, saya ingin melihat daftar file yang tersimpan. | Menampilkan daftar file dalam bentuk list/card (*RecyclerView*) lengkap dengan nama file, ukuran, dan tanggal. |
| **FR-M04** | Baca / Pratinjau File | Sebagai pengguna, saya ingin membaca/melihat konten file (gambar/teks/PDF). | Menampilkan preview file langsung di aplikasi jika format didukung, atau menyediakan tombol unduh/buka di app eksternal. |
| **FR-M05** | Hapus File | Sebagai pengguna, saya ingin menghapus file yang ada di storage. | Konfirmasi hapus file dan mengirimkan request *delete* ke backend. |
| **FR-M06** | Swipe to Refresh | Sebagai pengguna, saya ingin memperbarui daftar file secara manual. | Menggunakan komponen `SwipeRefreshLayout`. |

### 5.2 Fastly Compute@Edge Backend (Rust)

| ID | Feature | Description | Technical Detail |
|---|---|---|---|
| **FR-B01** | List Objects | Mengambil daftar file dari Object Storage. | Memanggil S3 API `ListObjectsV2` dan mengembalikan JSON ke Android App. |
| **FR-B02** | Stream Upload | Menerima stream file dari Android dan mengunggahnya ke S3. | Mendukung `PUT /upload/{filename}` dengan streaming body (*zero-buffering* di memory backend). |
| **FR-B03** | Presigned URL / Download Proxy | Membaca file dari Object Storage. | Menyediakan endpoint `GET /file/{filename}` yang mengalirkan (*stream*) data dari S3 ke client dengan header `Content-Type` sesuai file. |
| **FR-B04** | Delete Object | Menghapus file dari Object Storage. | Memanggil S3 API `DeleteObject`. |
| **FR-B05** | CORS Management | Izinkan request dari aplikasi Android / domain terkait. | Menangani preflight OPTIONS request dan menambahkan header CORS yang benar. |

---

## 6. Non-Functional Requirements

1. **Ukuran APK:** Ukuran APK release di bawah **8 MB**.
2. **Min SDK Android:** API Level 24 (Android 7.0 Nougat) ke atas.
3. **Response Time Backend:** Latensi kurang dari **100ms** (di luar transfer waktu file) karena berjalan di Fastly Edge.
4. **Keamanan:**
   - Semua komunikasi wajib menggunakan **HTTPS / TLS 1.3**.
   - API Key / Secret Key Object Storage disimpan pada Fastly Secret Store, tidak *hardcoded* di kode Rust maupun Android.

---

## 7. API Specification

### 1. GET `/api/v1/files`
* **Deskripsi:** Mengambil list semua file di Object Storage.
* **Response (200 OK):**
```json
{
  "success": true,
  "data": [
    {
      "name": "document.pdf",
      "size": 1048576,
      "last_modified": "2026-10-02T10:00:00Z"
    },
    {
      "name": "photo.jpg",
      "size": 204800,
      "last_modified": "2026-10-02T11:15:00Z"
    }
  ]
}
```

### 2. PUT `/api/v1/files/upload?name={filename}`
* **Deskripsi:** Mengunggah file baru.
* **Header:** `Content-Type: application/octet-stream` (atau tipe MIME file yang sesuai)
* **Body:** Binary stream dari file.
* **Response (201 Created):**
```json
{
  "success": true,
  "message": "File uploaded successfully",
  "filename": "document.pdf"
}
```

### 3. GET `/api/v1/files/{filename}`
* **Deskripsi:** Membaca/Mengunduh file.
* **Response (200 OK):** Binary stream file dengan header `Content-Type` yang sesuai (misal: `image/png`, `application/pdf`).

### 4. DELETE `/api/v1/files/{filename}`
* **Deskripsi:** Menghapus file dari storage.
* **Response (200 OK):**
```json
{
  "success": true,
  "message": "File deleted successfully"
}
```

---

## 8. Implementation Code Details

### 8.1 Backend Implementation (Rust on Fastly Compute@Edge)

#### `Cargo.toml`
```toml
[package]
name = "fastly-object-storage-backend"
version = "0.1.0"
edition = "2021"

[dependencies]
fastly = "0.9.8"
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
aws-credential-types = "0.55"
aws-sigv4 = "0.55"
http = "0.2"
bytes = "1.4"
chrono = "0.4"
```

#### `src/main.rs`
```rust
use fastly::http::{header, Method, StatusCode};
use fastly::{Error, Request, Response};
use serde::{Deserialize, Serialize};

const BUCKET_NAME: &str = "my-app-storage";
const S3_ENDPOINT: &str = "https://s3.us-east-1.amazonaws.com"; // Ubah sesuai provider (R2/MinIO/AWS)
const S3_BACKEND_NAME: &str = "s3_origin"; // Nama backend yang terdaftar di fastly.toml

#[derive(Serialize, Deserialize)]
struct FileItem {
    name: String,
    size: u64,
    last_modified: String,
}

#[derive(Serialize, Deserialize)]
struct ApiResponse<T> {
    success: bool,
    data: Option<T>,
    message: Option<String>,
}

#[fastly::main]
fn main(req: Request) -> Result<Response, Error> {
    // Handle CORS
    if req.get_method() == Method::OPTIONS {
        return Ok(Response::from_status(StatusCode::OK)
            .with_header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            .with_header(header::ACCESS_CONTROL_ALLOW_METHODS, "GET, PUT, DELETE, OPTIONS")
            .with_header(header::ACCESS_CONTROL_ALLOW_HEADERS, "*"));
    }

    let path = req.get_path().to_string();
    let method = req.get_method().clone();

    let res = match (method, path.as_str()) {
        (Method::GET, "/api/v1/files") => handle_list_files(),
        (Method::PUT, path) if path.starts_with("/api/v1/files/upload") => handle_upload_file(req),
        (Method::GET, path) if path.starts_with("/api/v1/files/") => {
            let filename = path.trim_start_matches("/api/v1/files/");
            handle_read_file(filename)
        }
        (Method::DELETE, path) if path.starts_with("/api/v1/files/") => {
            let filename = path.trim_start_matches("/api/v1/files/");
            handle_delete_file(filename)
        }
        _ => Ok(Response::from_status(StatusCode::NOT_FOUND)
            .with_body_json(&ApiResponse::<()>{
                success: false,
                data: None,
                message: Some("Endpoint not found".to_string()),
            })?),
    };

    let mut response = res?;
    response.set_header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    Ok(response)
}

fn handle_list_files() -> Result<Response, Error> {
    let s3_url = format!("{}/{}?list-type=2", S3_ENDPOINT, BUCKET_NAME);
    let s3_req = Request::get(s3_url);
    
    // Kirim request ke Object Storage Backend
    let mut s3_res = s3_req.send(S3_BACKEND_NAME)?;
    
    if s3_res.get_status() == StatusCode::OK {
        // Pada implementasi produksi, lakukan parsing XML dari S3 ListObjectsV2
        let dummy_files = vec![
            FileItem {
                name: "example.pdf".to_string(),
                size: 1024500,
                last_modified: "2026-10-02T10:00:00Z".to_string(),
            },
            FileItem {
                name: "sample.jpg".to_string(),
                size: 512000,
                last_modified: "2026-10-02T12:00:00Z".to_string(),
            }
        ];

        Ok(Response::from_status(StatusCode::OK)
            .with_body_json(&ApiResponse {
                success: true,
                data: Some(dummy_files),
                message: None,
            })?)
    } else {
        Ok(Response::from_status(s3_res.get_status())
            .with_body_json(&ApiResponse::<()>{
                success: false,
                data: None,
                message: Some("Failed to fetch list from storage".to_string()),
            })?)
    }
}

fn handle_upload_file(req: Request) -> Result<Response, Error> {
    let query_filename = req.get_query_str()
        .and_then(|q| q.split('&').find(|pair| pair.starts_with("name=")))
        .map(|pair| pair.trim_start_matches("name="))
        .unwrap_or("unnamed_file");

    let s3_url = format!("{}/{}/{}", S3_ENDPOINT, BUCKET_NAME, query_filename);
    
    // Stream request langsung dari client ke S3 tanpa buffering penuh di memori
    let s3_req = Request::put(s3_url)
        .with_body(req.into_body());

    let s3_res = s3_req.send(S3_BACKEND_NAME)?;

    if s3_res.get_status().is_success() {
        Ok(Response::from_status(StatusCode::CREATED)
            .with_body_json(&ApiResponse::<()>{
                success: true,
                data: None,
                message: Some(format!("File {} uploaded successfully", query_filename)),
            })?)
    } else {
        Ok(Response::from_status(s3_res.get_status())
            .with_body_json(&ApiResponse::<()>{
                success: false,
                data: None,
                message: Some("Failed to upload file to storage".to_string()),
            })?)
    }
}

fn handle_read_file(filename: &str) -> Result<Response, Error> {
    let s3_url = format!("{}/{}/{}", S3_ENDPOINT, BUCKET_NAME, filename);
    let s3_req = Request::get(s3_url);

    let s3_res = s3_req.send(S3_BACKEND_NAME)?;

    if s3_res.get_status().is_success() {
        Ok(s3_res)
    } else {
        Ok(Response::from_status(s3_res.get_status())
            .with_body_json(&ApiResponse::<()>{
                success: false,
                data: None,
                message: Some("File not found or unreadable".to_string()),
            })?)
    }
}

fn handle_delete_file(filename: &str) -> Result<Response, Error> {
    let s3_url = format!("{}/{}/{}", S3_ENDPOINT, BUCKET_NAME, filename);
    let s3_req = Request::delete(s3_url);

    let s3_res = s3_req.send(S3_BACKEND_NAME)?;

    if s3_res.get_status().is_success() {
        Ok(Response::from_status(StatusCode::OK)
            .with_body_json(&ApiResponse::<()>{
                success: true,
                data: None,
                message: Some(format!("File {} deleted successfully", filename)),
            })?)
    } else {
        Ok(Response::from_status(s3_res.get_status())
            .with_body_json(&ApiResponse::<()>{
                success: false,
                data: None,
                message: Some("Failed to delete file".to_string()),
            })?)
    }
}
```

---

### 8.2 Android Application Implementation (Kotlin)

#### `build.gradle.kts` (Module: app)
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.fastcloudstorage"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.fastcloudstorage"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Networking Ringan: Retrofit & OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Image Loader Ringan (Opsional)
    implementation("io.coil-kt:coil:2.5.0")
}
```

#### `FileItem.kt` (Model Data)
```kotlin
package com.example.fastcloudstorage.model

import com.google.gson.annotations.SerializedName

data class FileItem(
    @SerializedName("name") val name: String,
    @SerializedName("size") val size: Long,
    @SerializedName("last_modified") val lastModified: String
)

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: T?,
    @SerializedName("message") val message: String?
)
```

#### `ApiService.kt` (Retrofit Interface)
```kotlin
package com.example.fastcloudstorage.network

import com.example.fastcloudstorage.model.ApiResponse
import com.example.fastcloudstorage.model.FileItem
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @GET("api/v1/files")
    suspend fun getFileList(): Response<ApiResponse<List<FileItem>>>

    @PUT("api/v1/files/upload")
    suspend fun uploadFile(
        @Query("name") fileName: String,
        @Body fileBody: RequestBody
    ): Response<ApiResponse<Unit>>

    @GET("api/v1/files/{filename}")
    @Streaming
    suspend fun downloadFile(
        @Path("filename") fileName: String
    ): Response<ResponseBody>

    @DELETE("api/v1/files/{filename}")
    suspend fun deleteFile(
        @Path("filename") fileName: String
    ): Response<ApiResponse<Unit>>
}
```

#### `ApiClient.kt`
```kotlin
package com.example.fastcloudstorage.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val BASE_URL = "https://your-fastly-compute-domain.edgecompute.app/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
```

#### `FileAdapter.kt` (RecyclerView Adapter)
```kotlin
package com.example.fastcloudstorage.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.fastcloudstorage.databinding.ItemFileBinding
import com.example.fastcloudstorage.model.FileItem

class FileAdapter(
    private var fileList: List<FileItem>,
    private val onItemClick: (FileItem) -> Unit,
    private val onDeleteClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    inner class FileViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val item = fileList[position]
        with(holder.binding) {
            tvFileName.text = item.name
            tvFileSize.text = "${item.size / 1024} KB"
            tvFileDate.text = item.lastModified

            root.setOnClickListener { onItemClick(item) }
            btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }

    override fun getItemCount(): Int = fileList.size

    fun updateData(newList: List<FileItem>) {
        fileList = newList
        notifyDataSetChanged()
    }
}
```

#### `MainActivity.kt`
```kotlin
package com.example.fastcloudstorage

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.fastcloudstorage.adapter.FileAdapter
import com.example.fastcloudstorage.databinding.ActivityMainBinding
import com.example.fastcloudstorage.model.FileItem
import com.example.fastcloudstorage.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { uploadSelectedFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()

        binding.swipeRefresh.setOnRefreshListener {
            fetchFiles()
        }

        binding.fabUpload.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

        fetchFiles()
    }

    private fun setupRecyclerView() {
        adapter = FileAdapter(
            fileList = emptyList(),
            onItemClick = { file -> downloadAndOpenFile(file) },
            onDeleteClick = { file -> deleteFile(file) }
        )
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = adapter
    }

    private fun fetchFiles() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = ApiClient.instance.getFileList()
                withContext(Dispatchers.Main) {
                    binding.swipeRefresh.isRefreshing = false
                    if (response.isSuccessful && response.body()?.success == true) {
                        val files = response.body()?.data ?: emptyList()
                        adapter.updateData(files)
                    } else {
                        Toast.makeText(this@MainActivity, "Gagal mengambil daftar file", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.swipeRefresh.isRefreshing = false
                    Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun uploadSelectedFile(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileNameFromUri(uri) ?: "uploaded_file"
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes() ?: ByteArray(0)
                
                val requestBody: RequestBody = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())

                val response = ApiClient.instance.uploadFile(fileName, requestBody)

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body()?.success == true) {
                        Toast.makeText(this@MainActivity, "Berhasil mengunggah file", Toast.LENGTH_SHORT).show()
                        fetchFiles()
                    } else {
                        Toast.makeText(this@MainActivity, "Gagal mengunggah file", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Error upload: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadAndOpenFile(file: FileItem) {
        Toast.makeText(this, "Membuka file ${file.name}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = ApiClient.instance.downloadFile(file.name)
                if (response.isSuccessful && response.body() != null) {
                    val bytes = response.body()!!.bytes()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "File terunduh: ${bytes.size} bytes", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Gagal mengunduh file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteFile(file: FileItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = ApiClient.instance.deleteFile(file.name)
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body()?.success == true) {
                        Toast.makeText(this@MainActivity, "File berhasil dihapus", Toast.LENGTH_SHORT).show()
                        fetchFiles()
                    } else {
                        Toast.makeText(this@MainActivity, "Gagal menghapus file", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error hapus: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = it.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}
```

#### Layout XML: `activity_main.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <ProgressBar
        android:id="@+id/progressBar"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:indeterminate="true"
        android:visibility="gone"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        android:id="@+id/swipeRefresh"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toBottomOf="@id/progressBar"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/rvFiles"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clipToPadding="false"
            android:padding="8dp" />

    </androidx.swiperefreshlayout.widget.SwipeRefreshLayout>

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fabUpload"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        android:contentDescription="Upload File"
        android:src="@android:drawable/ic_menu_upload"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

#### Layout XML: `item_file.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginVertical="4dp"
    app:cardCornerRadius="8dp"
    app:cardElevation="2dp">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="12dp">

        <TextView
            android:id="@+id/tvFileName"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:text="File_Name.pdf"
            android:textSize="16sp"
            android:textStyle="bold"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toStartOf="@id/btnDelete"
            app:layout_constraintTop_toTopOf="parent" />

        <TextView
            android:id="@+id/tvFileSize"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="1024 KB"
            android:textSize="12sp"
            android:textColor="#666666"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toBottomOf="@id/tvFileName" />

        <TextView
            android:id="@+id/tvFileDate"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"
            android:text="2026-10-02"
            android:textSize="12sp"
            android:textColor="#666666"
            app:layout_constraintStart_toEndOf="@id/tvFileSize"
            app:layout_constraintTop_toBottomOf="@id/tvFileName" />

        <ImageButton
            android:id="@+id/btnDelete"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Hapus File"
            android:src="@android:drawable/ic_menu_delete"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintBottom_toBottomOf="parent" />

    </androidx.constraintlayout.widget.ConstraintLayout>
</com.google.android.material.card.MaterialCardView>
```

---

## 9. Test Plan & Acceptance Criteria

1. **Uji Coba Upload:**
   - Pilih file berukuran kecil (< 5MB) dan besar (> 20MB) dari Android.
   - **Kriteria Diterima:** File terunggah secara utuh di Object Storage dan muncul di daftar file tanpa kegagalan koneksi.
2. **Uji Coba Read / Download:**
   - Klik item file pada aplikasi Android.
   - **Kriteria Diterima:** Backend Fastly Compute mengirimkan stream file yang dapat diunduh/dibaca dengan benar.
3. **Uji Coba Delete:**
   - Klik ikon hapus pada salah satu file.
   - **Kriteria Diterima:** File terhapus dari Object Storage dan otomatis hilang dari UI aplikasi Android.
4. **Resiliency Testing:**
   - Matikan jaringan seluler/Wi-Fi saat pengunggahan berlangsung.
   - **Kriteria Diterima:** Aplikasi Android menampilkan Toast error yang ramah dan tidak mengalami *crash*.