import type { ChapterImages, ComicBrief, ComicDetail, PageResult, RankingTime, SearchOrder, SearchTime, SimpleResult } from './types';
/** 最新列表。 */
export declare function latest(page?: number, category?: string): Promise<PageResult<ComicBrief>>;
/** 排行榜。 */
export declare function ranking(time?: RankingTime, category?: string, page?: number): Promise<PageResult<ComicBrief>>;
/** 搜索。 */
export declare function search(q: string, page?: number, order?: SearchOrder, time?: SearchTime): Promise<PageResult<ComicBrief>>;
/** 漫画详情。 */
export declare function comicDetail(id: string): Promise<ComicDetail>;
/** 章节图片列表。 */
export declare function chapterImages(id: string): Promise<ChapterImages>;
/** 服务端收藏列表（需登录）。 */
export declare function favorites(page?: number): Promise<PageResult<ComicBrief>>;
/** 添加服务端收藏。 */
export declare function addFavorite(id: string): Promise<SimpleResult>;
/** 移除服务端收藏。 */
export declare function removeFavorite(id: string): Promise<SimpleResult>;
/** 登录。 */
export declare function login(username: string, password: string): Promise<SimpleResult>;
/** 登出（前端清本地 session 即可，Worker 无状态）。 */
export declare function logout(): Promise<SimpleResult>;
