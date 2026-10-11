package com.jetdo.android.services.reading

import com.jetdo.android.services.challenge.ChallengeShortcutEventBus
import com.jetdo.android.services.network.ChallengeState
import com.jetdo.android.services.network.NetworkResult
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

    private val tag = "[JetDo][ReadingTracker]"

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

    /**
     * 收尾用的延迟任务登记表：Job → 收尾动作（鸿蒙 pendingVisualTimers 的等价物）。
     *
     * 必须用集合而不是单槽位：两批上报可以前后脚返回（一批秒回、一批慢一点），
     * 单槽位下后一批的取消会把前一批还没到点的「清呼吸 + onRead」一起掐掉 ——
     * 那批楼的蓝点于是永远不再淡出。
     *
     * 存下 action 而不是只存 Job，是为了在 [reset] 里能**补跑**：上报成功那一刻
     * `readPosts` 已经写死（不会再发第二次），如果收尾被丢掉，这批楼的蓝点就永久
     * 停在未读 —— 这是"漏一次就永久错"的语义，宁可立刻补跑，也不可静默丢弃。
     */
    private val pendingVisualJobs = mutableMapOf<Job, () -> Unit>()

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

    /**
     * 已确认已读的楼号快照（服务端已读 + 本页上报成功的）。
     *
     * 供详情页在换窗整体替换楼层时把本地已读态接回新对象上：服务端未必已经记下这些楼
     * （上报可能还在攒批窗口里或正在重试），纯用响应里的 read 会让蓝点当场复现，
     * 而此处仍认为它们已读、不会再上报 —— 蓝点就永久卡住了。
     */
    fun confirmedReadPostNumbers(): Set<Int> = readPosts.toSet()

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
        // 未到点的视觉收尾不能直接丢掉：那批楼的上报已经成功、readPosts 也已记它们已读
        // （不会再有第二次上报），丢掉收尾 = 它们的蓝点永久停在未读。
        // 先取消定时，再把收尾立即跑一遍（setReporting(false) 与 onRead 都是幂等的）。
        //
        // 补跑不会误标别的帖子：本方法只在 start()（同页每实例仅一次，此时登记表必空）
        // 与 flushPending()（离页，UI 已销毁）两处被调用。
        val pendings = pendingVisualJobs.values.toList()
        pendingVisualJobs.keys.forEach { it.cancel() }
        pendingVisualJobs.clear()
        pendings.forEach { it() }
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
            Timber.i(
                "$tag flush batch topicId=$topicId n=${newTimings.size} posts=${newTimings.keys.sorted().take(10)}"
            )
            consolidate(newTimings)
            sendNextConsolidatedTiming()
            topicTime = 0L
        } else {
            // 走到这里说明"在屏楼一个都没计到时间"——最常见的原因是 onscreen 一直为空
            // （页面没把可见楼喂进来），或长时间没滚动已被暂停分支拦住。
            Timber.i(
                "$tag flush produced nothing topicId=$topicId onscreen=${onscreen.size} tracked=${timings.size}"
            )
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
        Timber.i("$tag sending topicId=$topicId n=${postNumbers.size} posts=${postNumbers.sorted().take(10)}")
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
            // 网络状态机同步推进：成功即记进 readPosts。这一行**不能**放进下面的延迟回调
            // （对齐鸿蒙：readPosts.add 写在 then 的 success 分支里，只有 setReporting(false)
            // 与 onRead 走 afterMinReporting）。放进延迟回调的话，job 一旦被 reset 或后续批次
            // 顶掉，这批楼的已读记忆就丢了 —— 它们会重新走一遍计时上报，蓝点白闪一圈。
            if (result.success) {
                ajaxFailures = 0
                ChallengeShortcutEventBus.notifyCleared()
                for (postNumber in postNumbers) readPosts.add(postNumber)
                Timber.i("$tag report success topicId=$topicId posts=$postNumbers")
            } else {
                Timber.i("$tag report failed topicId=$topicId status=${result.statusCode} challenge=${result.challengeState}")
                if (result.challengeState != ChallengeState.NONE) {
                    ChallengeShortcutEventBus.notifyBlocked()
                    handleChallenge(timingPayload, topicTimePayload)
                } else {
                    handleFailure(timingPayload, topicTimePayload, result.statusCode)
                }
            }
            // 只有视觉收尾延后：保证"呼吸"至少完整跑一轮再收（对齐鸿蒙 afterMinReporting）。
            afterMinReporting(startedAt) {
                setReporting(postNumbers, false)
                if (result.success) {
                    onRead?.invoke(postNumbers)
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
        var executed = false
        val job = scope.launch {
            delay(wait)
            executed = true
            action()
        }
        pendingVisualJobs[job] = action
        // 正常跑完就摘登记；被 reset 取消时不在这里补跑（由 reset 统一补，避免跑两遍）。
        job.invokeOnCompletion {
            if (executed) pendingVisualJobs.remove(job)
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