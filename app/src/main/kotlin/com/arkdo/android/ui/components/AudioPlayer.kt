package com.arkdo.android.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import com.arkdo.android.core.designsystem.FaIcon
import com.arkdo.android.services.content.MediaPlaybackCoordinator
import com.arkdo.android.services.content.MediaSource
import com.arkdo.android.ui.theme.arkdoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

private const val AUDIO_ERROR_BG = 0xFFDC2626.toInt()

// 帖子内联音频播放条,对齐鸿蒙 AudioPlayer.ets:首次点播放才建 ExoPlayer(一个长帖可能有很多条音频,
// 不提前占资源)。媒体源分两类(MediaSource):论坛同源上传件(/uploads/short-url/*.xz,CF 挡非浏览器)
// 先经 onResolveMedia(→ 桥 downloadMedia 经系统下载委托过 CF)下到沙箱再播本地文件;外站公开直链直接
// 流式播放。直连时补浏览器 UA + linux.do Referer,过第三方文件床按 UA/Referer 的防盗链。
// 组件销毁时释放 player 并注销全局播放协调器。
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AudioPlayer(
    src: String,
    onResolveMedia: suspend (String) -> String,
    modifier: Modifier = Modifier
) {
    // idle=未开始 / loading=创建+准备中 / playing / paused / error。对齐鸿蒙同名状态串。
    var status by remember { mutableStateOf(AudioState.IDLE) }
    var currentMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // 拖动进度条期间暂停回写,避免滑块与播放头打架(对齐鸿蒙 seeking)。
    var seeking by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 全局播放协调器:开始播放时通知其它播放器停止;收到他人开始则自己停——pause 后
    // UI 经 onIsPlayingChanged 回「播放」态。id 在组合期注册,销毁时注销并释放播放器。
    val coordinatorId = remember { MediaPlaybackCoordinator.register { player?.pause() } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // 本页被别的导航目的地盖住(跳另一帖/他人主页/内置网页)或整个应用退后台时,
        // 目的地生命周期离开 RESUMED → 确定性暂停。组合销毁虽也会 release,但导航转场
        // 期间销毁不及时,会出现「声音还在播、控制条不在可视区」的僵尸播放(用户实测)。
        // 回到本页不自动续播,保持暂停态等用户再点。
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                player?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            MediaPlaybackCoordinator.unregister(coordinatorId)
            player?.release()
            player = null
        }
    }

    // 播放中每 500ms 回写播放头(鸿蒙走 timeUpdate 事件,Compose 侧轮询等价)。
    LaunchedEffect(status) {
        while (status == AudioState.PLAYING) {
            if (!seeking) {
                player?.let { currentMs = it.currentPosition }
            }
            delay(500)
        }
    }

    fun playerListener(p: ExoPlayer) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    durationMs = p.duration.takeIf { it > 0 } ?: 0L
                }
                Player.STATE_ENDED -> {
                    status = AudioState.PAUSED
                    currentMs = durationMs
                }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                status = AudioState.PLAYING
            } else if (status == AudioState.PLAYING) {
                status = AudioState.PAUSED
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.i("[JetDo][AudioPlayer] player error code=%d msg=%s", error.errorCode, error.message)
            status = AudioState.ERROR
        }
    }

    fun prepareAndPlay(url: String, local: Boolean) {
        val p = player ?: buildPostMediaPlayer(context).also {
            it.addListener(playerListener(it))
            player = it
        }
        p.setMediaItem(postMediaItem(url, local))
        p.prepare()
        // 下载竞态:加载期间若已被别的播放器抢占,则不自动播,停在暂停态等用户再点(对齐鸿蒙)。
        p.playWhenReady = MediaPlaybackCoordinator.isActive(coordinatorId)
    }

    fun startPlayback() {
        if (src.isEmpty()) return
        status = AudioState.LOADING
        val direct = !MediaSource.needsDownloadBridge(src)
        Timber.i("[JetDo][AudioPlayer] startPlayback direct=%b src=%s", direct, src)
        if (direct) {
            prepareAndPlay(src, local = false)
            return
        }
        // 论坛同源上传件:先经桥的下载委托过 CF 下到沙箱,再播本地文件。
        scope.launch {
            try {
                val path = onResolveMedia(src.trim())
                prepareAndPlay(path, local = true)
            } catch (t: Throwable) {
                Timber.i("[JetDo][AudioPlayer] resolve media failed msg=%s", t.message)
                status = AudioState.ERROR
            }
        }
    }

    fun onPrimaryTap() {
        if (status == AudioState.LOADING) return
        if (status == AudioState.ERROR) {
            // 重试:释放旧播放器回 idle,继续往下走重建流程(对齐鸿蒙 onPrimaryTap)。
            player?.release()
            player = null
            status = AudioState.IDLE
        }
        if (status == AudioState.PLAYING) {
            player?.pause()
            return
        }
        // 本播放器开始/恢复播放前,先让其它播放器停下。
        MediaPlaybackCoordinator.notifyStarted(coordinatorId)
        if (status == AudioState.PAUSED && player != null) {
            player?.play()
            return
        }
        startPlayback()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(arkdoColors.pageBgGray, RoundedCornerShape(8.dp))
            .border(1.dp, arkdoColors.border, RoundedCornerShape(8.dp))
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
    ) {
        // 播放/暂停/重试按钮:loading 转圈,error 感叹号(点一下重试),其余 play/pause。
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (status == AudioState.ERROR) Color(AUDIO_ERROR_BG) else arkdoColors.ldAccent)
                .clickable { onPrimaryTap() }
        ) {
            when (status) {
                AudioState.LOADING -> CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp)
                )
                AudioState.ERROR -> FaIcon("circle-exclamation", Color.White, 18.dp)
                else -> FaIcon(if (status == AudioState.PLAYING) "pause" else "play", Color.White, 16.dp)
            }
        }

        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Slider(
                value = progressPercent(currentMs, durationMs),
                onValueChange = { value ->
                    if (durationMs > 0) {
                        seeking = true
                        currentMs = (value / 100f * durationMs).toLong()
                    }
                },
                onValueChangeFinished = {
                    seeking = false
                    player?.let { if (durationMs > 0) it.seekTo(currentMs) }
                },
                valueRange = 0f..100f,
                enabled = durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = arkdoColors.ldAccent,
                    activeTrackColor = arkdoColors.ldAccent,
                    inactiveTrackColor = arkdoColors.border
                )
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(formatTime(currentMs), fontSize = 11.sp, color = arkdoColors.textSecondary)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    if (durationMs > 0) formatTime(durationMs) else "--:--",
                    fontSize = 11.sp,
                    color = arkdoColors.textSecondary
                )
            }
        }
    }
}

private enum class AudioState { IDLE, LOADING, PLAYING, PAUSED, ERROR }

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val padded = if (seconds < 10) "0$seconds" else "$seconds"
    return "$minutes:$padded"
}

private fun progressPercent(currentMs: Long, durationMs: Long): Float {
    if (durationMs <= 0) return 0f
    val percent = currentMs.toFloat() / durationMs * 100f
    return percent.coerceIn(0f, 100f)
}
