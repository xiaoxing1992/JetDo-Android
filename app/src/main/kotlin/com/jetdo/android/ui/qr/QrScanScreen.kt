package com.jetdo.android.ui.qr

import android.Manifest
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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

/**
 * YUV_420_888 逐帧 → zxing 解码。节流 150ms/帧；命中后由 AtomicBoolean 停流。
 *
 * @param isTarget 解出的文本是否值得进登录流程。**必须在 `handled` 置位之前判**：
 *   这里是「不是本 App 的码就接着扫」的唯一实现点，放到回调里判就等于先把流停掉、
 *   再发现这码不是我们的，然后还得把它放回来（中间那几帧已经白丢）。
 */
private class QrAnalyzer(
    private val handled: AtomicBoolean,
    private val isTarget: (String) -> Boolean,
    private val onQr: (String) -> Unit
) : ImageAnalysis.Analyzer {

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
            // 统一逐行拷成「行距 == width」的紧凑平面：rowStride 是内存对齐后的值，可能大于 width。
            // 传给 zxing 的 dataWidth 必须是这个紧凑行距（= width），否则 getRow() 会按 rowStride
            // 定位，读越界或整帧错位。
            val data = ByteArray(width * height)
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(data, row * width, width)
            }
            // 不旋转。QR 码自带三个寻像图形，识别器自己定方向，帧转不转都解得开。
            // ⚠️ 千万别对 PlanarYUVLuminanceSource 调 rotateCounterClockwise()/rotateClockwise()：
            // zxing 3.5.3 里这个类**没有覆写**它们（javap 实证：类中既无 rotateCounterClockwise
            // 也无 isRotateSupported），调用直接落到基类默认实现，抛
            // UnsupportedOperationException("This luminance source does not support rotation by
            // 90 degrees.")。手机竖屏的 rotationDegrees 恒为 90/270，于是每一帧都炸、永远扫不出码
            // —— 扫码页「什么反应也没有」就是这个原因（logcat 曾以 150ms 一帧的节奏刷屏该异常）。
            val source: LuminanceSource = PlanarYUVLuminanceSource(
                data, width, height, 0, 0, width, height, false
            )
            val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            // 不是本 App 的登录码 → 静默忽略，下一帧接着扫。以前是把**任何**二维码都
            // 当登录码交给流程，扫到一张别人家的码就弹「二维码不是 JetDo 登录码」，
            // 而用户当下要的其实是「继续对准」。对齐 FluxDO 的
            // `if (parsePayload(raw) == null) return;`。
            if (!isTarget(text)) return
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
    // 扫码命中的 UI 态（纯状态机见 QrScanState.kt）。
    // 「命中后界面毫无变化」是这次要修的主诉：命中瞬间只停了相机的解码，
    // 预览照旧在动，而真正的反馈（Toast）要等整条兑换链路跑完才出现。
    var ui by remember { mutableStateOf(QrScanUi()) }
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

        // 内容区（相机 + 底部提示）整体包一层，好在其上盖「正在完成登录」遮罩。
        // 遮罩只盖内容区、**不盖顶栏**：兑换链路最长要等 45s，得给用户留一条随时能退出的路
        // （对齐 FluxDO 把遮罩放在 Scaffold body 而不是整屏）。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
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
                                            analysis.setAnalyzer(
                                                executor,
                                                QrAnalyzer(
                                                    handled = handled,
                                                    // 无关的码不进闸门，继续扫下一帧。
                                                    isTarget = { classifyScannedQr(it).shouldHandle() }
                                                ) { raw ->
                                                    val kind = classifyScannedQr(raw)
                                                    scope.launch {
                                                        // 先把遮罩打出来，再去跑网络。顺序反了就等于没有提示：
                                                        // 用户会盯着一个毫无变化的相机预览等几十秒。
                                                        ui = ui.reduce(QrScanAction.Detected)
                                                        val result = if (kind == ScannedQr.UNSUPPORTED) {
                                                            BrowserAuthResult.Failed(R.string.auth_qr_unsupported)
                                                        } else {
                                                            BrowserAuthLoginFlow.handleScannedPayload(context, raw)
                                                        }
                                                        BrowserAuthLoginFlow.toastResult(context, result)
                                                        if (result is BrowserAuthResult.Success) {
                                                            ui = ui.reduce(QrScanAction.Finished)
                                                            onNavigateBack()
                                                        } else {
                                                            // 失败必须回到可重扫态：撤遮罩 + 放开解码闸门，
                                                            // 只做一半的话用户就只能退出页面重进。
                                                            ui = ui.reduce(QrScanAction.Failed)
                                                            handled.set(false)
                                                        }
                                                    }
                                                }
                                            )
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
                            // 取景框：告诉用户该把码对在哪儿。纯视觉，不参与手势
                            // （不加 pointerInput，Compose 里没有手势修饰符就不会吃事件）。
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(240.dp)
                                        .border(
                                            width = 2.5.dp,
                                            color = Color.White.copy(alpha = 0.85f),
                                            shape = RoundedCornerShape(20.dp)
                                        )
                                )
                            }
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

            // 命中即遮罩（对齐 FluxDO QrLoginScanPage 的 `_handling || _statusMessage != null` 分支）。
            if (ui.showOverlay()) {
                QrScanOverlay(ui.statusRes)
            }
        }
    }
}

/**
 * 处理中遮罩：半透明压暗 + 居中卡片（转圈 + 状态文案）。
 *
 * 遮罩自己吃掉点击事件 —— 底下就是相机预览，命中期间再点一下不该有任何效果。
 */
@Composable
private fun QrScanOverlay(@StringRes statusRes: Int?) {
    val colors = jetdoColors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceCard)
                .padding(horizontal = 28.dp, vertical = 24.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = colors.ldAccent,
                strokeWidth = 3.dp
            )
            if (statusRes != null) {
                Text(
                    text = stringResource(statusRes),
                    fontSize = 14.sp,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
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
