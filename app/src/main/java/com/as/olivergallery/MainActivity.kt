package com.`as`.olivergallery

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import coil.ImageLoader
import coil.load
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.math.abs

class MainActivity : Activity() {
    private enum class Screen { HOME, BROWSER, PLAYER }

    private val red = Color.rgb(235, 40, 55)
    private val surface = Color.rgb(22, 22, 24)
    private val muted = Color.rgb(162, 162, 165)
    private val videoExt = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi", "ts")
    // Coil supports animated GIF and WebP (Android 9+); native/Coil decoders
    // support PNG, JPEG, BMP, SVG, HEIF/HEIC and AVIF when the device supports them.
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp",
        "heic", "heif", "avif", "svg")
    private val imageLoader by lazy {
        ImageLoader.Builder(this).components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            add(SvgDecoder.Factory())
        }.build()
    }
    private val worker = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private val thumbnails = object : LruCache<String, Bitmap>(20 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private lateinit var base: File
    private lateinit var root: FrameLayout
    private var screen = Screen.HOME
    private var container: File? = null
    private val directoryStack = mutableListOf<File>()
    private var mediaFiles = emptyList<File>()
    private var mediaIndex = 0
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var seekBar: SeekBar? = null
    private var seekInProgress = false
    private var rotationStep = 0
    private val updateProgress = object : Runnable {
        override fun run() {
            val exo = player ?: return
            if (!seekInProgress) {
                val duration = exo.duration
                seekBar?.progress = if (duration > 0L) {
                    (exo.currentPosition.toDouble() / duration.toDouble() * 1000)
                        .toInt().coerceIn(0, 1000)
                } else 0
            }
            ui.postDelayed(this, 250L)
        }
    }
    private var downX = 0f
    private var downY = 0f
    private var importing = false
    private var deleting = false
    private var deleteMode = false
    private val selectedEntries = linkedSetOf<File>()
    private var activeScroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        base = File(getExternalFilesDir(null) ?: filesDir, "base").apply { mkdirs() }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        showHome()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun background(fill: Int, border: Int? = null, radius: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (border != null) setStroke(dp(2), border)
        }

    private fun label(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun button(text: String, click: () -> Unit): TextView =
        label(text, 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = background(surface, red, 10)
            setOnClickListener { click() }
        }

    private fun sortedDirs(parent: File): List<File> =
        parent.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) ?: emptyList()

    private fun isVideo(file: File): Boolean =
        file.isFile && file.extension.lowercase(Locale.ROOT) in videoExt

    private fun isImage(file: File): Boolean =
        file.isFile && file.extension.lowercase(Locale.ROOT) in imageExt

    private fun isMedia(file: File): Boolean = isVideo(file) || isImage(file)

    // Only media immediately inside this directory, NOT media from its descendants.
    private fun directMedia(parent: File): List<File> =
        parent.listFiles()?.filter { isMedia(it) }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) ?: emptyList()

    private fun containsMedia(parent: File): Boolean =
        parent.walkTopDown().any { isMedia(it) }

    private fun contentRoot(c: File): File {
        // This one directory is internal to the .oliver container format, not user content.
        val packageFolders = File(c, "folders")
        return if (File(c, "manifest.json").isFile && packageFolders.isDirectory)
            packageFolders else c
    }

    private fun containerName(c: File): String =
        try {
            JSONObject(File(c, "manifest.json").readText()).optString("name")
                .takeIf { it.isNotBlank() } ?: c.name
        } catch (_: Exception) { c.name }

    private fun buildPage(title: String, subtitle: String, back: Boolean,
        rightText: String? = null, rightClick: (() -> Unit)? = null): Pair<LinearLayout, LinearLayout> {
        window.decorView.systemUiVisibility = 0
        root.removeAllViews()
        root.setBackgroundColor(Color.BLACK)

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(12))
        }
        if (back) {
            header.addView(button("‹") { onBackPressed() }, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                rightMargin = dp(12)
            })
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label(title, 23f, Color.WHITE, true))
        titles.addView(label(subtitle, 12f, muted))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        if (rightText != null && rightClick != null) {
            header.addView(button(rightText, rightClick),
                LinearLayout.LayoutParams(dp(105), dp(46)).apply { leftMargin = dp(8) })
        }
        page.addView(header)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        scroll.addView(content)
        activeScroll = scroll
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return page to content
    }

    private fun emptyMessage(parent: LinearLayout, message: String) {
        parent.addView(label(message, 15f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(60), dp(24), dp(24))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addTiles(parent: LinearLayout, files: List<File>, columns: Int, media: Boolean,
                         titleOf: (File) -> String, details: (File) -> String,
                         selectionMode: Boolean = false, selected: Set<File> = emptySet(),
                         open: (File) -> Unit) {
        val grid = GridLayout(this).apply {
            columnCount = columns
            setPadding(dp(16), dp(10), dp(4), dp(14))
        }
        val tileWidth = (resources.displayMetrics.widthPixels - dp(32) - dp(12) * columns) / columns
        for (file in files) {
            val tile = FrameLayout(this).apply {
                background = background(surface, red, 12)
                clipToOutline = true
                setOnClickListener { open(file) }
            }
            val params = GridLayout.LayoutParams().apply {
                width = tileWidth
                height = if (media) (tileWidth * 1.55f).toInt() else dp(120)
                rightMargin = dp(12)
                bottomMargin = dp(12)
            }
            if (media) {
                val preview = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.rgb(12, 12, 13))
                    tag = file.absolutePath
                }
                tile.addView(preview, FrameLayout.LayoutParams(-1, -1))
                if (isVideo(file)) {
                    loadThumbnail(file, preview)
                    val play = label("▶", 29f, Color.WHITE, true).apply {
                        gravity = Gravity.CENTER
                    }
                    tile.addView(play, FrameLayout.LayoutParams(-1, -1))
                } else {
                    preview.load(file, imageLoader)
                }
                val caption = label(titleOf(file), 12f, Color.WHITE, true).apply {
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = GradientDrawable().apply { setColor(0xDD111111.toInt()) }
                }
                tile.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            } else {
                val stack = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                stack.addView(label("▣", 30f, red, true).apply { gravity = Gravity.CENTER })
                stack.addView(label(titleOf(file), 15f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                })
                stack.addView(label(details(file), 11f, muted).apply {
                    gravity = Gravity.CENTER
                })
                tile.addView(stack, FrameLayout.LayoutParams(-1, -1))
            }
            if (selectionMode) {
                val checked = file in selected
                if (checked) {
                    val shade = View(this).apply { setBackgroundColor(0x44ED2837) }
                    tile.addView(shade, FrameLayout.LayoutParams(-1, -1))
                }
                val marker = label(if (checked) "✓" else "○", 22f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    background = background(if (checked) red else 0xBB111111.toInt(), red, 8)
                }
                tile.addView(marker, FrameLayout.LayoutParams(dp(34), dp(34),
                    Gravity.TOP or Gravity.RIGHT).apply {
                    topMargin = dp(6)
                    rightMargin = dp(6)
                })
            }
            grid.addView(tile, params)
        }
        parent.addView(grid)
    }

    private fun loadThumbnail(file: File, view: ImageView) {
        val path = file.absolutePath
        val cached = thumbnails.get(path)
        if (cached != null) { view.setImageBitmap(cached); return }
        worker.execute {
            var retriever: MediaMetadataRetriever? = null
            val thumbnail = try {
                retriever = MediaMetadataRetriever().apply { setDataSource(path) }
                val original = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                original?.let {
                    val targetWidth = dp(220)
                    val targetHeight = (it.height.toFloat() / it.width * targetWidth)
                        .toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(it, targetWidth, targetHeight, true)
                    if (scaled !== it) it.recycle()
                    scaled
                }
            } catch (_: Exception) { null }
            finally { try { retriever?.release() } catch (_: Exception) {} }
            if (thumbnail != null) {
                synchronized(thumbnails) { thumbnails.put(path, thumbnail) }
                ui.post { if (view.tag == path) view.setImageBitmap(thumbnail) }
            }
        }
    }

    private fun exitDeleteMode() {
        deleteMode = false
        selectedEntries.clear()
    }

    private fun toggleDeletionMode() {
        if (deleting) return
        val y = activeScroll?.scrollY ?: 0
        deleteMode = !deleteMode
        selectedEntries.clear()
        refresh(y)
    }

    private fun toggleEntry(file: File) {
        val y = activeScroll?.scrollY ?: 0
        if (!selectedEntries.add(file)) selectedEntries.remove(file)
        refresh(y)
    }

    private fun refresh(scrollY: Int = 0) {
        when (screen) {
            Screen.HOME -> showHome(scrollY)
            Screen.BROWSER -> showBrowser(scrollY)
            Screen.PLAYER -> Unit
        }
    }

    private fun deleteFooter(page: LinearLayout) {
        val btn = button(if (deleting) "EXCLUINDO..." else "EXCLUIR (" +
            selectedEntries.size + ")") { confirmDeletion() }
        btn.isEnabled = selectedEntries.isNotEmpty() && !deleting
        btn.alpha = if (btn.isEnabled) 1f else 0.45f
        page.addView(btn, LinearLayout.LayoutParams(-1, dp(54)).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(16)
        })
    }

    private fun restoreScroll(y: Int) {
        if (y > 0) {
            val scroll = activeScroll
            scroll?.post { scroll.scrollTo(0, y) }
        }
    }

    private fun showHome(scrollY: Int = 0) {
        screen = Screen.HOME
        val entries = sortedDirs(base)
        selectedEntries.retainAll(entries.toSet())
        val (page, content) = buildPage("OLIVER GL",
            if (deleteMode) selectedEntries.size.toString() + " SELECIONADO(S)"
            else "CONTAINERS • OFFLINE", false,
            if (deleteMode) "CANCELAR" else "DEL", { toggleDeletionMode() })
        if (entries.isEmpty()) emptyMessage(content,
            "Nenhum container ainda.\n\nToque em IMPORTAR para escolher um pacote .oliver ou .zip.")
        else addTiles(content, entries, 2, false, { containerName(it) },
            { "CONTAINER" }, deleteMode, selectedEntries) {
            if (deleteMode) toggleEntry(it)
            else {
                container = it
                directoryStack.clear()
                directoryStack.add(contentRoot(it))
                showBrowser()
            }
        }

        if (deleteMode) deleteFooter(page)
        else {
            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(10), dp(16), dp(18))
            }
            footer.addView(button(if (importing) "IMPORTANDO..." else "IMPORTAR") {
                if (!importing) openFilePicker()
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(10) })
            footer.addView(button("ATUALIZAR") { showHome() },
                LinearLayout.LayoutParams(0, dp(54), 1f))
            page.addView(footer)
        }
        restoreScroll(scrollY)
    }

    private fun showBrowser(scrollY: Int = 0) {
        val dir = directoryStack.lastOrNull() ?: return showHome()
        if (!dir.isDirectory) {
            exitDeleteMode()
            if (directoryStack.size > 1) {
                directoryStack.removeAt(directoryStack.lastIndex)
                return showBrowser()
            }
            return showHome()
        }
        screen = Screen.BROWSER
        val dirs = sortedDirs(dir)
        val media = directMedia(dir)
        val entries = dirs + media
        selectedEntries.retainAll(entries.toSet())
        val displayName = if (directoryStack.size == 1)
            containerName(container ?: dir) else dir.name
        val subtitle = if (deleteMode) {
            selectedEntries.size.toString() + " SELECIONADO(S)"
        } else {
            dirs.size.toString() + " PASTA(S) • " + media.size + " ARQUIVO(S)"
        }
        val (page, content) = buildPage(displayName, subtitle, true,
            if (deleteMode) "CANCELAR" else "DEL", { toggleDeletionMode() })

        if (entries.isEmpty()) {
            emptyMessage(content, "Esta pasta está vazia.")
        } else {
            if (dirs.isNotEmpty()) {
                addTiles(content, dirs, 2, false, { it.name }, { "PASTA" },
                    deleteMode, selectedEntries) { dirItem ->
                    if (deleteMode) toggleEntry(dirItem)
                    else {
                        directoryStack.add(dirItem)
                        showBrowser()
                    }
                }
            }
            if (media.isNotEmpty()) {
                addTiles(content, media, 3, true, { it.nameWithoutExtension }, { "" },
                    deleteMode, selectedEntries) { item ->
                    if (deleteMode) toggleEntry(item)
                    else {
                        mediaFiles = media
                        playAt(media.indexOf(item))
                    }
                }
            }
        }
        if (deleteMode) deleteFooter(page)
        restoreScroll(scrollY)
    }

    private fun confirmDeletion() {
        if (selectedEntries.isEmpty() || !deleteMode || deleting) return
        val items = selectedEntries.toList()
        val currentParent = when (screen) {
            Screen.HOME -> base
            Screen.BROWSER -> directoryStack.lastOrNull() ?: return
            Screen.PLAYER -> return
        }
        val folders = items.count { it.isDirectory }
        AlertDialog.Builder(this)
            .setTitle("Excluir " + items.size + " item(ns)?")
            .setMessage(if (folders > 0)
                "ATENÇÃO: " + folders + " pasta(s)/container(s) serão apagados COM TODO O CONTEÚDO, incluindo subpastas. Essa ação não pode ser desfeita. Os arquivos .zip/.oliver originais em Downloads não serão alterados."
            else "As " + items.size + " mídias selecionadas serão apagadas da base do aplicativo. Não será possível desfazer.")
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("EXCLUIR") { _, _ ->
                deleting = true
                refresh()
                worker.execute {
                    var deleted = 0
                    for (item in items) if (deleteEntry(item, currentParent)) deleted++
                    ui.post {
                        deleting = false
                        exitDeleteMode()
                        refresh()
                        Toast.makeText(this,
                            deleted.toString() + " de " + items.size + " item(ns) excluído(s)",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun deleteEntry(item: File, parent: File): Boolean {
        try {
            val rootPath = base.canonicalFile.toPath()
            val canonical = item.canonicalFile.toPath()
            val parentPath = parent.canonicalFile.toPath()
            // Deletions are restricted to direct children of the visible directory,
            // whose canonical path must be inside our app-owned storage.
            if (!canonical.startsWith(rootPath) || canonical == rootPath ||
                !parentPath.startsWith(rootPath) || canonical.parent != parentPath ||
                !Files.exists(canonical)) return false
            Files.walkFileTree(canonical, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            thumbnails.remove(item.absolutePath)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun playAt(index: Int) {
        if (mediaFiles.isEmpty() || index !in mediaFiles.indices) return
        mediaIndex = index
        screen = Screen.PLAYER
        rotationStep = 0
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        renderMedia()
    }

    private fun renderMedia() {
        ui.removeCallbacks(updateProgress)
        playerView?.player = null
        player?.release()
        player = null
        playerView = null
        seekBar = null
        seekInProgress = false

        val item = mediaFiles.getOrNull(mediaIndex) ?: return
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        root.removeAllViews()
        root.setBackgroundColor(Color.BLACK)

        if (isVideo(item)) {
            val view = PlayerView(this).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(Color.BLACK)
            }
            root.addView(view, FrameLayout.LayoutParams(-1, -1))
            playerView = view
            player = ExoPlayer.Builder(this).build().also {
                it.repeatMode = Player.REPEAT_MODE_ONE
                view.player = it
                it.setMediaItem(MediaItem.fromUri(Uri.fromFile(item)))
                it.prepare()
                it.playWhenReady = true
            }
        } else {
            // Coil supports full-size static images, GIF, animated WebP and SVG.
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
            }
            root.addView(image, FrameLayout.LayoutParams(-1, -1))
            image.load(item, imageLoader)
        }

        val gestures = View(this).apply {
            isClickable = true
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (abs(dy) > dp(90) && abs(dy) > abs(dx) * 1.25f) {
                            swipeMedia(if (dy < 0) 1 else -1)
                        } else if (abs(dx) < dp(25) && abs(dy) < dp(25)) {
                            val exo = player
                            if (exo?.isPlaying == true) exo.pause() else exo?.play()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        root.addView(gestures, FrameLayout.LayoutParams(-1, -1))

        if (isVideo(item)) {
            val timeline = SeekBar(this).apply {
                max = 1000
                progress = 0
                progressTintList = ColorStateList.valueOf(red)
                progressBackgroundTintList = ColorStateList.valueOf(Color.DKGRAY)
                thumbTintList = ColorStateList.valueOf(red)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                        seekInProgress = true
                    }
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int,
                                                   fromUser: Boolean) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        val duration = player?.duration ?: 0L
                        if (duration > 0L) player?.seekTo(duration * seekBar.progress / 1000L)
                        seekInProgress = false
                    }
                })
            }
            seekBar = timeline
            root.addView(timeline, FrameLayout.LayoutParams(-1, dp(44), Gravity.BOTTOM).apply {
                leftMargin = dp(8)
                rightMargin = dp(64)
                bottomMargin = dp(5)
            })
            ui.post(updateProgress)
        }

        val rotateButton = label("⟳", 28f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Girar tela 90 graus"
            background = background(0xAA18181A.toInt(), red, 12)
            setOnClickListener { rotateScreen() }
        }
        root.addView(rotateButton, FrameLayout.LayoutParams(dp(48), dp(44),
            Gravity.BOTTOM or Gravity.RIGHT).apply {
            rightMargin = dp(9)
            bottomMargin = dp(5)
        })
    }

    private fun rotateScreen() {
        rotationStep = (rotationStep + 1) % 4
        requestedOrientation = when (rotationStep) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun swipeMedia(direction: Int) {
        if (mediaFiles.isEmpty()) return
        mediaIndex = (mediaIndex + direction + mediaFiles.size) % mediaFiles.size
        renderMedia()
    }

    private fun releasePlayer() {
        if (screen == Screen.PLAYER) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            rotationStep = 0
        }
        ui.removeCallbacks(updateProgress)
        seekBar = null
        seekInProgress = false
        playerView?.player = null
        player?.release()
        player = null
        playerView = null
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        releasePlayer()
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.PLAYER -> {
                releasePlayer()
                showBrowser()
            }
            Screen.BROWSER -> {
                if (deleteMode) {
                    exitDeleteMode()
                    showBrowser()
                } else if (directoryStack.size > 1) {
                    directoryStack.removeAt(directoryStack.lastIndex)
                    showBrowser()
                } else {
                    directoryStack.clear()
                    container = null
                    showHome()
                }
            }
            Screen.HOME -> {
                if (deleteMode) {
                    exitDeleteMode()
                    showHome()
                } else super.onBackPressed()
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Android vendors may label .oliver as application/octet-stream.
        }
        startActivityForResult(intent, 142)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 142 && resultCode == RESULT_OK) {
            data?.data?.let { importArchive(it) }
        }
    }

    private fun selectedFilename(uri: Uri): String {
        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "Container.oliver"
        }
        return "Container.oliver"
    }

    private fun safeName(raw: String): String =
        raw.replace(Regex("[^\\p{L}\\p{N}_. -]"), "_")
            .trim(' ', '.', '_').take(80).ifBlank { "Container" }

    private fun importArchive(uri: Uri) {
        if (importing) return
        importing = true
        showHome()
        Toast.makeText(this, "Importando pacote. Não feche o app.", Toast.LENGTH_LONG).show()
        worker.execute {
            var staging: File? = null
            try {
                val filename = selectedFilename(uri)
                require(filename.lowercase(Locale.ROOT).endsWith(".oliver") ||
                        filename.lowercase(Locale.ROOT).endsWith(".zip")) {
                    "Selecione um arquivo .oliver ou .zip"
                }
                val baseName = safeName(filename.substringBeforeLast('.'))
                var destination = File(base, baseName)
                var suffix = 2
                while (destination.exists()) {
                    destination = File(base, baseName + " (" + suffix++ + ")")
                }
                val tmp = File(base, ".importing_" + destination.name)
                staging = tmp
                if (tmp.exists()) tmp.deleteRecursively()
                check(tmp.mkdirs()) { "Falha ao preparar pasta de importação." }
                var entries = 0
                var bytes = 0L
                contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(BufferedInputStream(stream)).use { zip ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            entries++
                            require(entries <= 100_000) { "Pacote contém arquivos demais." }
                            val relative = entry.name.replace('\\', '/')
                            val parts = relative.split('/')
                            require(!relative.startsWith("/") && !relative.contains('\u0000') &&
                                parts.none { it == ".." || it.contains(':') }) {
                                "Caminho inválido no pacote."
                            }
                            if (relative.isBlank() || relative.startsWith("__MACOSX/")) {
                                zip.closeEntry()
                                continue
                            }
                            val output = File(tmp, relative)
                            require(output.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                                "Caminho fora do container."
                            }
                            if (entry.isDirectory) {
                                check(output.mkdirs() || output.isDirectory)
                            } else {
                                check(output.parentFile?.mkdirs() == true ||
                                    output.parentFile?.isDirectory == true)
                                FileOutputStream(output).use { out ->
                                    while (true) {
                                        val read = zip.read(buffer)
                                        if (read < 0) break
                                        bytes += read
                                        require(bytes <= 20L * 1024 * 1024 * 1024) {
                                            "Pacote descompactado excede 20 GB."
                                        }
                                        out.write(buffer, 0, read)
                                    }
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                } ?: error("Não foi possível abrir o arquivo.")
                require(containsMedia(tmp)) { "Pacote sem imagens ou vídeos reconhecidos." }
                check(tmp.renameTo(destination)) { "Não foi possível finalizar a importação." }
                staging = null
                ui.post {
                    importing = false
                    showHome()
                    Toast.makeText(this, "Importado: " + containerName(destination),
                        Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                staging?.deleteRecursively()
                ui.post {
                    importing = false
                    showHome()
                    Toast.makeText(this, "Falha: " + (error.message ?: "arquivo inválido"),
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
