/**
 * 禁漫移动端 API 加解密。
 *
 * 协议（移植自 jmcomic python 库 JmCryptoTool / JmMagicConstants）：
 * - 请求头 token      = md5hex(ts + APP_TOKEN_SECRET)
 * - 请求头 tokenparam = "$ts,$APP_VERSION"
 * - 响应解密：base64 → AES-ECB(key = md5hex(ts + APP_DATA_SECRET) 的 UTF-8 字节) → 去 PKCS7 padding → UTF-8 JSON
 *
 * 说明：md5hex 是 32 字符 hex 串，其 UTF-8 字节为 32 字节，因此 AES 实际是 AES-256-ECB。
 *
 * Worker 环境用 nodejs_compat 的 node:crypto 模块（Web Crypto 不支持 MD5 和 ECB）。
 */
import { createHash, createDecipheriv } from 'node:crypto'

// ---- 密钥常量（来自 jmcomic.JmMagicConstants，APP 内置）----
export const APP_TOKEN_SECRET = '185Hcomic3PAPP7R'     // 普通 API 接口
export const APP_TOKEN_SECRET_2 = '18comicAPPContent'  // /chapter_view_template 专用
export const APP_DATA_SECRET = '185Hcomic3PAPP7R'      // 响应数据解密
export const APP_VERSION = '2.0.26'

// /chapter_view_template 接口用的域名服务器解密密钥
export const API_DOMAIN_SERVER_SECRET = 'diosfjckwpqpdfjkvnqQjsik'

// scramble 相关常量
export const SCRAMBLE_220980 = 220980   // 默认 scramble_id 兜底值
export const SCRAMBLE_268850 = 268850   // 旧分割阈值
export const SCRAMBLE_421926 = 421926   // 2023-02-08 后改了切割算法的阈值

/** md5 哈希，返回 32 字符小写 hex 串。 */
export function md5hex(input: string): string {
  return createHash('md5').update(input, 'utf8').digest('hex')
}

/** 生成请求 token = md5hex(ts + secret)。 */
export function token(ts: string, secret: string = APP_TOKEN_SECRET): string {
  return md5hex(ts + secret)
}

/** 生成请求 tokenparam = "$ts,$ver"。 */
export function tokenparam(ts: string, ver: string = APP_VERSION): string {
  return `${ts},${ver}`
}

/** 当前秒级时间戳字符串。 */
export function ts(): string {
  return Math.floor(Date.now() / 1000).toString()
}

/**
 * 解密接口返回的 data 字段。
 *
 * @param data   resp.json()["data"]，base64 编码的密文
 * @param tsStr  请求时使用的时间戳（解密 key 依赖它）
 * @param secret 密钥，默认 APP_DATA_SECRET
 * @return 解密后的 JSON 字符串
 */
export function decodeRespData(
  data: string,
  tsStr: string,
  secret: string = APP_DATA_SECRET,
): string {
  // 1. base64 解码（禁漫返回的是标准 base64）
  const cipherBytes = Buffer.from(data, 'base64')
  if (cipherBytes.length === 0) return ''

  // 2. AES-ECB 解密，key = md5hex(ts + secret) 的 UTF-8 字节（32 字节 → AES-256）
  const keyBytes = Buffer.from(md5hex(tsStr + secret), 'utf8')
  const decipher = createDecipheriv('aes-256-ecb', keyBytes, null)
  decipher.setAutoPadding(false)
  const decrypted = Buffer.concat([decipher.update(cipherBytes), decipher.final()])
  if (decrypted.length === 0) return ''

  // 3. 移除末尾的 PKCS7 padding（最后一个字节值 = padding 长度）
  const padLen = decrypted[decrypted.length - 1]
  const end =
    padLen >= 1 && padLen <= 16 && padLen <= decrypted.length
      ? decrypted.length - padLen
      : decrypted.length

  // 4. UTF-8 解码为 JSON 字符串
  return decrypted.subarray(0, end).toString('utf8')
}

/**
 * 解密"域名服务器"响应（newsvr-2025.txt），返回 JSON 字符串。
 *
 * 用于动态获取禁漫最新 API 域名：
 * - 响应文本开头可能带非 ascii 前缀（BOM/标识符），需先剥离
 * - 之后是 base64 密文，用 decodeRespData(text, ts="", API_DOMAIN_SERVER_SECRET) 解密
 * - 解密后 JSON 形如 {"Server": ["www.cdnhjk.net", ...]}
 */
export function decodeDomainServerResp(text: string): string {
  let t = text
  // 剥离非 ASCII 前缀
  while (t.length > 0 && t.charCodeAt(0) > 127) t = t.substring(1)
  return decodeRespData(t, '', API_DOMAIN_SERVER_SECRET)
}
