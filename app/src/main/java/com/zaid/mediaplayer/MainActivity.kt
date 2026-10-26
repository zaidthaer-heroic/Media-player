package com.zaid.mediaplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot()
                }
            }
        }
    }
}

// ───────────── الصلاحيات ─────────────

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.POST_NOTIFICATIONS
    ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun hasMediaAccess(context: Context): Boolean {
    val needed = if (Build.VERSION.SDK_INT >= 33)
        listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
    else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    return needed.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

// ───────────── الاتصال بخدمة الموسيقى ─────────────

@Composable
private fun rememberController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            if (!future.isCancelled) {
                try {
                    controller = future.get()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    return controller
}

private data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val hasItem: Boolean = false,
    val mediaId: String? = null
)

@Composable
private fun rememberNowPlaying(controller: MediaController?): NowPlaying {
    var state by remember { mutableStateOf(NowPlaying()) }
    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose { }
        fun update() {
            state = NowPlaying(
                title = controller.mediaMetadata.title?.toString() ?: "",
                artist = controller.mediaMetadata.artist?.toString() ?: "",
                isPlaying = controller.isPlaying,
                hasItem = controller.mediaItemCount > 0,
                mediaId = controller.currentMediaItem?.mediaId
            )
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = update()
        }
        controller.addListener(listener)
        update()
        onDispose { controller.removeListener(listener) }
    }
    return state
}

private fun AudioItem.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist).build()
        )
        .build()

private fun playAudio(controller: MediaController, items: List<AudioItem>, index: Int) {
    val sameQueue = controller.mediaItemCount == items.size &&
        controller.getMediaItemAt(index).mediaId == items[index].id.toString()
    if (sameQueue) {
        controller.seekTo(index, 0L)
    } else {
        controller.setMediaItems(items.map { it.toMediaItem() }, index, 0L)
    }
    controller.prepare()
    controller.play()
}

// ───────────── الواجهة الرئيسية ─────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(hasMediaAccess(context)) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf<List<AudioItem>>(emptyList()) }
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasMediaAccess(context) }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(requiredPermissions())
    }

    LaunchedEffect(granted, refreshKey) {
        if (granted) {
            loading = true
            audio = withContext(Dispatchers.IO) { MediaRepository.loadAudio(context) }
            videos = withContext(Dispatchers.IO) { MediaRepository.loadVideos(context) }
            loading = false
        }
    }

    val controller = rememberController()
    val now = rememberNowPlaying(controller)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tab == 0) "الموسيقى" else "الفيديو") },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                if (now.hasItem && controller != null) MiniPlayer(now, controller)
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                        label = { Text("الموسيقى") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null) },
                        label = { Text("الفيديو") }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                !granted -> PermissionPrompt { launcher.launch(requiredPermissions()) }
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                tab == 0 -> MusicList(audio, now) { index ->
                    controller?.let { playAudio(it, audio, index) }
                }
                else -> VideoList(videos)
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "يحتاج التطبيق إلى صلاحية الوصول إلى الملفات الصوتية والفيديو في جهازك لعرضها وتشغيلها.",
            textAlign = TextAlign.Center
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) {
            Text("منح الصلاحية")
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ───────────── قسم الموسيقى ─────────────

@Composable
private fun MusicList(items: List<AudioItem>, now: NowPlaying, onClick: (Int) -> Unit) {
    if (items.isEmpty()) {
        EmptyState("لا توجد أغانٍ في الجهاز")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val current = now.mediaId == item.id.toString()
            ListItem(
                modifier = Modifier.clickable { onClick(index) },
                leadingContent = {
                    Icon(
                        if (current && now.isPlaying) Icons.Filled.PlayArrow else Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = if (current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                headlineContent = {
                    Text(
                        item.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (current) MaterialTheme.colorScheme.primary
                        else Color.Unspecified
                    )
                },
                supportingContent = {
                    Text(
                        "${item.artist} • ${formatDuration(item.durationMs)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }
}

@Composable
private fun MiniPlayer(now: NowPlaying, controller: MediaController) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    now.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    now.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // أزرار التحكم تبقى بترتيب عالمي (سابق ◀ تشغيل ▶ تالي)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controller.seekToPreviousMediaItem() }) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "السابق")
                    }
                    IconButton(onClick = {
                        if (controller.isPlaying) controller.pause() else controller.play()
                    }) {
                        Icon(
                            if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "تشغيل/إيقاف"
                        )
                    }
                    IconButton(onClick = { controller.seekToNextMediaItem() }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "التالي")
                    }
                }
            }
        }
    }
}

// ───────────── قسم الفيديو ─────────────

@Composable
private fun VideoList(items: List<VideoItem>) {
    val context = LocalContext.current
    val loader = remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
    if (items.isEmpty()) {
        EmptyState("لا توجد فيديوهات في الجهاز")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(items, key = { _, v -> v.id }) { _, v ->
            ListItem(
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(context, VideoPlayerActivity::class.java)
                            .putExtra(VideoPlayerActivity.EXTRA_URI, v.uri.toString())
                            .putExtra(VideoPlayerActivity.EXTRA_TITLE, v.title)
                            .putExtra(VideoPlayerActivity.EXTRA_ID, v.id)
                    )
                },
                leadingContent = {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(v.uri)
                            .videoFrameMillis(1000)
                            .build(),
                        imageLoader = loader,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp, 60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF23262D))
                    )
                },
                headlineContent = {
                    Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text("${formatDuration(v.durationMs)} • ${formatSize(v.sizeBytes)}")
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }
}
