<script setup lang="ts">
/** 顶部导航栏：标题 + 主题切换 + 搜索入口。 */
import { useRouter } from 'vue-router'
import { computed } from 'vue'
import { Search, Sun, Moon, Monitor } from 'lucide-vue-next'
import { useSettingsStore } from '@/stores/settings'
import type { ThemeMode } from '@/api/types'

const props = defineProps<{
  title?: string
  showSearch?: boolean
}>()

const settings = useSettingsStore()
const router = useRouter()

const themeIcon = computed(() => {
  if (settings.theme === 'light') return Sun
  if (settings.theme === 'dark') return Moon
  return Monitor
})

function toggleTheme() {
  const order: ThemeMode[] = ['system', 'light', 'dark']
  const idx = order.indexOf(settings.theme)
  settings.setTheme(order[(idx + 1) % order.length])
}

function goSearch() {
  router.push({ name: 'search' })
}
</script>

<template>
  <header
    class="sticky top-0 z-30 flex items-center justify-between px-4 py-3 backdrop-blur-md"
    style="
      background: rgba(26, 22, 37, 0.85);
      padding-top: max(0.75rem, var(--safe-top));
      border-bottom: 1px solid var(--border-subtle);
    "
  >
    <h1 class="font-serif text-xl font-semibold tracking-wide" style="color: var(--text-primary)">
      {{ props.title || '本子天国' }}
    </h1>
    <div class="flex items-center gap-1">
      <button
        v-if="props.showSearch !== false"
        class="rounded-full p-2 transition-colors hover:bg-white/10"
        style="color: var(--text-secondary)"
        @click="goSearch"
      >
        <Search :size="20" />
      </button>
      <button
        class="rounded-full p-2 transition-colors hover:bg-white/10"
        style="color: var(--text-secondary)"
        @click="toggleTheme"
      >
        <component :is="themeIcon" :size="20" />
      </button>
    </div>
  </header>
</template>
