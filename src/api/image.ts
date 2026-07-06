/**
 * 禁漫图片 scramble 解码工具。
 *
 * 移植自 Android JmImageDecoder：
 * - getScrambleNum(): 根据 scramble_id / aid / filename 计算分割数
 * - decodeImage(): 把原图按高度切成 num 块，从下往上取、从上往下贴，还原原图
 *
 * 规则：
 *   aid < scramble_id        → 0（不分割）
 *   aid < 268850             → 10
 *   aid < 421926             → x=10, num = (md5("{aid}{filename}")[-1] % 10) * 2 + 2
 *   aid >= 421926            → x=8,  num = (md5("{aid}{filename}")[-1] % 8)  * 2 + 2
 *
 * 前端用 Canvas 实现，浏览器原生支持。
 */

const SCRAMBLE_220980 = 220980
const SCRAMBLE_268850 = 268850
const SCRAMBLE_421926 = 421926

// ============================================================================
// 纯 JS MD5 实现（移植自 blueimp/md5，Web Crypto 不支持 MD5）
// 与 worker/src/crypto.ts 的实现保持一致，已通过 Node crypto 对照测试
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
function md5hex(input: string): string {
  const bytes = new TextEncoder().encode(input)
  return bytesToHex(rstrMD5(bytes))
}

/** 计算分割数。aid=章节 photo_id，filename=图片文件名（不含扩展名，如 "00047"）。 */
export function getScrambleNum(
  scrambleId: number,
  aid: number,
  filename: string,
): number {
  if (aid < scrambleId) return 0
  if (aid < SCRAMBLE_268850) return 10
  const x = aid < SCRAMBLE_421926 ? 10 : 8
  const s = md5hex(`${aid}${filename}`)
  const last = s.charCodeAt(s.length - 1)
  return (last % x) * 2 + 2
}

/**
 * 解码图片：把原图按 num 块重排。
 *
 * 算法（移植自 Android JmImageDecoder.scramble）：
 *   over = h % num
 *   for i in range(num):
 *       move = floor(h / num)
 *       y_src = h - move*(i+1) - over
 *       y_dst = move * i
 *       if i == 0: move += over
 *       else: y_dst += over
 *       drawImage(src, 0,y_src,w,y_src+move, 0,y_dst,w,y_dst+move)
 *
 * @param srcUrl 原图 URL（Worker 图片代理 URL）
 * @param num 分割数（0=不分割，直接返回原 URL）
 * @return 解码后的 blob URL（需要时释放）；不分割时返回原 URL
 */
export async function decodeImage(srcUrl: string, num: number): Promise<string> {
  if (num === 0) return srcUrl

  const resp = await fetch(srcUrl)
  if (!resp.ok) throw new Error(`图片加载失败: ${resp.status}`)
  const blob = await resp.blob()

  // 用 Image 对象代替 createImageBitmap，兼容性更好且不会应用 EXIF 旋转
  const img = new Image()
  img.src = URL.createObjectURL(blob)
  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve()
    img.onerror = () => reject(new Error('图片解码失败'))
  })

  const w = img.naturalWidth
  const h = img.naturalHeight

  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    URL.revokeObjectURL(img.src)
    return srcUrl
  }
  // 黑色背景
  ctx.fillStyle = '#000'
  ctx.fillRect(0, 0, w, h)

  const over = h % num
  const moveBase = Math.floor(h / num)
  for (let i = 0; i < num; i++) {
    let move = moveBase
    let ySrc = h - move * (i + 1) - over
    let yDst = move * i
    if (i === 0) {
      move += over
    } else {
      yDst += over
    }
    // 从源 (0, ySrc, w, move) 贴到目标 (0, yDst, w, move)
    ctx.drawImage(img, 0, ySrc, w, move, 0, yDst, w, move)
  }
  URL.revokeObjectURL(img.src)

  return new Promise<string>((resolve) => {
    canvas.toBlob(
      (outBlob) => {
        if (outBlob) {
          resolve(URL.createObjectURL(outBlob))
        } else {
          resolve(srcUrl)
        }
      },
      'image/jpeg',
      0.92,
    )
  })
}

/** 从 Worker 图片代理 URL 解析 scramble 参数。 */
export function parseImageProxyUrl(
  url: string,
): {
  targetUrl: string
  aid: number
  scrambleId: number
  filename: string
} | null {
  try {
    const u = new URL(url, window.location.origin)
    if (u.pathname !== '/api/image-proxy') return null
    const targetUrl = u.searchParams.get('url') || ''
    const aid = parseInt(u.searchParams.get('aid') || '0', 10) || 0
    const scrambleId = parseInt(u.searchParams.get('scramble_id') || '0', 10) || 0
    const filename = u.searchParams.get('filename') || ''
    return { targetUrl, aid, scrambleId, filename }
  } catch {
    return null
  }
}

/** 缓存：避免同一图片重复解码。key=原图URL，value=解码后 blob URL。 */
const decodeCache = new Map<string, string>()

/** 解码图片（带缓存）。 */
export async function decodeImageCached(srcUrl: string, num: number): Promise<string> {
  if (num === 0) return srcUrl
  const cached = decodeCache.get(srcUrl)
  if (cached) return cached
  const decoded = await decodeImage(srcUrl, num)
  if (decoded !== srcUrl) {
    decodeCache.set(srcUrl, decoded)
  }
  return decoded
}
