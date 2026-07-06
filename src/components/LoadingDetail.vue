<script setup lang="ts">
// 详情页加载骨架屏
</script>

<template>
  <div class="px-4 pt-6">
    <div class="flex gap-4">
      <div class="h-40 w-28 animate-pulse rounded-lg bg-ink-800/60"></div>
      <div class="flex flex-1 flex-col justify-end gap-2">
        <div class="h-5 w-3/4 animate-pulse rounded bg-ink-800/60"></div>
        <div class="h-3 w-1/2 animate-pulse rounded bg-ink-800/40"></div>
        <div class="h-3 w-2/3 animate-pulse rounded bg-ink-800/40"></div>
      </div>
    </div>
    <div class="mt-4 flex gap-2">
      <div class="h-9 flex-1 animate-pulse rounded-xl bg-ink-800/60"></div>
      <div class="h-9 flex-1 animate-pulse rounded-xl bg-ink-800/60"></div>
    </div>
    <div class="mt-6 space-y-2">
      <div
        v-for="i in 5"
        :key="i"
        class="h-12 animate-pulse rounded-lg bg-ink-800/40"
      ></div>
    </div>
  </div>
</template>
