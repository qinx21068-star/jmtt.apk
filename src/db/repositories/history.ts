/** 阅读历史仓库。 */
import db from '../database'
import type { HistoryEntry } from '../database'

export async function getHistory(chapterId: string): Promise<HistoryEntry | undefined> {
  return db.history.get(chapterId)
}

export async function listHistory(limit = 50): Promise<HistoryEntry[]> {
  return db.history.orderBy('updated_at').reverse().limit(limit).toArray()
}

export async function saveHistory(entry: Omit<HistoryEntry, 'updated_at'>): Promise<void> {
  await db.history.put({ ...entry, updated_at: Date.now() })
}

export async function removeHistory(chapterId: string): Promise<void> {
  await db.history.delete(chapterId)
}

export async function clearHistory(): Promise<void> {
  await db.history.clear()
}

/** 获取某漫画最近阅读的章节 ID（按 updated_at 倒序找第一个匹配 comic_id 的）。 */
export async function getLastChapterOfComic(comicId: string): Promise<HistoryEntry | undefined> {
  const all = await db.history.where('comic_id').equals(comicId).toArray()
  if (all.length === 0) return undefined
  return all.reduce((latest, cur) =>
    cur.updated_at > latest.updated_at ? cur : latest,
  )
}
