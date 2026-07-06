package com.jmreader.data.download

import android.content.Context
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.dto.ComicDetailDto
import com.jmreader.data.repository.Resource
import com.jmreader.data.repository.proxiedImageUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }

data class DownloadTask(
    val comic: ComicBriefDto,
    val status: DownloadStatus,
    val progress: Int, // 0..100
    val totalChapters: Int,
    val doneChapters: Int,
    /** 失败的图片数（单图下载失败累计）。0=全部成功。 */
    val failedImages: Int = 0,
)

/**
 * 离线下载管理器。
 * 把整本漫画的每个章节图片下载到内部存储，离线阅读器优先读本地。
 *
 * 关键修复（曾经的问题）：
 * 1. **enqueue 去重清旧任务**：COMPLETED/FAILED 旧任务会被新任务顶替，
 *    避免 LazyColumn 同 key 崩溃。
 * 2. **remove 取消协程**：用 jobs Map 持有每本漫画的下载 Job，删除时 cancel，
 *    避免「假删除」（协程继续跑、写文件、占带宽）。
 * 3. **并发限制**：用 Semaphore(2) 限制同时下载的本数，避免 IP 封禁 + OOM。
 * 4. **单图失败计数**：runCatching 不再静默吞错，记录到 failedImages；
 *    失败比例 > 30% 时整本标 FAILED。
 * 5. **空章节处理**：chapters.isEmpty() 标 FAILED（而非假成功 COMPLETED）。
 * 6. **CancellationException 不吞**：协程取消正常传播，删除任务时能立即停止。
 */
class DownloadManager(
    private val context: Context,
    private val container: AppContainer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 并发下载数限制：同时只下 2 本，避免触发禁漫限流 + 避免内存峰值。 */
    private val concurrencyLimit = Semaphore(2)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** 每本漫画的下载 Job，remove 时 cancel。key=comicId。 */
    private val jobs = mutableMapOf<String, Job>()

    /**
     * 缓存每本漫画的详情，供 [retry] 在 DownloadsScreen 直接重试使用，
     * 避免界面需要重新请求详情接口。
     * key=comicId，value=enqueue 时传入的 ComicDetailDto。
     */
    private val detailCache = ConcurrentHashMap<String, ComicDetailDto>()

    private fun rootDir() = File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }

    fun chapterDir(comicId: String, chapterId: String): File =
        File(rootDir(), "$comicId/$chapterId").apply { if (!exists()) mkdirs() }

    /**
     * 记录每个章节的预期图片数，用于校验下载完整性。
     * key="$comicId/$chapterId"，value=预期图片张数。
     * 下载完成后写入；离线阅读时据此判断章节是否完整下载，
     * 避免半成品目录（中断/异常）被误认为已完成。
     */
    private val expectedCounts = mutableMapOf<String, Int>()

    /**
     * 章节是否已完整下载：不仅看目录非空，还要校验实际文件数 == 预期图片数。
     * 半成品目录（下载中断留下部分图片）不会被误认为已完成。
     */
    fun isChapterDownloaded(comicId: String, chapterId: String): Boolean {
        val dir = chapterDir(comicId, chapterId)
        val files = dir.listFiles() ?: return false
        if (files.isEmpty()) return false
        // 优先用记录的预期数校验；无记录时（旧版本下载的）只看非空，保持兼容
        val expected = expectedCounts["$comicId/$chapterId"]
        return expected == null || files.size >= expected
    }

    fun listLocalFiles(comicId: String, chapterId: String): List<File> {
        val dir = chapterDir(comicId, chapterId)
        val files = dir.listFiles() ?: return emptyList()
        // 仅在文件数齐全时返回，避免半成品目录被阅读器渲染成"只有几页的章节"
        val expected = expectedCounts["$comicId/$chapterId"]
        if (expected != null && files.size < expected) return emptyList()
        return files.sortedBy { it.nameWithoutExtension }
    }

    /**
     * 加入下载队列。
     *
     * 去重策略：
     * - 已有 QUEUED/DOWNLOADING 任务：直接返回（不重复下）
     * - 已有 COMPLETED/FAILED 任务：从列表移除旧任务（避免 LazyColumn 同 key 崩溃），
     *   然后追加新任务。这允许用户「重新下载」已失败/已完成的本子。
     * - 同步取消旧任务的协程（如有）。
     */
    fun enqueue(comic: ComicBriefDto, detail: ComicDetailDto) {
        // 原子检查 + 去重：已有排队/下载中任务则不重复
        val alreadyQueued = _tasks.value.any {
            it.comic.id == comic.id &&
                (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING)
        }
        if (alreadyQueued) return

        // 原子更新：移除旧任务 + 追加新任务，用 CAS 循环避免并发覆盖
        _tasks.update { list ->
            val filtered = list.filterNot { it.comic.id == comic.id }
            filtered + DownloadTask(comic, DownloadStatus.QUEUED, 0, detail.chapters.size, 0)
        }
        // 取消旧协程（若有）
        synchronized(jobs) { jobs.remove(comic.id)?.cancel() }
        // 缓存详情，供 retry 直接复用（DownloadsScreen 重试按钮无需重新拉详情）
        detailCache[comic.id] = detail

        val job = scope.launch { runDownload(comic.id, detail) }
        synchronized(jobs) { jobs[comic.id] = job }
    }

    private suspend fun runDownload(comicId: String, detail: ComicDetailDto) {
        // 限制并发：最多 2 本同时下载
        concurrencyLimit.withPermit {
            // 若任务已被 remove/cancel，直接返回
            if (Thread.currentThread().isInterrupted) return@withPermit
            // 任务已不在列表（被 remove 了）
            if (_tasks.value.none { it.comic.id == comicId }) return@withPermit

            val serverUrl = container.settingsStore.settings.first().serverUrl
            val useBackend = serverUrl.isNotBlank()
            val chapters = detail.chapters

            // 空章节：标 FAILED 而非假成功
            if (chapters.isEmpty()) {
                update(comicId) { it.copy(status = DownloadStatus.FAILED, progress = 0) }
                com.jmreader.core.Logger.w("Download", "下载失败：$comicId 无章节")
                return@withPermit
            }

            var done = 0
            var totalFailedImages = 0
            var totalImages = 0

            for (ch in chapters) {
                // 协程取消（remove 触发）时立即停止
                if (!isActive()) return@withPermit
                update(comicId) { it.copy(status = DownloadStatus.DOWNLOADING, doneChapters = done) }
                val res = try {
                    container.repository.chapterImages(ch.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    com.jmreader.core.Logger.w("Download", "章节图片接口失败: ${com.jmreader.core.Logger.brief(e)}")
                    update(comicId) { it.copy(status = DownloadStatus.FAILED) }
                    return@withPermit
                }
                when (res) {
                    is Resource.Success -> {
                        val images = res.data.images
                        val scrambleId = res.data.scramble_id?.toLongOrNull() ?: 0L
                        val dir = chapterDir(comicId, ch.id)
                        // 空图片章：跳过但不算失败
                        if (images.isEmpty()) {
                            com.jmreader.core.Logger.w("Download", "章节 ${ch.id} 无图片")
                            done++
                            update(comicId) {
                                it.copy(
                                    doneChapters = done,
                                    progress = (done * 100 / chapters.size.coerceAtLeast(1)),
                                )
                            }
                            continue
                        }
                        // 记录预期图片数，供 isChapterDownloaded/listLocalFiles 校验完整性
                        expectedCounts["$comicId/${ch.id}"] = images.size
                        totalImages += images.size
                        images.forEachIndexed { index, raw ->
                            if (!isActive()) return@withPermit
                            val file = File(dir, "%03d.jpg".format(index))
                            if (file.exists() && file.length() > 0) {
                                // 已存在且非空跳过，但仍推进进度条
                                val pct = ((done + (index + 1).toFloat() / images.size) / chapters.size * 100).toInt()
                                update(comicId) { it.copy(progress = pct) }
                                return@forEachIndexed
                            }
                            // 单图下载：失败计数而非吞掉
                            // 关键修复：用 .tmp 临时文件 + renameTo 原子落盘，
                            // 避免下载中断留下半成品文件被重试时误认为已完成
                            val tmp = File(dir, "%03d.jpg.tmp".format(index))
                            val ok = try {
                                if (useBackend) {
                                    downloadFile(proxiedImageUrl(serverUrl, raw), tmp)
                                } else {
                                    downloadAndDecode(raw, scrambleId, tmp)
                                }
                                // 下载成功后原子重命名到目标文件
                                if (tmp.exists() && tmp.length() > 0) {
                                    file.delete()
                                    if (!tmp.renameTo(file)) {
                                        // rename 失败：清理临时文件，标记本次失败
                                        tmp.delete()
                                        false
                                    } else {
                                        true
                                    }
                                } else {
                                    tmp.delete()
                                    false
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                com.jmreader.core.Logger.w("Download", "图片下载失败: ${com.jmreader.core.Logger.brief(e)}")
                                tmp.delete()
                                false
                            }
                            if (!ok) totalFailedImages++
                            val pct = ((done + (index + 1).toFloat() / images.size) / chapters.size * 100).toInt()
                            update(comicId) { it.copy(progress = pct, failedImages = totalFailedImages) }
                        }
                    }
                    else -> {
                        update(comicId) { it.copy(status = DownloadStatus.FAILED) }
                        return@withPermit
                    }
                }
                done++
                update(comicId) {
                    it.copy(
                        doneChapters = done,
                        progress = (done * 100 / chapters.size.coerceAtLeast(1)),
                    )
                }
            }

            // 失败比例 > 30% → 标 FAILED
            val failRate = if (totalImages > 0) totalFailedImages.toFloat() / totalImages else 0f
            val finalStatus = if (failRate > 0.3f) DownloadStatus.FAILED else DownloadStatus.COMPLETED
            update(comicId) {
                it.copy(
                    status = finalStatus,
                    progress = 100,
                    failedImages = totalFailedImages,
                )
            }
            com.jmreader.core.Logger.i(
                "Download",
                "下载完成: $comicId status=$finalStatus failImg=$totalFailedImages/$totalImages",
            )
        }
    }

    /** 协程是否仍活跃（未被 cancel）。必须在 suspend 上下文中调用。 */
    private suspend fun isActive(): Boolean = kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

    private fun downloadFile(url: String, target: File) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("download ${resp.code}")
            // body 为 null 时抛异常，避免创建空文件被误认为下载成功
            val body = resp.body ?: error("空响应 body")
            target.outputStream().use { body.byteStream().copyTo(it) }
        }
    }

    /**
     * 直连模式：下载禁漫图片并解密分割图后落盘。
     * url 形如 https://cdn-msp.xxx/media/photos/{aid}/00047.webp?jm_sid=220980
     */
    private fun downloadAndDecode(url: String, scrambleId: Long, target: File) {
        // 去掉所有 query 参数（jm_sid、v 等）拿到真实图片 URL
        val cleanUrl = url.substringBefore('?')
        val aid = com.jmreader.data.api.direct.JmImageDecoder.parseAidFromUrl(cleanUrl)
        val filename = com.jmreader.data.api.direct.JmImageDecoder.parseFileNameFromUrl(cleanUrl)

        val req = Request.Builder()
            .url(cleanUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36")
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("X-Requested-With", "com.JMComic3.app")
            .header("Referer", "https://www.cdnaspa.club/")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("download ${resp.code}")
            val bytes = resp.body?.bytes() ?: error("空响应")
            val decoded = com.jmreader.data.api.direct.JmImageDecoder.decodeToBytes(bytes, scrambleId, aid, filename)
            target.writeBytes(decoded)
        }
    }

    private fun update(comicId: String, transform: (DownloadTask) -> DownloadTask) {
        // 原子 CAS 更新，避免并发覆盖
        _tasks.update { list -> list.map { if (it.comic.id == comicId) transform(it) else it } }
    }

    /**
     * 删除下载任务（含正在下载的）。
     * 关键修复：cancel 协程，避免「假删除」（协程继续跑、写文件、占带宽）。
     * 同时删除本地文件 + 详情缓存。
     */
    fun remove(comicId: String) {
        synchronized(jobs) { jobs.remove(comicId)?.cancel() }
        _tasks.update { list -> list.filterNot { it.comic.id == comicId } }
        detailCache.remove(comicId)
        runCatching { File(rootDir(), comicId).deleteRecursively() }
    }

    /**
     * 重试已失败的任务：从失败处继续（已下载的章节图片文件保留，runDownload 会自动跳过）。
     *
     * 优先使用 enqueue 时缓存的 detail；若缓存已丢失（如进程重启），调用方需传入 detail。
     * @return true=已触发重试；false=任务不存在/状态非 FAILED/无 detail 无法重试
     */
    fun retry(comic: ComicBriefDto, detail: ComicDetailDto? = null): Boolean {
        // 仅对 FAILED 任务重试
        val existing = _tasks.value.firstOrNull { it.comic.id == comic.id } ?: return false
        if (existing.status != DownloadStatus.FAILED) return false
        val d = detail ?: detailCache[comic.id] ?: return false
        enqueue(comic, d)
        return true
    }
}
