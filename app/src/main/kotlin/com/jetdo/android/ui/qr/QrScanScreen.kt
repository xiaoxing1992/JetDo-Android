package com.jetdo.android.ui.qr

import android.Manifest
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.jetdo.android.R
import com.jetdo.android.services.auth.BrowserAuthLoginFlow
import com.jetdo.android.services.auth.BrowserAuthResult
import com.jetdo.android.services.permission.CameraPermission
import com.jetdo.android.services.permission.CameraPermissionState
import com.jetdo.android.services.permission.findActivity
import com.jetdo.android.ui.theme.jetdoColors
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// ==========================================================================
// 扫码登录·扫入端：相机逐帧找二维码 → 解出另一台设备的 key+OTP → 兑换登录。
// zxing core 纯 Java 解码（MultiFormatReader + PlanarYUVLuminanceSource），
// 不依赖 ML Kit / GMS —— 国产机没有 Google 服务也要能扫。
// ==========================================================================

private const val LOG_TAG = "[JetDo][QrScan]"
private const val DECODE_INTERVAL_MS = 150L

/** YUV_420_888 逐帧 → zxing 解码。节流 150ms/帧；命中后由 AtomicBoolean 停流。 */
private class QrAnalyzer(private val handled: AtomicBoolean, private val onQr: (String) -> Unit) :
    ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }
    private var lastAt = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (handled.get() || now - lastAt < DECODE_INTERVAL_MS) {
            image.close()
            return
        }
        lastAt = now
        try {
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val buffer = plane.buffer
            // rowStride 可能大于 width（内存对齐），逐行拷出连续平面数据。
            val data = if (rowStride == width) {
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } else {
                ByteArray(width * height).also { out ->
                    for (row in 0 until height) {
                        buffer.position(row * rowStride)
                        buffer.get(out, row * width, width)
                    }
                }
            }
            var source: LuminanceSource = PlanarYUVLuminanceSource(
                data, rowStride, height, 0, 0, width, height, false
            )
            // 相机帧相对屏幕有旋转：按 rotationDegrees 转到「用户视角」再解。
            // ⚠️ zxing 的 PlanarYUVLuminanceSource 只实现 rotateCounterClockwise
            // （rotateClockwise 走基类默认抛 UnsupportedOperationException），270° 转三次。
            when (image.imageInfo.rotationDegrees) {
                90 -> source = source.rotateCounterClockwise()
                180 -> source = source.rotateCounterClockwise().rotateCounterClockwise()
                270 -> source = source
                    .rotateCounterClockwise().rotateCounterClockwise().rotateCounterClockwise()
            }
            val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            Timber.i("%s qr decoded len=%d", LOG_TAG, text.length)
            handled.set(true)
            onQr(text)
        } catch (_: NotFoundException) {
            // 没扫到，下一帧再说
        } catch (e: Exception) {
            Timber.v("%s frame decode error msg=%s", LOG_TAG, e.message)
        } finally {
            reader.reset()
            image.close()
        }
    }
}

@Composable
fun QrScanScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = jetdoColors
    // shouldShowRequestPermissionRationale 是 Activity 的方法；LocalContext 在 Activity 宿主下
    // 就是它本身，但经 ContextWrapper 包过一层，所以要走 findActivity 拆出来。
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionState by remember {
        mutableStateOf(CameraPermission.state(context, activity))
    }
    // 扫码命中守卫：分析器跑在相机线程，用 AtomicBoolean；失败时复位允许重扫。
    val handled = remember { AtomicBoolean(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 回调时重算而不是直接用 granted 布尔：shouldShowRequestPermissionRationale 的
        // 真值要到这一刻才更新，用布尔会把「还能再问」和「不再询问」压成同一个态。
        permissionState = CameraPermission.state(context, activity)
    }

    // 进页面即申请：只对「还能弹框」的态发起 —— 永久拒绝态发起也弹不出来（系统静默回调拒绝）。
    LaunchedEffect(Unit) {
        if (permissionState == CameraPermissionState.DENIED_CAN_ASK) {
            CameraPermission.markRequested(context) // 必须在 launch 之前落盘
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 从系统设置返回时重算：用户在设置里开了相机权限，只有 ON_RESUME 能收到这个变化
    // （记住的 permissionState 不会自己变），否则预览永远起不来。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionState = CameraPermission.state(context, activity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg)
    ) {
        // 顶栏：与登录页同骨架（60dp + 居中标题 + 右上角关闭）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(60.dp)
                .background(colors.pageBg)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.auth_qr_scan_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
            Text(
                text = "✕",
                fontSize = 18.sp,
                color = colors.textPrimary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clickable { onNavigateBack() }
                    .padding(8.dp)
            )
        }

        when (permissionState) {
            CameraPermissionState.GRANTED -> {
                // lifecycleOwner 必须在 composable 上下文取（AndroidView factory 不是 composable）
                val lifecycleOwner = LocalLifecycleOwner.current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            runCatching {
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build()
                                preview.setSurfaceProvider(previewView.surfaceProvider)
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                val executor = Executors.newSingleThreadExecutor()
                                analysis.setAnalyzer(executor, QrAnalyzer(handled) { raw ->
                                    scope.launch {
                                        val result =
                                            BrowserAuthLoginFlow.handleScannedPayload(context, raw)
                                        BrowserAuthLoginFlow.toastResult(context, result)
                                        if (result is BrowserAuthResult.Success) {
                                            onNavigateBack()
                                        } else {
                                            handled.set(false) // 失败允许重扫
                                        }
                                    }
                                })
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                            }.onFailure {
                                Timber.w("[JetDo][QrScan] bind camera failed msg=%s", it.message)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        }
                    )
                }
            }
            // 还能弹框：给一次「重新授权」，点了立刻再弹一次系统框。
            CameraPermissionState.DENIED_CAN_ASK -> CameraDeniedPane(
                message = stringResource(R.string.auth_qr_camera_denied),
                actionLabel = stringResource(R.string.auth_qr_camera_retry),
                onAction = {
                    CameraPermission.markRequested(context)
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            )
            // 系统不再弹框：App 侧无能为力，只能把人送去本应用的设置页（ON_RESUME 会把
            // 结果捞回来，授权后无需重进页面）。
            CameraPermissionState.DENIED_PERMANENT -> CameraDeniedPane(
                message = stringResource(R.string.auth_qr_camera_denied_permanent),
                actionLabel = stringResource(R.string.common_open_system_settings),
                onAction = { CameraPermission.openSystemSettings(context) }
            )
        }

        Text(
            text = stringResource(R.string.auth_qr_scan_hint),
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        )
    }
}

/** 相机权限缺失时的引导面板：一句说明 + 一个动作按钮（重新申请 / 去系统设置）。 */
@Composable
private fun ColumnScope.CameraDeniedPane(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val colors = jetdoColors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 32.dp)
    ) {
        Text(
            text = message,
            fontSize = 14.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Box(
            modifier = Modifier
                .padding(top = 18.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(colors.ldAccent)
                .clickable(onClick = onAction)
                .padding(horizontal = 28.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(actionLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
}

/** zxing BitMatrix → Bitmap（出示端画二维码用）。 */
internal fun qrBitmap(content: String, size: Int): Bitmap {
    val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
    val matrix = com.google.zxing.qrcode.QRCodeWriter()
        .encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) Color.Black.toArgb() else Color.White.toArgb()
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
