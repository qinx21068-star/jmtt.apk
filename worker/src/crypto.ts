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
 * Worker 环境限制：
 * - Web Crypto API 不支持 MD5 也不支持 ECB 模式
 * - node:crypto 的 createDecipheriv 在 Worker runtime 未实现
 * 因此 MD5 和 AES-256-ECB 均用纯 JS 实现。
 */

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

// ============================================================================
// 纯 JS MD5 实现（移植自 blueimp/md5，Web Crypto 不支持 MD5）
// ============================================================================
const MD5_S = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
]
const MD5_K = [
  0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a,
  0xa8304613, 0xfd469501, 0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
  0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821, 0xf61e2562, 0xc040b340,
  0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
  0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8,
  0x676f02d9, 0x8d2a4c8a, 0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
  0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70, 0x289b7ec6, 0xeaa127fa,
  0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
  0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92,
  0xffeff47d, 0x85845dd1, 0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
  0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
]

function safeAdd(x: number, y: number): number {
  const lsw = (x & 0xffff) + (y & 0xffff)
  const msw = (x >> 16) + (y >> 16) + (lsw >> 16)
  return (msw << 16) | (lsw & 0xffff)
}

function bitRol(num: number, cnt: number): number {
  return (num << cnt) | (num >>> (32 - cnt))
}

function md5cmn(q: number, a: number, b: number, x: number, s: number, t: number): number {
  return safeAdd(bitRol(safeAdd(safeAdd(a, q), safeAdd(x, t)), s), b)
}
function md5ff(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number {
  return md5cmn((b & c) | (~b & d), a, b, x, s, t)
}
function md5gg(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number {
  return md5cmn((b & d) | (c & ~d), a, b, x, s, t)
}
function md5hh(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number {
  return md5cmn(b ^ c ^ d, a, b, x, s, t)
}
function md5ii(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number {
  return md5cmn(c ^ (b | ~d), a, b, x, s, t)
}

function md5cycle(x: Int32Array, k: Int32Array): void {
  let [a, b, c, d] = [x[0], x[1], x[2], x[3]]

  a = md5ff(a, b, c, d, k[0], MD5_S[0], MD5_K[0])
  d = md5ff(d, a, b, c, k[1], MD5_S[1], MD5_K[1])
  c = md5ff(c, d, a, b, k[2], MD5_S[2], MD5_K[2])
  b = md5ff(b, c, d, a, k[3], MD5_S[3], MD5_K[3])
  a = md5ff(a, b, c, d, k[4], MD5_S[4], MD5_K[4])
  d = md5ff(d, a, b, c, k[5], MD5_S[5], MD5_K[5])
  c = md5ff(c, d, a, b, k[6], MD5_S[6], MD5_K[6])
  b = md5ff(b, c, d, a, k[7], MD5_S[7], MD5_K[7])
  a = md5ff(a, b, c, d, k[8], MD5_S[8], MD5_K[8])
  d = md5ff(d, a, b, c, k[9], MD5_S[9], MD5_K[9])
  c = md5ff(c, d, a, b, k[10], MD5_S[10], MD5_K[10])
  b = md5ff(b, c, d, a, k[11], MD5_S[11], MD5_K[11])
  a = md5ff(a, b, c, d, k[12], MD5_S[12], MD5_K[12])
  d = md5ff(d, a, b, c, k[13], MD5_S[13], MD5_K[13])
  c = md5ff(c, d, a, b, k[14], MD5_S[14], MD5_K[14])
  b = md5ff(b, c, d, a, k[15], MD5_S[15], MD5_K[15])

  a = md5gg(a, b, c, d, k[1], MD5_S[16], MD5_K[16])
  d = md5gg(d, a, b, c, k[6], MD5_S[17], MD5_K[17])
  c = md5gg(c, d, a, b, k[11], MD5_S[18], MD5_K[18])
  b = md5gg(b, c, d, a, k[0], MD5_S[19], MD5_K[19])
  a = md5gg(a, b, c, d, k[5], MD5_S[20], MD5_K[20])
  d = md5gg(d, a, b, c, k[10], MD5_S[21], MD5_K[21])
  c = md5gg(c, d, a, b, k[15], MD5_S[22], MD5_K[22])
  b = md5gg(b, c, d, a, k[4], MD5_S[23], MD5_K[23])
  a = md5gg(a, b, c, d, k[9], MD5_S[24], MD5_K[24])
  d = md5gg(d, a, b, c, k[14], MD5_S[25], MD5_K[25])
  c = md5gg(c, d, a, b, k[3], MD5_S[26], MD5_K[26])
  b = md5gg(b, c, d, a, k[8], MD5_S[27], MD5_K[27])
  a = md5gg(a, b, c, d, k[13], MD5_S[28], MD5_K[28])
  d = md5gg(d, a, b, c, k[2], MD5_S[29], MD5_K[29])
  c = md5gg(c, d, a, b, k[7], MD5_S[30], MD5_K[30])
  b = md5gg(b, c, d, a, k[12], MD5_S[31], MD5_K[31])

  a = md5hh(a, b, c, d, k[5], MD5_S[32], MD5_K[32])
  d = md5hh(d, a, b, c, k[8], MD5_S[33], MD5_K[33])
  c = md5hh(c, d, a, b, k[11], MD5_S[34], MD5_K[34])
  b = md5hh(b, c, d, a, k[14], MD5_S[35], MD5_K[35])
  a = md5hh(a, b, c, d, k[1], MD5_S[36], MD5_K[36])
  d = md5hh(d, a, b, c, k[4], MD5_S[37], MD5_K[37])
  c = md5hh(c, d, a, b, k[7], MD5_S[38], MD5_K[38])
  b = md5hh(b, c, d, a, k[10], MD5_S[39], MD5_K[39])
  a = md5hh(a, b, c, d, k[13], MD5_S[40], MD5_K[40])
  d = md5hh(d, a, b, c, k[0], MD5_S[41], MD5_K[41])
  c = md5hh(c, d, a, b, k[3], MD5_S[42], MD5_K[42])
  b = md5hh(b, c, d, a, k[6], MD5_S[43], MD5_K[43])
  a = md5hh(a, b, c, d, k[9], MD5_S[44], MD5_K[44])
  d = md5hh(d, a, b, c, k[12], MD5_S[45], MD5_K[45])
  c = md5hh(c, d, a, b, k[15], MD5_S[46], MD5_K[46])
  b = md5hh(b, c, d, a, k[2], MD5_S[47], MD5_K[47])

  a = md5ii(a, b, c, d, k[0], MD5_S[48], MD5_K[48])
  d = md5ii(d, a, b, c, k[7], MD5_S[49], MD5_K[49])
  c = md5ii(c, d, a, b, k[14], MD5_S[50], MD5_K[50])
  b = md5ii(b, c, d, a, k[5], MD5_S[51], MD5_K[51])
  a = md5ii(a, b, c, d, k[12], MD5_S[52], MD5_K[52])
  d = md5ii(d, a, b, c, k[3], MD5_S[53], MD5_K[53])
  c = md5ii(c, d, a, b, k[10], MD5_S[54], MD5_K[54])
  b = md5ii(b, c, d, a, k[1], MD5_S[55], MD5_K[55])
  a = md5ii(a, b, c, d, k[8], MD5_S[56], MD5_K[56])
  d = md5ii(d, a, b, c, k[15], MD5_S[57], MD5_K[57])
  c = md5ii(c, d, a, b, k[6], MD5_S[58], MD5_K[58])
  b = md5ii(b, c, d, a, k[13], MD5_S[59], MD5_K[59])
  a = md5ii(a, b, c, d, k[4], MD5_S[60], MD5_K[60])
  d = md5ii(d, a, b, c, k[11], MD5_S[61], MD5_K[61])
  c = md5ii(c, d, a, b, k[2], MD5_S[62], MD5_K[62])
  b = md5ii(b, c, d, a, k[9], MD5_S[63], MD5_K[63])

  x[0] = safeAdd(a, x[0])
  x[1] = safeAdd(b, x[1])
  x[2] = safeAdd(c, x[2])
  x[3] = safeAdd(d, x[3])
}

function md5blk(data: Uint8Array): Int32Array {
  const md5blks = new Int32Array(16)
  for (let i = 0; i < 64; i += 4) {
    md5blks[i >> 2] =
      data[i] |
      (data[i + 1] << 8) |
      (data[i + 2] << 16) |
      (data[i + 3] << 24)
  }
  return md5blks
}

function rstrMD5(data: Uint8Array): Uint8Array {
  const n = data.length
  const dataLen = n
  // 填充：1 个 0x80 + 0 填充到 56 mod 64 + 8 字节长度（小端）
  const padded = new Uint8Array(((n + 72) >> 6) << 6)
  padded.set(data)
  padded[n] = 0x80
  // 长度按位（小端）
  const bitLen = dataLen * 8
  const lo = bitLen >>> 0
  const hi = Math.floor(bitLen / 0x100000000) >>> 0
  const lenPos = padded.length - 8
  padded[lenPos] = lo & 0xff
  padded[lenPos + 1] = (lo >>> 8) & 0xff
  padded[lenPos + 2] = (lo >>> 16) & 0xff
  padded[lenPos + 3] = (lo >>> 24) & 0xff
  padded[lenPos + 4] = hi & 0xff
  padded[lenPos + 5] = (hi >>> 8) & 0xff
  padded[lenPos + 6] = (hi >>> 16) & 0xff
  padded[lenPos + 7] = (hi >>> 24) & 0xff

  const state = new Int32Array([0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476])
  for (let i = 0; i < padded.length; i += 64) {
    md5cycle(state, md5blk(padded.subarray(i, i + 64)))
  }

  const out = new Uint8Array(16)
  const dv = new DataView(out.buffer)
  for (let i = 0; i < 4; i++) {
    dv.setInt32(i * 4, state[i], true)
  }
  return out
}

function bytesToHex(bytes: Uint8Array): string {
  const hex = '0123456789abcdef'
  let s = ''
  for (let i = 0; i < bytes.length; i++) {
    s += hex[(bytes[i] >> 4) & 0x0f] + hex[bytes[i] & 0x0f]
  }
  return s
}

/** md5 哈希，返回 32 字符小写 hex 串。 */
export function md5hex(input: string): string {
  const bytes = new TextEncoder().encode(input)
  return bytesToHex(rstrMD5(bytes))
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

// ============================================================================
// 纯 JS AES-256-ECB 实现（Web Crypto 不支持 ECB，node:crypto 在 Worker 不可用）
// 移植自 Wikipedia AES 算法描述 + rijndael-js
// ============================================================================

// 标准 AES S-Box（硬编码，避免生成算法 bug）
const SBOX: Uint8Array = new Uint8Array([
  0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
  0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
  0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
  0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
  0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
  0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
  0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
  0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
  0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
  0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
  0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
  0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
  0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
  0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
  0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
  0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
])

const INV_SBOX: Uint8Array = (() => {
  const inv = new Uint8Array(256)
  for (let i = 0; i < 256; i++) inv[SBOX[i]] = i
  return inv
})()

const RCON: Uint8Array = new Uint8Array([
  0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40,
  0x80, 0x1b, 0x36, 0x6c, 0xd8, 0xab, 0x4d, 0x9a,
])

function xtime(x: number): number {
  return ((x << 1) ^ ((x & 0x80) ? 0x1b : 0)) & 0xff
}

function gmul(a: number, b: number): number {
  let r = 0
  for (let i = 0; i < 8; i++) {
    if (b & 1) r ^= a
    const hi = a & 0x80
    a = (a << 1) & 0xff
    if (hi) a ^= 0x1b
    b >>= 1
  }
  return r & 0xff
}

/** AES 密钥扩展（256-bit key, 14 rounds）。返回 240 字节扩展密钥。 */
function keyExpansion(key: Uint8Array): Uint8Array {
  const Nk = 8       // 256-bit = 8 words
  const Nr = 14
  const Nb = 4
  const w = new Uint8Array(Nb * (Nr + 1) * 4)  // 240 字节
  w.set(key)

  for (let i = Nk; i < Nb * (Nr + 1); i++) {
    const off = i * 4
    const prev = w.subarray(off - 4, off)
    const t = new Uint8Array(prev)
    if (i % Nk === 0) {
      // RotWord
      const tmp = t[0]
      t[0] = t[1]; t[1] = t[2]; t[2] = t[3]; t[3] = tmp
      // SubWord
      t[0] = SBOX[t[0]]; t[1] = SBOX[t[1]]; t[2] = SBOX[t[2]]; t[3] = SBOX[t[3]]
      t[0] ^= RCON[i / Nk]
    } else if (i % Nk === 4) {
      // SubWord
      t[0] = SBOX[t[0]]; t[1] = SBOX[t[1]]; t[2] = SBOX[t[2]]; t[3] = SBOX[t[3]]
    }
    const wPrev = w.subarray(off - Nk * 4, off - Nk * 4 + 4)
    w[off] = wPrev[0] ^ t[0]
    w[off + 1] = wPrev[1] ^ t[1]
    w[off + 2] = wPrev[2] ^ t[2]
    w[off + 3] = wPrev[3] ^ t[3]
  }
  return w
}

/** AES 逆向列混合。 */
function invMixColumns(state: Uint8Array): void {
  for (let c = 0; c < 4; c++) {
    const i = c * 4
    const s0 = state[i], s1 = state[i + 1], s2 = state[i + 2], s3 = state[i + 3]
    state[i]     = gmul(0x0e, s0) ^ gmul(0x0b, s1) ^ gmul(0x0d, s2) ^ gmul(0x09, s3)
    state[i + 1] = gmul(0x09, s0) ^ gmul(0x0e, s1) ^ gmul(0x0b, s2) ^ gmul(0x0d, s3)
    state[i + 2] = gmul(0x0d, s0) ^ gmul(0x09, s1) ^ gmul(0x0e, s2) ^ gmul(0x0b, s3)
    state[i + 3] = gmul(0x0b, s0) ^ gmul(0x0d, s1) ^ gmul(0x09, s2) ^ gmul(0x0e, s3)
  }
}

/** AES 逆向行移位（解密时各行右移：row r 右移 r 位）。 */
function invShiftRows(state: Uint8Array): void {
  // state 按列存：state[col*4 + row]，行 r 的元素在 state[r], state[r+4], state[r+8], state[r+12]
  let t: number
  // row 1 右移 1: [s0,s1,s2,s3] → [s3,s0,s1,s2]
  t = state[13]; state[13] = state[9]; state[9] = state[5]; state[5] = state[1]; state[1] = t
  // row 2 右移 2: [s0,s1,s2,s3] → [s2,s3,s0,s1]（交换）
  t = state[2]; state[2] = state[10]; state[10] = t
  t = state[6]; state[6] = state[14]; state[14] = t
  // row 3 右移 3 = 左移 1: [s0,s1,s2,s3] → [s1,s2,s3,s0]
  t = state[3]; state[3] = state[7]; state[7] = state[11]; state[11] = state[15]; state[15] = t
}

/** AddRoundKey。 */
function addRoundKey(state: Uint8Array, roundKey: Uint8Array): void {
  for (let i = 0; i < 16; i++) state[i] ^= roundKey[i]
}

/** AES-256-ECB 单块解密（16 字节）。 */
function decryptBlock(block: Uint8Array, expandedKey: Uint8Array): Uint8Array {
  const Nr = 14
  const state = new Uint8Array(block)

  // 初始 AddRoundKey
  addRoundKey(state, expandedKey.subarray(Nr * 16, (Nr + 1) * 16))

  for (let round = Nr - 1; round >= 1; round--) {
    // InvShiftRows
    invShiftRows(state)
    // InvSubBytes
    for (let i = 0; i < 16; i++) state[i] = INV_SBOX[state[i]]
    // AddRoundKey
    addRoundKey(state, expandedKey.subarray(round * 16, (round + 1) * 16))
    // InvMixColumns
    invMixColumns(state)
  }

  // 最后一轮
  invShiftRows(state)
  for (let i = 0; i < 16; i++) state[i] = INV_SBOX[state[i]]
  addRoundKey(state, expandedKey.subarray(0, 16))

  return state
}

/** AES-256-ECB 解密（无 padding，调用方自行处理 PKCS7）。 */
function aes256EcbDecrypt(cipherBytes: Uint8Array, key: Uint8Array): Uint8Array {
  if (key.length !== 32) throw new Error(`AES-256 key must be 32 bytes, got ${key.length}`)
  if (cipherBytes.length % 16 !== 0) throw new Error(`密文长度必须 16 字节对齐，实际 ${cipherBytes.length}`)

  const expandedKey = keyExpansion(key)
  const out = new Uint8Array(cipherBytes.length)

  for (let i = 0; i < cipherBytes.length; i += 16) {
    const block = cipherBytes.subarray(i, i + 16)
    const decrypted = decryptBlock(block, expandedKey)
    out.set(decrypted, i)
  }

  return out
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
  const cipherBytes = base64ToBytes(data)
  if (cipherBytes.length === 0) return ''

  // 2. AES-ECB 解密，key = md5hex(ts + secret) 的 UTF-8 字节（32 字节 → AES-256）
  const keyBytes = new TextEncoder().encode(md5hex(tsStr + secret))
  const decrypted = aes256EcbDecrypt(cipherBytes, keyBytes)
  if (decrypted.length === 0) return ''

  // 3. 移除末尾的 PKCS7 padding（最后一个字节值 = padding 长度）
  const padLen = decrypted[decrypted.length - 1]
  const end =
    padLen >= 1 && padLen <= 16 && padLen <= decrypted.length
      ? decrypted.length - padLen
      : decrypted.length

  // 4. UTF-8 解码为 JSON 字符串
  return new TextDecoder().decode(decrypted.subarray(0, end))
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

/** base64 字符串转 Uint8Array（兼容包含换行/空格的 base64）。 */
function base64ToBytes(b64: string): Uint8Array {
  // 移除所有非 base64 字符（换行、空格等）
  const clean = b64.replace(/[^A-Za-z0-9+/=]/g, '')
  if (!clean) return new Uint8Array(0)

  // 用 atob 解码（Worker 支持 atob）
  const binary = atob(clean)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}
