package com.zaid.mediaplayer

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** شاشة تشغيل الفيديو بملء الشاشة مع حفظ آخر موضع مشاهدة. */
class VideoPlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var videoUri: Uri? = null
    private var videoTitle: String = ""
    private var videoId: Long = 0L
    private val prefs by lazy { getSharedPreferences("video_positions", MODE_PRIVATE) }
    private val posKey get() = "pos_$videoId"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        videoUri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        videoId = intent.getLongExtra(EXTRA_ID, 0L)
        if (videoUri == null) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        playerView = PlayerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setControllerShowTimeoutMs(3000)
        }
        setContentView(playerView)
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (videoUri != null) initPlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun initPlayer() {
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@VideoPlayerActivity,
                    "تعذّر تشغيل هذا الفيديو",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        val item = MediaItem.Builder()
            .setUri(videoUri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(videoTitle).build())
            .build()
        val startPos = prefs.getLong(posKey, 0L)
        exo.setMediaItem(item, startPos)
        exo.prepare()
        exo.playWhenReady = true

        playerView.player = exo
        player = exo
    }

    private fun releasePlayer() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur != C.TIME_UNSET && pos > dur - 5000) {
            prefs.edit().remove(posKey).apply()
        } else if (pos > 3000) {
            prefs.edit().putLong(posKey, pos).apply()
        }
        playerView.player = null
        p.release()
        player = null
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ID = "id"
    }
}
