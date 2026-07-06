/**
 * 禁漫业务 API 封装。
 *
 * 所有方法返回 Promise，错误时抛出 Error。
 * 调用 Worker 代理，不直接访问禁漫。
 */
import { del, get, post } from './client'
import type {
  ChapterImages,
  ComicBrief,
  ComicDetail,
  PageResult,
  RankingTime,
  SearchOrder,
  SearchTime,
  SimpleResult,
} from './types'

/** 最新列表。 */
export function latest(page = 1, category = ''): Promise<PageResult<ComicBrief>> {
  return get<PageResult<ComicBrief>>('/api/latest', { page, category })
}

/** 排行榜。 */
export function ranking(
  time: RankingTime = 'all',
  category = '',
  page = 1,
): Promise<PageResult<ComicBrief>> {
  return get<PageResult<ComicBrief>>('/api/ranking', { time, category, page })
}

/** 搜索。 */
export function search(
  q: string,
  page = 1,
  order: SearchOrder = 'latest',
  time: SearchTime = 'all',
): Promise<PageResult<ComicBrief>> {
  return get<PageResult<ComicBrief>>('/api/search', { q, page, order, time })
}

/** 漫画详情。 */
export function comicDetail(id: string): Promise<ComicDetail> {
  return get<ComicDetail>(`/api/comic/${encodeURIComponent(id)}`)
}

/** 章节图片列表。 */
export function chapterImages(id: string): Promise<ChapterImages> {
  return get<ChapterImages>(`/api/chapter/${encodeURIComponent(id)}`)
}

/** 服务端收藏列表（需登录）。 */
export function favorites(page = 1): Promise<PageResult<ComicBrief>> {
  return get<PageResult<ComicBrief>>('/api/favorites', { page })
}

/** 添加服务端收藏。 */
export function addFavorite(id: string): Promise<SimpleResult> {
  return post<SimpleResult>(`/api/favorite/${encodeURIComponent(id)}`)
}

/** 移除服务端收藏。 */
export function removeFavorite(id: string): Promise<SimpleResult> {
  return del<SimpleResult>(`/api/favorite/${encodeURIComponent(id)}`)
}

/** 登录。 */
export function login(username: string, password: string): Promise<SimpleResult> {
  return post<SimpleResult>('/api/login', { username, password })
}

/** 登出（前端清本地 session 即可，Worker 无状态）。 */
export function logout(): Promise<SimpleResult> {
  return post<SimpleResult>('/api/logout')
}
