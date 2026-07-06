<script setup lang="ts">
/** 错误状态：图标 + 错误信息 + 重试按钮。 */
defineProps<{
  message?: string
}>()

const emit = defineEmits<{
  (e: 'retry'): void
}>()
</script>

<template>
  <div class="flex flex-col items-center justify-center py-12 text-center">
    <div class="mb-3 text-4xl opacity-50">⚠️</div>
    <p class="mb-4 text-sm" style="color: var(--text-muted)">
      {{ message || '加载失败' }}
    </p>
    <button class="btn-primary text-sm" @click="emit('retry')">重试</button>
  </div>
</template>
