<script setup lang="ts">
/**
 * 阅读器图片组件：全宽自适应 + scramble 解码 + 懒加载。
 *
 * 专为阅读器设计（与 JmImage 区别：不固定宽高比，图片原始比例显示）。
 */
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { decodeImageCached, getScrambleNum, parseImageProxyUrl } from '@/api/image'

const props = defineProps<{
  src: string
}>()

const container = ref<HTMLDivElement | null>(null)
const displaySrc = ref<string>('')
const loaded = ref(false)
const error = ref(false)
const inView = ref(false)
let observer: IntersectionObserver | null = null

async function tryLoad() {
  if (!props.src || displaySrc.value) return
  const parsed = parseImageProxyUrl(props.src)
  if (parsed && parsed.aid && parsed.scrambleId && parsed.filename) {
    const num = getScrambleNum(parsed.scrambleId, parsed.aid, parsed.filename)
    displaySrc.value = await decodeImageCached(props.src, num)
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
    { rootMargin: '300px' },
  )
  observer.observe(container.value)
})

onUnmounted(() => {
  observer?.disconnect()
})
</script>

<template>
  <div ref="container" class="w-full">
    <!-- 加载占位 -->
    <div
      v-if="!loaded && !error"
      class="flex h-40 w-full items-center justify-center bg-black/40"
    >
      <div class="h-6 w-6 animate-spin rounded-full border-2 border-white/20 border-t-white/60"></div>
    </div>
    <!-- 图片 -->
    <img
      v-if="displaySrc && !error"
      :src="displaySrc"
      loading="lazy"
      decoding="async"
      class="block w-full"
      :class="loaded ? 'opacity-100' : 'opacity-0'"
      @load="onImgLoad"
      @error="onImgError"
    />
    <!-- 错误占位 -->
    <div
      v-if="error"
      class="flex h-40 w-full items-center justify-center bg-black/40 text-white/40"
    >
      <span class="text-xs">图片加载失败</span>
    </div>
  </div>
</template>
