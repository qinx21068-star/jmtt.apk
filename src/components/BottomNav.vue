<script setup lang="ts">
/** 底部 Tab 导航：首页/搜索/收藏/设置。 */
import { useRoute, useRouter } from 'vue-router'
import { computed } from 'vue'
import { Home, Search, Heart, Settings } from 'lucide-vue-next'

const router = useRouter()
const route = useRoute()

const tabs = [
  { name: 'home', label: '首页', icon: Home, path: '/' },
  { name: 'search', label: '搜索', icon: Search, path: '/search' },
  { name: 'favorites', label: '收藏', icon: Heart, path: '/favorites' },
  { name: 'settings', label: '设置', icon: Settings, path: '/settings' },
] as const

const activeName = computed(() => {
  // 当前路由匹配的 tab（comic/reader 等子页归到首页）
  const name = route.name as string
  if (name === 'comic' || name === 'reader') return 'home'
  return name
})

function go(name: string, path: string) {
  if (route.path !== path) router.push(path)
}
</script>

<template>
  <nav
    class="fixed bottom-0 left-0 right-0 z-30 flex items-center justify-around backdrop-blur-md"
    style="
      background: rgba(20, 16, 30, 0.92);
      padding-bottom: var(--safe-bottom);
      border-top: 1px solid var(--border-subtle);
    "
  >
    <button
      v-for="tab in tabs"
      :key="tab.name"
      class="flex flex-1 flex-col items-center gap-0.5 py-2 transition-colors"
      :style="{ color: activeName === tab.name ? 'var(--accent)' : 'var(--text-muted)' }"
      @click="go(tab.name, tab.path)"
    >
      <component :is="tab.icon" :size="22" :stroke-width="activeName === tab.name ? 2.5 : 2" />
      <span class="text-[10px]">{{ tab.label }}</span>
    </button>
  </nav>
</template>
