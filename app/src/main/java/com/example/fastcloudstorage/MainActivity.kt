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
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.fastcloudstorage.adapter.FileAdapter
import com.example.fastcloudstorage.databinding.ActivityMainBinding
import com.example.fastcloudstorage.model.FileItem
import com.example.fastcloudstorage.model.RenameFileRequest
import com.example.fastcloudstorage.model.UploadUrlRequest
import com.example.fastcloudstorage.network.ApiClient
import com.example.fastcloudstorage.network.ContentUriRequestBody
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
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
        binding.rvFiles.adapter = adapter
        updateViewMode()

        binding.swipeRefresh.setOnRefreshListener { fetchFiles() }
        binding.swipeRefresh.setColorSchemeResources(R.color.brand)
        binding.fabUpload.setOnClickListener { pickFilesLauncher.launch("*/*") }
        binding.fabNewFolder.setOnClickListener { showCreateFolderDialog() }
        binding.btnFolderBack.setOnClickListener {
            folderMode = "Semua folder"
            applyListState()
        }
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

        listOf(binding.btnRefresh, binding.btnHistory, binding.btnSort, binding.btnViewMode, binding.btnFolderBack).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }

        listOf("Semua", "Favorit", "Terbaru").forEach { binding.tabs.addTab(binding.tabs.newTab().setText(it)) }
        binding.tabs.getTabAt(activeTab)?.select()
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                activeTab = tab.position
                applyListState()
            }
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
                        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        binding.tvStatus.text = "Terhubung • Sinkron $time"
                    } else {
                        showToast(response.body()?.message ?: "Gagal mengambil daftar file")
                        binding.tvStatus.text = "Gagal memperbarui / data offline"
                        applyListState()
                    }
                }.onFailure {
                    showToast("Mode offline: ${it.localizedMessage}")
                    binding.tvStatus.text = "Offline / cache lokal"
                    applyListState()
                }
            }
        }
    }

    private fun uploadSelectedFiles(uris: List<Uri>) {
        if (uploading) return
        uploading = true
        binding.fabUpload.isEnabled = false
        binding.fabNewFolder.isEnabled = false
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
                    uploadHistory.add(0, "$time • ${if (result.isSuccess) "Berhasil" else "Gagal"}\n$name" +
                        (result.exceptionOrNull()?.let { "\n${it.localizedMessage}" } ?: ""))
                    while (uploadHistory.size > 50) uploadHistory.removeAt(uploadHistory.lastIndex)
                    preferences.edit().putString("history", gson.toJson(uploadHistory)).apply()
                }
            }
            withContext(Dispatchers.Main) {
                binding.uploadPanel.visibility = View.GONE
                uploading = false
                binding.fabUpload.isEnabled = true
                binding.fabNewFolder.isEnabled = true
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
            binding.tvUploadStatus.text = "Upload $current/$totalFiles: $baseFileName"
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
                    binding.tvUploadStatus.text = "$current/$totalFiles ($progress%) • $baseFileName"
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
        val realFiles = allFiles.filter { !it.name.endsWith("/.keep") && it.name != ".keep" }
        val filtered = realFiles
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

        val isInsideFolder = folderMode != "Semua folder" && folderMode != "Root"
        binding.btnFolderBack.visibility = if (isInsideFolder) View.VISIBLE else View.GONE
        binding.btnFolder.text = when (folderMode) {
            "Semua folder" -> "Semua folder"
            "Root" -> "Folder Utama (Root)"
            else -> "Folder: $folderMode"
        }

        binding.tvCount.text = "${filtered.size} file ditemukan"
        binding.tvStorage.text = "${realFiles.size} file • ${formatBytes(realFiles.sumOf { it.size })}"

        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmptyTitle.text = when {
            !hasLoaded && refreshInFlight -> "Memuat file..."
            searchQuery.isNotEmpty() -> "Tidak ada hasil pencarian"
            activeTab == 1 -> "Belum ada file favorit"
            isInsideFolder -> "Folder '$folderMode' kosong"
            else -> "Belum ada file"
        }
        binding.tvEmptyMessage.text = when {
            !hasLoaded && refreshInFlight -> "Sedang menyinkronkan dengan Edge S3 storage..."
            searchQuery.isNotEmpty() -> "Tidak ditemukan file dengan kata kunci '$searchQuery'."
            activeTab == 1 -> "Tandai bintang pada file favorit untuk kemudahan akses."
            isInsideFolder -> "Gunakan tombol Upload untuk menambahkan file ke folder ini."
            !hasLoaded -> "Daftar belum tersedia. Tarik layar ke bawah untuk memperbarui."
            else -> "Sentuh tombol Upload untuk mulai mengunggah file Anda."
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
        val detectedFolders = allFiles.mapNotNull {
            if (it.name.contains('/')) it.name.substringBeforeLast('/') else null
        }.distinct().sorted()
        val folders = listOf("Semua folder", "Root") + detectedFolders
        PopupMenu(this, binding.btnFolder).apply {
            folders.forEachIndexed { index, folder ->
                val displayLabel = when (folder) {
                    "Semua folder" -> "📁 Semua folder"
                    "Root" -> "🏠 Folder Utama (Root)"
                    else -> "📂 $folder"
                }
                menu.add(0, index, index, displayLabel).apply {
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
        val rawName = file.name.substringAfterLast('/')
        val rows = sheetContent(rawName, "${formatBytes(file.size)} • ${file.contentType ?: "File"} • ${folderOf(file)}")
        val isStarred = file.name in favorites
        val actions = listOf(
            Triple("Preview file", android.R.drawable.ic_menu_view, { openFile(file) }),
            Triple(if (isStarred) "Hapus dari favorit" else "Tambah ke favorit", if (isStarred) R.drawable.ic_star_filled else R.drawable.ic_star_outline, { toggleFavorite(file) }),
            Triple("Detail informasi", android.R.drawable.ic_menu_info_details, { showDetails(file) }),
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
            .setTitle(file.name.substringAfterLast('/'))
            .setMessage(
                "Kategori: ${FilterMode.categoryLabel(file)}\n" +
                    "Folder: ${folderOf(file)}\n" +
                    "Ukuran: ${formatBytes(file.size)}\n" +
                    "Tipe: ${file.contentType ?: "-"}\n" +
                    "Tanggal: ${file.lastModified}\n" +
                    "Object path: ${file.name}",
            )
            .setPositiveButton("Preview") { _, _ -> openFile(file) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showRenameDialog(file: FileItem) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val input = EditText(this).apply {
            setText(file.name)
            setSelection(text.length)
            setSingleLine(true)
            textSize = 15f
        }
        container.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Rename file")
            .setView(container)
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
            .setMessage("File '${file.name}' akan dihapus secara permanen dari storage.")
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
                        showToast("File berhasil dihapus")
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
            binding.tvStatus.text = "Cache lokal • ${allFiles.size} file"
        }
    }

    private fun folderOf(file: FileItem): String {
        return if (file.name.contains("/")) file.name.substringBeforeLast("/") else "Root"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun updateViewMode() {
        adapter.gridMode = gridMode
        binding.rvFiles.layoutManager = if (gridMode) {
            val spanCount = (resources.configuration.screenWidthDp / 160).coerceAtLeast(2)
            GridLayoutManager(this, spanCount)
        } else {
            LinearLayoutManager(this)
        }
        adapter.notifyDataSetChanged()
        binding.btnViewMode.setImageResource(if (gridMode) R.drawable.ic_list else R.drawable.ic_grid)
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
                text = title
                textSize = 18f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                setPadding(0, dp(6), 0, dp(16))
            })
        }

    private fun showUploadHistory() {
        val dialog = BottomSheetDialog(this)
        val rows = sheetContent("Riwayat transfer", "${uploadHistory.size} aktivitas tercatat")
        if (uploadHistory.isEmpty()) rows.addView(TextView(this).apply {
            text = "Belum ada riwayat aktivitas upload."
            setPadding(0, dp(12), 0, dp(12))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
        })
        uploadHistory.forEach { entry ->
            rows.addView(TextView(this).apply {
                text = entry
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                setPadding(0, dp(10), 0, dp(10))
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
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
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
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val input = EditText(this).apply {
            hint = "Contoh: Dokumen, Foto2026"
            setSingleLine(true)
            textSize = 15f
        }
        container.addView(input)

        val targetLoc = if (folderMode != "Semua folder" && folderMode != "Root") "'$folderMode'" else "Folder Utama"
        AlertDialog.Builder(this)
            .setTitle("Buat Folder Baru")
            .setMessage("Folder baru akan dibuat di dalam $targetLoc.")
            .setView(container)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Buat") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) createFolder(newName)
            }
            .show()
    }

    private fun createFolder(folderName: String) {
        val cleanName = folderName.trim().replace("\\", "/").trim('/')
        val folderPrefix = if (folderMode == "Semua folder" || folderMode == "Root") "" else "$folderMode/"
        val targetFolder = "$folderPrefix$cleanName"
        val dummyFile = "$targetFolder/.keep"
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val dummyContent = " ".toByteArray()
                val uploadUrlResponse = ApiClient.instance.createUploadUrl(
                    UploadUrlRequest(dummyFile, "text/plain", dummyContent.size.toLong())
                )
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
                    showToast("Folder '$cleanName' berhasil dibuat")
                    folderMode = targetFolder
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
