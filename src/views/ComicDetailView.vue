<script setup lang="ts">
/**
 * 漫画详情页：封面 + 信息 + 章节列表 + 操作按钮。
 */
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Heart, BookOpen, ChevronRight } from 'lucide-vue-next'
import { comicDetail } from '@/api/jm'
import type { ComicDetail } from '@/api/types'
import { useFavoritesStore } from '@/stores/favorites'
import { getLastChapterOfComic, saveHistory } from '@/db/repositories/history'
import JmImage from '@/components/JmImage.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingDetail from '@/components/LoadingDetail.vue'

const route = useRoute()
const router = useRouter()
const favorites = useFavoritesStore()

const comic = ref<ComicDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const lastChapterId = ref<string | null>(null)

const comicId = computed(() => String(route.params.id || ''))
const isFavorited = computed(() =>
  comic.value ? favorites.isFavorited(comic.value.id) : false,
)

async function loadDetail() {
  loading.value = true
  error.value = null
  try {
    comic.value = await comicDetail(comicId.value)
    // 查找阅读历史
    const last = await getLastChapterOfComic(comicId.value)
    lastChapterId.value = last?.chapter_id || null
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function toggleFavorite() {
  if (!comic.value) return
  await favorites.toggleFavorite({
    id: comic.value.id,
    name: comic.value.name,
    author: comic.value.author,
    tags: comic.value.tags,
    cover: comic.value.cover,
    likes: null,
    views: null,
  })
}

async function startReading(chapterId?: string) {
  if (!comic.value) return
  const targetId = chapterId || comic.value.chapters[0]?.id
  if (!targetId) return
  // 记录历史（首次进入时记录，滚动位置由阅读器更新）
  await saveHistory({
    chapter_id: targetId,
    comic_id: comic.value.id,
    comic_name: comic.value.name,
    comic_cover: comic.value.cover,
    page: 0,
    scroll: 0,
  })
  router.push({ name: 'reader', params: { chapterId: targetId } })
}

function back() {
  if (window.history.length > 1) router.back()
  else router.push({ name: 'home' })
}

onMounted(loadDetail)
</script>

<template>
  <div class="min-h-screen pb-20">
    <!-- 顶部返回栏（悬浮在封面上） -->
    <div
      class="sticky top-0 z-20 flex items-center justify-between px-3 py-2 backdrop-blur-md"
      style="
        background: rgba(26, 22, 37, 0.6);
        padding-top: max(0.5rem, var(--safe-top));
      "
    >
      <button
        class="rounded-full p-2"
        style="color: var(--text-primary)"
        @click="back"
      >
        <ArrowLeft :size="22" />
      </button>
    </div>

    <ErrorState v-if="error" :message="error" @retry="loadDetail" />

    <LoadingDetail v-else-if="loading || !comic" />

    <template v-else>
      <!-- 头部：模糊背景 + 封面 + 信息 -->
      <div class="relative">
        <!-- 模糊封面背景 -->
        <div class="absolute inset-0 overflow-hidden">
          <JmImage
            v-if="comic.cover"
            :src="comic.cover"
            :alt="comic.name"
            :rounded="0"
            class="h-full w-full object-cover opacity-30 blur-2xl"
          />
          <div
            class="absolute inset-0"
            style="background: linear-gradient(to bottom, transparent 0%, var(--bg-base) 100%)"
          ></div>
        </div>

        <!-- 前景内容 -->
        <div class="relative flex gap-4 px-4 pb-4 pt-6">
          <div class="flex-shrink-0">
            <JmImage
              v-if="comic.cover"
              :src="comic.cover"
              :alt="comic.name"
              :ratio="3 / 4"
              :rounded="8"
              class="w-28 shadow-card"
            />
          </div>
          <div class="flex flex-1 flex-col justify-end">
            <h2 class="font-serif text-lg font-semibold leading-tight" style="color: var(--text-primary)">
              {{ comic.name }}
            </h2>
            <p
              v-if="comic.author"
              class="mt-1 text-xs"
              style="color: var(--text-secondary)"
            >
              作者：{{ comic.author }}
            </p>
            <div class="mt-2 flex gap-2 text-[10px]" style="color: var(--text-muted)">
              <span v-if="comic.likes">喜欢 {{ comic.likes }}</span>
              <span v-if="comic.views">· 观看 {{ comic.views }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 标签 -->
      <div v-if="comic.tags.length > 0" class="mb-3 flex flex-wrap gap-1.5 px-4">
        <span
          v-for="tag in comic.tags"
          :key="tag"
          class="rounded-full px-2.5 py-0.5 text-[11px]"
          style="background: var(--accent-soft); color: var(--accent)"
        >
          {{ tag }}
        </span>
      </div>

      <!-- 简介 -->
      <div v-if="comic.description" class="mb-4 px-4">
        <p class="text-xs leading-relaxed" style="color: var(--text-secondary)">
          {{ comic.description }}
        </p>
      </div>

      <!-- 操作按钮 -->
      <div class="mb-4 flex gap-2 px-4">
        <button
          class="flex flex-1 items-center justify-center gap-1.5 rounded-xl py-2.5 text-sm transition-all active:scale-95"
          :style="
            isFavorited
              ? { background: 'var(--accent-soft)', color: 'var(--accent)' }
              : { border: '1px solid var(--border-subtle)', color: 'var(--text-secondary)' }
          "
          @click="toggleFavorite"
        >
          <Heart :size="16" :fill="isFavorited ? 'currentColor' : 'none'" />
          {{ isFavorited ? '已收藏' : '收藏' }}
        </button>
        <button
          v-if="lastChapterId"
          class="flex flex-1 items-center justify-center gap-1.5 rounded-xl py-2.5 text-sm text-white transition-all active:scale-95"
          style="background: linear-gradient(135deg, #d946ef 0%, #c026d3 100%)"
          @click="startReading(lastChapterId!)"
        >
          <BookOpen :size="16" />
          继续阅读
        </button>
        <button
          v-else
          class="flex flex-1 items-center justify-center gap-1.5 rounded-xl py-2.5 text-sm text-white transition-all active:scale-95"
          style="background: linear-gradient(135deg, #d946ef 0%, #c026d3 100%)"
          @click="startReading()"
        >
          <BookOpen :size="16" />
          开始阅读
        </button>
      </div>

      <!-- 章节列表 -->
      <div class="px-4 pb-6">
        <h3 class="mb-2 font-serif text-base font-semibold" style="color: var(--text-primary)">
          章节列表
          <span class="ml-1 text-xs font-normal" style="color: var(--text-muted)">
            共 {{ comic.chapters.length }} 章
          </span>
        </h3>
        <div class="space-y-1">
          <button
            v-for="ch in comic.chapters"
            :key="ch.id"
            class="flex w-full items-center justify-between rounded-lg px-3 py-2.5 text-left transition-colors hover:bg-white/5"
            :style="{
              background:
                lastChapterId === ch.id ? 'var(--accent-soft)' : 'var(--bg-card)',
            }"
            @click="startReading(ch.id)"
          >
            <div class="min-w-0 flex-1">
              <p class="truncate text-sm" style="color: var(--text-primary)">
                {{ ch.title }}
              </p>
              <p v-if="lastChapterId === ch.id" class="text-[10px]" style="color: var(--accent)">
                上次阅读
              </p>
            </div>
            <ChevronRight :size="16" style="color: var(--text-muted)" />
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
