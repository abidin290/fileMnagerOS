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
            if (file.name in changed && (file.name in previous) != (file.name in names)) notifyItemChanged(index)
        }
    }

    override fun getItemViewType(position: Int) = if (gridMode) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder =
        FileViewHolder(LayoutInflater.from(parent.context).inflate(
            if (viewType == 1) R.layout.item_file_grid else R.layout.item_file, parent, false))

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) = holder.bind(getItem(position))

    inner class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val preview = view.findViewById<ImageView>(R.id.ivPreview)
        private val title = view.findViewById<TextView>(R.id.tvFileName)
        private val meta = view.findViewById<TextView>(R.id.tvMeta)
        private val favorite = view.findViewById<ImageButton>(R.id.btnFavorite)
        private val more = view.findViewById<ImageButton>(R.id.btnMore)

        fun bind(item: FileItem) {
            title.text = item.name.substringAfterLast('/')
            title.contentDescription = item.name
            val extension = item.name.substringAfterLast('.', "FILE").uppercase().take(8)
            val date = item.lastModified.take(10)
            meta.text = if (gridMode) "$extension  /  ${formatBytes(item.size)}"
                else "${formatBytes(item.size)}  /  $date"
            val image = item.contentType?.startsWith("image/") == true
            preview.scaleType = if (image) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER
            preview.load(if (image) item.downloadUrl else android.R.drawable.ic_menu_save) {
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_gallery)
                crossfade(true)
                memoryCacheKey("${item.name}:${item.lastModified}")
                diskCacheKey("${item.name}:${item.lastModified}")
            }
            val starred = item.name in favorites
            favorite.setImageResource(if (starred) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
            favorite.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(itemView.context,
                if (starred) R.color.favorite else R.color.muted))
            favorite.contentDescription = if (starred) "Hapus dari favorit" else "Tambah favorit"
            androidx.appcompat.widget.TooltipCompat.setTooltipText(favorite, favorite.contentDescription)
            androidx.appcompat.widget.TooltipCompat.setTooltipText(more, "Opsi file")
            favorite.setOnClickListener { onFavoriteClick(item) }
            more.setOnClickListener { onMoreClick(item) }
            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener { onMoreClick(item); true }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return "${DecimalFormat("#,##0.#").format(value)} ${units[unit]}"
    }
}
