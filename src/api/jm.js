/**
 * 禁漫业务 API 封装。
 *
 * 所有方法返回 Promise，错误时抛出 Error。
 * 调用 Worker 代理，不直接访问禁漫。
 */
import { del, get, post } from './client';
/** 最新列表。 */
export function latest(page = 1, category = '') {
    return get('/api/latest', { page, category });
}
/** 排行榜。 */
export function ranking(time = 'all', category = '', page = 1) {
    return get('/api/ranking', { time, category, page });
}
/** 搜索。 */
export function search(q, page = 1, order = 'latest', time = 'all') {
    return get('/api/search', { q, page, order, time });
}
/** 漫画详情。 */
export function comicDetail(id) {
    return get(`/api/comic/${encodeURIComponent(id)}`);
}
/** 章节图片列表。 */
export function chapterImages(id) {
    return get(`/api/chapter/${encodeURIComponent(id)}`);
}
/** 服务端收藏列表（需登录）。 */
export function favorites(page = 1) {
    return get('/api/favorites', { page });
}
/** 添加服务端收藏。 */
export function addFavorite(id) {
    return post(`/api/favorite/${encodeURIComponent(id)}`);
}
/** 移除服务端收藏。 */
export function removeFavorite(id) {
    return del(`/api/favorite/${encodeURIComponent(id)}`);
}
/** 登录。 */
export function login(username, password) {
    return post('/api/login', { username, password });
}
/** 登出（前端清本地 session 即可，Worker 无状态）。 */
export function logout() {
    return post('/api/logout');
}
