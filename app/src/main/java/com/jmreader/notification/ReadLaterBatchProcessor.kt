package com.jmreader.notification

import android.content.Context
import com.jmreader.core.CrashHandler
import com.jmreader.core.Logger
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.repository.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.regex.Pattern

/**
 * v27.15.1 批量处理通知输入的 JM 号。
 *
 * v27.15.2 重要调整：
 * - 所有反馈都通过 [ReadLaterForegroundService.setFeedback] 而非直接更新通知。
 *   原因：直接调 NotificationHelper.update 后，Service 的 ensureForeground 会用默认文本覆盖；
 *   而 setFeedback 会先把反馈存入 Service 的 [currentFeedback] 状态，再 ensureForeground，
 *   这样无论是 START_STICKY 重启、新 intent、还是反馈自动清除，都读到一致的文本。
 * - 失败反馈延长自动清除时间（10s），成功反馈 5s 自动清除。
 *
 * 设计：
 * - 由 [ReadLaterForegroundService] 调用，在独立协程里跑（ForegroundService 不受 10s 限制）
 * - 即时反馈：解析完立即显示"正在拉取 N 个..."
 * - 并发限制：Semaphore(3) 避免 jm365 通道限流
 * - 单个失败不影响其他：supervisorScope
 * - 失败原因细化：超时/网络错误/ID 不存在/写入失败
 */
object ReadLaterBatchProcessor {

    /** JM 号校验：字母数字组合，1-30 字符。 */
    private val idPattern: Pattern = Pattern.compile("^[A-Za-z0-9]{1,30}$")

    /** 单个 ID 拉取详情的超时。jm365 重定向 + AES 解密有时较慢，给足 15s。 */
    private const val singleTimeoutMs = 15_000L

    /**
     * 并发拉取上限。
     * v27.15.2：从 3 降到 2，jm365 通道对同 IP 高频请求限流明显，
     * 3 并发时部分请求会超时（用户反馈"同时添加多个有问题"）。
     */
    private const val maxConcurrency = 2

    /**
     * v27.15.2：单次拉取失败后的重试次数。
     * jm365 通道对同 IP 高频请求会偶发返回 429/超时，重试 1 次（间隔 800ms）能救回大部分。
     * 不重试 3 次：太慢，且如果是真限流，多次重试只会加剧。
     */
    private const val maxRetry = 1
    private const val retryDelayMs = 800L

    /** Service scope：与 Service 生命周期绑定，独立于 BroadcastReceiver 的 goAsync 10s 限制。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashHandler.coroutineHandler)

    /**
     * v27.15.2 自检修复：串行化批量处理。
     * 之前两次输入并发跑两个 processBatch，交替调用 setFeedback 导致通知文本闪烁混乱
     * （"正在拉取 3 个..." / "已添加 1 部" 交替出现），用户无法判断当前状态，
     * 也是"同时添加多个本子有问题"的反馈根源之一。现在批次排队顺序执行。
     */
    private val processMutex = Mutex()

    /**
     * v27.15.2 自检修复：取消所有进行中的批量任务。
     * 由 [ReadLaterForegroundService.onDestroy] 调用，切断协程对已销毁 Service 实例的持有
     * （之前协程闭包持有 Service 最长数十秒——单个 ID 15s 超时 × N 个）。
     */
    fun cancelAll() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
    }

    /**
     * 处理输入：解析 → 即时反馈 → 并发拉详情 → 写入 → 更新通知。
     * 不阻塞调用方（Service.onStartCommand），立即返回。
     *
     * v27.15.2：批量任务通过 [processMutex] 串行化，多批输入排队执行，
     * 反馈文本不会互相覆盖闪烁（见 [processMutex] 注释）。
     *
     * @param service 持有 [currentFeedback] 状态的 Service，所有反馈通过 [setFeedback] 更新
     */
    fun process(service: ReadLaterForegroundService, container: AppContainer, input: String) {
        val ids = parseIds(input)
        if (ids.isEmpty()) {
            service.setFeedback("输入无效：请用空格或逗号分隔 JM 号（仅字母数字）", autoClearMs = 8000L)
            return
        }

        // 即时反馈，不自动清除（等批量完成后由结果反馈覆盖）
        service.setFeedback("正在拉取 ${ids.size} 个本子详情...", autoClearMs = 0L)

        scope.launch {
            processMutex.withLock {
                try {
                    processBatch(service, container, ids)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.e("ReadLaterProcessor", "批量处理异常", e)
                    service.setFeedback("添加失败：${e.message ?: "未知错误"}", autoClearMs = 10_000L)
                }
            }
        }
    }

    private fun currentCount(container: AppContainer): Int = try {
        container.favoritesStore.readLaterCount()
    } catch (_: Throwable) { 0 }

    /**
     * 解析输入字符串为有序去重的 JM ID 列表。
     * 支持分隔符：空格 / 英文逗号 / 中文逗号 / 句号（中英） / 分号（中英） / 换行。
     */
    private fun parseIds(input: String): List<String> {
        val tokens = input.split(Regex("[\\s,，。.;;；、]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val seen = HashSet<String>(tokens.size)
        val out = ArrayList<String>(tokens.size)
        for (t in tokens) {
            if (!idPattern.matcher(t).matches()) continue
            if (seen.add(t)) out.add(t)
        }
        return out
    }

    /**
     * 批量处理：并发拉详情 → 顺序写入 → 更新通知。
     *
     * 使用 supervisorScope 让单个 async 失败不会取消其他。
     * 使用 Semaphore 限制并发避免触发 jm365 限流。
     */
    private suspend fun processBatch(
        service: ReadLaterForegroundService,
        container: AppContainer,
        ids: List<String>,
    ) {
        val results = HashMap<String, ComicBriefDto>(ids.size)
        val failures = HashMap<String, FailureReason>(ids.size)

        supervisorScope {
            val sem = Semaphore(maxConcurrency)
            ids.map { id ->
                async {
                    sem.withPermit {
                        val outcome = fetchOne(container, id)
                        synchronized(results) {
                            when (outcome) {
                                is FetchOutcome.Success -> results[id] = outcome.dto
                                is FetchOutcome.Failure -> failures[id] = outcome.reason
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        // 顺序写入稍后再看，单个失败不影响其他
        val added = ArrayList<ComicBriefDto>(results.size)
        var firstAddedTitle: String? = null
        for (id in ids) {
            val dto = results[id] ?: continue
            try {
                container.favoritesStore.addReadLater(dto)
                added.add(dto)
                if (firstAddedTitle == null) firstAddedTitle = dto.name
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w("ReadLaterProcessor", "写入稍后再看失败 id=$id: ${Logger.brief(e)}")
                failures[id] = FailureReason.WriteError(e.message ?: "写入失败")
            }
        }

        val total = currentCount(container)
        val (feedback, autoClearMs) = buildFeedback(ids.size, added.size, failures, firstAddedTitle, ids)
        // 成功反馈 5s 自动清除回默认；失败反馈 10s（让用户有时间看清原因）
        service.setFeedback(feedback, autoClearMs = autoClearMs)
    }

    /**
     * 拉取单个 ID 详情，区分失败原因。
     *
     * v27.15.2：增加重试机制。
     * jm365 通道对同 IP 高频请求会偶发返回错误/超时（用户反馈"批量添加有问题"），
     * 重试 [maxRetry] 次（间隔 [retryDelayMs]）能救回大部分偶发失败。
     * 只有连续失败才判定为真失败（超时/网络错误/不存在）。
     */
    private suspend fun fetchOne(
        container: AppContainer,
        id: String,
    ): FetchOutcome {
        var lastOutcome: FetchOutcome = FetchOutcome.Failure(FailureReason.NetworkError("未尝试"))
        repeat(maxRetry + 1) { attempt ->
            lastOutcome = fetchOnce(container, id)
            // 成功或确定不存在（404）则不重试
            if (lastOutcome is FetchOutcome.Success) return lastOutcome
            if (lastOutcome is FetchOutcome.Failure && lastOutcome.reason is FailureReason.NotFound) return lastOutcome
            // 超时/网络错误：如果是最后一次尝试就返回，否则延迟后重试
            if (attempt < maxRetry) {
                kotlinx.coroutines.delay(retryDelayMs)
            }
        }
        return lastOutcome
    }

    /** 单次拉取，不重试。 */
    private suspend fun fetchOnce(
        container: AppContainer,
        id: String,
    ): FetchOutcome {
        return try {
            val detail = withTimeoutOrNull(singleTimeoutMs) {
                when (val r = container.repository.comicDetail(id)) {
                    is Resource.Success -> r.data
                    is Resource.Error -> throw FetchException(r.message ?: "拉取失败", isNotFound = false)
                    else -> throw FetchException("未知响应", isNotFound = false)
                }
            } ?: return FetchOutcome.Failure(FailureReason.Timeout)

            if (detail.id.isBlank() && detail.name.isBlank()) {
                return FetchOutcome.Failure(FailureReason.NotFound)
            }
            FetchOutcome.Success(
                ComicBriefDto(
                    id = detail.id.ifBlank { id },
                    name = detail.name.ifBlank { "JM$id" },
                    author = detail.author,
                    tags = detail.tags,
                    cover = detail.cover,
                    likes = detail.likes,
                    views = detail.views,
                    publishTime = detail.publishTime,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: FetchException) {
            FetchOutcome.Failure(if (e.isNotFound) FailureReason.NotFound else FailureReason.NetworkError(e.message ?: "拉取失败"))
        } catch (e: Throwable) {
            Logger.w("ReadLaterProcessor", "拉取详情异常 id=$id: ${Logger.brief(e)}")
            FetchOutcome.Failure(FailureReason.NetworkError(e.message ?: "网络错误"))
        }
    }

    /**
     * 构建通知反馈文本，细化失败原因。
     * @return (反馈文本, 自动清除毫秒) —— 成功用 5s，失败用 10s 让用户看清原因
     */
    private fun buildFeedback(
        total: Int,
        added: Int,
        failures: Map<String, FailureReason>,
        firstTitle: String?,
        ids: List<String>,
    ): Pair<String, Long> {
        if (added == total) {
            // 全部成功
            val text = when {
                total == 1 -> "已添加：${firstTitle ?: "JM${ids.first()}"}"
                else -> "已添加 $added 部到稍后再看"
            }
            return text to 5_000L
        }
        if (added == 0) {
            // 全部失败
            val timeoutCount = failures.values.count { it is FailureReason.Timeout }
            val notFoundCount = failures.values.count { it is FailureReason.NotFound }
            val networkErrorCount = failures.values.count { it is FailureReason.NetworkError }
            val writeErrorCount = failures.values.count { it is FailureReason.WriteError }
            val parts = mutableListOf<String>()
            if (timeoutCount > 0) parts.add("$timeoutCount 个超时")
            if (notFoundCount > 0) parts.add("$notFoundCount 个不存在")
            if (networkErrorCount > 0) parts.add("$networkErrorCount 个网络错误")
            if (writeErrorCount > 0) parts.add("$writeErrorCount 个写入失败")
            val reasonText = if (parts.isEmpty()) "未知原因" else parts.joinToString("、")
            return "全部 $total 个失败：$reasonText" to 10_000L
        }
        // 部分成功
        val failedIds = ids.filter { failures.containsKey(it) }
        val text = "已添加 $added/$total 部，失败 ${failedIds.size} 个：${failedIds.take(3).joinToString(" ")}${if (failedIds.size > 3) "..." else ""}"
        return text to 10_000L
    }

    private sealed class FetchOutcome {
        data class Success(val dto: ComicBriefDto) : FetchOutcome()
        data class Failure(val reason: FailureReason) : FetchOutcome()
    }

    private sealed class FailureReason {
        object Timeout : FailureReason()
        object NotFound : FailureReason()
        data class NetworkError(val msg: String) : FailureReason()
        data class WriteError(val msg: String) : FailureReason()
    }

    private class FetchException(message: String, val isNotFound: Boolean) : Exception(message)
}
