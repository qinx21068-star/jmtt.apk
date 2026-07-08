<script setup lang="ts">
/**
 * 阅读器图片组件：全宽自适应 + scramble 解码。
 *
 * 简化版：不用 IntersectionObserver（阅读器滚动容器不是视口，IO 用视口做 root
 * 会失效，导致 tryLoad 永不触发 → 黑屏）。阅读器一次只展示一个章节，图片数有限，
 * 直接加载即可。
 */
import { ref, watch } from 'vue'
import { decodeImageCached, getScrambleNum, parseImageProxyUrl } from '@/api/image'

const props = defineProps<{
  src: string
}>()

const displaySrc = ref<string>('')
const loaded = ref(false)
const error = ref(false)

async function tryLoad() {
  if (!props.src || displaySrc.value) return
  const parsed = parseImageProxyUrl(props.src)
  if (parsed && parsed.aid && parsed.scrambleId && parsed.filename) {
    const num = getScrambleNum(parsed.scrambleId, parsed.aid, parsed.filename)
    console.log('[ReaderImage] scramble decode', {
      aid: parsed.aid,
      scrambleId: parsed.scrambleId,
      filename: parsed.filename,
      num,
      url: props.src,
    })
    try {
      displaySrc.value = await decodeImageCached(props.src, num)
    } catch (e) {
      // 解码失败时 fallback 到原图（虽然会是乱的 scramble 图），避免黑屏
      console.warn('[ReaderImage] decode failed, fallback to raw url:', e)
      displaySrc.value = props.src
    }
  } else {
    console.log('[ReaderImage] no scramble params, direct load', props.src)
    displaySrc.value = props.src
  }
}

function onImgLoad() {
  console.log('[ReaderImage] img loaded', displaySrc.value.slice(0, 80))
  loaded.value = true
}
function onImgError() {
  console.warn('[ReaderImage] img error', displaySrc.value.slice(0, 80))
  error.value = true
  loaded.value = true
}

// src 变化时重置并重新加载
watch(
  () => props.src,
  () => {
    displaySrc.value = ''
    loaded.value = false
    error.value = false
    tryLoad()
  },
  { immediate: true },
)
</script>

<template>
  <div class="w-full">
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
