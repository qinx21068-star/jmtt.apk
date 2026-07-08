<script setup lang="ts">
/**
 * 诊断页：逐项检查 Worker / API / 图片解码链路，把结果直接显示在页面上。
 * 访问路径：/#/debug
 */
import { ref, onMounted } from 'vue'
import { get, getWorkerUrl } from '@/api/client'
import { search, comicDetail, chapterImages } from '@/api/jm'
import { decodeImage, getScrambleNum, parseImageProxyUrl } from '@/api/image'

type Step = {
  name: string
  status: 'pending' | 'ok' | 'fail' | 'info'
  detail: string
  data?: any
}

const steps = ref<Step[]>([])
const testImageUrl = ref<string>('')
const testImageLoaded = ref<boolean | null>(null)
const testImageError = ref<string>('')
const decodedImageUrl = ref<string>('')
const decodeLog = ref<string>('')

function addStep(name: string) {
  const s: Step = { name, status: 'pending', detail: '' }
  steps.value.push(s)
  return s
}

function update(s: Step, status: Step['status'], detail: string, data?: any) {
  s.status = status
  s.detail = detail
  s.data = data
}

async function run() {
  // 0. 页面已加载（确认 JS 执行到了这里）
  const s0 = addStep('0. 诊断脚本启动')
  update(s0, 'ok', 'DebugView 已加载，开始诊断…')

  try {
    // 1. Worker URL
    const s1 = addStep('1. Worker URL')
    const workerUrl = getWorkerUrl()
    update(s1, 'info', `当前 Worker: ${workerUrl}`)

  // 2. Health
  const s2 = addStep('2. Worker 健康检查 /api/health')
  try {
    const data = await get<{ status: string; time: number }>('/api/health')
    update(s2, 'ok', `status=${data.status}, time=${data.time}ms`, data)
  } catch (e: any) {
    update(s2, 'fail', e?.message || String(e))
    return
  }

  // 3. 搜索
  const s3 = addStep('3. 搜索测试 (关键词: 線画)')
  let firstComicId = ''
  let firstChapterId = ''
  try {
    const result = await search('線画', 1, 'latest', 'all')
    update(
      s3,
      result.items.length > 0 ? 'ok' : 'fail',
      `返回 ${result.items.length} 条结果，total=${result.total}`,
      result.items.slice(0, 3),
    )
    if (result.items.length === 0) return
    firstComicId = result.items[0].id
  } catch (e: any) {
    update(s3, 'fail', e?.message || String(e))
    return
  }

  // 4. 详情
  const s4 = addStep(`4. 漫画详情 /api/comic/${firstComicId}`)
  try {
    const detail = await comicDetail(firstComicId)
    update(
      s4,
      'ok',
      `name="${detail.name}", 章节数=${detail.chapters.length}, cover=${detail.cover?.slice(0, 60)}...`,
      detail,
    )
    if (detail.chapters.length === 0) {
      update(s4, 'fail', '没有章节')
      return
    }
    firstChapterId = detail.chapters[0].id
  } catch (e: any) {
    update(s4, 'fail', e?.message || String(e))
    return
  }

  // 5. 章节图片列表（关键！）
  const s5 = addStep(`5. 章节图片列表 /api/chapter/${firstChapterId}`)
  let firstImage = ''
  try {
    const info = await chapterImages(firstChapterId)
    update(
      s5,
      info.images.length > 0 ? 'ok' : 'fail',
      `scramble_id=${info.scramble_id}, 图片数=${info.images.length}`,
      {
        scramble_id: info.scramble_id,
        title: info.title,
        images_count: info.images.length,
        first_image_url: info.images[0],
        first_image_parsed: parseImageProxyUrl(info.images[0]),
      },
    )
    if (info.images.length === 0) {
      update(s5, 'fail', '图片列表为空！这就是阅读器黑屏的原因——Worker 返回的章节没有图片数据')
      return
    }
    firstImage = info.images[0]
    testImageUrl.value = firstImage
  } catch (e: any) {
    update(s5, 'fail', e?.message || String(e))
    return
  }

  // 6. 单张图片直接加载（不经解码）
  const s6 = addStep('6. 直接加载第一张图片（原图，未解码）')
  try {
    const resp = await fetch(firstImage)
    const ct = resp.headers.get('Content-Type') || ''
    const blob = await resp.blob()
    update(
      s6,
      resp.ok && ct.startsWith('image/') ? 'ok' : 'fail',
      `HTTP ${resp.status}, Content-Type=${ct}, 大小=${blob.size} 字节`,
    )
    if (!resp.ok || !ct.startsWith('image/')) {
      update(s6, 'fail', `图片代理返回的不是图片！可能是 Worker 转发禁漫 CDN 失败`)
      return
    }
  } catch (e: any) {
    update(s6, 'fail', e?.message || String(e))
    return
  }

  // 7. scramble 解码
  const s7 = addStep('7. scramble 解码测试')
  try {
    const parsed = parseImageProxyUrl(firstImage)
    if (!parsed || !parsed.aid || !parsed.scrambleId || !parsed.filename) {
      update(s7, 'fail', '图片 URL 缺少 scramble 参数，无法解码')
      return
    }
    const num = getScrambleNum(parsed.scrambleId, parsed.aid, parsed.filename)
    decodeLog.value = `aid=${parsed.aid}, scramble_id=${parsed.scrambleId}, filename=${parsed.filename}, num=${num}`
    const decoded = await decodeImage(firstImage, num)
    decodedImageUrl.value = decoded
    update(s7, 'ok', `${decodeLog.value} → 解码成功，生成 blob URL`)
  } catch (e: any) {
    update(s7, 'fail', e?.message || String(e))
  }
  } catch (e: any) {
    // 整个 run 的兜底：任何未预期的错误都显示出来
    const sErr = addStep('❌ 诊断脚本异常')
    update(sErr, 'fail', e?.message || String(e) + '\n' + (e?.stack || ''))
  }
}

onMounted(() => run())
</script>

<template>
  <div class="min-h-screen p-4" style="background: #0a0a0a; color: #e5e5e5">
    <div class="mx-auto max-w-2xl">
      <h1 class="mb-2 text-xl font-bold">PWA 诊断</h1>
      <p class="mb-4 text-xs" style="color: #6b7280">
        如果你看到这段文字，说明 DebugView 模板已渲染。如果下面没有检查项，说明 JS 报错了，请打开 F12 控制台看错误。
      </p>

      <!-- 步骤列表 -->
      <div class="space-y-3">
        <div
          v-for="(s, i) in steps"
          :key="i"
          class="rounded-lg border p-3"
          :style="{
            borderColor:
              s.status === 'ok'
                ? '#10b981'
                : s.status === 'fail'
                  ? '#ef4444'
                  : s.status === 'info'
                    ? '#3b82f6'
                    : '#6b7280',
            background: '#1a1a1a',
          }"
        >
          <div class="flex items-center gap-2">
            <span class="text-base">
              {{ s.status === 'ok' ? '✓' : s.status === 'fail' ? '✗' : s.status === 'info' ? 'ℹ' : '⏳' }}
            </span>
            <span class="font-semibold">{{ s.name }}</span>
          </div>
          <div class="mt-1 text-sm" style="color: #a3a3a3">{{ s.detail }}</div>
          <pre
            v-if="s.data !== undefined"
            class="mt-2 overflow-x-auto rounded p-2 text-xs"
            style="background: #0d0d0d; color: #d4d4d4"
          >{{ JSON.stringify(s.data, null, 2) }}</pre>
        </div>
      </div>

      <!-- 原图直显 -->
      <div v-if="testImageUrl" class="mt-6">
        <h2 class="mb-2 text-sm font-semibold">第一张原图（未解码，应该是乱的切片）</h2>
        <img
          :src="testImageUrl"
          class="w-full rounded-lg"
          style="max-height: 400px; object-fit: contain; background: #000"
          @load="testImageLoaded = true"
          @error="(e) => { testImageError = '原图加载失败'; testImageLoaded = false }"
        />
        <p v-if="testImageError" class="mt-1 text-sm" style="color: #ef4444">{{ testImageError }}</p>
      </div>

      <!-- 解码后图 -->
      <div v-if="decodedImageUrl" class="mt-6">
        <h2 class="mb-2 text-sm font-semibold">解码后图片（应该是正常顺序的漫画页）</h2>
        <img
          :src="decodedImageUrl"
          class="w-full rounded-lg"
          style="max-height: 400px; object-fit: contain; background: #000"
        />
      </div>

      <div class="h-20"></div>
    </div>
  </div>
</template>
