/**
 * 本地测试：验证纯 JS MD5 和 AES-256-ECB 实现正确性。
 * 运行：npx tsx test-crypto.ts
 */
import crypto from 'node:crypto'
import { md5hex, decodeRespData, APP_DATA_SECRET } from './src/crypto.ts'

let pass = 0, fail = 0
function assert(name: string, actual: unknown, expected: unknown) {
  if (actual === expected) {
    console.log(`✓ ${name}`)
    pass++
  } else {
    console.log(`✗ ${name}`)
    console.log(`  expected: ${JSON.stringify(expected)}`)
    console.log(`  actual:   ${JSON.stringify(actual)}`)
    fail++
  }
}

// ---- MD5 测试（用 Node 内置 crypto 对照）----
console.log('\n== MD5 测试 ==')
const md5Cases = [
  '',
  'abc',
  '185Hcomic3PAPP7R',
  '1234567890185Hcomic3PAPP7R',
  'diosfjckwpqpdfjkvnqQjsik',
  '中文测试 hello world 123',
]
for (const c of md5Cases) {
  const expected = crypto.createHash('md5').update(c, 'utf8').digest('hex')
  assert(`md5("${c}")`, md5hex(c), expected)
}

// ---- AES-256-ECB 解密测试 ----
console.log('\n== AES-256-ECB 解密测试 ==')

// 用 Node crypto 加密一段数据，再用我们的纯 JS 解密
function encryptWithNode(plaintext: string, keyStr: string): string {
  const key = Buffer.from(keyStr, 'utf8')
  const cipher = crypto.createCipheriv('aes-256-ecb', key, null)
  cipher.setAutoPadding(true) // PKCS7
  const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()])
  return encrypted.toString('base64')
}

const ts = '1234567890'
const secret = APP_DATA_SECRET
const keyStr = md5hex(ts + secret)

const aesCases = [
  '{"code":200,"data":"hello"}',
  '{"list":[1,2,3],"name":"test"}',
  'short',
  'a'.repeat(100),
  '{"中文":"测试","emoji":"😀🎉"}',
]
for (const plain of aesCases) {
  const encrypted = encryptWithNode(plain, keyStr)
  const decrypted = decodeRespData(encrypted, ts, secret)
  const label = plain.length > 30 ? plain.slice(0, 30) + '...' : plain
  assert(`AES 解密 "${label}"`, decrypted, plain)
}

// ---- 模拟禁漫 API 响应解密 ----
console.log('\n== 模拟禁漫 API 响应 ==')
const jmResp = '{"code":200,"data":[{"id":"123","name":"测试漫画","author":"作者"}]}'
const jmEncrypted = encryptWithNode(jmResp, keyStr)
const jmDecrypted = decodeRespData(jmEncrypted, ts, secret)
assert('禁漫 API 响应解密', jmDecrypted, jmResp)

// ---- 输出结果 ----
console.log(`\n== 结果 ==`)
console.log(`✓ 通过: ${pass}`)
console.log(`✗ 失败: ${fail}`)
process.exit(fail > 0 ? 1 : 0)
