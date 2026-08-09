package com.jmreader.data.api.direct

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.jmreader.core.Logger
import java.io.ByteArrayOutputStream

/**
 * 禁漫图片分割解密。
 *
 * 移植自 jmcomic python 库 JmImageTool：
 * - getNum(): 根据 scramble_id / aid / filename 计算分割数
 * - decode(): 把原图按高度切成 num 块，从下往上取、从上往下贴，还原原图
 *
 * 规则：
 *   aid < scramble_id        → 0（不分割）
 *   aid < 268850             → 10
 *   aid < 421926             → x=10, num = (md5("{aid}{filename}")[-1] % 10) * 2 + 2
 *   aid >= 421926            → x=8,  num = (md5("{aid}{filename}")[-1] % 8)  * 2 + 2
 */
object JmImageDecoder {

    /** 计算分割数。aid 为章节(photo)id，filename 为图片文件名（不含扩展名，如 "00047"）。 */
    fun getScrambleNum(scrambleId: Long, aid: Long, filename: String): Int {
        if (aid < scrambleId) return 0
        if (aid < JMCrypto.SCRAMBLE_268850) return 10
        val x = if (aid < JMCrypto.SCRAMBLE_421926) 10 else 8
        val s = JMCrypto.md5hex("$aid$filename")
        val last = s.last().code   // ord(s[-1])
        return (last % x) * 2 + 2
    }

    /**
     * 从图片 URL 解析 aid（photo_id）。
     * URL 形如 https://cdn-msp.xxx/media/photos/413446/00047.webp
     */
    fun parseAidFromUrl(url: String): Long {
        // /media/photos/{aid}/
        val marker = "/media/photos/"
        val idx = url.indexOf(marker)
        if (idx < 0) return 0L
        val rest = url.substring(idx + marker.length)
        val end = rest.indexOf('/')
        val numStr = if (end < 0) rest else rest.substring(0, end)
        return numStr.toLongOrNull() ?: 0L
    }

    /**
     * 从 URL 提取文件名（不含扩展名），用于分割数计算。
     *
     * 必须去掉扩展名：禁漫分割算法 md5("{aid}{filename}") 中的 filename 是不含后缀的
     * （如 "00047"，不是 "00047.webp"）。移植自 python jmcomic：
     *   JmImageDetail.img_file_name  # without suffix
     *   JmImageTool.get_num_by_url → of_file_name(url, True)  # True=去后缀
     * 若带后缀会导致 md5 末位不同，算出的分割数错误，图片被切得过密而无法还原。
     */
    fun parseFileNameFromUrl(url: String): String {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        val slash = path.lastIndexOf('/')
        val fullName = if (slash >= 0) path.substring(slash + 1) else path
        val dot = fullName.lastIndexOf('.')
        return if (dot > 0) fullName.substring(0, dot) else fullName
    }

    /**
     * 解密图片字节，返回还原后的 bitmap。
     * 若无需分割，直接解码原图。
     *
     * v27.5 稳定性加固：scramble 内 Bitmap.createBitmap 在大图 + 内存紧张时可能 OOM，
     * 用 runCatching 兜底；失败时回退返回原图（不分割），让 UI 至少能显示一张图，
     * 而不是直接抛 OutOfMemoryError 让 Coil 把整张图加载流程崩掉。
     *
     * v27.5 稳定性加固：BitmapFactory.decodeByteArray 同样可能抛 OutOfMemoryError
     * （大图解码成 100MP bitmap 需要数百 MB 堆），之前未包裹，OOM 会沿调用链冒泡到
     * JmImageFetcher.fetch() → Coil 把整张图加载流程崩掉，且 OOM 后进程不稳定。
     * 现在用 runCatching 兜底，OOM 时返回 null（让 decodeToBytes 退化为返回原始字节，
     * 与 scramble 失败时的兜底策略一致）。
     */
    fun decode(bytes: ByteArray, scrambleId: Long, aid: Long, filename: String): Bitmap? {
        val src = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrElse {
                Logger.w("JmImg", "decodeByteArray 失败 aid=$aid: ${Logger.brief(it)}")
                return null
            } ?: return null
        val num = getScrambleNum(scrambleId, aid, filename)
        if (num == 0) return src
        return runCatching { scramble(src, num) }.getOrElse {
            // 分割失败（如 OOM）：返回未分割的原图，至少能显示
            Logger.w("JmImg", "图片分割失败 num=$num aid=$aid: ${Logger.brief(it)}")
            src
        }
    }

    /**
     * 按禁漫算法重排图片：把原图切成 num 块（高度方向），从下往上取贴到新图。
     *
     * Python 原算法（JmImageTool.decode_and_save）：
     *   over = h % num
     *   for i in range(num):
     *       move = floor(h / num)
     *       y_src = h - move*(i+1) - over
     *       y_dst = move * i
     *       if i == 0: move += over
     *       else: y_dst += over
     *       paste(crop(0,y_src,w,y_src+move), (0,y_dst,w,y_dst+move))
     */
    private fun scramble(src: Bitmap, num: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val over = h % num
        val moveBase = h / num  // floor(h/num)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        for (i in 0 until num) {
            var move = moveBase
            var ySrc = h - move * (i + 1) - over
            var yDst = move * i

            if (i == 0) {
                move += over
            } else {
                yDst += over
            }

            // 裁剪源区域，贴到目标区域
            val srcRect = Rect(0, ySrc, w, ySrc + move)
            val dstRect = Rect(0, yDst, w, yDst + move)
            canvas.drawBitmap(src, srcRect, dstRect, paint)
        }
        // 回收源 bitmap：所有块已绘制到 out，src 不再需要，及时释放避免大图 OOM
        src.recycle()
        return out
    }

    /** 解密并把结果编码为字节数组（jpeg），供 Coil/下载流程使用。 */
    fun decodeToBytes(bytes: ByteArray, scrambleId: Long, aid: Long, filename: String): ByteArray {
        val bmp = decode(bytes, scrambleId, aid, filename) ?: return bytes
        try {
            // v27.5 稳定性加固：compress/toByteArray 对大图可能 OOM（baos.toByteArray 复制一份完整 JPEG 字节）。
            // 失败时 return bytes（原始未解密字节），与 decode 失败的兜底策略一致。
            // 不让 OOM 冒泡到 JmImageFetcher.fetch / DownloadManager.runDownload 导致整章加载失败。
            return runCatching {
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, baos)
                baos.toByteArray()
            }.getOrElse {
                Logger.w("JmImg", "compress/toByteArray 失败 aid=$aid: ${Logger.brief(it)}")
                bytes
            }
        } finally {
            // v27.5 稳定性加固：bmp 是从 BitmapFactory.decodeByteArray 出来的大图（可能数 MB），
            // compress 完成后不再使用，必须 recycle 释放原生堆内存。
            // 不 recycle 时连续解码多张大图会累积 OOM，触发 Coil 加载失败 → 阅读页空白页。
            // 注意 decode() 在 num==0 时返回的 src 也是同一来源，同样需要 recycle。
            if (!bmp.isRecycled) bmp.recycle()
        }
    }
}
