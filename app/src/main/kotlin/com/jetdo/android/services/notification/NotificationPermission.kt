package com.jetdo.android.services.notification

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

// ==========================================================================
// 系统通知权限（POST_NOTIFICATIONS）。
//
// Manifest 里这行权限从项目建立起就声明着，但从未申请过 —— targetSdk 33 之后，
// 「声明了不申请」等于声明了个寂寞：系统只在被显式请求时才弹框，不请求就是永远关闭。
//
// 与鸿蒙的差别（同一个需求，两套权限模型，不能照搬）：
//   鸿蒙  PushNotificationPermissionService.ensureEnabled() 里 requestEnableNotification
//         可以反复弹系统授权框，用户拒绝后下次开推送还能再弹。
//   Android  POST_NOTIFICATIONS 只有两次机会：第一次拒绝后仍可再申请一次，此后系统
//         不再弹框（shouldShowRequestPermissionRationale 转 false），只能引导去系统设置。
//         所以这里多出一个 DENIED_PERMANENT 态，鸿蒙侧不存在对应物。
//
// 「通知能不能显示」的唯一真源用 NotificationManagerCompat.areNotificationsEnabled()，
// 而不是 checkSelfPermission：前者还能捕捉「权限已授予、但用户在系统设置里把通知整体关掉」
// 这种情况，后者在那种情况下依然返回 granted，UI 会显示已开启而实际收不到。
// checkSelfPermission 只在判断「还需不需要走请求流程」时用。
// ==========================================================================

/** API 33（Tiramisu）：POST_NOTIFICATIONS 成为运行时权限的分界线。 */
internal const val SDK_TIRAMISU = 33

/** 系统通知权限的三态。 */
enum class NotificationPermissionState {
    /** 通知可达。 */
    GRANTED,

    /** 未开启，但还能弹出系统请求框（首次进入，或用户拒绝过但未选「不再询问」）。 */
    DENIED_CAN_ASK,

    /** 未开启且系统不会再弹框。App 侧已无能为力，只能引导用户去系统设置。 */
    DENIED_PERMANENT,
}

/**
 * 三态判定。
 *
 * 抽成纯函数是因为它承载了**全部版本分支**，而分支判断恰恰是最容易写错、
 * 又最难在真机上一一复现的部分（需要凑齐「拒绝一次」「拒绝两次」「系统设置里关掉」
 * 「API 32 设备」四种设备状态）。纯函数让这四条路径在 JVM 单测里一次跑完。
 *
 * @param sdkInt          Build.VERSION.SDK_INT
 * @param enabled         通知当前是否可达（areNotificationsEnabled）
 * @param canRequestAgain 系统是否还会弹请求框（shouldShowRequestPermissionRationale 的反面）
 */
internal fun resolveNotificationPermissionState(
    sdkInt: Int,
    enabled: Boolean,
    canRequestAgain: Boolean,
): NotificationPermissionState = when {
    enabled -> NotificationPermissionState.GRANTED

    // API 33 以下没有 POST_NOTIFICATIONS 这个运行时权限，走到这里只可能是用户在系统设置里
    // 把本应用的通知关掉了。这种状态请求框弹不出来（也没有请求框可弹），只能去系统设置。
    sdkInt < SDK_TIRAMISU -> NotificationPermissionState.DENIED_PERMANENT

    canRequestAgain -> NotificationPermissionState.DENIED_CAN_ASK

    else -> NotificationPermissionState.DENIED_PERMANENT
}

object NotificationPermission {

    // 「是否已经弹出过一次系统请求框」的本地记录。
    //
    // 这个标记系统侧查不到，必须自己记：shouldShowRequestPermissionRationale 在
    // 「从未请求过」和「已永久拒绝」两种情况下**都返回 false**，而它们的应对方式完全相反
    // ——前者要继续弹框，后者只能引导去系统设置。少了这个标记，首次点开关就会被判成
    // 永久拒绝、直接跳系统设置，用户永远等不到那个弹框。
    private const val REQUEST_PREFS = "jetdo_notification_permission"
    private const val KEY_HAS_REQUESTED = "hasRequested"

    /** 通知当前是否真的可达。UI 的开关状态绑这个，而不是绑权限位。 */
    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 是否还需要走系统请求流程。
     *
     * API 33 以下不申请（申请了也没有对应权限位，会直接被系统判为拒绝）。
     */
    fun needsRequest(context: Context): Boolean =
        Build.VERSION.SDK_INT >= SDK_TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * 判定当前状态。
     *
     * @param canRequestAgain 由调用方从 Activity 侧取得（shouldShowRequestPermissionRationale
     *        取反）。这里不自己取是因为该方法需要 Activity，而本对象刻意只依赖 Context。
     */
    fun state(context: Context, canRequestAgain: Boolean): NotificationPermissionState =
        resolveNotificationPermissionState(
            sdkInt = Build.VERSION.SDK_INT,
            enabled = isEnabled(context),
            canRequestAgain = canRequestAgain
        )

    /**
     * 系统是否还会弹出请求框。
     *
     * 需要 Activity 是因为 shouldShowRequestPermissionRationale 是它的方法；本对象其余
     * 部分刻意只依赖 Context，好让状态机能在 JVM 上跑。
     */
    fun canRequestAgain(context: Context, activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < SDK_TIRAMISU) return false
        val requested = context
            .getSharedPreferences(REQUEST_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_REQUESTED, false)
        // 从未请求过 → 系统一定会弹框。
        if (!requested) return true
        // 请求过 → true 表示用户只拒绝过一次，还能再问。
        return activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 在调用 requestPermission 启动器**之前**调用，否则下一次 canRequestAgain 会误判。 */
    fun markRequested(context: Context) {
        context.getSharedPreferences(REQUEST_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_REQUESTED, true)
            .apply()
    }

    /**
     * 跳到本应用的通知设置。
     *
     * 优先用 ACTION_APP_NOTIFICATION_SETTINGS（API 26+ 直达通知频道页，用户少点一层）；
     * 低版本退回 ACTION_APPLICATION_DETAILS_SETTINGS。两者都要带包名，否则会落到
     * 系统设置的首页而不是本应用 —— 用户看到一堆不认识的条目，通常就放弃了。
     */
    fun openSystemSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        }
        // 从非 Activity 的 Context（Application）调用时需要这个 flag。
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
