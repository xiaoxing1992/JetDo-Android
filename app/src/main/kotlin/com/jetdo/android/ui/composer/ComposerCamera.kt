package com.jetdo.android.ui.composer

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * composer 的「拍照」入口：`TakePicture` 契约 + 既有 FileProvider。
 *
 * 选型理由：走**系统相机 App** 而不是自建 CameraX 取景器 —— 预览、对焦、方向、
 * 折叠屏姿态适配全由系统相机自理（它的预览自然贴着铰链正确渲染），App 侧只需要
 * 拿回一张成图塞进既有图片上传链路（压缩/串行上传/插 markdown 全部复用）。
 * 代价是 App 内没有预览可谈，鸿蒙没有对应物（纯 Android 反超项）。
 *
 * 前置条件一览：
 * - **不要 CAMERA 权限**：`ACTION_IMAGE_CAPTURE` 由拥有权限的相机 App 执行，
 *   调用方声明权限反而会触发 Play 的权限审查（官方文档明确建议不加）。
 * - 成图落 `cacheDir/camera/`（file_paths.xml 的 `camera` 段），FileProvider
 *   换成 `content://` 交给相机写入 —— 拍完我们自己的上传管线再按 content:// 读回。
 * - 设备没有任何相机时返回 null，调用方据此藏掉入口（免得点开直接崩在
 *   `ActivityNotFoundException` 上）。
 *
 * @param onCaptured 拍摄成功，参数是成图的 `content://` Uri（上传管线经
 *   ContentResolver 读，与选择器拿到的 Uri 同构）。
 * @return 发起拍摄的回调；无相机设备为 null。
 */
@Composable
fun rememberCameraCapture(onCaptured: (Uri) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    if (!hasCamera) return null

    // TakePicture 的成图写入 launch 前给定的 uri —— 得先落一个目标文件再发起，
    // 回调里用 target 配对「这次拍摄写到了哪」。单次拍摄一个文件，拍完即清。
    var target by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = target
        target = null
        if (saved && uri != null) onCaptured(uri)
    }

    return {
        val file = File(File(context.cacheDir, "camera"), "${System.currentTimeMillis()}.jpg")
        file.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        target = uri
        launcher.launch(uri)
    }
}
