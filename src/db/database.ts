/**
 * IndexedDB 数据层（Dexie 封装）。
 *
 * 存储用户本地数据：收藏、阅读历史、设置、屏蔽词、搜索历史、漫画缓存。
 * 所有数据仅存于本地，不上传任何服务器。
 */
import Dexie, { type Table } from 'dexie'

export interface FavoriteEntry {
  id: string // 漫画 ID
  name: string
  author: string | null
  cover: string | null
  tags: string[]
  added_at: number
}

export interface HistoryEntry {
  chapter_id: string // 章节 ID
  comic_id: string
  comic_name: string
  comic_cover: string | null
  page: number // 当前页码（0-based）
  scroll: number // 滚动位置 px
  updated_at: number
}

export interface SettingEntry {
  key: string
  value: unknown
}

export interface BlockedTagEntry {
  tag: string
  added_at: number
}

export interface BlockedNameEntry {
  name: string
  added_at: number
}

export interface BlockedAuthorEntry {
  author: string
  added_at: number
}

export interface SearchHistoryEntry {
  keyword: string
  searched_at: number
}

export interface ComicCacheEntry {
  id: string
  detail: string // JSON 序列化的 ComicDetail
  cached_at: number
}

class JmDatabase extends Dexie {
  favorites!: Table<FavoriteEntry, string>
  history!: Table<HistoryEntry, string>
  settings!: Table<SettingEntry, string>
  blockedTags!: Table<BlockedTagEntry, string>
  blockedNames!: Table<BlockedNameEntry, string>
  blockedAuthors!: Table<BlockedAuthorEntry, string>
  searchHistory!: Table<SearchHistoryEntry, string>
  comicCache!: Table<ComicCacheEntry, string>

  constructor() {
    super('jmtt-pwa')
    this.version(1).stores({
      favorites: 'id, added_at, name',
      history: 'chapter_id, comic_id, updated_at',
      settings: 'key',
      blockedTags: 'tag, added_at',
      blockedNames: 'name, added_at',
      blockedAuthors: 'author, added_at',
      searchHistory: 'keyword, searched_at',
      comicCache: 'id, cached_at',
    })
  }
}

const db = new JmDatabase()
export default db
