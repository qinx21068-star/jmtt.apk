/**
 * IndexedDB 数据层（Dexie 封装）。
 *
 * 存储用户本地数据：收藏、阅读历史、设置、屏蔽词、搜索历史、漫画缓存。
 * 所有数据仅存于本地，不上传任何服务器。
 */
import Dexie, { type Table } from 'dexie';
export interface FavoriteEntry {
    id: string;
    name: string;
    author: string | null;
    cover: string | null;
    tags: string[];
    added_at: number;
}
export interface HistoryEntry {
    chapter_id: string;
    comic_id: string;
    comic_name: string;
    comic_cover: string | null;
    page: number;
    scroll: number;
    updated_at: number;
}
export interface SettingEntry {
    key: string;
    value: unknown;
}
export interface BlockedTagEntry {
    tag: string;
    added_at: number;
}
export interface BlockedNameEntry {
    name: string;
    added_at: number;
}
export interface BlockedAuthorEntry {
    author: string;
    added_at: number;
}
export interface SearchHistoryEntry {
    keyword: string;
    searched_at: number;
}
export interface ComicCacheEntry {
    id: string;
    detail: string;
    cached_at: number;
}
declare class JmDatabase extends Dexie {
    favorites: Table<FavoriteEntry, string>;
    history: Table<HistoryEntry, string>;
    settings: Table<SettingEntry, string>;
    blockedTags: Table<BlockedTagEntry, string>;
    blockedNames: Table<BlockedNameEntry, string>;
    blockedAuthors: Table<BlockedAuthorEntry, string>;
    searchHistory: Table<SearchHistoryEntry, string>;
    comicCache: Table<ComicCacheEntry, string>;
    constructor();
}
declare const db: JmDatabase;
export default db;
