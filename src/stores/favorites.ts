/** 本地收藏 store。 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ComicBrief } from '@/api/types'
import * as favoritesRepo from '@/db/repositories/favorites'

export const useFavoritesStore = defineStore('favorites', () => {
  const favorites = ref<ComicBrief[]>([])
  const favoriteIds = ref<Set<string>>(new Set())
  const loaded = ref(false)

  async function load() {
    favorites.value = await favoritesRepo.listFavorites()
    favoriteIds.value = new Set(favorites.value.map((f) => f.id))
    loaded.value = true
  }

  function isFavorited(id: string): boolean {
    return favoriteIds.value.has(id)
  }

  async function toggleFavorite(comic: ComicBrief): Promise<boolean> {
    const added = await favoritesRepo.toggleFavorite(comic)
    if (added) {
      favoriteIds.value.add(comic.id)
      favorites.value.unshift(comic)
    } else {
      favoriteIds.value.delete(comic.id)
      favorites.value = favorites.value.filter((f) => f.id !== comic.id)
    }
    // 触发响应式更新
    favoriteIds.value = new Set(favoriteIds.value)
    return added
  }

  async function removeFavorite(id: string) {
    await favoritesRepo.removeFavorite(id)
    favoriteIds.value.delete(id)
    favorites.value = favorites.value.filter((f) => f.id !== id)
    favoriteIds.value = new Set(favoriteIds.value)
  }

  return {
    favorites,
    favoriteIds,
    loaded,
    load,
    isFavorited,
    toggleFavorite,
    removeFavorite,
  }
})
