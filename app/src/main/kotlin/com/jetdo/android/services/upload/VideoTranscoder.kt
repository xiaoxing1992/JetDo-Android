package com.jetdo.android.services.upload

import com.jetdo.android.R
import com.jetdo.android.core.data.AppContextHolder
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 把视频压到目标体积以内（对齐鸿蒙 `services/upload/VideoTranscoder.ets`）。
 *
 * ## 为什么是「测量 → 重试」而不是查表
 * 同样码率下，录屏比实拍好压一个数量级（画面大半静止，H.264 大量跳过块）。按固定的
 * 「某分辨率至少要多少码率」查表去决定降到几档，会把本来能保住 1080p 的录屏无脑降成 720p。
 * 所以第一遍按原分辨率乐观地试，量出来超了再降档重来 —— 录屏基本一遍过。
 *
 * ## 时长才是真正的约束
 * 4 MiB = 33.55 Mbit。扣掉容器开销与码率超调按 88% 算，再减去音频，留给视频的是：
 *
 *     10s → 2.89 Mbps（1080p 几乎无损）     60s → 428 kbps（480p）
 *     30s → 920 kbps（720p）                90s → 264 kbps（360p）
 *
 * 所以「10 MB 的视频压不压得动」问错了问题：手机实拍 1080p 约 17 Mbps，10 MB 才 5 秒，
 * 随便压；而 3 分钟的视频哪怕原文件只有 8 MB 也压不进 4 MB 还能看。上限由 [LADDER]
 * 最低档反推，见 [maxDurationSeconds]。
 *
 * ## 实现载体：Media3 Transformer
 * 鸿蒙用 `AVTranscoder`（系统一面镜）；Android 侧的等价物是 Media3 的 `Transformer`
 * （解码 → 可选缩放 → 重编码），本工程已依赖 media3 1.5.1，故直接用它而不是手搓
 * `MediaCodec`+`MediaMuxer` —— 后者要自己处理轨道复制、时间戳、关键帧与 EOS，几百行且
 * 极易出错，收益只有省一个依赖。
 */
class VideoTranscoder(private val context: Context) {

    private val cancelled = AtomicBoolean(false)

    /** 当前这遍正在跑的 Transformer，供 [cancel] 叫停。 */
    @Volatile
    private var active: Transformer? = null

    /** 当前这遍的等待句柄：取消时要主动把它拒掉，否则不会有回调来，await 永远挂着。 */
    @Volatile
    private var abortPass: ((Throwable) -> Unit)? = null

    /**
     * 把 [sourceUri] 压到 [targetBytes] 以内。成功返回沙箱里的 mp4 路径。
     * 全程不抛异常，失败以 `result.error` 返回可展示文案。
     *
     * [known] 是调用方已经探过的元数据。探测要开 fd、解容器头，而上传侧为了给 `<video>`
     * 标签算播放方向尺寸本来就先探了一次 —— 传进来就不必再探第二遍。
     */
    suspend fun fit(
        sourceUri: Uri,
        targetBytes: Long,
        known: VideoProbe? = null,
        onPlan: ((VideoTranscodePlan) -> Unit)? = null,
        onProgress: ((percent: Int, pass: Int) -> Unit)? = null
    ): VideoTranscodeResult {
        cancelled.set(false)

        val probe = known ?: withContext(Dispatchers.IO) {
            runCatching { probeVideo(context, sourceUri) }.getOrNull()
        } ?: return VideoTranscodeResult(error = AppContextHolder.getString(R.string.composer_video_unreadable))

        if (probe.durationSeconds <= 0.0 || probe.width <= 0 || probe.height <= 0) {
            return VideoTranscodeResult(error = AppContextHolder.getString(R.string.composer_video_probe_failed))
        }

        val usableBits = targetBytes.toDouble() * 8.0 * SIZE_SAFETY
        val totalBps = usableBits / probe.durationSeconds
        var videoBps = totalBps - AUDIO_BITRATE
        val floorBps = LADDER.last().minVideoBps.toDouble()
        if (videoBps < floorBps) {
            // 时长超限。把上限算出来告诉用户，比干巴巴一句「太长了」有用。
            val limit = maxDurationSeconds(targetBytes)
            return VideoTranscodeResult(
                error = AppContextHolder.getString(R.string.composer_video_too_long, readableDuration(probe.durationSeconds)) +
                    AppContextHolder.getString(R.string.composer_cannot_fit, readableSize(targetBytes), readableDuration(limit.toDouble()))
            )
        }

        // 首遍：在「原分辨率」和「这个码率撑得住的最高档」里取低的那个 —— 既不上采样
        // （放大等于白烧码率），也不拿 300 kbps 去硬编 1080p。
        val sourceShort = min(probe.width, probe.height)
        var tierIndex = tierIndexFor(sourceShort, videoBps)

        val firstShort = min(sourceShort, LADDER[tierIndex].short)
        onPlan?.invoke(
            VideoTranscodePlan(
                durationSeconds = probe.durationSeconds,
                shortSide = firstShort,
                willDegrade = firstShort <= DEGRADED_SHORT_SIDE
            )
        )

        var lastError: String? = null
        for (pass in 1..MAX_PASSES) {
            if (cancelled.get()) return VideoTranscodeResult(error = "")
            val targetShort = min(sourceShort, LADDER[tierIndex].short)
            val outputPath = File(
                context.cacheDir,
                "$TRANSCODE_FILE_PREFIX${System.currentTimeMillis()}_$pass.mp4"
            ).absolutePath
            Timber.i(
                "[JetDo][Transcode] pass=%d target=%dx(sourceShort=%d) videoBps=%d",
                pass, targetShort, sourceShort, videoBps.roundToInt()
            )

            val produced = try {
                runPass(sourceUri, outputPath, probe, targetShort, videoBps.roundToInt(), pass, onProgress)
            } catch (e: Exception) {
                discard(outputPath)
                if (cancelled.get()) return VideoTranscodeResult(error = "")
                Timber.i(e, "[JetDo][Transcode] pass=%d failed", pass)
                lastError = AppContextHolder.getString(R.string.composer_video_compress_failed)
                break
            }

            if (produced <= targetBytes) {
                Timber.i("[JetDo][Transcode] pass=%d ok size=%d", pass, produced)
                return VideoTranscodeResult(path = outputPath, shortSide = targetShort)
            }

            // 超了：按实测比例回推码率，再多压一点，同时降一档分辨率。
            // 只降码率不降分辨率的话，下一遍会在同样的像素上摊更少的比特，糊得更难看。
            discard(outputPath)
            Timber.i("[JetDo][Transcode] pass=%d overshoot size=%d > %d", pass, produced, targetBytes)
            videoBps = videoBps * (targetBytes.toDouble() / produced.toDouble()) * RETRY_SHRINK
            if (tierIndex < LADDER.size - 1) tierIndex++
            if (videoBps < floorBps) break
        }

        return VideoTranscodeResult(
            error = lastError ?: AppContextHolder.getString(R.string.composer_cannot_compress_to, readableSize(targetBytes))
        )
    }

    /**
     * 中断正在进行的压缩。[fit] 会以空 error 返回（空 = 用户主动取消，调用方不必提示）。
     *
     * 顺序要紧：先让引擎真的停下，再让等待句柄落地 —— 反过来的话 [runPass] 的收尾会在
     * 编码器仍可能写盘时抽走输出文件。
     */
    fun cancel() {
        // active/abort 两处状态一起打：取消「没反应」时，一眼能看出是没在压、
        // 还是压着但叫不停（后者才是真 bug）。
        Timber.i(
            "[JetDo][Transcode] cancel requested active=%s abort=%s",
            active != null, abortPass != null
        )
        cancelled.set(true)
        // 顺序要紧：先让引擎真的停下，再拒等待句柄。反过来的话 runPass 的收尾会在编码器
        // 仍可能写盘时抽走输出文件描述符。
        runCatching { active?.cancel() }
        abortPass?.invoke(IllegalStateException("transcode cancelled"))
    }

    /** 跑一遍完整转码，返回产物字节数。失败/取消抛异常。 */
    private suspend fun runPass(
        sourceUri: Uri,
        outputPath: String,
        probe: VideoProbe,
        targetShort: Int,
        videoBps: Int,
        pass: Int,
        onProgress: ((Int, Int) -> Unit)?
    ): Long = withContext(Dispatchers.Main.immediate) {
        // Transformer 必须在有 Looper 的线程上建（默认取当前线程的 Looper），且进度回调
        // 与完成回调都投递到那个 Looper。统一用主线程，语义最清楚。
        val outputHeight = outputHeightFor(probe, targetShort)
        val effects = if (outputHeight != null) {
            Effects(emptyList(), listOf(Presentation.createForHeight(outputHeight)))
        } else {
            Effects.EMPTY
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
            .setEffects(effects)
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            // 1.5.1 的入口是「编码器设置」而不是裸码率：setRequestedVideoEncoderSettings(...)。
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(videoBps).build()
            )
            .build()

        val finished = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .setEncoderFactory(encoderFactory)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    finished.completeExceptionally(exportException)
                }
            })
            .build()
        active = transformer
        // 1.5.1 的 Listener 没有 onCancelled，cancel() 之后任何回调都不会来 ——
        // 不自建这个「叫醒」句柄的话 finished.await() 会永远挂着。
        abortPass = { reason -> finished.completeExceptionally(reason) }

        val poll = launchProgressPolling(transformer, pass, onProgress)
        try {
            File(outputPath).takeIf { it.exists() }?.delete()
            transformer.start(item, outputPath)
            finished.await()
        } finally {
            poll.cancel()
            active = null
            abortPass = null
        }
        File(outputPath).length()
    }

    /**
     * 进度轮询。
     *
     * 用 `getProgress` 轮询而不是 `Transformer.Listener.onProgress`：后者在不同 media3
     * 小版本间签名反复变过，而 `getProgress` 从 1.0 起就稳定。250ms 一次，肉眼够顺，
     * 也远低于一次递归查询的代价。
     */
    private fun CoroutineScope.launchProgressPolling(
        transformer: Transformer,
        pass: Int,
        onProgress: ((Int, Int) -> Unit)?
    ) = launch {
        if (onProgress == null) return@launch
        val holder = ProgressHolder()
        while (isActive) {
            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress(holder.progress, pass)
            }
            delay(PROGRESS_POLL_MS)
        }
    }

    companion object {

        // ---- 质量阶梯 ----

        /** 分辨率阶梯：short 是短边像素（竖屏视频同样适用），minVideoBps 是这一档还看得过去的码率下限。 */
        internal data class QualityTier(val short: Int, val minVideoBps: Int)

        /**
         * 下限取系统默认码率的一半左右（240~480p 默认 1 Mbps、480~720p 2 Mbps、
         * 720~1080p 4 Mbps），即「明显有损但仍可看」的水位。
         */
        internal val LADDER = listOf(
            QualityTier(1080, 2_500_000),
            QualityTier(720, 1_200_000),
            QualityTier(540, 700_000),
            QualityTier(480, 500_000),
            QualityTier(360, 300_000),
            QualityTier(240, 150_000)
        )

        /** 音频码率。默认 48 kbps，这里给 64 kbps 换点音质 —— 90 秒下也只多吃 0.2 MB。 */
        private const val AUDIO_BITRATE = 64_000.0

        /** 可用比例。MP4 容器开销（moov、每帧头）加上码率控制的超调，留 12% 余量。 */
        internal const val SIZE_SAFETY = 0.88

        /** 最多压几遍。每遍都是一次完整重编码，30 秒的 1080p 单遍就可能十几秒。 */
        private const val MAX_PASSES = 3

        /** 重试时在「按实测比例回推」的基础上再压一点，避免又踩在线上。 */
        private const val RETRY_SHRINK = 0.9

        /** 低于此短边就认为画质已经明显劣化，提示用户。 */
        private const val DEGRADED_SHORT_SIDE = 360

        /**
         * 转码产物的文件名前缀。冷启动清扫（[clearStaleOutputs]）靠它认出「是我们留下的」，
         * 故此处与产物命名必须共用同一个常量。
         */
        private const val TRANSCODE_FILE_PREFIX = "jetdo_transcode_"

        private const val PROGRESS_POLL_MS = 250L

        /**
         * 当前在跑的实例，供 UI 层的「取消」够得着 —— 压缩要走几十秒，取消按钮在
         * ViewModel 里，而转码器是上传路径内部造的，中间隔着两层，只能在这里登记。
         * 同一时刻只可能有一个（上传是串行的）。
         */
        @Volatile
        private var activeInstance: VideoTranscoder? = null

        /** 由上传路径在 [fit] 前后登记/清空。 */
        fun markActive(transcoder: VideoTranscoder?) {
            activeInstance = transcoder
        }

        /** 中断正在进行的压缩；没有在压时是空操作。 */
        fun cancelActive() {
            activeInstance?.cancel()
        }

        // ---- 探测 ----

        /**
         * 读时长、编码方向宽高、旋转角。
         *
         * ## 编码方向 vs 播放方向：两个用途要的不是同一个
         * 带 90/270 度旋转的视频，元数据里的宽高是**编码方向**的（如 1920x1080 + rotate 90，
         * 播放出来才是竖的）。这两处要区别对待，搞混了两边都会错：
         *
         *  · 喂给缩放的**目标高度** —— 用**播放方向**。Media3 Transformer 的输出是旋转后
         *    的播放方向画面，把编码方向的高度当目标值会算出一个比例完全不对的尺寸。
         *  · 写进 `<video>` 标签的宽高 —— 用**播放方向**（见 [displaySize]），理由同鸿蒙。
         *
         * 画质决策不受影响：阶梯按短边 min(w,h) 算，互换不改变这个集合。
         */
        fun probeVideo(context: Context, uri: Uri): VideoProbe {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                return VideoProbe(
                    durationSeconds = durationMs / 1000.0,
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0,
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0,
                    rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                )
            } finally {
                runCatching { retriever.release() }
            }
        }

        /**
         * 播放方向的宽高（应用旋转角后）。写 `<video>` 标签的宽高属性用这个。
         * 转码只等比缩放、不改变宽高比，所以拿源视频的比例给产物用是对的。
         */
        fun displaySize(probe: VideoProbe): VideoSize {
            val rotated = probe.rotation == 90 || probe.rotation == 270
            return VideoSize(
                width = if (rotated) probe.height else probe.width,
                height = if (rotated) probe.width else probe.height
            )
        }

        // ---- 纯计算（单测覆盖） ----

        /** 在给定码率下，从原始短边出发能站住的最高一档。 */
        internal fun tierIndexFor(sourceShort: Int, videoBps: Double): Int {
            LADDER.forEachIndexed { index, tier ->
                if (tier.short > sourceShort) return@forEachIndexed // 比原片还大的档跳过 —— 只降不升
                if (videoBps >= tier.minVideoBps || index == LADDER.size - 1) return index
            }
            return LADDER.size - 1
        }

        /**
         * 目标输出高度（播放方向）。返回 null = 不需要缩，交给 `/uploads.json` 之外的
         * 原尺寸路径。
         *
         * 只按**短边**说话：横屏短边是高、竖屏短边是宽，这里折成同一个高度传出去。
         */
        internal fun outputHeightFor(probe: VideoProbe, targetShort: Int): Int? {
            val display = displaySize(probe)
            val sourceShort = min(display.width, display.height)
            if (sourceShort <= targetShort || sourceShort <= 0) return null
            return evenize(display.height.toDouble() * targetShort.toDouble() / sourceShort.toDouble())
        }

        /** 偶数化（H.264 要求宽高为偶数）。 */
        internal fun evenize(value: Double): Int {
            val rounded = value.roundToInt()
            return max(2, if (rounded % 2 == 0) rounded else rounded - 1)
        }

        /** 给定目标体积，最长能压多久（秒）。由阶梯最低档反推，常数一改这里自动跟上。 */
        internal fun maxDurationSeconds(targetBytes: Long): Long {
            val floorBps = LADDER.last().minVideoBps + AUDIO_BITRATE
            return (targetBytes.toDouble() * 8.0 * SIZE_SAFETY / floorBps).toLong()
        }

        internal fun readableDuration(seconds: Double): String {
            val total = seconds.roundToInt()
            if (total < 60) return AppContextHolder.getString(R.string.relative_seconds, total)
            val minutes = total / 60
            val rest = total % 60
            return if (rest == 0) AppContextHolder.getString(R.string.relative_minutes, minutes) else AppContextHolder.getString(R.string.relative_min_sec, minutes, rest)
        }

        internal fun readableSize(bytes: Long): String {
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.1f MB", mb)
        }

        // ---- 产物清理 ----

        /** 删掉沙箱里的临时产物。上传完成、失败、取消都要调。 */
        fun discard(path: String?) {
            if (path.isNullOrEmpty()) return
            runCatching { File(path).delete() }
        }

        /**
         * 冷启动清扫遗留的转码产物。正常路径上 [discard] 已经删干净了，这里兜的是
         * **没有正常路径**的那种收场：压 1080p 要十几秒，期间切后台被系统回收、或崩溃，
         * finally 根本不会跑，产物（可能几十 MB）就永久留在 cacheDir 里。
         *
         * 与 `ArkWebNetworkBridge.clearMediaCacheDir` 同一时机、同一理由调用。只删我们
         * 自己的前缀，不动 cacheDir 里别人的东西。
         */
        fun clearStaleOutputs(context: Context) {
            runCatching {
                val stale = context.cacheDir.listFiles { file -> file.name.startsWith(TRANSCODE_FILE_PREFIX) }
                if (stale.isNullOrEmpty()) return@runCatching
                stale.forEach { it.delete() }
                Timber.i("[JetDo][Transcode] cleared %d stale transcode output(s)", stale.size)
            }.onFailure { Timber.i(it, "[JetDo][Transcode] clear stale outputs failed") }
        }
    }
}

/** 一次压缩任务的产出。error 是可直接展示给用户的文案。 */
data class VideoTranscodeResult(
    /** 沙箱里的产物路径。调用方用完**必须**调 `VideoTranscoder.discard` 删掉。 */
    val path: String? = null,
    val error: String? = null,
    /** 实际落到的分辨率短边。<=360 时画质已明显劣化，调用方可据此提示。 */
    val shortSide: Int = 0
)

/** 探测完成、真正开压之前的计划，供 UI 提前告知用户。 */
data class VideoTranscodePlan(
    val durationSeconds: Double,
    /** 首遍目标分辨率短边。 */
    val shortSide: Int,
    /** 预计画质会明显下降（首遍就已经要降到 360p 及以下）。 */
    val willDegrade: Boolean
)

/** 视频元数据。width/height 是**编码方向**的，播放方向见 `VideoTranscoder.displaySize`。 */
data class VideoProbe(
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    /** 旋转角（度）。90/270 表示播放时宽高互换。 */
    val rotation: Int
)

data class VideoSize(val width: Int, val height: Int)
