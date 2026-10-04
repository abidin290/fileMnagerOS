package com.example.fastcloudstorage

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.widget.TooltipCompat
import com.google.android.material.tabs.TabLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.example.fastcloudstorage.adapter.FileAdapter
import com.example.fastcloudstorage.databinding.ActivityMainBinding
import com.example.fastcloudstorage.model.FileItem
import com.example.fastcloudstorage.model.RenameFileRequest
import com.example.fastcloudstorage.model.UploadUrlRequest
import com.example.fastcloudstorage.network.ApiClient
import com.example.fastcloudstorage.network.ContentUriRequestBody
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import java.net.URLEncoder
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter
    private val gson = Gson()
    private val allFiles = mutableListOf<FileItem>()
    private var searchQuery = ""
    private var filterMode = FilterMode.ALL
    private var sortMode = SortMode.NEWEST
    private var folderMode = "Semua folder"
    private val preferences by lazy { getSharedPreferences("fastcloud_ui", MODE_PRIVATE) }
    private var favorites = mutableSetOf<String>()
    private var gridMode = false
    private var activeTab = 0
    private var hasLoaded = false
    private var uploading = false
    private var refreshInFlight = false
    private val uploadHistory = mutableListOf<String>()

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) uploadSelectedFiles(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        favorites = preferences.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        gridMode = preferences.getBoolean("grid", false)
        runCatching {
            uploadHistory.addAll(gson.fromJson(preferences.getString("history", "[]"), Array<String>::class.java))
        }
        activeTab = savedInstanceState?.getInt("tab") ?: 0
        filterMode = FilterMode.values().getOrElse(savedInstanceState?.getInt("filter") ?: 0) { FilterMode.ALL }
        sortMode = SortMode.values().getOrElse(preferences.getInt("sort", 0)) { SortMode.NEWEST }
        folderMode = savedInstanceState?.getString("folder") ?: "Semua folder"

        adapter = FileAdapter(
            onItemClick = ::openFile,
            onMoreClick = ::showFileMenu,
            onFavoriteClick = ::toggleFavorite,
        )
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = adapter
        updateViewMode()
        binding.swipeRefresh.setOnRefreshListener { fetchFiles() }
        binding.swipeRefresh.setColorSchemeResources(R.color.brand)
        binding.fabUpload.setOnClickListener { pickFilesLauncher.launch("*/*") }
        binding.fabNewFolder.setOnClickListener { showCreateFolderDialog() }
        binding.btnSort.setOnClickListener { showSortMenu() }
        binding.btnFolder.setOnClickListener { showFolderMenu() }
        binding.btnRefresh.setOnClickListener { fetchFiles() }
        binding.btnHistory.setOnClickListener { showUploadHistory() }
        binding.btnClearSearch.setOnClickListener { binding.etSearch.text.clear() }
        binding.btnViewMode.setOnClickListener {
            gridMode = !gridMode
            preferences.edit().putBoolean("grid", gridMode).apply()
            updateViewMode()
        }
        listOf(binding.btnRefresh, binding.btnHistory, binding.btnSort, binding.btnViewMode).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        listOf("File", "Favorit", "Terbaru").forEach { binding.tabs.addTab(binding.tabs.newTab().setText(it)) }
        binding.tabs.getTabAt(activeTab)?.select()
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { activeTab = tab.position; applyListState() }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        val chips = listOf(binding.chip0, binding.chip1, binding.chip2, binding.chip3, binding.chip4)
        chips[filterMode.ordinal].isChecked = true
        binding.categoryChips.setOnCheckedStateChangeListener { _, ids ->
            filterMode = FilterMode.values()[chips.indexOfFirst { it.id in ids }.coerceAtLeast(0)]
            applyListState()
        }
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            binding.storageSummary.visibility = View.GONE
        }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                binding.btnClearSearch.visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
                applyListState()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        loadCachedFiles()
        fetchFiles()
    }

    private fun fetchFiles() {
        if (refreshInFlight) return
        refreshInFlight = true
        binding.swipeRefresh.isRefreshing = true
        binding.btnRefresh.isEnabled = false
        applyListState()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { ApiClient.instance.getFileList() }
            withContext(Dispatchers.Main) {
                binding.swipeRefresh.isRefreshing = false
                binding.btnRefresh.isEnabled = true
                refreshInFlight = false
                result.onSuccess { response ->
                    if (response.isSuccessful && response.body()?.success == true) {
                        allFiles.clear()
                        allFiles.addAll(response.body()?.data.orEmpty())
                        cacheFiles()
                        hasLoaded = true
                        applyListState()
                        binding.tvStatus.text = "Diperbarui " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    } else {
                        showToast(response.body()?.message ?: "Gagal mengambil daftar file")
                        binding.tvStatus.text = "Gagal memperbarui / data tersimpan"
                        applyListState()
                    }
                }.onFailure {
                    showToast("Memakai cache lokal: ${it.localizedMessage}")
                    binding.tvStatus.text = "Offline / data tersimpan"
                    applyListState()
                }
            }
        }
    }

    private fun uploadSelectedFiles(uris: List<Uri>) {
        if (uploading) return
        uploading = true
        binding.fabUpload.isEnabled = false
        binding.progressBar.progress = 0
        binding.uploadPanel.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            var success = 0
            var failed = 0
            uris.forEachIndexed { index, uri ->
                val result = runCatching { uploadOne(uri, index + 1, uris.size) }
                if (result.isSuccess) success++ else failed++
                val name = getFileName(uri) ?: "File"
                val time = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale("id", "ID")).format(java.util.Date())
                withContext(Dispatchers.Main) {
                    uploadHistory.add(0, "$time / ${if (result.isSuccess) "Berhasil" else "Gagal"}\n$name" +
                        (result.exceptionOrNull()?.let { "\n${it.localizedMessage}" } ?: ""))
                    while (uploadHistory.size > 50) uploadHistory.removeAt(uploadHistory.lastIndex)
                    preferences.edit().putString("history", gson.toJson(uploadHistory)).apply()
                }
            }
            withContext(Dispatchers.Main) {
                binding.uploadPanel.visibility = View.GONE
                uploading = false
                binding.fabUpload.isEnabled = true
                showToast("Upload selesai: $success berhasil, $failed gagal")
                fetchFiles()
            }
        }
    }

    private suspend fun uploadOne(uri: Uri, current: Int, totalFiles: Int) {
        val baseFileName = getFileName(uri) ?: "upload-${System.currentTimeMillis()}"
        val folderPrefix = if (folderMode == "Semua folder" || folderMode == "Root") "" else "$folderMode/"
        val fileName = folderPrefix + baseFileName
        val contentType = contentResolver.getType(uri) ?: "application/octet-stream"
        val size = getFileSize(uri)
        runOnUiThread {
            binding.tvUploadStatus.text = "Upload $current/$totalFiles: $fileName"
            binding.progressBar.progress = 0
        }

        val uploadUrlResponse = ApiClient.instance.createUploadUrl(UploadUrlRequest(fileName, contentType, size))
        val uploadData = uploadUrlResponse.body()?.data
        check(uploadUrlResponse.isSuccessful && uploadUrlResponse.body()?.success == true && uploadData != null) {
            uploadUrlResponse.body()?.message ?: "Gagal meminta URL upload"
        }

        val body = ContentUriRequestBody(
            contentResolver = contentResolver,
            uri = uri,
            mediaType = contentType.toMediaTypeOrNull(),
            knownLength = size,
        ) { sent, total ->
            if (total > 0) {
                val progress = ((sent * 100) / total).toInt().coerceIn(0, 100)
                runOnUiThread {
                    binding.progressBar.progress = progress
                    binding.tvUploadStatus.text = "$current/$totalFiles / $progress% / $fileName"
                }
            }
        }

        val builder = Request.Builder().url(uploadData.uploadUrl).put(body)
        uploadData.headers.forEach { (name, value) -> builder.header(name, value) }
        ApiClient.rawHttpClient.newCall(builder.build()).execute().use {
            check(it.isSuccessful) { "Storage menolak upload: HTTP ${it.code}" }
        }
    }

    private fun applyListState() {
        val filtered = allFiles
            .asSequence()
            .filter { file -> searchQuery.isBlank() || file.name.contains(searchQuery, ignoreCase = true) }
            .filter { file -> filterMode.matches(file) }
            .filter { file -> activeTab != 1 || file.name in favorites }
            .filter { file -> folderMode == "Semua folder" || folderOf(file) == folderMode }
            .let { sequence ->
                when (if (activeTab == 2) SortMode.NEWEST else sortMode) {
                    SortMode.NEWEST -> sequence.sortedByDescending { it.lastModified }
                    SortMode.NAME -> sequence.sortedBy { it.name.lowercase() }
                    SortMode.SIZE_DESC -> sequence.sortedByDescending { it.size }
                    SortMode.SIZE_ASC -> sequence.sortedBy { it.size }
                }
            }
            .let { if (activeTab == 2) it.take(20) else it }
            .toList()
        adapter.setFavorites(favorites)
        adapter.submitList(filtered)
        binding.btnFolder.text = folderMode
        binding.tvCount.text = "${filtered.size} file"
        binding.tvStorage.text = "${allFiles.size} file / ${formatBytes(allFiles.sumOf { it.size })}"
        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmptyTitle.text = when {
            !hasLoaded && refreshInFlight -> "Memuat file..."
            searchQuery.isNotEmpty() -> "Tidak ada hasil"
            activeTab == 1 -> "Belum ada favorit"
            else -> "Belum ada file"
        }
        binding.tvEmptyMessage.text = when {
            !hasLoaded && refreshInFlight -> ""
            searchQuery.isNotEmpty() || filterMode != FilterMode.ALL || folderMode != "Semua folder" -> "Tidak ada file yang cocok dengan pilihan ini."
            activeTab == 1 -> "File favorit Anda akan tampil di sini."
            !hasLoaded -> "Daftar belum tersedia. Coba perbarui kembali."
            else -> "File yang diunggah akan tampil di sini."
        }
        binding.btnSort.isEnabled = activeTab != 2
        binding.btnSort.alpha = if (activeTab == 2) 0.4f else 1f
    }

    private fun showSortMenu() {
        PopupMenu(this, binding.btnSort).apply {
            SortMode.values().forEachIndexed { index, mode ->
                menu.add(0, index, index, mode.label).apply {
                    isCheckable = true
                    isChecked = mode == sortMode
                }
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener {
                sortMode = SortMode.values()[it.itemId]
                preferences.edit().putInt("sort", sortMode.ordinal).apply()
                applyListState()
                true
            }
        }.show()
    }

    private fun showFolderMenu() {
        val folders = listOf("Semua folder") + allFiles.map(::folderOf).distinct().sorted()
        PopupMenu(this, binding.btnFolder).apply {
            folders.forEachIndexed { index, folder ->
                menu.add(0, index, index, folder).apply {
                    isCheckable = true
                    isChecked = folder == folderMode
                }
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener {
                folderMode = folders[it.itemId]
                applyListState()
                true
            }
        }.show()
    }

    private fun showFileMenu(file: FileItem) {
        val dialog = BottomSheetDialog(this)
        val rows = sheetContent(file.name.substringAfterLast('/'), "${formatBytes(file.size)} / ${file.contentType ?: "File"}")
        val actions = listOf(
            Triple("Preview", android.R.drawable.ic_menu_view, { openFile(file) }),
            Triple(if (file.name in favorites) "Hapus favorit" else "Tambah favorit", android.R.drawable.btn_star_big_off, { toggleFavorite(file) }),
            Triple("Detail", android.R.drawable.ic_menu_info_details, { showDetails(file) }),
            Triple("Ganti nama", android.R.drawable.ic_menu_edit, { showRenameDialog(file) }),
            Triple("Bagikan link", android.R.drawable.ic_menu_share, { shareFile(file) }),
            Triple("Salin link", android.R.drawable.ic_menu_set_as, { copyLink(file) }),
            Triple("Hapus file", android.R.drawable.ic_menu_delete, { confirmDelete(file) }),
        )
        actions.forEach { (label, icon, action) ->
            val row = TextView(this).apply {
                text = label
                textSize = 15f
                minHeight = dp(52)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setTextColor(ContextCompat.getColor(this@MainActivity, if (label == "Hapus file") android.R.color.holo_red_dark else R.color.ink))
                setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawablePadding = dp(16)
                val value = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                setBackgroundResource(value.resourceId)
                setOnClickListener { dialog.dismiss(); action() }
            }
            rows.addView(row)
        }
        dialog.setContentView(ScrollView(this).apply { addView(rows) })
        dialog.show()
    }

    private fun showDetails(file: FileItem) {
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage(
                "Kategori: ${FilterMode.categoryLabel(file)}\n" +
                    "Folder: ${folderOf(file)}\n" +
                    "Ukuran: ${formatBytes(file.size)}\n" +
                    "Tipe: ${file.contentType ?: "-"}\n" +
                    "Tanggal: ${file.lastModified}\n" +
                    "Object key: ${file.name}",
            )
            .setPositiveButton("Preview") { _, _ -> openFile(file) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showRenameDialog(file: FileItem) {
        val input = EditText(this).apply {
            setText(file.name)
            setSelection(text.length)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename file")
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) renameFile(file, newName)
            }
            .show()
    }

    private fun renameFile(file: FileItem, newName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                ApiClient.instance.renameFile(RenameFileRequest(file.name, newName))
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { response ->
                    if (response.isSuccessful && response.body()?.success == true) {
                        showToast("File di-rename")
                        if (favorites.remove(file.name)) {
                            favorites.add(newName)
                            saveFavorites()
                        }
                        fetchFiles()
                    } else {
                        showToast(response.body()?.message ?: "Gagal rename")
                    }
                }.onFailure { showToast("Gagal rename: ${it.localizedMessage}") }
            }
        }
    }

    private fun openFile(file: FileItem) {
        requestDownloadUrl(file) { url ->
            openPreview(file.name, url, file.contentType)
        }
    }

    private fun requestDownloadUrl(file: FileItem, onReady: (String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { ApiClient.instance.getFileUrl(encodePath(file.name)) }
            withContext(Dispatchers.Main) {
                result.onSuccess { response ->
                    val data = response.body()?.data
                    if (response.isSuccessful && response.body()?.success == true && data?.downloadUrl != null) {
                        onReady(data.downloadUrl)
                    } else {
                        showToast(response.body()?.message ?: "Gagal membuka file")
                    }
                }.onFailure { showToast("Gagal membuka file: ${it.localizedMessage}") }
            }
        }
    }

    private fun shareFile(file: FileItem) {
        requestDownloadUrl(file) { url ->
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TEXT, url)
        }, "Share link"))
        showToast("Link berlaku 10 menit")
        }
    }

    private fun copyLink(file: FileItem) {
        requestDownloadUrl(file) { url ->
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(file.name, url))
        showToast("Link disalin, berlaku 10 menit")
        }
    }

    private fun confirmDelete(file: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("Hapus file?")
            .setMessage(file.name)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ -> deleteFile(file) }
            .show()
    }

    private fun deleteFile(file: FileItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { ApiClient.instance.deleteFile(encodePath(file.name)) }
            withContext(Dispatchers.Main) {
                result.onSuccess { response ->
                    if (response.isSuccessful && response.body()?.success == true) {
                        showToast("File dihapus")
                        favorites.remove(file.name)
                        saveFavorites()
                        fetchFiles()
                    } else {
                        showToast(response.body()?.message ?: "Gagal menghapus file")
                    }
                }.onFailure { showToast("Gagal menghapus file: ${it.localizedMessage}") }
            }
        }
    }

    private fun cacheFiles() {
        getSharedPreferences("fastcloud_cache", MODE_PRIVATE)
            .edit()
            .putString("files", gson.toJson(allFiles))
            .apply()
    }

    private fun loadCachedFiles() {
        val json = getSharedPreferences("fastcloud_cache", MODE_PRIVATE).getString("files", null) ?: return
        runCatching {
            allFiles.clear()
            allFiles.addAll(gson.fromJson(json, Array<FileItem>::class.java).toList())
            hasLoaded = true
            applyListState()
            binding.tvStatus.text = "Cache lokal: ${allFiles.size} file"
        }
    }

    private fun folderOf(file: FileItem): String {
        return if (file.name.contains("/")) file.name.substringBeforeLast("/") else "Root"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun updateViewMode() {
        adapter.gridMode = gridMode
        binding.rvFiles.layoutManager = if (gridMode) GridLayoutManager(this,
            (resources.configuration.screenWidthDp / 170).coerceAtLeast(2))
            else LinearLayoutManager(this)
        adapter.notifyDataSetChanged()
        binding.btnViewMode.setImageResource(if (gridMode) android.R.drawable.ic_menu_sort_by_size else android.R.drawable.ic_dialog_dialer)
        binding.btnViewMode.contentDescription = if (gridMode) "Tampilan daftar" else "Tampilan grid"
        TooltipCompat.setTooltipText(binding.btnViewMode, binding.btnViewMode.contentDescription)
    }

    private fun toggleFavorite(file: FileItem) {
        if (!favorites.add(file.name)) favorites.remove(file.name)
        saveFavorites()
        applyListState()
    }

    private fun saveFavorites() {
        preferences.edit().putStringSet("favorites", favorites.toSet()).apply()
    }

    private fun sheetContent(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
            addView(TextView(this@MainActivity).apply {
                text = title; textSize = 19f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle; textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                setPadding(0, dp(8), 0, dp(16))
            })
        }

    private fun showUploadHistory() {
        val dialog = BottomSheetDialog(this)
        val rows = sheetContent("Riwayat upload", "${uploadHistory.size} transfer tersimpan")
        if (uploadHistory.isEmpty()) rows.addView(TextView(this).apply {
            text = "Belum ada aktivitas upload"
            setPadding(0, dp(12), 0, dp(12))
        })
        uploadHistory.forEach { entry ->
            rows.addView(TextView(this).apply {
                text = entry; textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                setPadding(0, dp(12), 0, dp(12))
            })
            rows.addView(View(this).apply { setBackgroundResource(R.color.line) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        dialog.setContentView(ScrollView(this).apply { addView(rows) })
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", activeTab)
        outState.putInt("filter", filterMode.ordinal)
        outState.putString("folder", folderMode)
        super.onSaveInstanceState(outState)
    }

    private fun getFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    private fun getFileSize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getLong(index)
        }
        return -1L
    }

    private fun encodePath(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun openPreview(name: String, url: String, contentType: String?) {
        startActivity(PreviewActivity.createIntent(this, name, url, contentType))
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "${DecimalFormat("#,##0.#").format(value)} ${units[unit]}"
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showCreateFolderDialog() {
        val input = EditText(this).apply {
            hint = "Nama Folder"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Buat Folder Baru")
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Buat") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) createFolder(newName)
            }
            .show()
    }

    private fun createFolder(folderName: String) {
        val folderPrefix = if (folderMode == "Semua folder" || folderMode == "Root") "" else "$folderMode/"
        val dummyFile = "$folderPrefix$folderName/.keep"
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val dummyContent = " ".toByteArray()
                val uploadUrlResponse = ApiClient.instance.createUploadUrl(UploadUrlRequest(dummyFile, "text/plain", dummyContent.size.toLong()))
                val uploadData = uploadUrlResponse.body()?.data
                check(uploadUrlResponse.isSuccessful && uploadData != null) { "Gagal meminta URL" }

                val builder = Request.Builder().url(uploadData.uploadUrl).put(okhttp3.RequestBody.create(null, dummyContent))
                uploadData.headers.forEach { (name, value) -> builder.header(name, value) }
                ApiClient.rawHttpClient.newCall(builder.build()).execute().use {
                    check(it.isSuccessful) { "Gagal membuat folder" }
                }
            }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    showToast("Folder '$folderName' dibuat")
                    fetchFiles()
                } else {
                    showToast("Gagal membuat folder: ${result.exceptionOrNull()?.localizedMessage}")
                }
            }
        }
    }

    private enum class FilterMode(val label: String) {
        ALL("Semua"),
        IMAGE("Gambar"),
        PDF("PDF"),
        TEXT("Teks"),
        OTHER("Lainnya");

        fun matches(file: FileItem): Boolean {
            return when (this) {
                ALL -> true
                IMAGE -> categoryLabel(file) == "Gambar"
                PDF -> categoryLabel(file) == "PDF"
                TEXT -> categoryLabel(file) == "Teks"
                OTHER -> categoryLabel(file) == "Lainnya"
            }
        }

        companion object {
            fun categoryLabel(file: FileItem): String {
                val type = file.contentType.orEmpty()
                return when {
                    type.startsWith("image/") -> "Gambar"
                    type == "application/pdf" || file.name.endsWith(".pdf", true) -> "PDF"
                    type.startsWith("text/") ||
                        file.name.endsWith(".txt", true) ||
                        file.name.endsWith(".json", true) ||
                        file.name.endsWith(".csv", true) ||
                        file.name.endsWith(".md", true) -> "Teks"
                    else -> "Lainnya"
                }
            }
        }
    }

    private enum class SortMode(val label: String) {
        NEWEST("Tanggal terbaru"),
        NAME("Nama A-Z"),
        SIZE_DESC("Ukuran terbesar"),
        SIZE_ASC("Ukuran terkecil"),
    }
}
