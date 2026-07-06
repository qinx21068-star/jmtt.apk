import type { HistoryEntry } from '../database';
export declare function getHistory(chapterId: string): Promise<HistoryEntry | undefined>;
export declare function listHistory(limit?: number): Promise<HistoryEntry[]>;
export declare function saveHistory(entry: Omit<HistoryEntry, 'updated_at'>): Promise<void>;
export declare function removeHistory(chapterId: string): Promise<void>;
export declare function clearHistory(): Promise<void>;
/** 获取某漫画最近阅读的章节 ID（按 updated_at 倒序找第一个匹配 comic_id 的）。 */
export declare function getLastChapterOfComic(comicId: string): Promise<HistoryEntry | undefined>;
