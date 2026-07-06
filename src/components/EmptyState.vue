<script setup lang="ts">
/** 空状态：图标 + 文案。 */
withDefaults(
  defineProps<{
    text?: string
    icon?: string
  }>(),
  {
    text: '没有数据',
    icon: '📭',
  },
)
</script>

<template>
  <div class="flex flex-col items-center justify-center py-16 text-center">
    <div class="mb-3 text-5xl opacity-50">{{ icon }}</div>
    <p class="text-sm" style="color: var(--text-muted)">{{ text }}</p>
  </div>
</template>
