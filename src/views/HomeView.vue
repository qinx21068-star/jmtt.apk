<script setup lang="ts">
/**
 * 首页：最新 / 周/月/总排行切换 + 分类筛选 + 漫画网格 + 无限滚动。
 */
import { ref, watch, computed, onMounted } from 'vue'
import { latest, ranking } from '@/api/jm'
import type { ComicBrief, RankingTime } from '@/api/types'
import { useBlockFilter } from '@/composables/useBlockFilter'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'
import ComicGrid from '@/components/ComicGrid.vue'
import LoadingGrid from '@/components/LoadingGrid.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'

type Tab = 'latest' | 'week' | 'month' | 'all'

const tabs: { key: Tab; label: string; time: RankingTime | null }[] = [
  { key: 'latest', label: '最新', time: null },
  { key: 'week', label: '周榜', time: 'week' },
  { key: 'month', label: '月榜', time: 'month' },
  { key: 'all', label: '总榜', time: 'all' },
]

const categories = [
  { slug: '', label: '全部' },
  { slug: 'doujin', label: '同人' },
  { slug: 'single', label: '单本' },
  { slug: 'short', label: '短篇' },
  { slug: 'hanman', label: '汉化' },
  { slug: 'meiman', label: '美漫' },
  { slug: 'doujin_cosplay', label: 'Cosplay' },
  { slug: '3D', label: '3D' },
]

const currentTab = ref<Tab>('latest')
const currentCategory = ref('')
const items = ref<ComicBrief[]>([])
const page = ref(1)
const total = ref<number | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const initialized = ref(false)

// 应用屏蔽词过滤
const filteredItems = useBlockFilter(items)
const hasMore = computed(() => {
  if (total.value === null) return true
  return items.value.length < total.value
})

async function loadPage(reset = false) {
  if (loading.value) return
  if (!reset && !hasMore.value) return
  loading.value = true
  error.value = null
  try {
    const targetPage = reset ? 1 : page.value
    const cat = currentCategory.value
    const tab = tabs.find((t) => t.key === currentTab.value)!
    let result
    if (tab.key === 'latest') {
      result = await latest(targetPage, cat)
    } else {
      result = await ranking(tab.time!, cat, targetPage)
    }
    if (reset) {
      items.value = result.items
    } else {
      items.value = [...items.value, ...result.items]
    }
    page.value = targetPage + 1
    total.value = result.total
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
    initialized.value = true
  }
}

const { sentinel, loading: scrollLoading } = useInfiniteScroll(() => loadPage(false))

function selectTab(t: Tab) {
  if (currentTab.value === t) return
  currentTab.value = t
}
function selectCategory(slug: string) {
  if (currentCategory.value === slug) return
  currentCategory.value = slug
}

// 切换 Tab 或分类时重新加载
watch([currentTab, currentCategory], () => {
  items.value = []
  page.value = 1
  total.value = null
  loadPage(true)
})

onMounted(() => {
  loadPage(true)
})
</script>

<template>
  <div class="px-3 pt-3">
    <!-- Tab 切换 -->
    <div class="mb-3 flex gap-2 overflow-x-auto scrollbar-hidden">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="flex-shrink-0 rounded-full px-4 py-1.5 text-sm transition-all"
        :style="
          currentTab === tab.key
            ? {
                background: 'linear-gradient(135deg, #d946ef 0%, #c026d3 100%)',
                color: 'white',
              }
            : {
                background: 'var(--bg-card)',
                color: 'var(--text-secondary)',
              }
        "
        @click="selectTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- 分类筛选 -->
    <div class="mb-3 flex gap-2 overflow-x-auto scrollbar-hidden">
      <button
        v-for="cat in categories"
        :key="cat.slug || 'all'"
        class="flex-shrink-0 rounded-full border px-3 py-1 text-xs transition-all"
        :style="
          currentCategory === cat.slug
            ? {
                borderColor: 'var(--accent)',
                background: 'var(--accent-soft)',
                color: 'var(--accent)',
              }
            : {
                borderColor: 'var(--border-subtle)',
                color: 'var(--text-muted)',
              }
        "
        @click="selectCategory(cat.slug)"
      >
        {{ cat.label }}
      </button>
    </div>

    <!-- 内容区 -->
    <ComicGrid v-if="filteredItems.length > 0" :comics="filteredItems" />

    <!-- 加载骨架（首次加载） -->
    <LoadingGrid v-if="!initialized && loading" :count="12" />

    <!-- 错误 -->
    <ErrorState
      v-else-if="error && items.length === 0"
      :message="error"
      @retry="loadPage(true)"
    />

    <!-- 空状态 -->
    <EmptyState
      v-else-if="initialized && !loading && filteredItems.length === 0"
      text="暂无数据"
    />

    <!-- 无限滚动触发器 -->
    <div
      v-if="initialized && filteredItems.length > 0"
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
