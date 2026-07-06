<script setup lang="ts">
/**
 * 阅读器：全屏图片浏览 + 章节切换 + 进度记忆。
 *
 * - 上下滚动浏览（默认）
 * - 图片懒加载 + scramble 解码（JmImage 组件处理）
 * - 点击屏幕中央切换控件显示
 * - 自动记录滚动位置到 IndexedDB
 * - 章节切换：上一章/下一章
 */
import { ref, onMounted, onUnmounted, computed, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ChevronUp, ChevronDown, List, X } from 'lucide-vue-next'
import { chapterImages } from '@/api/jm'
import type { ChapterImages, ComicDetail } from '@/api/types'
import { comicDetail } from '@/api/jm'
import { getHistory, saveHistory, getLastChapterOfComic } from '@/db/repositories/history'
import JmImage from '@/components/JmImage.vue'
import ErrorState from '@/components/ErrorState.vue'

const route = useRoute()
const router = useRouter()

const chapterId = computed(() => String(route.params.chapterId || ''))
const images = ref<string[]>([])
const chapterInfo = ref<ChapterImages | null>(null)
const comic = ref<ComicDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const showControls = ref(false)
const showChapterList = ref(false)
const scrollContainer = ref<HTMLElement | null>(null)
let saveTimer: number | null = null

const currentIndex = ref(0)
const totalPages = computed(() => images.value.length)

async function loadChapter(id: string) {
  loading.value = true
  error.value = null
  images.value = []
  chapterInfo.value = null
  try {
    const info = await chapterImages(id)
    chapterInfo.value = info
    images.value = info.images
    // 同时加载漫画详情（用于章节切换）
    if (!comic.value) {
      // 尝试从历史记录找到 comic_id
      const history = await getHistory(id)
      if (history) {
        comic.value = await comicDetail(history.comic_id)
      }
    }
    // 恢复阅读进度
    const history = await getHistory(id)
    await nextTick()
    if (history && scrollContainer.value) {
      requestAnimationFrame(() => {
        if (scrollContainer.value) {
          scrollContainer.value.scrollTop = history.scroll
        }
      })
    } else if (scrollContainer.value) {
      scrollContainer.value.scrollTop = 0
    }
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function toggleControls() {
  showControls.value = !showControls.value
}

function back() {
  if (comic.value) {
    router.push({ name: 'comic', params: { id: comic.value.id } })
  } else {
    router.back()
  }
}

async function goPrevChapter() {
  if (!comic.value) return
  const chapters = comic.value.chapters
  const idx = chapters.findIndex((c) => c.id === chapterId.value)
  if (idx > 0) {
    const prevId = chapters[idx - 1].id
    router.replace({ name: 'reader', params: { chapterId: prevId } })
    await loadChapter(prevId)
  }
}

async function goNextChapter() {
  if (!comic.value) return
  const chapters = comic.value.chapters
  const idx = chapters.findIndex((c) => c.id === chapterId.value)
  if (idx >= 0 && idx < chapters.length - 1) {
    const nextId = chapters[idx + 1].id
    router.replace({ name: 'reader', params: { chapterId: nextId } })
    await loadChapter(nextId)
  }
}

function selectChapter(id: string) {
  showChapterList.value = false
  router.replace({ name: 'reader', params: { chapterId: id } })
  loadChapter(id)
}

/** 滚动事件：记录当前位置 + 计算当前页码。 */
function onScroll() {
  if (!scrollContainer.value) return
  const el = scrollContainer.value
  // 当前页码估算
  const ratio = el.scrollTop / (el.scrollHeight - el.clientHeight || 1)
  currentIndex.value = Math.floor(ratio * totalPages.value)
  // 防抖保存历史
  if (saveTimer) window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => {
    saveProgress()
  }, 500)
}

async function saveProgress() {
  if (!comic.value || !scrollContainer.value || !chapterInfo.value) return
  await saveHistory({
    chapter_id: chapterId.value,
    comic_id: comic.value.id,
    comic_name: comic.value.name,
    comic_cover: comic.value.cover,
    page: currentIndex.value,
    scroll: scrollContainer.value.scrollTop,
  })
}

onMounted(() => loadChapter(chapterId.value))

onUnmounted(() => {
  if (saveTimer) window.clearTimeout(saveTimer)
  saveProgress()
})
</script>

<template>
  <div
    class="fixed inset-0 z-50 flex flex-col"
    style="background: #000; padding-top: var(--safe-top); padding-bottom: var(--safe-bottom)"
  >
    <!-- 顶部控件栏（点击切换显示） -->
    <transition name="slide-down">
      <div
        v-if="showControls"
        class="absolute left-0 right-0 top-0 z-20 flex items-center justify-between px-3 py-2 backdrop-blur-md"
        style="background: rgba(0, 0, 0, 0.7); padding-top: max(0.5rem, var(--safe-top))"
      >
        <button class="rounded-full p-2 text-white" @click="back">
          <ArrowLeft :size="22" />
        </button>
        <div class="flex-1 truncate px-3 text-center text-xs text-white/80">
          {{ comic?.name || '加载中' }}
          <span v-if="chapterInfo?.title"> · {{ chapterInfo.title }}</span>
        </div>
        <button
          v-if="comic && comic.chapters.length > 1"
          class="rounded-full p-2 text-white"
          @click="showChapterList = !showChapterList"
        >
          <List :size="22" />
        </button>
      </div>
    </transition>

    <!-- 错误状态 -->
    <ErrorState v-if="error" :message="error" @retry="loadChapter(chapterId)" />

    <!-- 加载状态 -->
    <div v-else-if="loading" class="flex flex-1 items-center justify-center">
      <div class="text-sm text-white/60">加载中…</div>
    </div>

    <!-- 图片列表 -->
    <div
      v-else
      ref="scrollContainer"
      class="flex-1 overflow-y-auto"
      @scroll.passive="onScroll"
      @click.self="toggleControls"
    >
      <div class="mx-auto flex max-w-3xl flex-col items-center">
        <img
          v-for="(img, idx) in images"
          :key="idx"
          :src="img"
          loading="lazy"
          decoding="async"
          class="block w-full"
          @click="toggleControls"
          @error="(e) => (e.target as HTMLImageElement).style.opacity = '0.2'"
        />
        <!-- 章节末尾操作 -->
        <div class="flex w-full items-center justify-center gap-3 py-6 text-white/80">
          <button
            v-if="comic && comic.chapters.findIndex((c) => c.id === chapterId) > 0"
            class="rounded-full border border-white/30 px-4 py-2 text-xs"
            @click="goPrevChapter"
          >
            <ChevronUp :size="14" class="inline" />
            上一章
          </button>
          <button
            v-if="
              comic &&
              comic.chapters.findIndex((c) => c.id === chapterId) <
                comic.chapters.length - 1
            "
            class="rounded-full bg-white/20 px-4 py-2 text-xs"
            @click="goNextChapter"
          >
            下一章
            <ChevronDown :size="14" class="inline" />
          </button>
        </div>
      </div>
    </div>

    <!-- 底部页码指示器 -->
    <transition name="slide-up">
      <div
        v-if="showControls && !loading && !error"
        class="absolute bottom-0 left-0 right-0 z-20 flex items-center justify-center px-3 py-2 backdrop-blur-md"
        style="background: rgba(0, 0, 0, 0.7); padding-bottom: max(0.5rem, var(--safe-bottom))"
      >
        <span class="text-xs text-white/80">
          {{ Math.min(currentIndex + 1, totalPages) }} / {{ totalPages }}
        </span>
      </div>
    </transition>

    <!-- 章节列表抽屉 -->
    <transition name="fade">
      <div
        v-if="showChapterList"
        class="absolute inset-0 z-30 flex justify-end"
        style="background: rgba(0, 0, 0, 0.5)"
        @click="showChapterList = false"
      >
        <div
          class="h-full w-72 overflow-y-auto px-4 py-4"
          style="background: var(--bg-elevated); padding-top: max(1rem, var(--safe-top))"
          @click.stop
        >
          <div class="mb-3 flex items-center justify-between">
            <h3 class="font-serif text-base text-white">章节</h3>
            <button class="text-white/70" @click="showChapterList = false">
              <X :size="18" />
            </button>
          </div>
          <div class="space-y-1">
            <button
              v-for="ch in comic?.chapters || []"
              :key="ch.id"
              class="block w-full rounded-lg px-3 py-2 text-left text-sm transition-colors"
              :style="
                ch.id === chapterId
                  ? { background: 'var(--accent-soft)', color: 'var(--accent)' }
                  : { color: 'var(--text-secondary)' }
              "
              @click="selectChapter(ch.id)"
            >
              {{ ch.title }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.slide-down-enter-active,
.slide-down-leave-active {
  transition: transform 0.2s ease, opacity 0.2s ease;
}
.slide-down-enter-from,
.slide-down-leave-to {
  transform: translateY(-100%);
  opacity: 0;
}
.slide-up-enter-active,
.slide-up-leave-active {
  transition: transform 0.2s ease, opacity 0.2s ease;
}
.slide-up-enter-from,
.slide-up-leave-to {
  transform: translateY(100%);
  opacity: 0;
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
