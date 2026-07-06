<script setup lang="ts">
/** 漫画卡片：封面 + 标题 + 作者。点击跳转详情。 */
import { useRouter } from 'vue-router'
import type { ComicBrief } from '@/api/types'
import JmImage from './JmImage.vue'

const props = defineProps<{
  comic: ComicBrief
}>()

const router = useRouter()
function goDetail() {
  router.push({ name: 'comic', params: { id: props.comic.id } })
}
</script>

<template>
  <div
    class="card group cursor-pointer overflow-hidden transition-transform active:scale-95"
    @click="goDetail"
  >
    <div class="relative">
      <JmImage
        :src="comic.cover"
        :alt="comic.name"
        :ratio="3 / 4"
        :rounded="0"
      />
      <!-- 标签胶囊（前 1 个） -->
      <div
        v-if="comic.tags && comic.tags.length > 0"
        class="absolute left-1.5 top-1.5 rounded-full bg-black/70 px-2 py-0.5 text-[10px] text-white backdrop-blur-sm"
      >
        {{ comic.tags[0] }}
      </div>
    </div>
    <div class="p-2">
      <h3
        class="line-clamp-2 text-xs font-medium leading-tight"
        style="color: var(--text-primary); min-height: 2.4em"
      >
        {{ comic.name }}
      </h3>
      <p
        v-if="comic.author"
        class="mt-1 truncate text-[10px]"
        style="color: var(--text-muted)"
      >
        {{ comic.author }}
      </p>
    </div>
  </div>
</template>
