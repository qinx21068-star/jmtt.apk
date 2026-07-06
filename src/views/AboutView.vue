<script setup lang="ts">
/**
 * 关于页：版本号 / 免责声明查看 / GitHub 仓库 / 技术栈。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Github, ShieldAlert, Code2, Heart } from 'lucide-vue-next'
import DisclaimerDialog from '@/components/DisclaimerDialog.vue'

const router = useRouter()
const showDisclaimer = ref(false)

const version = '1.0.0'

const techStack = [
  'Vue 3 + TypeScript',
  'Vite + vite-plugin-pwa',
  'Tailwind CSS',
  'Pinia + Vue Router',
  'Dexie.js (IndexedDB)',
  'Cloudflare Workers',
]

const GITHUB_URL = 'https://github.com/qinx21068-star/jmtt.apk'

function back() {
  if (window.history.length > 1) router.back()
  else router.push({ name: 'settings' })
}

function openGithub() {
  window.open(GITHUB_URL, '_blank', 'noopener')
}

function viewDisclaimer() {
  showDisclaimer.value = true
}

function closeDisclaimer() {
  showDisclaimer.value = false
}
</script>

<template>
  <div class="min-h-screen">
    <!-- 顶部返回栏 -->
    <div
      class="sticky top-0 z-20 flex items-center px-3 py-2 backdrop-blur-md"
      style="
        background: rgba(26, 22, 37, 0.85);
        padding-top: max(0.5rem, var(--safe-top));
        border-bottom: 1px solid var(--border-subtle);
      "
    >
      <button
        class="rounded-full p-2"
        style="color: var(--text-primary)"
        @click="back"
      >
        <ArrowLeft :size="22" />
      </button>
      <h2 class="ml-1 font-serif text-lg font-semibold" style="color: var(--text-primary)">
        关于
      </h2>
    </div>

    <div class="px-5 py-6">
      <!-- App 图标 + 名称 -->
      <div class="mb-8 flex flex-col items-center text-center">
        <div
          class="mb-4 flex h-20 w-20 items-center justify-center rounded-2xl shadow-card"
          style="background: linear-gradient(135deg, #d946ef 0%, #7c3aed 100%)"
        >
          <span class="font-serif text-4xl font-bold text-white">天</span>
        </div>
        <h1 class="font-serif text-2xl font-bold" style="color: var(--text-primary)">
          本子天国
        </h1>
        <p class="mt-1 text-xs" style="color: var(--text-muted)">
          PWA 网页版 · v{{ version }}
        </p>
      </div>

      <!-- 功能介绍 -->
      <div class="card mb-4 p-4">
        <h3 class="mb-2 font-serif text-sm font-semibold" style="color: var(--text-primary)">
          关于本项目
        </h3>
        <p class="text-xs leading-relaxed" style="color: var(--text-secondary)">
          本子天国是一个第三方禁漫漫画阅读器 PWA 应用。无需上架应用商店，添加到主屏幕即可像原生 App 一样使用。所有数据存储在本地，不收集任何用户信息。
        </p>
      </div>

      <!-- 技术栈 -->
      <div class="card mb-4 p-4">
        <div class="mb-2 flex items-center gap-2">
          <Code2 :size="16" style="color: var(--accent)" />
          <h3 class="font-serif text-sm font-semibold" style="color: var(--text-primary)">
            技术栈
          </h3>
        </div>
        <div class="flex flex-wrap gap-1.5">
          <span
            v-for="tech in techStack"
            :key="tech"
            class="rounded-full px-2.5 py-1 text-[11px]"
            style="background: var(--accent-soft); color: var(--accent)"
          >
            {{ tech }}
          </span>
        </div>
      </div>

      <!-- 操作项 -->
      <div class="card mb-4 overflow-hidden">
        <button
          class="flex w-full items-center justify-between px-4 py-3.5 transition-colors hover:bg-white/5"
          @click="viewDisclaimer"
        >
          <div class="flex items-center gap-3">
            <ShieldAlert :size="18" style="color: var(--text-secondary)" />
            <span class="text-sm" style="color: var(--text-primary)">查看免责声明</span>
          </div>
        </button>
        <div class="h-px" style="background: var(--border-subtle)"></div>
        <button
          class="flex w-full items-center justify-between px-4 py-3.5 transition-colors hover:bg-white/5"
          @click="openGithub"
        >
          <div class="flex items-center gap-3">
            <Github :size="18" style="color: var(--text-secondary)" />
            <span class="text-sm" style="color: var(--text-primary)">GitHub 仓库</span>
          </div>
          <span class="text-[10px]" style="color: var(--text-muted)">在新窗口打开</span>
        </button>
      </div>

      <!-- 致谢 -->
      <div class="mb-4 text-center">
        <p class="flex items-center justify-center gap-1 text-xs" style="color: var(--text-muted)">
          Made with
          <Heart :size="12" fill="currentColor" style="color: var(--accent)" />
          by 开源社区
        </p>
        <p class="mt-2 text-[10px] leading-relaxed" style="color: var(--text-muted)">
          本项目仅供学习交流使用，请遵守所在地区法律法规。<br />
          支持正版，尊重版权。
        </p>
      </div>
    </div>

    <!-- 免责声明弹窗（非倒计时） -->
    <DisclaimerDialog
      v-if="showDisclaimer"
      :force-countdown="false"
      @accept="closeDisclaimer"
      @dismiss="closeDisclaimer"
    />
  </div>
</template>
