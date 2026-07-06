<script setup lang="ts">
/**
 * App 根组件：路由出口 + 免责声明弹窗 + 路由初始化。
 *
 * - 启动时初始化设置 store（加载持久化设置）
 * - 首次访问弹免责声明（5 秒倒计时强制阅读）
 * - 阅读器路由隐藏底部导航
 */
import { onMounted, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { useFavoritesStore } from '@/stores/favorites'
import AppHeader from '@/components/AppHeader.vue'
import BottomNav from '@/components/BottomNav.vue'
import DisclaimerDialog from '@/components/DisclaimerDialog.vue'

const settings = useSettingsStore()
const favorites = useFavoritesStore()
const route = useRoute()

const showDisclaimer = ref(false)

// 阅读器全屏，不显示顶栏和底栏
const isFullscreen = computed(() => route.name === 'reader')
const showHeader = computed(() => !isFullscreen.value)
const showBottomNav = computed(() => !isFullscreen.value)

onMounted(async () => {
  await settings.init()
  await favorites.load()
  // 首次访问弹免责声明
  if (!settings.disclaimerAccepted) {
    showDisclaimer.value = true
  }
})

function onAcceptDisclaimer() {
  settings.acceptDisclaimer()
  showDisclaimer.value = false
}

function onDismissDisclaimer() {
  // 不同意：关闭页面（移动端无法关闭，跳到空白页）
  showDisclaimer.value = false
  window.location.href = 'about:blank'
}
</script>

<template>
  <div class="app-container">
    <AppHeader v-if="showHeader" />

    <main
      class="relative"
      :class="showHeader ? 'pt-0' : ''"
      :style="{
        paddingBottom: showBottomNav ? 'calc(60px + var(--safe-bottom))' : '0',
      }"
    >
      <router-view />
    </main>

    <BottomNav v-if="showBottomNav" />

    <!-- 首次访问免责声明（强制倒计时） -->
    <DisclaimerDialog
      v-if="showDisclaimer"
      :force-countdown="true"
      @accept="onAcceptDisclaimer"
      @dismiss="onDismissDisclaimer"
    />
  </div>
</template>
