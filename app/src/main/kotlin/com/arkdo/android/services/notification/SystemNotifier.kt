package com.arkdo.android.services.notification

import android.annotation.SuppressLint
import com.arkdo.android.core.data.AppContextHolder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.arkdo.android.R
import timber.log.Timber

// ==========================================================================
// 系统通知栏的出口。
//
// 现在只有一条「测试通知」——它的价值不在于功能，而在于**验证**：
// 权限位返回 granted 不等于通知真的能出现在通知栏（渠道被用户单独关掉、系统级勿扰、
// App 被限制后台等都会让通知静默消失）。唯一可信的验证方式是发一条真的看一眼。
// 所以本页的「发送测试通知」不是玩具，是权限能否工作的判据。
//
// 渠道（Channel）是 API 26 起的硬要求：没有渠道的通知会被系统直接丢弃，且**不报错**。
// 通知渠道一旦创建，其重要性就只能由用户在系统设置里改，App 侧再调 createNotificationChannel
// 也改不动 —— 所以这里只在缺失时创建，不做"更新"。
// ==========================================================================

object SystemNotifier {

    /** 默认渠道 id。P0-1 系统推送落地后按用途细分渠道时，这个保留给一般提醒。 */
    const val CHANNEL_ID = "arkdo_default"

    private const val LOG_TAG = "[JetDo][Notifier]"

    /** 固定 id：重复发送只覆盖同一条，不会在通知栏堆积一列测试通知。 */
    private const val TEST_NOTIFICATION_ID = 1000

    /** 创建通知渠道。幂等，在 Application.onCreate 调用一次即可。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // 已存在就返回：渠道的重要性/描述由用户主导，重复创建不会生效，只会掩盖真实状态。
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            AppContextHolder.getString(R.string.home_notifications),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = AppContextHolder.getString(R.string.home_push_summary)
        }
        manager.createNotificationChannel(channel)
        Timber.i("$LOG_TAG channel created id=%s", CHANNEL_ID)
    }

    /**
     * 发一条测试通知。
     *
     * @return 是否真的投递成功。false 时调用方应提示用户去系统设置检查 ——
     *         最可能的原因是权限或渠道被关掉了。
     */
    @SuppressLint("MissingPermission") // 前置 isEnabled 检查即权限校验，lint 看不到这层关联。
    fun notifyTest(context: Context): Boolean {
        if (!NotificationPermission.isEnabled(context)) {
            Timber.i("$LOG_TAG notifyTest skipped: notifications disabled")
            return false
        }
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(AppContextHolder.getString(R.string.notif_test))
            .setContentText(AppContextHolder.getString(R.string.notif_test_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(AppContextHolder.getString(R.string.notif_test_body))
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
        }
            .onSuccess { Timber.i("$LOG_TAG test notification posted") }
            .onFailure { Timber.i("$LOG_TAG test notification failed error=%s", it.message) }
            .isSuccess
    }
}
