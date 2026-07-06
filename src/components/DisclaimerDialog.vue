<script setup lang="ts">
/**
 * 免责声明对话框。
 *
 * - forceCountdown=true：首次启动，5 秒倒计时强制阅读，"不同意"按钮退出
 * - forceCountdown=false：关于页查看，无倒计时，"关闭"按钮即可
 */
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { ShieldAlert } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    forceCountdown?: boolean
  }>(),
  {
    forceCountdown: false,
  },
)

const emit = defineEmits<{
  (e: 'accept'): void
  (e: 'dismiss'): void
}>()

const remaining = ref(props.forceCountdown ? 5 : 0)
let timer: number | null = null

onMounted(() => {
  if (props.forceCountdown && remaining.value > 0) {
    timer = window.setInterval(() => {
      remaining.value -= 1
      if (remaining.value <= 0 && timer) {
        window.clearInterval(timer)
        timer = null
      }
    }, 1000)
  }
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})

const canAct = computed(() => remaining.value === 0)
const buttonText = computed(() => {
  if (remaining.value > 0) return `请阅读 ${remaining}s`
  return props.forceCountdown ? '我已阅读并同意' : '关闭'
})

function onAccept() {
  if (!canAct.value) return
  emit('accept')
}
function onDismiss() {
  if (!canAct.value) return
  emit('dismiss')
}
</script>

<template>
  <div
    class="fixed inset-0 z-50 flex items-center justify-center p-4"
    style="background: rgba(0, 0, 0, 0.75); backdrop-filter: blur(4px)"
  >
    <div
      class="card max-h-[85vh] w-full max-w-md overflow-hidden animate-slide-up"
      style="background: var(--bg-elevated)"
    >
      <!-- 标题 -->
      <div class="flex items-center gap-2 border-b px-5 py-4" style="border-color: var(--border-subtle)">
        <ShieldAlert :size="20" class="text-accent-500" />
        <h2 class="font-serif text-lg font-semibold" style="color: var(--text-primary)">
          免责声明
        </h2>
      </div>

      <!-- 内容 -->
      <div class="overflow-y-auto px-5 py-4" style="max-height: calc(85vh - 140px)">
        <div
          class="space-y-3 text-sm leading-relaxed"
          style="color: var(--text-secondary)"
        >
          <p>本项目仅供个人学习、技术交流和研究使用。</p>
          <ol class="space-y-2 pl-4">
            <li>1. 本项目为第三方开源客户端，不提供任何漫画内容资源，仅作为访问接口的工具。</li>
            <li>2. 项目所有使用者必须遵守所在国家/地区的法律法规。</li>
            <li>3. 本项目不鼓励、不支持任何侵犯版权的行为。请尊重原作者和版权方的合法权益，支持正版内容。</li>
            <li>4. 项目作者不承担任何因使用本软件产生的法律责任、账号封禁、数据丢失或其他任何形式的后果。</li>
            <li>5. 未成年人禁止使用本软件。</li>
            <li>6. 使用本软件即表示您已阅读并同意本声明。如不同意，请立即停止使用并删除本软件。</li>
          </ol>
          <p class="font-serif italic" style="color: var(--text-muted)">
            珍爱生命，爱护身体，理性使用，远离风险。
          </p>
        </div>
      </div>

      <!-- 按钮 -->
      <div
        class="flex gap-2 border-t px-5 py-3"
        style="border-color: var(--border-subtle); padding-bottom: max(0.75rem, var(--safe-bottom))"
      >
        <button
          v-if="forceCountdown"
          class="btn-ghost flex-1 text-sm"
          :disabled="!canAct"
          :style="{ opacity: canAct ? 1 : 0.5 }"
          @click="onDismiss"
        >
          不同意，退出
        </button>
        <button
          class="flex-1 text-sm"
          :class="forceCountdown ? 'btn-primary' : 'btn-ghost'"
          :disabled="!canAct"
          :style="{ opacity: canAct ? 1 : 0.5 }"
          @click="onAccept"
        >
          {{ buttonText }}
        </button>
      </div>
    </div>
  </div>
</template>
