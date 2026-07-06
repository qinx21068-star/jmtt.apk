<script setup lang="ts">
/**
 * 禁漫图片组件：懒加载 + scramble 解码。
 *
 * 用法：
 *   <JmImage :src="coverUrl" alt="..." :ratio="3/4" />
 *
 * 若 src 是 Worker 图片代理 URL 且含 scramble 参数，自动解码后显示。
 * 用 IntersectionObserver 懒加载，仅加载可视区域 + 1 屏预加载。
 */
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { decodeImageCached, getScrambleNum, parseImageProxyUrl } from '@/api/image'

const props = withDefaults(
  defineProps<{
    src: string | null
    alt?: string
    /** 宽高比（如 3/4 用于封面），不传则按图片原始尺寸 */
    ratio?: number
    /** 圆角像素 */
    rounded?: number
  }>(),
  {
    alt: '',
    ratio: 0,
    rounded: 8,
  },
)

const container = ref<HTMLDivElement | null>(null)
const imgEl = ref<HTMLImageElement | null>(null)
const loaded = ref(false)
const error = ref(false)
const displaySrc = ref<string>('')
const inView = ref(false)
let observer: IntersectionObserver | null = null

async function tryLoad() {
  if (!props.src || displaySrc.value) return
  // 检查是否需要 scramble 解码
  const parsed = parseImageProxyUrl(props.src)
  if (parsed && parsed.aid && parsed.scrambleId && parsed.filename) {
    const num = getScrambleNum(parsed.scrambleId, parsed.aid, parsed.filename)
    try {
      displaySrc.value = await decodeImageCached(props.src, num)
    } catch (e) {
      console.warn('[JmImage] decode failed, fallback to raw url:', e)
      displaySrc.value = props.src
    }
  } else {
    displaySrc.value = props.src
  }
}

function onImgLoad() {
  loaded.value = true
}
function onImgError() {
  error.value = true
  loaded.value = true
}

watch(
  () => props.src,
  () => {
    displaySrc.value = ''
    loaded.value = false
    error.value = false
    if (inView.value) tryLoad()
  },
)

onMounted(() => {
  if (!container.value) return
  observer = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (e.isIntersecting) {
          inView.value = true
          tryLoad()
          observer?.disconnect()
        }
      }
    },
    { rootMargin: '200px' },
  )
  observer.observe(container.value)
})

onUnmounted(() => {
  observer?.disconnect()
})
</script>

<template>
  <div
    ref="container"
    class="relative overflow-hidden bg-ink-800"
    :style="{
      borderRadius: `${rounded}px`,
      aspectRatio: ratio ? String(ratio) : undefined,
    }"
  >
    <!-- 加载中骨架 -->
    <div
      v-if="!loaded && !error"
      class="absolute inset-0 animate-pulse bg-ink-700/50"
    ></div>

    <!-- 占位图标（错误或无图） -->
    <div
      v-if="error || !src"
      class="absolute inset-0 flex items-center justify-center text-ink-400"
    >
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
        <path
          d="M21 19V5a2 2 0 00-2-2H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2zM8.5 11l1.5 2 2-3 3.5 5H6l2.5-4z"
          fill="currentColor"
          opacity="0.5"
        />
      </svg>
    </div>

    <img
      v-if="displaySrc && !error"
      ref="imgEl"
      :src="displaySrc"
      :alt="alt"
      loading="lazy"
      decoding="async"
      class="h-full w-full object-cover transition-opacity duration-300"
      :class="loaded ? 'opacity-100' : 'opacity-0'"
      @load="onImgLoad"
      @error="onImgError"
    />
  </div>
</template>
