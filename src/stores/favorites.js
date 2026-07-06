/** 本地收藏 store。 */
import { defineStore } from 'pinia';
import { ref } from 'vue';
import * as favoritesRepo from '@/db/repositories/favorites';
export const useFavoritesStore = defineStore('favorites', () => {
    const favorites = ref([]);
    const favoriteIds = ref(new Set());
    const loaded = ref(false);
    async function load() {
        favorites.value = await favoritesRepo.listFavorites();
        favoriteIds.value = new Set(favorites.value.map((f) => f.id));
        loaded.value = true;
    }
    function isFavorited(id) {
        return favoriteIds.value.has(id);
    }
    async function toggleFavorite(comic) {
        const added = await favoritesRepo.toggleFavorite(comic);
        if (added) {
            favoriteIds.value.add(comic.id);
            favorites.value.unshift(comic);
        }
        else {
            favoriteIds.value.delete(comic.id);
            favorites.value = favorites.value.filter((f) => f.id !== comic.id);
        }
        // 触发响应式更新
        favoriteIds.value = new Set(favoriteIds.value);
        return added;
    }
    async function removeFavorite(id) {
        await favoritesRepo.removeFavorite(id);
        favoriteIds.value.delete(id);
        favorites.value = favorites.value.filter((f) => f.id !== id);
        favoriteIds.value = new Set(favoriteIds.value);
    }
    return {
        favorites,
        favoriteIds,
        loaded,
        load,
        isFavorited,
        toggleFavorite,
        removeFavorite,
    };
});
