<script setup lang="ts">
/**
 * 搜索页：关键词搜索 + 排序 + 搜索历史。
 */
import { ref, onMounted, computed } from 'vue'
import { search } from '@/api/jm'
import type { ComicBrief, SearchOrder } from '@/api/types'
import { useBlockFilter } from '@/composables/useBlockFilter'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'
import ComicGrid from '@/components/ComicGrid.vue'
import LoadingGrid from '@/components/LoadingGrid.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { Search, X, Clock } from 'lucide-vue-next'
import {
  listSearchHistory,
  addSearchHistory,
  removeSearchHistory,
  clearSearchHistory,
} from '@/db/repositories/searchHistory'

const keyword = ref('')
const submittedKeyword = ref('')
const order = ref<SearchOrder>('latest')
const items = ref<ComicBrief[]>([])
const page = ref(1)
const total = ref<number | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const initialized = ref(false)
const history = ref<string[]>([])

const orders: { key: SearchOrder; label: string }[] = [
  { key: 'latest', label: '最新' },
  { key: 'views', label: '观看' },
  { key: 'likes', label: '评论' },
  { key: 'picture', label: '图片数' },
]

const filteredItems = useBlockFilter(items)
const hasMore = computed(() => {
  if (total.value === null) return true
  return items.value.length < total.value
})

async function doSearch(kw?: string, reset = true) {
  const q = (kw ?? keyword.value).trim()
  if (!q) return
  if (loading.value) return
  keyword.value = q
  submittedKeyword.value = q
  loading.value = true
  error.value = null
  initialized.value = false
  if (reset) {
    items.value = []
    page.value = 1
    total.value = null
  }
  try {
    const result = await search(q, page.value, order.value, 'all')
    if (reset) {
      items.value = result.items
    } else {
      items.value = [...items.value, ...result.items]
    }
    page.value += 1
    total.value = result.total
    await addSearchHistory(q)
    await loadHistory()
  } catch (e: any) {
    error.value = e?.message || '搜索失败'
  } finally {
    loading.value = false
    initialized.value = true
  }
}

const { sentinel, loading: scrollLoading } = useInfiniteScroll(() => loadMore())

async function loadMore() {
  if (!submittedKeyword.value) return
  if (loading.value || !hasMore.value) return
  page.value = page.value + 1
  await doSearch(submittedKeyword.value, false)
}

function selectOrder(o: SearchOrder) {
  if (order.value === o) return
  order.value = o
  if (submittedKeyword.value) doSearch()
}

async function loadHistory() {
  history.value = await listSearchHistory()
}

async function useHistory(kw: string) {
  await doSearch(kw)
}

async function deleteHistory(kw: string, ev: Event) {
  ev.stopPropagation()
  await removeSearchHistory(kw)
  await loadHistory()
}

async function clearAllHistory() {
  await clearSearchHistory()
  await loadHistory()
}

onMounted(loadHistory)
</script>

<template>
  <div class="px-3 pt-3">
    <!-- 搜索框 -->
    <div class="mb-3 flex gap-2">
      <div class="relative flex-1">
        <Search
          :size="16"
          class="absolute left-3 top-1/2 -translate-y-1/2"
          style="color: var(--text-muted)"
        />
        <input
          v-model="keyword"
          type="text"
          placeholder="搜索漫画 / 作者 / 本子号"
          class="w-full rounded-full border py-2 pl-9 pr-4 text-sm outline-none transition-colors"
          :style="{
            background: 'var(--bg-card)',
            borderColor: 'var(--border-subtle)',
            color: 'var(--text-primary)',
          }"
          @keydown.enter="doSearch()"
        />
      </div>
      <button
        class="rounded-full px-4 text-sm text-white"
        style="background: linear-gradient(135deg, #d946ef 0%, #c026d3 100%)"
        @click="doSearch()"
      >
        搜索
      </button>
    </div>

    <!-- 排序选项（仅已搜索时显示） -->
    <div v-if="submittedKeyword" class="mb-3 flex gap-2 overflow-x-auto scrollbar-hidden">
      <button
        v-for="o in orders"
        :key="o.key"
        class="flex-shrink-0 rounded-full px-3 py-1 text-xs transition-all"
        :style="
          order === o.key
            ? { background: 'var(--accent-soft)', color: 'var(--accent)' }
            : { background: 'var(--bg-card)', color: 'var(--text-muted)' }
        "
        @click="selectOrder(o.key)"
      >
        {{ o.label }}
      </button>
    </div>

    <!-- 搜索历史（未搜索时显示） -->
    <div v-if="!submittedKeyword && history.length > 0" class="mb-4">
      <div class="mb-2 flex items-center justify-between">
        <div class="flex items-center gap-1 text-xs" style="color: var(--text-muted)">
          <Clock :size="12" />
          <span>搜索历史</span>
        </div>
        <button
          class="text-xs"
          style="color: var(--text-muted)"
          @click="clearAllHistory"
        >
          清空
        </button>
      </div>
      <div class="flex flex-wrap gap-2">
        <div
          v-for="kw in history"
          :key="kw"
          class="group flex items-center gap-1 rounded-full border px-3 py-1 text-xs transition-colors"
          :style="{
            borderColor: 'var(--border-subtle)',
            color: 'var(--text-secondary)',
          }"
          @click="useHistory(kw)"
        >
          <span>{{ kw }}</span>
          <button
            class="opacity-50 transition-opacity group-hover:opacity-100"
            @click="deleteHistory(kw, $event)"
          >
            <X :size="10" />
          </button>
        </div>
      </div>
    </div>

    <!-- 搜索结果 -->
    <ComicGrid v-if="filteredItems.length > 0" :comics="filteredItems" />

    <LoadingGrid v-if="!initialized && loading" :count="12" />

    <ErrorState
      v-else-if="error && items.length === 0"
      :message="error"
      @retry="doSearch()"
    />

    <EmptyState
      v-else-if="submittedKeyword && initialized && !loading && filteredItems.length === 0"
      text="没有找到相关漫画"
    />

    <!-- 无限滚动触发器 -->
    <div
      v-if="submittedKeyword && filteredItems.length > 0"
      ref="sentinel"
      class="flex items-center justify-center py-4 text-xs"
      style="color: var(--text-muted)"
    >
      <span v-if="scrollLoading">加载中…</span>
      <span v-else-if="!hasMore">— 到底了 —</span>
      <span v-else>&nbsp;</span>
    </div>
  </div>
</template>
