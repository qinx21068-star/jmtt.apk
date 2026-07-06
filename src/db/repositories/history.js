/** 阅读历史仓库。 */
import db from '../database';
export async function getHistory(chapterId) {
    return db.history.get(chapterId);
}
export async function listHistory(limit = 50) {
    return db.history.orderBy('updated_at').reverse().limit(limit).toArray();
}
export async function saveHistory(entry) {
    await db.history.put({ ...entry, updated_at: Date.now() });
}
export async function removeHistory(chapterId) {
    await db.history.delete(chapterId);
}
export async function clearHistory() {
    await db.history.clear();
}
/** 获取某漫画最近阅读的章节 ID（按 updated_at 倒序找第一个匹配 comic_id 的）。 */
export async function getLastChapterOfComic(comicId) {
    const all = await db.history.where('comic_id').equals(comicId).toArray();
    if (all.length === 0)
        return undefined;
    return all.reduce((latest, cur) => cur.updated_at > latest.updated_at ? cur : latest);
}
