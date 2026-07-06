/** 收藏仓库（本地收藏，与服务端收藏独立）。 */
import db from '../database';
export async function listFavorites() {
    const entries = await db.favorites.orderBy('added_at').reverse().toArray();
    return entries.map((e) => ({
        id: e.id,
        name: e.name,
        author: e.author,
        tags: e.tags,
        cover: e.cover,
        likes: null,
        views: null,
    }));
}
export async function isFavorited(id) {
    return !!(await db.favorites.get(id));
}
export async function addFavorite(comic) {
    await db.favorites.put({
        id: comic.id,
        name: comic.name,
        author: comic.author,
        cover: comic.cover,
        tags: comic.tags,
        added_at: Date.now(),
    });
}
export async function removeFavorite(id) {
    await db.favorites.delete(id);
}
export async function toggleFavorite(comic) {
    const exists = await isFavorited(comic.id);
    if (exists) {
        await removeFavorite(comic.id);
        return false;
    }
    else {
        await addFavorite(comic);
        return true;
    }
}
