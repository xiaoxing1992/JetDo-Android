package com.arkdo.android.ui.components

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.services.web.UserAgentProvider

// 帖内音频/视频播放条共用的 ExoPlayer 构建工厂。对齐鸿蒙两条取流路径:
//   - 外站公开直链:直接流播,请求带浏览器 UA + linux.do Referer,过第三方文件床的防盗链;
//   - 论坛同源上传件:调用方先经桥 downloadMedia 过 CF 下到沙箱,再播本地文件。
// 引擎不处理音频焦点(对齐鸿蒙 AVPlayer/Video 组件的默认行为)。
@UnstableApi
internal fun buildPostMediaPlayer(context: Context): ExoPlayer {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(UserAgentProvider.get(context))
        .setDefaultRequestProperties(mapOf("Referer" to AppConstants.BASE_URL))
        .setAllowCrossProtocolRedirects(true)
    val factory = DataSource.Factory {
        SchemeRoutingDataSource(httpFactory.createDataSource(), FileDataSource())
    }
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
        .build()
}

@UnstableApi
internal fun postMediaItem(url: String, local: Boolean): MediaItem =
    if (local) MediaItem.fromUri(Uri.fromFile(java.io.File(url))) else MediaItem.fromUri(url)

// 按 scheme 分流的数据源:http(s) 走带浏览器 UA/Referer 的源(防盗链),本地文件走 FileDataSource。
// DefaultMediaSourceFactory 对 file:// 也会用注入的 factory,不分流的话本地回放会误用 http 源。
@UnstableApi
private class SchemeRoutingDataSource(
    private val http: DataSource,
    private val file: DataSource
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        http.addTransferListener(transferListener)
        file.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val delegate = if (dataSpec.uri.scheme?.lowercase() in setOf("http", "https")) http else file
        current = delegate
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(current) { "open() must be called before read()" }.read(buffer, offset, length)

    override fun close() {
        current?.close()
        current = null
    }

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        current?.responseHeaders ?: emptyMap()
}
