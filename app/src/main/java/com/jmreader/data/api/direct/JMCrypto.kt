package com.jmreader.data.api.direct

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 禁漫移动端 API 加解密。
 *
 * 协议（移植自 jmcomic python 库 JmCryptoTool / JmMagicConstants）：
 * - 请求头 token      = md5hex(ts + APP_TOKEN_SECRET)
 * - 请求头 tokenparam = "$ts,$APP_VERSION"
 * - 响应解密：base64 → AES-ECB(key = md5hex(ts + APP_DATA_SECRET) 的 UTF-8 字节) → 去 PKCS7 padding → UTF-8 JSON
 *
 * 说明：md5hex 是 32 字符 hex 串，其 UTF-8 字节为 32 字节，因此 AES 实际是 AES-256-ECB。
 */
object JMCrypto {

    // ---- 密钥常量（来自 jmcomic.JmMagicConstants，APP 内置）----
    const val APP_TOKEN_SECRET = "185Hcomic3PAPP7R"     // 普通 API 接口
    const val APP_TOKEN_SECRET_2 = "18comicAPPContent"  // /chapter_view_template 专用
    const val APP_DATA_SECRET = "185Hcomic3PAPP7R"      // 响应数据解密
    const val APP_VERSION = "2.0.26"

    // /chapter_view_template 接口用的域名服务器解密密钥（获取最新 API 域名时用，这里备查）
    const val API_DOMAIN_SERVER_SECRET = "diosfjckwpqpdfjkvnqQjsik"

    // scramble 相关常量
    const val SCRAMBLE_220980 = 220980   // 默认 scramble_id 兜底值
    const val SCRAMBLE_268850 = 268850   // 旧分割阈值
    const val SCRAMBLE_421926 = 421926   // 2023-02-08 后改了切割算法的阈值

    /** md5 哈希，返回 32 字符小写 hex 串。 */
    fun md5hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** 生成请求 token = md5hex(ts + secret)。 */
    fun token(ts: String, secret: String = APP_TOKEN_SECRET): String =
        md5hex(ts + secret)

    /** 生成请求 tokenparam = "$ts,$ver"。 */
    fun tokenparam(ts: String, ver: String = APP_VERSION): String = "$ts,$ver"

    /**
     * 解密接口返回的 data 字段。
     *
     * @param data  resp.json()["data"]，base64 编码的密文
     * @param ts    请求时使用的时间戳（解密 key 依赖它）
     * @param secret 密钥，默认 APP_DATA_SECRET
     * @return 解密后的 JSON 字符串
     */
    fun decodeRespData(data: String, ts: String, secret: String = APP_DATA_SECRET): String {
        // 1. base64 解码（禁漫返回的是标准 base64，可能含换行，用 NO_WRAP 容错）
        val cipherBytes = Base64.decode(data, Base64.DEFAULT)

        // 2. AES-ECB 解密，key = md5hex(ts + secret) 的 UTF-8 字节（32 字节 → AES-256）
        val keyBytes = md5hex(ts + secret).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        val decrypted = cipher.doFinal(cipherBytes)

        // 空数据兜底：服务器返回空 data 时 decrypted 可能为空，直接返回空串避免越界
        if (decrypted.isEmpty()) return ""

        // 3. 移除末尾的 PKCS7 padding（最后一个字节值 = padding 长度）
        val padLen = decrypted[decrypted.size - 1].toInt() and 0xff
        val end = if (padLen in 1..16 && padLen <= decrypted.size) decrypted.size - padLen else decrypted.size

        // 4. UTF-8 解码为 JSON 字符串
        return String(decrypted, 0, end, Charsets.UTF_8)
    }

    /**
     * 解密“域名服务器”响应（newsvr-2025.txt），返回 JSON 字符串。
     *
     * 用于动态获取禁漫最新 API 域名，协议移植自 jmcomic.JmApiClient.req_api_domain_server：
     * - 响应文本开头可能带非 ascii 前缀（BOM/标识符），需先剥离
     * - 之后是 base64 密文，用 decodeRespData(text, ts="", API_DOMAIN_SERVER_SECRET) 解密
     * - 解密后 JSON 形如 {"Server": ["www.cdnhjk.net", ...]}
     */
    fun decodeDomainServerResp(text: String): String {
        var t = text
        while (t.isNotEmpty() && t[0].code > 127) t = t.substring(1)
        return decodeRespData(t, "", API_DOMAIN_SERVER_SECRET)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
