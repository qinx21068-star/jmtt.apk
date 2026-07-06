/** 搜索历史仓库。 */
import db from '../database';
const MAX_HISTORY = 20;
export async function listSearchHistory() {
    const arr = await db.searchHistory.orderBy('searched_at').reverse().limit(MAX_HISTORY).toArray();
    return arr.map((e) => e.keyword);
}
export async function addSearchHistory(keyword) {
    const k = keyword.trim();
    if (!k)
        return;
    await db.searchHistory.put({ keyword: k, searched_at: Date.now() });
    // 清理超过 MAX_HISTORY 的旧记录
    const count = await db.searchHistory.count();
    if (count > MAX_HISTORY) {
        const oldest = await db.searchHistory.orderBy('searched_at').limit(count - MAX_HISTORY).toArray();
        await Promise.all(oldest.map((e) => db.searchHistory.delete(e.keyword)));
    }
}
export async function removeSearchHistory(keyword) {
    await db.searchHistory.delete(keyword);
}
export async function clearSearchHistory() {
    await db.searchHistory.clear();
}
