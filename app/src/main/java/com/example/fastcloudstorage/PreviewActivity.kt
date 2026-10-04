package com.example.fastcloudstorage

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.fastcloudstorage.databinding.ActivityPreviewBinding
import com.example.fastcloudstorage.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

class PreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPreviewBinding
    private lateinit var fileUrl: String
    private lateinit var fileName: String
    private var contentType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        androidx.appcompat.widget.TooltipCompat.setTooltipText(binding.btnBack, "Kembali")
        androidx.appcompat.widget.TooltipCompat.setTooltipText(binding.btnOpenExternal, "Buka di aplikasi lain")

        fileName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        fileUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE)

        if (fileUrl.isBlank()) {
            Toast.makeText(this, "URL file tidak tersedia", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.tvPreviewTitle.text = fileName.ifBlank { "Preview file" }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnOpenExternal.setOnClickListener { openExternal() }
        showPreview()
    }

    private fun showPreview() {
        when {
            isImage() -> showImage()
            isText() -> showText()
            isPdf() -> showPdf()
            isWebLike() -> showWeb(fileUrl)
            else -> showUnsupported()
        }
    }

    private fun showImage() {
        showOnly(binding.ivPreviewFull)
        binding.previewProgress.visibility = View.VISIBLE
        binding.ivPreviewFull.load(fileUrl) {
            listener(
                onSuccess = { _, _ -> binding.previewProgress.visibility = View.GONE },
                onError = { _, _ ->
                    binding.previewProgress.visibility = View.GONE
                    showOnly(binding.tvUnsupported)
                    binding.tvUnsupported.text = "Gagal memuat gambar. Ketuk untuk mencoba lagi."
                    binding.tvUnsupported.setOnClickListener { showImage() }
                },
            )
        }
    }

    private fun showText() {
        showOnly(binding.textScroll)
        binding.previewProgress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                ApiClient.rawHttpClient.newCall(Request.Builder().url(fileUrl).get().build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    response.body?.string().orEmpty()
                }
            }
            withContext(Dispatchers.Main) {
                binding.previewProgress.visibility = View.GONE
                result.onSuccess { binding.tvTextPreview.text = it }
                    .onFailure {
                        binding.tvTextPreview.text = "Gagal memuat teks: ${it.localizedMessage}"
                    }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showPdf() {
        val encoded = URLEncoder.encode(fileUrl, "UTF-8")
        showWeb("https://docs.google.com/gview?embedded=1&url=$encoded")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(url: String) {
        showOnly(binding.webPreview)
        binding.previewProgress.visibility = View.VISIBLE
        binding.webPreview.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                binding.previewProgress.visibility = View.GONE
            }
        }
        binding.webPreview.settings.javaScriptEnabled = true
        binding.webPreview.settings.builtInZoomControls = true
        binding.webPreview.settings.displayZoomControls = false
        binding.webPreview.loadUrl(url)
    }

    private fun showUnsupported() {
        showOnly(binding.tvUnsupported)
        binding.tvUnsupported.text = "Format ini belum bisa dipreview langsung di aplikasi.\nGunakan tombol kanan atas untuk membuka dengan aplikasi lain."
    }

    private fun showOnly(view: View) {
        listOf(binding.ivPreviewFull, binding.textScroll, binding.webPreview, binding.tvUnsupported).forEach {
            it.visibility = if (it == view) View.VISIBLE else View.GONE
        }
    }

    private fun openExternal() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, fileUrl.toUri()))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isImage(): Boolean = contentType?.startsWith("image/") == true ||
        fileName.endsWith(".jpg", true) ||
        fileName.endsWith(".jpeg", true) ||
        fileName.endsWith(".png", true) ||
        fileName.endsWith(".webp", true) ||
        fileName.endsWith(".gif", true)

    private fun isText(): Boolean = contentType?.startsWith("text/") == true ||
        fileName.endsWith(".txt", true) ||
        fileName.endsWith(".json", true) ||
        fileName.endsWith(".csv", true) ||
        fileName.endsWith(".md", true) ||
        fileName.endsWith(".log", true)

    private fun isPdf(): Boolean = contentType == "application/pdf" || fileName.endsWith(".pdf", true)

    private fun isWebLike(): Boolean = contentType == "text/html" || fileName.endsWith(".html", true)

    companion object {
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_CONTENT_TYPE = "extra_content_type"

        fun createIntent(context: android.content.Context, name: String, url: String, contentType: String?): Intent {
            return Intent(context, PreviewActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_CONTENT_TYPE, contentType)
        }
    }
}
