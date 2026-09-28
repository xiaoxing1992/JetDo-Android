package com.arkdo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.arkdo.android.R
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.services.content.MediaPlaybackCoordinator
import com.arkdo.android.services.content.MediaSource
import kotlinx.coroutines.launch
import timber.log.Timber

// 帖子内联视频卡,对齐鸿蒙 VideoPlayer.ets:黑底圆角卡(比例取 <video> 自带宽高,缺省 16:9,
// 上限 520dp),首帧前显示黑底 + 播放钮,点击后加载并自动播放,系统控制条(对齐 controls(true))。
// 媒体源分流见 PostMediaEngine:论坛同源上传件经 onResolveMedia 过 CF 下到沙箱再播本地;
// 外站公开直链直接流播。参与全局播放协调器;页面被别的目的地盖住/退后台即暂停(同 AudioPlayer)。
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    src: String,
    aspectRatio: Float,
    onResolveMedia: suspend (String) -> String,
    modifier: Modifier = Modifier
) {
    // idle=未开始(黑底播放钮) / loading=下载中 / ready=已交给 PlayerView / error。
    var status by remember { mutableStateOf(VideoState.IDLE) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 协调器:他人开播则自己暂停;本组件真正开播(onIsPlayingChanged)时抢占活跃者。
    val coordinatorId = remember { MediaPlaybackCoordinator.register { player?.pause() } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // 页面被盖住/退后台 → 确定性暂停(同 AudioPlayer 的僵尸播放修复)。
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                player?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            MediaPlaybackCoordinator.unregister(coordinatorId)
            playerView?.player = null
            player?.release()
            player = null
        }
    }

    fun playerListener(p: ExoPlayer) = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 视频真正开始播放:让其它播放器停下(对齐鸿蒙 Video onStart → notifyStarted)。
            if (isPlaying) {
                MediaPlaybackCoordinator.notifyStarted(coordinatorId)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.i("[ArkDO][VideoPlayer] player error code=%d msg=%s", error.errorCode, error.message)
            // 回到占位错误态,点一下重试(对齐鸿蒙 onError → localUri='' + status=error)。
            status = VideoState.ERROR
            playerView?.player = null
            p.release()
            if (player === p) player = null
        }
    }

    fun attachAndPlay(url: String, local: Boolean) {
        val p = player ?: buildPostMediaPlayer(context).also {
            it.addListener(playerListener(it))
            player = it
        }
        p.setMediaItem(postMediaItem(url, local))
        p.prepare()
        // 下载竞态:加载期间若已被别的播放器抢占,则不自动播,停在首帧等用户点(对齐鸿蒙 autoPlay)。
        p.playWhenReady = MediaPlaybackCoordinator.isActive(coordinatorId)
        status = VideoState.READY
    }

    fun start() {
        // 加载中忽略点击(对齐鸿蒙 start 的 loading 判)。
        if (status == VideoState.LOADING || src.isEmpty()) return
        if (status == VideoState.ERROR) {
            // 重试:释放旧播放器回 idle,继续走加载。
            playerView?.player = null
            player?.release()
            player = null
            status = VideoState.IDLE
        }
        // 点击加载即抢占播放:先让其它播放器停下(对齐鸿蒙 start 开头的 notifyStarted)。
        MediaPlaybackCoordinator.notifyStarted(coordinatorId)
        // 外站公开直链:直接流播,不经下载委托。
        if (!MediaSource.needsDownloadBridge(src)) {
            attachAndPlay(src, local = false)
            return
        }
        status = VideoState.LOADING
        scope.launch {
            try {
                val path = onResolveMedia(src.trim())
                attachAndPlay(path, local = true)
            } catch (t: Throwable) {
                Timber.i("[ArkDO][VideoPlayer] resolve media failed msg=%s", t.message)
                status = VideoState.ERROR
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .aspectRatio(aspectRatio)
            .background(Color.Black, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .then(if (status == VideoState.READY) Modifier else Modifier.clickable { start() })
    ) {
        when (status) {
            VideoState.READY -> player?.let { p ->
                AndroidView(
                    factory = { ctx ->
                        // 必须走 XML inflate:surface_type=texture_view 只能由布局 attr 指定(media3 无代码 setter)。
                        // 用 SurfaceView 时,画面会与纯黑高速交替闪 —— 见 arkdo_player_view.xml 里的成因说明。
                        @Suppress("InflateParams")
                        val view = android.view.LayoutInflater.from(ctx)
                            .inflate(R.layout.arkdo_player_view, null) as PlayerView
                        view.apply {
                            useController = true
                            // 首帧前黑底,避免 Surface 初始化的白闪。
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = p
                            playerView = this
                        }
                    },
                    update = { view -> if (view.player !== p) view.player = p },
                    modifier = Modifier.matchParentSize()
                )
            }
            VideoState.LOADING -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.matchParentSize()
            ) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
            }
            VideoState.ERROR -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                modifier = Modifier.matchParentSize()
            ) {
                FaIcon("circle-exclamation", Color.White, 40.dp)
                Text(
                    text = "加载失败，点击重试",
                    fontSize = 13.sp,
                    color = Color.White,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            VideoState.IDLE -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.matchParentSize()
            ) {
                FaIcon("play", Color.White, 30.dp)
            }
        }
    }
}

private enum class VideoState { IDLE, LOADING, READY, ERROR }
