/**
 * 生成 PWA 图标 PNG（纯 Node 实现，无需外部依赖）。
 *
 * 渐变 #d946ef → #7c3aed（与 App 图标一致）。
 * 输出：public/icons/pwa-192.png, pwa-512.png, maskable-512.png, apple-touch-icon.png
 */
const fs = require('fs')
const path = require('path')
const zlib = require('zlib')

// 渐变起止色
const C1 = [0xd9, 0x46, 0xef] // #d946ef
const C2 = [0x7c, 0x3a, 0xed] // #7c3aed

/** 生成渐变 RGB 像素数据（每行带滤波字节 0）。 */
function gradientPixels(size) {
  const rowLen = size * 3 + 1 // 每行前 1 字节滤波 + RGB
  const data = Buffer.alloc(rowLen * size)
  for (let y = 0; y < size; y++) {
    data[y * rowLen] = 0 // 滤波：None
    for (let x = 0; x < size; x++) {
      // 对角线渐变
      const t = (x / size + y / size) / 2
      const r = Math.round(C1[0] + (C2[0] - C1[0]) * t)
      const g = Math.round(C1[1] + (C2[1] - C1[1]) * t)
      const b = Math.round(C1[2] + (C2[2] - C1[2]) * t)
      const off = y * rowLen + 1 + x * 3
      data[off] = r
      data[off + 1] = g
      data[off + 2] = b
    }
  }
  return data
}

/** 生成 maskable 图标（带白色边距安全区，中心渐变方块占 80%）。 */
function maskablePixels(size) {
  const rowLen = size * 3 + 1
  const data = Buffer.alloc(rowLen * size)
  const padding = size * 0.1 // 安全区 10%
  const innerSize = size - padding * 2
  for (let y = 0; y < size; y++) {
    data[y * rowLen] = 0
    for (let x = 0; x < size; x++) {
      let r, g, b
      if (x < padding || x > size - padding || y < padding || y > size - padding) {
        // 安全区：白色背景
        r = g = b = 0xff
      } else {
        const ix = x - padding
        const iy = y - padding
        const t = (ix / innerSize + iy / innerSize) / 2
        r = Math.round(C1[0] + (C2[0] - C1[0]) * t)
        g = Math.round(C1[1] + (C2[1] - C1[1]) * t)
        b = Math.round(C1[2] + (C2[2] - C1[2]) * t)
      }
      const off = y * rowLen + 1 + x * 3
      data[off] = r
      data[off + 1] = g
      data[off + 2] = b
    }
  }
  return data
}

/** 计算 CRC32。 */
function crc32(buf) {
  let table = crc32.table
  if (!table) {
    table = new Uint32Array(256)
    for (let n = 0; n < 256; n++) {
      let c = n
      for (let k = 0; k < 8; k++) {
        c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
      }
      table[n] = c
    }
    crc32.table = table
  }
  let crc = 0xffffffff
  for (let i = 0; i < buf.length; i++) {
    crc = table[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8)
  }
  return (crc ^ 0xffffffff) >>> 0
}

/** 构造 PNG chunk。 */
function chunk(type, data) {
  const typeBuf = Buffer.from(type, 'ascii')
  const lenBuf = Buffer.alloc(4)
  lenBuf.writeUInt32BE(data.length, 0)
  const crcBuf = Buffer.alloc(4)
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0)
  return Buffer.concat([lenBuf, typeBuf, data, crcBuf])
}

/** 生成 PNG 文件 Buffer。 */
function makePng(size, pixelsData) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 2 // color type: RGB
  ihdr[10] = 0 // compression
  ihdr[11] = 0 // filter
  ihdr[12] = 0 // interlace
  const idat = zlib.deflateSync(pixelsData, { level: 9 })
  return Buffer.concat([
    sig,
    chunk('IHDR', ihdr),
    chunk('IDAT', idat),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

// 生成所有图标
const outDir = path.join(__dirname, '..', 'public', 'icons')
fs.mkdirSync(outDir, { recursive: true })

const tasks = [
  { name: 'pwa-192.png', size: 192, fn: gradientPixels },
  { name: 'pwa-512.png', size: 512, fn: gradientPixels },
  { name: 'maskable-512.png', size: 512, fn: maskablePixels },
  { name: 'apple-touch-icon.png', size: 180, fn: gradientPixels },
]

for (const t of tasks) {
  const pixels = t.fn(t.size)
  const png = makePng(t.size, pixels)
  fs.writeFileSync(path.join(outDir, t.name), png)
  console.log(`✓ ${t.name} (${t.size}x${t.size}, ${png.length} bytes)`)
}

console.log('Done.')
