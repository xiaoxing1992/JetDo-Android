package com.arkdo.android.services.reading

import com.arkdo.android.services.challenge.ChallengeShortcutEventBus
import com.arkdo.android.services.network.ChallengeState
import com.arkdo.android.services.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 详情页阅读计时上报（对齐鸿蒙 services/reading/ReadingTracker.ets）。
 *
 * 行为要点逐条保留：
 * - 以 1s tick 累加在屏楼层时长；`NEXT_FLUSH_MS`(60s) 常规兜底或"看到新楼"攒批满
 *   `REPORT_BATCH_INTERVAL_MS`(5s) 触发合并上报一次；
 * - 上报 `POST /topics/timings` 成功 → 回调 [onRead] 把这些楼标已读（蓝点淡出）；
 * - 失败按 [ALLOWED_AJAX_FAILURES] 退避重试（0/405/429/5xx），不可重试则抹账 + 静默期；
 * - 撞盾（challenge）→ 批次退回队列挂起，过盾成功（[notifyChallengeResolved]）补发；
 * - 长时间没滚动（3 分钟）停止累加；单楼最大跟踪 6 分钟；
 * - 上报期间让对应楼蓝点"呼吸"（reporting 状态）。
 *
 * 与鸿蒙差异：鸿蒙 setInterval 驱动 tick，这里用协程 [scope] + delay；其余对照逐条实现。
 */
class ReadingTracker(private val scope: CoroutineScope) {

    private val tag = "[ArkDO][ReadingTracker]"

    companion object {
        const val TICK_INTERVAL_MS = 1000L
        const val NEXT_FLUSH_MS = 60 * 1000L
        const val PAUSE_UNLESS_SCROLLED_MS = 1000L * 60 * 3
        const val MAX_TRACKING_TIME_MS = 1000L * 60 * 6
        const val REPORT_BATCH_INTERVAL_MS = 5000L
        const val CHALLENGE_HOLD_MAX_MS = 45 * 1000L
        const val NON_RETRYABLE_HOLD_MS = 60 * 1000L
        const val MIN_REPORTING_VISIBLE_MS = 1400L

        /** 可退避重试的失败。0 = 请求没拿到响应（桥层超时/宿主未就绪）。 */
        val ALLOWED_AJAX_FAILURES = intArrayOf(0, 405, 429, 500, 501, 502, 503, 504)
        private val AJAX_FAILURE_DELAYS = longArrayOf(5000, 10000, 20000, 40000)
    }

    /** 上报函数：由详情页接入 TopicService.reportTimings。 */
    fun interface ReportTimings {
        suspend fun report(topicId: Long, timings: Map<Int, Int>, topicTime: Int): NetworkResult<String>
    }

    private var topicId: Long = 0L
    private var reportFn: ReportTimings? = null
    private var onRead: ((List<Int>) -> Unit)? = null
    private var onReporting: ((List<Int>, Boolean) -> Unit)? = null

    // 在屏楼号（按 postNumber）。
    private var onscreen: List<Int> = emptyList()
    private val timings = HashMap<Int, Long>()
    private val totalTimings = HashMap<Int, Long>()
    private val readPosts = HashSet<Int>()
    private var topicTime = 0L
    private var lastTick = 0L
    private var lastScrolled = 0L
    private var lastFlush = 0L
    private var hasFocus = true
    private var running = false
    private var paused = false
    private var inProgress = false
    private var deferSends = false
    private var blockSendingTill = 0L
    private var challengeHoldUntil = 0L
    private var lastReportAt = 0L
    private var ajaxFailures = 0
    private val seenBlinking = HashSet<Int>()

    // 合并中的批次：单个 topicId 只会同时存在一条未发出的账。
    private data class Consolidated(
        val timings: MutableMap<Int, Long> = HashMap(),
        var topicTime: Long = 0L
    )

    private var consolidated: Consolidated? = null

    private var tickJob: Job? = null

    // 收尾 dispatch 用的延迟上报 job（鸿蒙 setTimeout 的等价物）。
    private var pendingVisualJob: Job? = null

    fun start(
        topicId: Long,
        reportFn: ReportTimings,
        onRead: (List<Int>) -> Unit,
        onReporting: ((List<Int>, Boolean) -> Unit)? = null
    ) {
        if (running) stop()
        reset()
        this.topicId = topicId
        this.reportFn = reportFn
        this.onRead = onRead
        this.onReporting = onReporting
        val now = System.currentTimeMillis()
        lastTick = now
        lastScrolled = now
        lastReportAt = now - REPORT_BATCH_INTERVAL_MS
        running = true
        paused = false
        startInterval()
        Timber.i("$tag start topicId=$topicId")
    }

    /** 离开页面：停表、结算最后一次计时、把已攒批次入队。返回之前是否在运行。 */
    fun stopTiming(): Boolean {
        if (!running) return false
        running = false
        paused = false
        deferSends = true
        tick()
        tickJob?.cancel()
        tickJob = null
        flush()
        return true
    }

    /** 纯网络收尾：把队列里剩余批次强制发出。必须在 [stopTiming] 返回 true 后调用。 */
    fun flushPending() {
        deferSends = false
        drainRemaining()
        reset()
    }

    fun stop() {
        if (!stopTiming()) return
        flushPending()
    }

    fun pause() {
        if (!running || paused) return
        tick()
        flush()
        tickJob?.cancel()
        tickJob = null
        paused = true
        Timber.i("$tag pause topicId=$topicId")
    }

    fun resume() {
        if (!running || !paused) return
        val now = System.currentTimeMillis()
        lastTick = now
        lastScrolled = now
        paused = false
        startInterval()
        Timber.i("$tag resume topicId=$topicId")
    }

    fun setOnscreen(postNumbers: List<Int>) {
        onscreen = postNumbers
    }

    fun setFocus(hasFocus: Boolean) {
        this.hasFocus = hasFocus
        if (hasFocus) resume() else pause()
    }

    fun scrolled() {
        lastScrolled = System.currentTimeMillis()
    }

    /** 预置已读楼（服务端已带 read=true 的、以及页面上已确认读过的）。 */
    fun seedRead(postNumbers: List<Int>) {
        for (postNumber in postNumbers) {
            readPosts.add(postNumber)
            totalTimings.putIfAbsent(postNumber, 0L)
        }
    }

    /** 过盾成功：解除撞盾挂起并立刻补发积压批次。 */
    fun notifyChallengeResolved() {
        val wasHeld = challengeHoldUntil > 0L
        challengeHoldUntil = 0L
        if (!running || deferSends) return
        Timber.i("$tag challenge resolved topicId=$topicId wasHeld=$wasHeld hasPending=${consolidated?.timings?.isNotEmpty() == true}")
        flush()
        sendNextConsolidatedTiming()
    }

    private fun reset() {
        timings.clear()
        totalTimings.clear()
        readPosts.clear()
        onscreen = emptyList()
        topicTime = 0L
        lastTick = System.currentTimeMillis()
        lastScrolled = lastTick
        lastFlush = 0L
        inProgress = false
        consolidated = null
        ajaxFailures = 0
        blockSendingTill = 0L
        challengeHoldUntil = 0L
        lastReportAt = System.currentTimeMillis()
        seenBlinking.clear()
        deferSends = false
        pendingVisualJob?.cancel()
        pendingVisualJob = null
    }

    private fun startInterval() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                tick()
            }
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()

        // 长时间没滚动就停止计时（防挂机误读），但先给积压批次一次发送机会。
        if (now - lastScrolled > PAUSE_UNLESS_SCROLLED_MS) {
            if (!inProgress) sendNextConsolidatedTiming()
            return
        }

        val diff = now - lastTick
        lastFlush += diff
        lastTick = now

        var rush = false
        for ((postNumber, timing) in timings) {
            val alreadyTotalled = totalTimings[postNumber] ?: 0L
            if (timing > 0 && alreadyTotalled == 0L && postNumber !in readPosts) {
                rush = true
            }
        }

        val batchDue = (now - lastReportAt) >= REPORT_BATCH_INTERVAL_MS
        if (!inProgress && (lastFlush > NEXT_FLUSH_MS || (rush && batchDue))) {
            flush()
        }
        if (!inProgress) {
            sendNextConsolidatedTiming()
        }

        if (hasFocus) {
            topicTime += diff
            val newlySeen = mutableListOf<Int>()
            for (postNumber in onscreen) {
                val current = timings[postNumber] ?: 0L
                timings[postNumber] = current + diff
                if (postNumber !in readPosts && postNumber !in seenBlinking) {
                    seenBlinking.add(postNumber)
                    newlySeen.add(postNumber)
                }
            }
            if (newlySeen.isNotEmpty()) {
                setReporting(newlySeen, true)
            }
        }
    }

    private fun flush() {
        val newTimings = HashMap<Int, Long>()
        for ((postNumber, time) in timings) {
            val total = totalTimings[postNumber] ?: 0L
            // 已确认已读的不再重复上报。
            if (time > 0 && total < MAX_TRACKING_TIME_MS && postNumber !in readPosts) {
                totalTimings[postNumber] = total + time
                newTimings[postNumber] = time
            }
            timings[postNumber] = 0L
        }

        var highestSeen = 0
        for (postNumber in newTimings.keys) {
            if (postNumber > highestSeen) highestSeen = postNumber
        }

        if (highestSeen > 0) {
            lastReportAt = System.currentTimeMillis()
            consolidate(newTimings)
            sendNextConsolidatedTiming()
            topicTime = 0L
        }
        lastFlush = 0L
    }

    private fun consolidate(timings: Map<Int, Long>) {
        val batch = consolidated ?: Consolidated().also { consolidated = it }
        for ((postNumber, ms) in timings) {
            batch.timings[postNumber] = (batch.timings[postNumber] ?: 0L) + ms
        }
        batch.topicTime += topicTime
    }

    private fun sendNextConsolidatedTiming() {
        val batch = consolidated ?: return
        if (batch.timings.isEmpty() || inProgress || reportFn == null) return
        if (deferSends) return
        if (challengeHoldUntil > System.currentTimeMillis()) return
        if (blockSendingTill > System.currentTimeMillis()) return

        consolidated = null
        val postNumbers = batch.timings.keys.toList()
        inProgress = true
        setReporting(postNumbers, true)
        val startedAt = System.currentTimeMillis()

        val timingPayload = batch.timings.mapValues { (_, ms) -> ms.toInt() }
        val topicTimePayload = batch.topicTime.toInt()

        scope.launch {
            val result = try {
                reportFn?.report(topicId, timingPayload, topicTimePayload)
                    ?: NetworkResult(success = false, statusCode = 0, challengeState = ChallengeState.NONE, error = null)
            } catch (e: Exception) {
                Timber.e(e, "$tag report threw")
                NetworkResult(success = false, statusCode = 0, challengeState = ChallengeState.NONE, error = null)
            }
            inProgress = false
            lastFlush = 0L
            afterMinReporting(startedAt) {
                setReporting(postNumbers, false)
                if (result.success) {
                    ajaxFailures = 0
                    ChallengeShortcutEventBus.notifyCleared()
                    for (postNumber in postNumbers) readPosts.add(postNumber)
                    Timber.i("$tag report success topicId=$topicId posts=$postNumbers")
                    onRead?.invoke(postNumbers)
                } else {
                    Timber.i("$tag report failed topicId=$topicId status=${result.statusCode} challenge=${result.challengeState}")
                    if (result.challengeState != ChallengeState.NONE) {
                        ChallengeShortcutEventBus.notifyBlocked()
                        handleChallenge(timingPayload, topicTimePayload)
                    } else {
                        handleFailure(timingPayload, topicTimePayload, result.statusCode)
                    }
                }
            }
        }
    }

    private fun setReporting(postNumbers: List<Int>, reporting: Boolean) {
        if (postNumbers.isEmpty()) return
        onReporting?.invoke(postNumbers, reporting)
    }

    /** 保证"呼吸"至少可见 [MIN_REPORTING_VISIBLE_MS]（对齐鸿蒙 afterMinReporting）。 */
    private fun afterMinReporting(startedAt: Long, action: () -> Unit) {
        val wait = MIN_REPORTING_VISIBLE_MS - (System.currentTimeMillis() - startedAt)
        if (wait <= 0) {
            action()
            return
        }
        pendingVisualJob?.cancel()
        pendingVisualJob = scope.launch {
            delay(wait)
            action()
        }
    }

    /** 收尾时把剩余批次全部发出（忽略退避门闩），确保离页前的已读不丢。 */
    private fun drainRemaining() {
        val batch = consolidated ?: return
        if (batch.timings.isEmpty()) return
        consolidated = null
        val timingPayload = batch.timings.mapValues { (_, ms) -> ms.toInt() }
        val topicTimePayload = batch.topicTime.toInt()
        Timber.i("$tag drain remaining batch posts=${timingPayload.size} topicId=$topicId")
        scope.launch {
            try {
                reportFn?.report(topicId, timingPayload, topicTimePayload)
            } catch (e: Exception) {
                Timber.e(e, "$tag drain report threw")
            }
        }
    }

    /** 撞盾：退回队列并挂起发送，等过盾成功（notifyChallengeResolved）或超时再补发。 */
    private fun handleChallenge(timings: Map<Int, Int>, topicTime: Int) {
        val batch = consolidated ?: Consolidated().also { consolidated = it }
        for ((postNumber, ms) in timings) {
            batch.timings[postNumber] = (batch.timings[postNumber] ?: 0L) + ms
        }
        batch.topicTime += topicTime
        challengeHoldUntil = System.currentTimeMillis() + CHALLENGE_HOLD_MAX_MS
        Timber.i("$tag challenge detected, requeue + hold topicId=$topicId holdMs=$CHALLENGE_HOLD_MAX_MS")
    }

    private fun handleFailure(timings: Map<Int, Int>, topicTime: Int, statusCode: Int) {
        if (statusCode !in ALLOWED_AJAX_FAILURES) {
            Timber.i("$tag report dropped (non-retryable) topicId=$topicId status=$statusCode")
            // 抹账 + 压静默期（对齐鸿蒙 forgetTotals + blockSendingTill）。
            for (postNumber in timings.keys) totalTimings.remove(postNumber)
            blockSendingTill = System.currentTimeMillis() + NON_RETRYABLE_HOLD_MS
            return
        }
        val delay = AJAX_FAILURE_DELAYS.getOrNull(ajaxFailures)
        ajaxFailures += 1
        if (delay != null) {
            blockSendingTill = System.currentTimeMillis() + delay
            // 退避重试：把本批并回队列。
            val batch = consolidated ?: Consolidated().also { consolidated = it }
            for ((postNumber, ms) in timings) {
                batch.timings[postNumber] = (batch.timings[postNumber] ?: 0L) + ms
            }
            batch.topicTime += topicTime
            Timber.i("$tag report retry scheduled topicId=$topicId status=$statusCode attempt=$ajaxFailures delayMs=$delay")
        } else {
            Timber.i("$tag report retry exhausted topicId=$topicId attempts=$ajaxFailures")
        }
    }
}