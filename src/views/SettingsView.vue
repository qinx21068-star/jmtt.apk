<script setup lang="ts">
/**
 * 设置页：主题 / Worker URL / 阅读方向 / 屏蔽词管理 / 关于入口。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  Sun,
  Moon,
  Monitor,
  Check,
  ChevronRight,
  Plus,
  X,
  Loader2,
  Server,
  Info,
  ArrowDownUp,
  Tag,
  Type,
  User,
} from 'lucide-vue-next'
import { useSettingsStore } from '@/stores/settings'
import type { ThemeMode, ReaderDirection } from '@/api/types'
import { get, getWorkerUrl } from '@/api/client'

const settings = useSettingsStore()
const router = useRouter()

const themes: { key: ThemeMode; label: string; icon: typeof Sun }[] = [
  { key: 'system', label: '跟随系统', icon: Monitor },
  { key: 'light', label: '浅色', icon: Sun },
  { key: 'dark', label: '深色', icon: Moon },
]

const directions: { key: ReaderDirection; label: string }[] = [
  { key: 'vertical', label: '上下滚动' },
  { key: 'horizontal', label: '左右翻页' },
]

// ---- Worker URL ----
const workerUrlInput = ref(settings.workerUrl)
const workerTesting = ref(false)
const workerTestResult = ref<{ ok: boolean; msg: string } | null>(null)

async function saveWorkerUrl() {
  const url = workerUrlInput.value.trim()
  if (url && !/^https?:\/\//i.test(url)) {
    workerTestResult.value = {
      ok: false,
      msg: 'URL 必须以 http:// 或 https:// 开头',
    }
    return
  }
  settings.setWorkerUrl(url)
  workerTestResult.value = { ok: true, msg: '已保存' }
  // 2 秒后清空"已保存"提示
  setTimeout(() => {
    if (workerTestResult.value?.msg === '已保存') {
      workerTestResult.value = null
    }
  }, 2000)
}

async function testWorker() {
  const url = workerUrlInput.value.trim()
  if (!url) {
    workerTestResult.value = { ok: false, msg: '请先填入 Worker URL' }
    return
  }
  if (!/^https?:\/\//i.test(url)) {
    workerTestResult.value = {
      ok: false,
      msg: 'URL 必须以 http:// 或 https:// 开头',
    }
    return
  }
  // 先保存再测试
  settings.setWorkerUrl(url)
  workerTesting.value = true
  workerTestResult.value = null
  try {
    const data = await get<{ status: string; time: number }>('/api/health')
    workerTestResult.value = { ok: true, msg: `连接正常 (${data.time}ms)，已自动保存` }
  } catch (e: any) {
    const msg = e?.message || '连接失败'
    if (msg.includes('DOCTYPE') || msg.includes('<html')) {
      workerTestResult.value = {
        ok: false,
        msg: 'Worker URL 无效或未部署：返回了 HTML 而非 JSON',
      }
    } else {
      workerTestResult.value = { ok: false, msg }
    }
  } finally {
    workerTesting.value = false
  }
}

// ---- 屏蔽词 ----
type BlockType = 'tag' | 'name' | 'author'
const blockTabs: { key: BlockType; label: string; icon: typeof Tag }[] = [
  { key: 'tag', label: '标签', icon: Tag },
  { key: 'name', label: '标题', icon: Type },
  { key: 'author', label: '作者', icon: User },
]
const currentBlockTab = ref<BlockType>('tag')
const blockInput = ref('')

function currentBlockList(): string[] {
  if (currentBlockTab.value === 'tag') return settings.blockedTags
  if (currentBlockTab.value === 'name') return settings.blockedNames
  return settings.blockedAuthors
}

async function addBlock() {
  const v = blockInput.value.trim()
  if (!v) return
  if (currentBlockTab.value === 'tag') await settings.addBlockedTag(v)
  else if (currentBlockTab.value === 'name') await settings.addBlockedName(v)
  else await settings.addBlockedAuthor(v)
  blockInput.value = ''
}

async function removeBlock(item: string) {
  if (currentBlockTab.value === 'tag') await settings.removeBlockedTag(item)
  else if (currentBlockTab.value === 'name') await settings.removeBlockedName(item)
  else await settings.removeBlockedAuthor(item)
}

function goAbout() {
  router.push({ name: 'about' })
}

onMounted(() => {
  workerUrlInput.value = getWorkerUrl()
})
</script>

<template>
  <div class="px-3 pt-3 pb-6">
    <!-- 主题 -->
    <section class="mb-5">
      <h3 class="mb-2 px-1 font-serif text-sm font-semibold" style="color: var(--text-secondary)">
        外观
      </h3>
      <div class="card p-3">
        <div class="grid grid-cols-3 gap-2">
          <button
            v-for="t in themes"
            :key="t.key"
            class="flex flex-col items-center gap-1.5 rounded-lg border py-3 transition-all"
            :style="
              settings.theme === t.key
                ? {
                    borderColor: 'var(--accent)',
                    background: 'var(--accent-soft)',
                  }
                : { borderColor: 'var(--border-subtle)' }
            "
            @click="settings.setTheme(t.key)"
          >
            <component
              :is="t.icon"
              :size="20"
              :style="{ color: settings.theme === t.key ? 'var(--accent)' : 'var(--text-muted)' }"
            />
            <span
              class="text-xs"
              :style="{
                color: settings.theme === t.key ? 'var(--accent)' : 'var(--text-secondary)',
              }"
            >
              {{ t.label }}
            </span>
            <Check
              v-if="settings.theme === t.key"
              :size="12"
              style="color: var(--accent)"
            />
          </button>
        </div>
      </div>
    </section>

    <!-- Worker URL -->
    <section class="mb-5">
      <h3 class="mb-2 px-1 font-serif text-sm font-semibold" style="color: var(--text-secondary)">
        网络代理
      </h3>
      <div class="card p-3">
        <div class="mb-2 flex items-center gap-2">
          <Server :size="14" style="color: var(--text-muted)" />
          <span class="text-xs" style="color: var(--text-muted)">
            Cloudflare Worker 代理地址
          </span>
        </div>
        <input
          v-model="workerUrlInput"
          type="text"
          placeholder="https://your-worker.workers.dev"
          class="mb-2 w-full rounded-lg border px-3 py-2 text-sm outline-none"
          :style="{
            background: 'var(--bg-card)',
            borderColor: 'var(--border-subtle)',
            color: 'var(--text-primary)',
          }"
        />
        <div class="flex gap-2">
          <button
            class="flex-1 rounded-lg border py-2 text-xs transition-colors"
            :style="{
              borderColor: 'var(--border-subtle)',
              color: 'var(--text-secondary)',
            }"
            @click="saveWorkerUrl"
          >
            保存
          </button>
          <button
            class="flex flex-1 items-center justify-center gap-1 rounded-lg py-2 text-xs text-white transition-all active:scale-95 disabled:opacity-50"
            style="background: linear-gradient(135deg, #d946ef 0%, #c026d3 100%)"
            :disabled="workerTesting"
            @click="testWorker"
          >
            <Loader2 v-if="workerTesting" :size="12" class="animate-spin" />
            <span>{{ workerTesting ? '测试中…' : '测试连接' }}</span>
          </button>
        </div>
        <p
          v-if="workerTestResult"
          class="mt-2 text-xs"
          :style="{ color: workerTestResult.ok ? '#10b981' : '#ef4444' }"
        >
          {{ workerTestResult.ok ? '✓ ' : '✗ ' }}{{ workerTestResult.msg }}
        </p>
        <p class="mt-2 text-[11px] leading-relaxed" style="color: var(--text-muted)">
          留空则使用与当前站点同源的 /api 路径。需自行部署 Worker 代理，详见 GitHub README。
        </p>
      </div>
    </section>

    <!-- 阅读方向 -->
    <section class="mb-5">
      <h3 class="mb-2 px-1 font-serif text-sm font-semibold" style="color: var(--text-secondary)">
        阅读方向
      </h3>
      <div class="card p-3">
        <div class="mb-2 flex items-center gap-2">
          <ArrowDownUp :size="14" style="color: var(--text-muted)" />
        </div>
        <div class="grid grid-cols-2 gap-2">
          <button
            v-for="d in directions"
            :key="d.key"
            class="rounded-lg border py-2.5 text-sm transition-all"
            :style="
              settings.readerDirection === d.key
                ? {
                    borderColor: 'var(--accent)',
                    background: 'var(--accent-soft)',
                    color: 'var(--accent)',
                  }
                : {
                    borderColor: 'var(--border-subtle)',
                    color: 'var(--text-secondary)',
                  }
            "
            @click="settings.setReaderDirection(d.key)"
          >
            {{ d.label }}
          </button>
        </div>
      </div>
    </section>

    <!-- 屏蔽词管理 -->
    <section class="mb-5">
      <h3 class="mb-2 px-1 font-serif text-sm font-semibold" style="color: var(--text-secondary)">
        屏蔽词
      </h3>
      <div class="card p-3">
        <!-- 子 Tab -->
        <div class="mb-3 flex gap-1.5">
          <button
            v-for="t in blockTabs"
            :key="t.key"
            class="flex flex-1 items-center justify-center gap-1 rounded-lg py-1.5 text-xs transition-all"
            :style="
              currentBlockTab === t.key
                ? { background: 'var(--accent-soft)', color: 'var(--accent)' }
                : { background: 'var(--bg-card)', color: 'var(--text-muted)' }
            "
            @click="currentBlockTab = t.key"
          >
            <component :is="t.icon" :size="12" />
            <span>{{ t.label }}</span>
          </button>
        </div>

        <!-- 添加输入 -->
        <div class="mb-3 flex gap-2">
          <input
            v-model="blockInput"
            type="text"
            :placeholder="`添加${currentBlockTab === 'tag' ? '标签' : currentBlockTab === 'name' ? '标题关键词' : '作者名'}`"
            class="flex-1 rounded-lg border px-3 py-1.5 text-sm outline-none"
            :style="{
              background: 'var(--bg-card)',
              borderColor: 'var(--border-subtle)',
              color: 'var(--text-primary)',
            }"
            @keydown.enter="addBlock"
          />
          <button
            class="flex items-center justify-center rounded-lg px-3 text-white transition-all active:scale-95"
            style="background: linear-gradient(135deg, #d946ef 0%, #c026d3 100%)"
            @click="addBlock"
          >
            <Plus :size="16" />
          </button>
        </div>

        <!-- 屏蔽词列表 -->
        <div v-if="currentBlockList().length > 0" class="flex flex-wrap gap-1.5">
          <div
            v-for="item in currentBlockList()"
            :key="item"
            class="group flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs"
            :style="{
              borderColor: 'var(--border-subtle)',
              color: 'var(--text-secondary)',
            }"
          >
            <span>{{ item }}</span>
            <button
              class="opacity-50 transition-opacity group-hover:opacity-100"
              style="color: var(--text-muted)"
              @click="removeBlock(item)"
            >
              <X :size="11" />
            </button>
          </div>
        </div>
        <p v-else class="py-3 text-center text-xs" style="color: var(--text-muted)">
          暂无屏蔽词
        </p>
      </div>
    </section>

    <!-- 关于入口 -->
    <section class="mb-5">
      <button
        class="card flex w-full items-center justify-between p-4 transition-colors"
        @click="goAbout"
      >
        <div class="flex items-center gap-2">
          <Info :size="18" style="color: var(--text-secondary)" />
          <span class="text-sm" style="color: var(--text-primary)">关于</span>
        </div>
        <ChevronRight :size="16" style="color: var(--text-muted)" />
      </button>
    </section>
  </div>
</template>
