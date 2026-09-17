package com.arkdo.android.services.topic

import com.arkdo.android.data.models.Poll
import com.arkdo.android.data.models.PollOption
import com.arkdo.android.data.models.PollVoteResult

/**
 * 投票的本地乐观更新逻辑,1:1 对齐鸿蒙 `services/topic/PollMutation.ets`。
 *
 * 独立成纯逻辑便于单测:UI 层点击时先同步改本地状态(必定重渲染),
 * 请求回来再用服务端真值校正(吸收他人并发投票带来的偏差),失败则回滚。
 *
 * 全部函数**返回新对象**,不原地修改 —— 鸿蒙靠 `@Observed`/`@ObjectLink` 精准重渲染,
 * Compose 靠 `State` 的新旧值比对,原地改同一实例不会被感知(这是本仓库踩过的坑:
 * boost 曾因 UiState 引用了同一个 mutable list 而「退出重进才刷新」)。
 */
object PollMutation {

  /** 乐观更新前的快照,用于失败回滚。 */
  data class Snapshot(
    val options: List<PollOption>,
    val voters: Int,
    val chosenOptionIds: List<String>,
    val canVote: Boolean
  )

  fun snapshot(poll: Poll): Snapshot = Snapshot(
    options = poll.options.toList(),
    voters = poll.voters,
    chosenOptionIds = poll.chosenOptionIds.toList(),
    canVote = poll.canVote
  )

  fun restore(poll: Poll, snap: Snapshot): Poll = poll.copy(
    options = snap.options.toList(),
    voters = snap.voters,
    chosenOptionIds = snap.chosenOptionIds.toList(),
    canVote = snap.canVote
  )

  /**
   * 乐观投票:按新旧已投项差异增减各选项票数;
   * 从未投→已投时投票人数 +1,从已投→未投时 -1。
   */
  fun applyVote(poll: Poll, optionIds: List<String>): Poll {
    val previous = poll.chosenOptionIds
    val wasEmpty = previous.isEmpty()
    var options = poll.options.toList()

    // 取消不再选中的旧项。
    previous.filterNot { it in optionIds }.forEach { options = addVotes(options, it, -1) }
    // 新增本次选中的新项。
    optionIds.filterNot { it in previous }.forEach { options = addVotes(options, it, 1) }

    val nextVoters = when {
      wasEmpty && optionIds.isNotEmpty() -> poll.voters + 1
      !wasEmpty && optionIds.isEmpty() -> maxOf(0, poll.voters - 1)
      else -> poll.voters
    }
    return poll.copy(
      options = options,
      chosenOptionIds = optionIds.toList(),
      voters = nextVoters,
      canVote = optionIds.isEmpty()
    )
  }

  /** 乐观撤销:已投各项票数 -1、投票人数 -1、清空已投项。 */
  fun applyRemove(poll: Poll): Poll {
    val previous = poll.chosenOptionIds
    if (previous.isEmpty()) return poll
    var options = poll.options.toList()
    previous.forEach { options = addVotes(options, it, -1) }
    return poll.copy(
      options = options,
      chosenOptionIds = emptyList(),
      voters = maxOf(0, poll.voters - 1),
      canVote = true
    )
  }

  /**
   * 用服务端返回的真实值校正:按选项 id 覆盖票数,覆盖投票人数与已投项。
   * 服务端多出来的选项(罕见)一并并入,已有选项保留本地 label(服务端 html 未剥标签)。
   */
  fun applyServerResult(poll: Poll, result: PollVoteResult): Poll {
    val merged = poll.options.toMutableList()
    result.options.forEach { serverOption ->
      val index = merged.indexOfFirst { it.id == serverOption.id }
      if (index >= 0) {
        merged[index] = merged[index].copy(votes = serverOption.votes)
      } else {
        merged.add(serverOption)
      }
    }
    return poll.copy(
      options = merged.toList(),
      voters = result.voters,
      chosenOptionIds = result.chosenOptionIds.toList(),
      canVote = result.chosenOptionIds.isEmpty()
    )
  }

  private fun addVotes(options: List<PollOption>, optionId: String, delta: Int): List<PollOption> {
    val index = options.indexOfFirst { it.id == optionId }
    if (index < 0) return options
    val next = options.toMutableList()
    next[index] = next[index].copy(votes = maxOf(0, next[index].votes + delta))
    return next.toList()
  }

  /** 总票数,驱动百分比。 */
  fun totalVotes(poll: Poll): Int = poll.options.sumOf { it.votes }

  /** 某选项占比(四舍五入到整数),总票数为 0 时返回 0。 */
  fun percentOf(poll: Poll, option: PollOption): Int {
    val total = totalVotes(poll)
    if (total <= 0) return 0
    return Math.round(option.votes.toFloat() / total * 100f)
  }
}