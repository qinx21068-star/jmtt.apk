<script setup lang="ts">
/** 漫画网格：响应式列数，移动端 2-3 列，桌面端 4-6 列。 */
import type { ComicBrief } from '@/api/types'
import ComicCard from './ComicCard.vue'

defineProps<{
  comics: ComicBrief[]
}>()
</script>

<template>
  <div class="grid grid-cols-3 gap-2 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6">
    <ComicCard v-for="comic in comics" :key="comic.id" :comic="comic" />
  </div>
</template>
