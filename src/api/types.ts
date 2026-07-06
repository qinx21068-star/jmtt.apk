/**
 * 前端 TypeScript 类型定义。
 * 与 Worker 端返回格式一致，前端只调用 Worker，不直接访问禁漫。
 */

/** Worker 返回的统一响应格式。 */
export interface ApiResponse<T> {
  code: number
  data: T | null
  errorMsg: string | null
}

/** 漫画简要信息（列表项）。 */
export interface ComicBrief {
  id: string
  name: string
  author: string | null
  tags: string[]
  cover: string | null
  likes: string | null
  views: string | null
}

/** 分页结果。 */
export interface PageResult<T> {
  page: number
  total: number | null
  items: T[]
}

/** 章节。 */
export interface Chapter {
  id: string
  title: string
  sort: number
}

/** 漫画详情。 */
export interface ComicDetail {
  id: string
  name: string
  author: string | null
  description: string | null
  tags: string[]
  cover: string | null
  likes: string | null
  views: string | null
  chapters: Chapter[]
}

/** 章节图片列表。 */
export interface ChapterImages {
  id: string
  title: string | null
  scramble_id: string
  images: string[]
}

/** 简单操作结果。 */
export interface SimpleResult {
  success: boolean
  message: string | null
}

/** 排序选项。 */
export type SearchOrder = 'latest' | 'views' | 'likes' | 'picture'

/** 时间范围。 */
export type SearchTime = 'all' | 'today' | 'week' | 'month'

/** 排行时间范围。 */
export type RankingTime = 'all' | 'today' | 'week' | 'month'

/** 主题模式。 */
export type ThemeMode = 'system' | 'light' | 'dark'

/** 阅读方向。 */
export type ReaderDirection = 'vertical' | 'horizontal'
