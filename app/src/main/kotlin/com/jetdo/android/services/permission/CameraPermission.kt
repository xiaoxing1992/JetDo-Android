package com.jetdo.android.services.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

// ==========================================================================
// 相机权限（CAMERA）—— 扫码登录（扫入端）用。
//
// 这个权限踩过的坑比 POST_NOTIFICATIONS 更隐蔽：Manifest 里**根本没声明**。
// 未声明的权限调 requestPermission 不会报错、不会弹框，系统直接按「拒绝」回调 ——
// 表现就是「进了扫码页什么都没发生，相机也不亮」。所以声明与申请缺一不可。
//
// 三态与通知权限同构（见 NotificationPermission）：
//   GRANTED           相机可用
//   DENIED_CAN_ASK    还没问过，或用户拒绝过但未勾「不再询问」→ 可以继续弹框
//   DENIED_PERMANENT  系统不会再弹框 → App 侧无能为力，只能引导去系统设置
//
// shouldShowRequestPermissionRationale 在「从未请求过」和「已永久拒绝」两种情况下
// **都返回 false**，而两者应对方式相反 —— 所以必须自己持久化 hasRequested 才能区分。
// ==========================================================================

/** 相机权限的三态。 */
enum class CameraPermissionState {
    /** 已授权，可以开预览。 */
    GRANTED,

    /** 未授权，但系统还会弹请求框（首次进入，或只拒绝过一次）。 */
    DENIED_CAN_ASK,

    /** 未授权且系统不再弹框，只能引导用户去系统设置。 */
    DENIED_PERMANENT,
}

/**
 * 三态判定。
 *
 * 抽成纯函数是为了能在 JVM 单测里把四条路径一次跑完 —— 「从未请求」「拒绝一次」
 * 「拒绝两次（不再询问）」这三种设备状态在真机上凑齐很麻烦，而它们恰恰是最容易写错的分支。
 *
 * @param granted         当前权限位是否已授予
 * @param hasRequested    本应用是否弹出过一次系统请求框（自己持久化的标记）
 * @param canRequestAgain 系统是否还会弹框（shouldShowRequestPermissionRationale 的反面）
 */
internal fun resolveCameraPermissionState(
    granted: Boolean,
    hasRequested: Boolean,
    canRequestAgain: Boolean,
): CameraPermissionState = when {
    granted -> CameraPermissionState.GRANTED

    // 从未请求过 → 系统一定会弹框。这一条必须排在 canRequestAgain 之前：
    // 首次进入时 shouldShowRequestPermissionRationale 也是 false，
    // 少了这一条就会把「还没问过」误判成「永久拒绝」，直接跳系统设置，用户永远等不到弹框。
    !hasRequested -> CameraPermissionState.DENIED_CAN_ASK

    canRequestAgain -> CameraPermissionState.DENIED_CAN_ASK

    else -> CameraPermissionState.DENIED_PERMANENT
}

object CameraPermission {

    private const val REQUEST_PREFS = "jetdo_camera_permission"
    private const val KEY_HAS_REQUESTED = "hasRequested"

    private fun prefs(context: Context) =
        context.getSharedPreferences(REQUEST_PREFS, Context.MODE_PRIVATE)

    /** 权限位是否已授予。只用于判断「还需不需要走请求流程」，不用于判断相机能否工作。 */
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 系统是否还会弹出请求框。
     *
     * 需要 Activity 是因为 shouldShowRequestPermissionRationale 是它的方法。
     */
    fun canRequestAgain(context: Context, activity: Activity): Boolean {
        if (!prefs(context).getBoolean(KEY_HAS_REQUESTED, false)) return true
        return activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }

    /** 在调用 requestPermission 启动器**之前**调用，否则下一次 canRequestAgain 会误判。 */
    fun markRequested(context: Context) {
        prefs(context).edit().putBoolean(KEY_HAS_REQUESTED, true).apply()
    }

    /**
     * 判定当前状态。
     *
     * @param activity 取不到时（理论上不该发生）回落到「还能再问」，宁可多弹一次也不要
     *        把用户直接推去系统设置。
     */
    fun state(context: Context, activity: Activity?): CameraPermissionState =
        resolveCameraPermissionState(
            granted = isGranted(context),
            hasRequested = prefs(context).getBoolean(KEY_HAS_REQUESTED, false),
            canRequestAgain = activity?.let { canRequestAgain(context, it) } ?: true
        )

    /**
     * 跳到本应用的详情设置页。
     *
     * 带包名，否则会落到系统设置首页 —— 用户看到一堆不认识的条目通常就放弃了。
     */
    fun openSystemSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            // 从非 Activity 的 Context 调用时需要这个 flag。
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

/** LocalContext 在 Activity 宿主下可能是被包装过的 Context，逐层拆到 Activity。 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
