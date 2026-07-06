<script setup lang="ts">
/**
 * 收藏页：本地收藏（IndexedDB）+ 服务端收藏（API）Tab 切换。
 *
 * - 本地收藏：始终可用，无需登录，可在详情页取消收藏
 * - 服务端收藏：需登录禁漫账号，分页加载
 */
import { ref, computed, onMounted } from 'vue'
import { useFavoritesStore } from '@/stores/favorites'
import { favorites as apiFavorites, login, logout } from '@/api/jm'
import type { ComicBrief } from '@/api/types'
import ComicGrid from '@/components/ComicGrid.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingGrid from '@/components/LoadingGrid.vue'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'
import { LogOut, LogIn, Heart } from 'lucide-vue-next'

type Tab = 'local' | 'server'

const tab = ref<Tab>('local')

const tabs: { key: Tab; label: string }[] = [
  { key: 'local', label: '本地收藏' },
  { key: 'server', label: '服务端收藏' },
]

// ---- 本地收藏 ----
const favoritesStore = useFavoritesStore()
const localItems = computed(() => favoritesStore.favorites)

// ---- 服务端收藏 ----
const isLoggedIn = ref(false)
const loginLoading = ref(false)
const loginError = ref<string | null>(null)
const username = ref('')
const password = ref('')

const serverItems = ref<ComicBrief[]>([])
const serverPage = ref(1)
const serverTotal = ref<number | null>(null)
const serverLoading = ref(false)
const serverError = ref<string | null>(null)
const serverInitialized = ref(false)

const serverHasMore = computed(() => {
  if (serverTotal.value === null) return true
  return serverItems.value.length < serverTotal.value
})

async function doLogin() {
  if (!username.value || !password.value) return
  loginLoading.value = true
  loginError.value = null
  try {
    await login(username.value, password.value)
    isLoggedIn.value = true
    await loadServerFavorites(true)
  } catch (e: any) {
    loginError.value = e?.message || '登录失败'
  } finally {
    loginLoading.value = false
  }
}

async function doLogout() {
  try {
    await logout()
  } catch {
    // 忽略登出错误
  }
  isLoggedIn.value = false
  username.value = ''
  password.value = ''
  serverItems.value = []
  serverPage.value = 1
  serverTotal.value = null
  serverInitialized.value = false
}

async function loadServerFavorites(reset = false) {
  if (serverLoading.value) return
  if (!reset && !serverHasMore.value) return
  serverLoading.value = true
  serverError.value = null
  try {
    const targetPage = reset ? 1 : serverPage.value
    const result = await apiFavorites(targetPage)
    serverItems.value = reset ? result.items : [...serverItems.value, ...result.items]
    serverPage.value = targetPage + 1
    serverTotal.value = result.total
  } catch (e: any) {
    serverError.value = e?.message || '加载失败'
  } finally {
    serverLoading.value = false
    serverInitialized.value = true
  }
}

const { sentinel: serverSentinel, loading: serverScrollLoading } = useInfiniteScroll(
  () => loadServerFavorites(false),
)

onMounted(async () => {
  // 首次进入确保本地收藏已加载
  if (!favoritesStore.loaded) {
    await favoritesStore.load()
  }
})
</script>

<template>
  <div class="px-3 pt-3">
    <!-- Tab 切换 -->
    <div class="mb-3 flex gap-2">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="flex-1 rounded-full py-1.5 text-sm transition-all"
        :style="
          tab === t.key
            ? {
                background: 'linear-gradient(135deg, #d946ef 0%, #c026d3 100%)',
                color: 'white',
              }
            : { background: 'var(--bg-card)', color: 'var(--text-secondary)' }
        "
        @click="tab = t.key"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- 本地收藏 -->
    <template v-if="tab === 'local'">
      <ComicGrid v-if="localItems.length > 0" :comics="localItems" />

      <EmptyState
        v-else
        icon="💔"
        text="还没有收藏，去首页找找喜欢的吧"
      />
    </template>

    <!-- 服务端收藏 -->
    <template v-else>
      <!-- 未登录：登录表单 -->
      <div v-if="!isLoggedIn" class="card mx-auto mt-6 max-w-md p-5">
        <div class="mb-4 flex flex-col items-center text-center">
          <div
            class="mb-2 flex h-12 w-12 items-center justify-center rounded-full"
            style="background: var(--accent-soft); color: var(--accent)"
          >
            <LogIn :size="22" />
          </div>
          <h3 class="font-serif text-base font-semibold" style="color: var(--text-primary)">
            登录禁漫账号
          </h3>
          <p class="mt-1 text-xs" style="color: var(--text-muted)">
            登录后可同步服务端收藏
          </p>
        </div>

        <div class="space-y-3">
          <input
            v-model="username"
            type="text"
            placeholder="用户名"
            class="w-full rounded-lg border px-3 py-2.5 text-sm outline-none"
            :style="{
              background: 'var(--bg-card)',
              borderColor: 'var(--border-subtle)',
              color: 'var(--text-primary)',
            }"
          />
          <input
            v-model="password"
            type="password"
            placeholder="密码"
            class="w-full rounded-lg border px-3 py-2.5 text-sm outline-none"
            :style="{
              background: 'var(--bg-card)',
              borderColor: 'var(--border-subtle)',
              color: 'var(--text-primary)',
            }"
            @keydown.enter="doLogin"
          />
          <p v-if="loginError" class="text-xs" style="color: #ef4444">
            {{ loginError }}
          </p>
          <button
            class="w-full rounded-lg py-2.5 text-sm text-white transition-all active:scale-95 disabled:opacity-50"
            style="background: linear-gradient(135deg, #d946ef 0%, #c026d3 100%)"
            :disabled="loginLoading || !username || !password"
            @click="doLogin"
          >
            {{ loginLoading ? '登录中…' : '登录' }}
          </button>
        </div>
      </div>

      <!-- 已登录：服务端收藏列表 -->
      <template v-else>
        <!-- 已登录提示条 -->
        <div
          class="mb-3 flex items-center justify-between rounded-lg px-3 py-2"
          style="background: var(--bg-card)"
        >
          <div class="flex items-center gap-1.5 text-xs" style="color: var(--text-secondary)">
            <Heart :size="12" style="color: var(--accent)" />
            <span>{{ username }}</span>
          </div>
          <button
            class="flex items-center gap-1 text-xs"
            style="color: var(--text-muted)"
            @click="doLogout"
          >
            <LogOut :size="12" />
            <span>登出</span>
          </button>
        </div>

        <!-- 列表 -->
        <ComicGrid v-if="serverItems.length > 0" :comics="serverItems" />

        <LoadingGrid v-if="!serverInitialized && serverLoading" :count="12" />

        <ErrorState
          v-else-if="serverError && serverItems.length === 0"
          :message="serverError"
          @retry="loadServerFavorites(true)"
        />

        <EmptyState
          v-else-if="serverInitialized && !serverLoading && serverItems.length === 0"
          icon="💔"
          text="服务端没有收藏"
        />

        <!-- 无限滚动触发器 -->
        <div
          v-if="serverInitialized && serverItems.length > 0"
          ref="serverSentinel"
          class="flex items-center justify-center py-4 text-xs"
          style="color: var(--text-muted)"
        >
          <span v-if="serverScrollLoading">加载中…</span>
          <span v-else-if="!serverHasMore">— 到底了 —</span>
          <span v-else>&nbsp;</span>
        </div>
      </template>
    </template>
  </div>
</template>
