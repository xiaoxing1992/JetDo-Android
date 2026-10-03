package com.arkdo.android.ui.components

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.arkdo.android.common.layout.LocalTabletopHingeCenter
import com.arkdo.android.common.layout.TabletopMedia
import com.arkdo.android.common.layout.rememberTabletopPosture
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.services.content.MediaPlaybackCoordinator
import com.arkdo.android.services.content.MediaSource
import kotlinx.coroutines.delay
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

    // ── 桌面姿态上下分区（官方 tabletop 指导：画面在上半屏、控件在下半屏）──
    // 仅在「桌面姿态 ∧ 已开始播放」时参与：铰链划过卡片就按铰链切上下两区（画面在上、
    // 自绘控制条在下），划不进卡片（首帧、用户没滚到、或姿态不对）整卡退回普通布局。
    // PlayerView 的内置控制条只能渲染在自己 bounds 底部，分区后画面被裁在铰链上方、
    // 控制条会悬在画面中间 —— 所以分区时必须关掉它换自绘条（见 TabletopControlBar）。
    val tabletop = rememberTabletopPosture()
    val tabletopHingeCenter = LocalTabletopHingeCenter.current
    val density = LocalDensity.current.density
    // 卡片顶缘/高度（窗口坐标 dp）。铰链位置也在窗口坐标系（LocalTabletopHingeCenter），
    // 两者同一基准才能求交。首帧未量到时 NaN → splitFraction 判 null，退回普通布局。
    var cardTopYDp by remember { mutableStateOf(Float.NaN) }
    var cardHeightDp by remember { mutableStateOf(0f) }
    val splitFraction = if (tabletop && status == VideoState.READY) {
        TabletopMedia.splitFraction(
            cardTopYDp = cardTopYDp,
            cardHeightDp = cardHeightDp,
            hingeCenterYDp = tabletopHingeCenter?.value ?: Float.NaN
        )
    } else {
        null
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .aspectRatio(aspectRatio)
            .background(Color.Black, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .onGloballyPositioned { coords ->
                cardTopYDp = coords.positionInWindow().y / density
                cardHeightDp = coords.size.height / density
            }
            .then(if (status == VideoState.READY) Modifier else Modifier.clickable { start() })
    ) {
        when (status) {
            VideoState.READY -> player?.let { p ->
                if (splitFraction == null) {
                    AndroidView(
                        factory = { ctx ->
                            val view = createPostPlayerView(ctx, p)
                            view.useController = true
                            playerView = view
                            view
                        },
                        update = { view -> if (view.player !== p) view.player = p },
                        modifier = Modifier.matchParentSize()
                    )
                } else {
                    Column(modifier = Modifier.matchParentSize()) {
                        AndroidView(
                            factory = { ctx ->
                                val view = createPostPlayerView(ctx, p)
                                view.useController = false
                                playerView = view
                                view
                            },
                            update = { view -> if (view.player !== p) view.player = p },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(splitFraction)
                        )
                        TabletopControlBar(
                            player = p,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f - splitFraction)
                        )
                    }
                }
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
                    text = stringResource(R.string.common_load_failed_tap),
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

/**
 * 构建帖子内联视频的 [PlayerView]。必须走 XML inflate：`surface_type=texture_view` 只能由
 * 布局 attr 指定（media3 无代码 setter）—— 用 SurfaceView 时画面会与纯黑高速交替闪，
 * 成因见 `arkdo_player_view.xml` 里的说明。是否启用内置控制条由调用点决定（桌面姿态
 * 分区布局必须关掉，控制条换到铰链下方的 [TabletopControlBar]）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun createPostPlayerView(ctx: android.content.Context, player: ExoPlayer): PlayerView {
    @Suppress("InflateParams")
    val view = android.view.LayoutInflater.from(ctx)
        .inflate(R.layout.arkdo_player_view, null) as PlayerView
    // 首帧前黑底,避免 Surface 初始化的白闪。
    view.setShutterBackgroundColor(android.graphics.Color.BLACK)
    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    view.player = player
    return view
}

/**
 * 桌面姿态**下半区**的自绘控制条。
 *
 * 存在的理由：分区布局里画面被裁在铰链上方，PlayerView 内置控制条只能渲染在自己 bounds
 * 底部、会悬在画面中间 —— 而「控件落到下半屏」正是桌面姿态上下分区的全部意义
 * （下半屏是手最方便够到的区域）。只做播放/暂停 + 拖动进度 + 时间，够用即可。
 */
@Composable
private fun TabletopControlBar(player: ExoPlayer, modifier: Modifier = Modifier) {
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var positionMs by remember { mutableStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var durationMs by remember { mutableStateOf(player.duration.coerceAtLeast(0L)) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                durationMs = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // 进度轮询：media3 没有流式进度回调，500ms 对进度条足够平滑且不费电；
    // 暂停后循环仍在跑但只是读两个属性，成本可忽略。
    LaunchedEffect(isPlaying) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clickable { if (player.isPlaying) player.pause() else player.play() }
        ) {
            FaIcon(if (isPlaying) "pause" else "play", Color.White, 18.dp)
        }
        Slider(
            value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
            onValueChange = { fraction ->
                if (durationMs > 0) {
                    val target = (fraction * durationMs).toLong()
                    positionMs = target
                    player.seekTo(target)
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )
        Text(
            text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
            fontSize = 11.sp,
            color = Color.White
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
