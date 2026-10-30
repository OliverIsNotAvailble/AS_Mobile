package com.`as`.olivergallery

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.math.abs

class MainActivity : Activity() {
    private enum class Screen { HOME, FOLDERS, VIDEOS, PLAYER }

    private val red = Color.rgb(235, 40, 55)
    private val surface = Color.rgb(22, 22, 24)
    private val muted = Color.rgb(162, 162, 165)
    private val videoExt = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi", "ts")
    private val worker = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private val thumbnails = object : LruCache<String, Bitmap>(20 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private lateinit var base: File
    private lateinit var root: FrameLayout
    private var screen = Screen.HOME
    private var container: File? = null
    private var folder: File? = null
    private var videos = emptyList<File>()
    private var videoIndex = 0
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

    private fun isVideo(file: File): Boolean = file.isFile && file.extension.lowercase(Locale.ROOT) in videoExt

    private fun findVideos(parent: File): List<File> =
        if (!parent.exists()) emptyList()
        else parent.walkTopDown().filter { isVideo(it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativeTo(parent).path })
            .toList()

    private fun contentRoot(c: File): File {
        val folders = File(c, "folders")
        if (folders.isDirectory) return folders
        val children = sortedDirs(c)
        if (children.size == 1 && children[0].name.equals(c.name, true)) return children[0]
        return c
    }

    private fun folders(c: File): List<File> {
        val r = contentRoot(c)
        val dirs = sortedDirs(r)
        return if (dirs.isEmpty() && findVideos(r).isNotEmpty()) listOf(r) else dirs
    }

    private fun containerName(c: File): String =
        try {
            JSONObject(File(c, "manifest.json").readText()).optString("name")
                .takeIf { it.isNotBlank() } ?: c.name
        } catch (_: Exception) { c.name }

    private fun buildPage(title: String, subtitle: String, back: Boolean): Pair<LinearLayout, LinearLayout> {
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
        page.addView(header)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return page to content
    }

    private fun emptyMessage(parent: LinearLayout, message: String) {
        parent.addView(label(message, 15f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(60), dp(24), dp(24))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addTiles(parent: LinearLayout, files: List<File>, columns: Int, video: Boolean,
                         titleOf: (File) -> String, details: (File) -> String, open: (File) -> Unit) {
        val grid = GridLayout(this).apply {
            columnCount = columns
            setPadding(dp(16), dp(10), dp(4), dp(20))
        }
        val tileWidth = (resources.displayMetrics.widthPixels - dp(32) - dp(12) * columns) / columns
        for (file in files) {
            val tile = FrameLayout(this).apply {
                background = background(surface, red, 12)
                clipToOutline = true
                isClickable = true
                setOnClickListener { open(file) }
            }
            val params = GridLayout.LayoutParams().apply {
                width = tileWidth
                height = if (video) (tileWidth * 1.55f).toInt() else dp(120)
                rightMargin = dp(12)
                bottomMargin = dp(12)
            }
            if (video) {
                val preview = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.rgb(12, 12, 13))
                    tag = file.absolutePath
                }
                tile.addView(preview, FrameLayout.LayoutParams(-1, -1))
                loadThumbnail(file, preview)
                val play = label("▶", 31f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
                tile.addView(play, FrameLayout.LayoutParams(-1, -1))
                val caption = label(titleOf(file), 12f, Color.WHITE, true).apply {
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = GradientDrawable().apply { setColor(0xDD111111.toInt()) }
                    maxLines = 2
                }
                tile.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            } else {
                val stack = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                stack.addView(label("▣", 31f, red, true).apply { gravity = Gravity.CENTER })
                stack.addView(label(titleOf(file), 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
                stack.addView(label(details(file), 11f, muted).apply { gravity = Gravity.CENTER })
                tile.addView(stack, FrameLayout.LayoutParams(-1, -1))
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

    private fun showHome() {
        screen = Screen.HOME
        val (page, content) = buildPage("AS MOBILE", "CONTAINERS • OFFLINE", false)
        val containers = sortedDirs(base)
        if (containers.isEmpty()) emptyMessage(content,
            "Nenhum container ainda.\n\nToque em IMPORTAR para escolher um pacote .oliver ou .zip.")
        else addTiles(content, containers, 2, false, { containerName(it) },
            { folders(it).size.toString() + " pasta(s)" }) {
            container = it
            showFolders()
        }

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

    private fun showFolders() {
        val c = container ?: return showHome()
        screen = Screen.FOLDERS
        val (_, content) = buildPage(containerName(c), "PASTAS", true)
        val folders = folders(c)
        if (folders.isEmpty()) emptyMessage(content, "Nenhuma pasta com vídeos encontrada.")
        else addTiles(content, folders, 2, false,
            { if (it == contentRoot(c)) "Vídeos" else it.name },
            { findVideos(it).size.toString() + " vídeo(s)" }) {
            folder = it
            showVideos()
        }
    }

    private fun showVideos() {
        val f = folder ?: return showFolders()
        screen = Screen.VIDEOS
        videos = findVideos(f)
        val (_, content) = buildPage(
            if (container != null && f == contentRoot(container!!)) "Vídeos" else f.name,
            videos.size.toString() + " VÍDEO(S) • TOQUE PARA REPRODUZIR", true)
        if (videos.isEmpty()) emptyMessage(content, "Esta pasta não tem vídeos compatíveis.")
        else addTiles(content, videos, 3, true, { it.nameWithoutExtension }, { "" }) { file ->
            playAt(videos.indexOf(file))
        }
    }

    private fun playAt(index: Int) {
        if (videos.isEmpty() || index !in videos.indices) return
        videoIndex = index
        screen = Screen.PLAYER
        rotationStep = 0
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // Hide status only; leave Android's navigation/back button or gesture available.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        root.removeAllViews()
        root.setBackgroundColor(Color.BLACK)

        val view = PlayerView(this).apply {
            useController = false  // Removes large pause / seek / skip overlays.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        playerView = view

        player = ExoPlayer.Builder(this).build().also {
            it.repeatMode = Player.REPEAT_MODE_ONE
            view.player = it
        }

        // Transparent gesture area: tap pauses/plays; vertical swipes change videos.
        // It sits behind the progress bar, so the bar remains seekable.
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
                            swipeVideo(if (dy < 0) 1 else -1)
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

        // Only visible control: permanent seek/progress bar at the bottom.
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
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val duration = player?.duration ?: 0L
                    if (duration > 0L) {
                        player?.seekTo(duration * seekBar.progress / 1000L)
                    }
                    seekInProgress = false
                }
            })
        }
        seekBar = timeline
        root.addView(timeline, FrameLayout.LayoutParams(-1, dp(44), Gravity.BOTTOM).apply {
            leftMargin = dp(8)
            rightMargin = dp(64)  // Leave room for the rotate button.
            bottomMargin = dp(5)
        })

        // Rotate the ACTIVITY (not just the video texture) by 90° each tap.
        // The video keeps playing at the current timestamp during orientation changes.
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

        ui.removeCallbacks(updateProgress)
        ui.post(updateProgress)
        selectVideo()
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

    private fun selectVideo() {
        val exo = player ?: return
        val file = videos.getOrNull(videoIndex) ?: return
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun swipeVideo(direction: Int) {
        if (videos.isEmpty()) return
        videoIndex = (videoIndex + direction + videos.size) % videos.size
        selectVideo()
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
            Screen.PLAYER -> { releasePlayer(); showVideos() }
            Screen.VIDEOS -> showFolders()
            Screen.FOLDERS -> showHome()
            Screen.HOME -> super.onBackPressed()
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
                require(findVideos(tmp).isNotEmpty()) { "Pacote sem vídeos reconhecidos." }
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
