package com.example.fastcloudstorage.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.fastcloudstorage.R
import com.example.fastcloudstorage.model.FileItem
import com.google.android.material.card.MaterialCardView
import java.text.DecimalFormat

class FileAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onMoreClick: (FileItem) -> Unit,
    private val onFavoriteClick: (FileItem) -> Unit,
) : ListAdapter<FileItem, FileAdapter.FileViewHolder>(object : DiffUtil.ItemCallback<FileItem>() {
    override fun areItemsTheSame(a: FileItem, b: FileItem) = a.name == b.name
    override fun areContentsTheSame(a: FileItem, b: FileItem) = a == b
}) {
    var gridMode = false
    private var favorites = emptySet<String>()

    fun setFavorites(names: Set<String>) {
        val changed = favorites union names
        val previous = favorites
        favorites = names.toSet()
        currentList.forEachIndexed { index, file ->
            if (file.name in changed && (file.name in previous) != (file.name in names)) {
                notifyItemChanged(index)
            }
        }
    }

    override fun getItemViewType(position: Int) = if (gridMode) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder =
        FileViewHolder(
            LayoutInflater.from(parent.context).inflate(
                if (viewType == 1) R.layout.item_file_grid else R.layout.item_file,
                parent,
                false
            )
        )

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) = holder.bind(getItem(position))

    inner class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val preview = view.findViewById<ImageView>(R.id.ivPreview)
        private val title = view.findViewById<TextView>(R.id.tvFileName)
        private val meta = view.findViewById<TextView>(R.id.tvMeta)
        private val badge = view.findViewById<TextView?>(R.id.tvBadge)
        private val cardPreview = view.findViewById<MaterialCardView?>(R.id.cardPreview)
        private val favorite = view.findViewById<ImageButton>(R.id.btnFavorite)
        private val more = view.findViewById<ImageButton>(R.id.btnMore)

        fun bind(item: FileItem) {
            val context = itemView.context
            val rawName = item.name.substringAfterLast('/')
            title.text = rawName
            title.contentDescription = item.name

            val isFolder = item.name.endsWith("/") || rawName.isEmpty()
            val ext = if (rawName.contains('.')) rawName.substringAfterLast('.').uppercase() else "FILE"
            val mime = item.contentType.orEmpty()
            val date = item.lastModified.take(10)

            val typeInfo = when {
                isFolder -> FileTypeUi(
                    badge = "DIR",
                    iconRes = R.drawable.ic_folder,
                    bgRes = R.color.file_folder_bg,
                    tintRes = R.color.file_folder,
                    isImage = false
                )
                mime.startsWith("image/") || ext in listOf("PNG", "JPG", "JPEG", "WEBP", "GIF") -> FileTypeUi(
                    badge = ext.take(4),
                    iconRes = R.drawable.ic_file_image,
                    bgRes = R.color.file_image_bg,
                    tintRes = R.color.file_image,
                    isImage = true
                )
                mime == "application/pdf" || ext == "PDF" -> FileTypeUi(
                    badge = "PDF",
                    iconRes = R.drawable.ic_file_pdf,
                    bgRes = R.color.file_pdf_bg,
                    tintRes = R.color.file_pdf,
                    isImage = false
                )
                mime.startsWith("video/") || ext in listOf("MP4", "MKV", "MOV", "AVI") -> FileTypeUi(
                    badge = "VIDEO",
                    iconRes = R.drawable.ic_file_video,
                    bgRes = R.color.file_video_bg,
                    tintRes = R.color.file_video,
                    isImage = false
                )
                mime.startsWith("audio/") || ext in listOf("MP3", "WAV", "AAC", "OGG") -> FileTypeUi(
                    badge = "AUDIO",
                    iconRes = R.drawable.ic_file_audio,
                    bgRes = R.color.file_audio_bg,
                    tintRes = R.color.file_audio,
                    isImage = false
                )
                mime.startsWith("text/") || ext in listOf("TXT", "MD", "JSON", "CSV", "DOC", "DOCX", "XLS", "XLSX") -> FileTypeUi(
                    badge = ext.take(4),
                    iconRes = R.drawable.ic_file_doc,
                    bgRes = R.color.file_doc_bg,
                    tintRes = R.color.file_doc,
                    isImage = false
                )
                ext in listOf("ZIP", "RAR", "7Z", "TAR", "GZ") -> FileTypeUi(
                    badge = "ZIP",
                    iconRes = R.drawable.ic_file_archive,
                    bgRes = R.color.file_archive_bg,
                    tintRes = R.color.file_archive,
                    isImage = false
                )
                else -> FileTypeUi(
                    badge = ext.take(4),
                    iconRes = R.drawable.ic_file_generic,
                    bgRes = R.color.file_generic_bg,
                    tintRes = R.color.file_generic,
                    isImage = false
                )
            }

            badge?.text = typeInfo.badge
            val badgeColor = ContextCompat.getColor(context, typeInfo.tintRes)
            val badgeBgColor = ContextCompat.getColor(context, typeInfo.bgRes)
            badge?.setTextColor(badgeColor)
            badge?.backgroundTintList = ColorStateList.valueOf(badgeBgColor)

            if (gridMode) {
                meta.text = "${formatBytes(item.size)} • $date"
            } else {
                meta.text = "${formatBytes(item.size)} • $date"
            }

            if (typeInfo.isImage && !item.downloadUrl.isNullOrEmpty()) {
                preview.imageTintList = null
                preview.scaleType = ImageView.ScaleType.CENTER_CROP
                cardPreview?.setCardBackgroundColor(ContextCompat.getColor(context, R.color.canvas))
                preview.load(item.downloadUrl) {
                    crossfade(true)
                    placeholder(typeInfo.iconRes)
                    error(typeInfo.iconRes)
                    memoryCacheKey("${item.name}:${item.lastModified}")
                    diskCacheKey("${item.name}:${item.lastModified}")
                }
            } else {
                preview.scaleType = ImageView.ScaleType.CENTER_INSIDE
                cardPreview?.setCardBackgroundColor(badgeBgColor)
                preview.setImageResource(typeInfo.iconRes)
                preview.imageTintList = ColorStateList.valueOf(badgeColor)
            }

            val starred = item.name in favorites
            favorite.setImageResource(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            favorite.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, if (starred) R.color.favorite else R.color.muted)
            )
            favorite.contentDescription = if (starred) "Hapus dari favorit" else "Tambah favorit"

            androidx.appcompat.widget.TooltipCompat.setTooltipText(favorite, favorite.contentDescription)
            androidx.appcompat.widget.TooltipCompat.setTooltipText(more, "Opsi file")

            favorite.setOnClickListener { onFavoriteClick(item) }
            more.setOnClickListener { onMoreClick(item) }
            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener {
                onMoreClick(item)
                true
            }
        }
    }

    private data class FileTypeUi(
        val badge: String,
        val iconRes: Int,
        val bgRes: Int,
        val tintRes: Int,
        val isImage: Boolean,
    )

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
}
