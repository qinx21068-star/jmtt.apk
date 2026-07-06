<script setup lang="ts">
/** 加载骨架屏：网格占位。 */
defineProps<{
  count?: number
}>()
</script>

<template>
  <div class="grid grid-cols-3 gap-2 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6">
    <div
      v-for="i in count || 12"
      :key="i"
      class="overflow-hidden rounded-lg bg-ink-800/50"
    >
      <div class="aspect-[3/4] animate-pulse bg-ink-700/60"></div>
      <div class="p-2">
        <div class="h-3 w-3/4 animate-pulse rounded bg-ink-700/60"></div>
        <div class="mt-1 h-2 w-1/2 animate-pulse rounded bg-ink-700/40"></div>
      </div>
    </div>
  </div>
</template>
