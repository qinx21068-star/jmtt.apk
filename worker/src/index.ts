/**
 * Worker 入口：路由 + CORS + 错误处理。
 *
 * 路由：
 *   GET  /api/health          健康检查
 *   GET  /api/latest          最新列表
 *   GET  /api/ranking         排行榜
 *   GET  /api/search          搜索
 *   GET  /api/comic/:id       漫画详情
 *   GET  /api/chapter/:id     章节图片
 *   GET  /api/favorites       服务端收藏
 *   POST /api/favorite/:id    添加收藏
 *   DELETE /api/favorite/:id  移除收藏
 *   POST /api/login           登录
 *   POST /api/logout          登出
 *   GET  /api/image-proxy     图片代理
 */
import {
  addFavorite,
  albumDetail,
  chapterImages,
  favorites,
  latest,
  login,
  ranking,
  removeFavorite,
  search,
  type ApiResponse,
} from './jmclient'
import { proxyImage } from './imageproxy'

/** CORS 头（所有响应统一加） */
function corsHeaders(): Record<string, string> {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    'Access-Control-Max-Age': '86400',
  }
}

function jsonOk<T>(data: T): Response {
  const body: ApiResponse<T> = { code: 200, data, errorMsg: null }
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      ...corsHeaders(),
    },
  })
}

function jsonError(status: number, msg: string): Response {
  const body: ApiResponse<null> = { code: status, data: null, errorMsg: msg }
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      ...corsHeaders(),
    },
  })
}

/** 从 URL 提取路径参数（如 /api/comic/123 → id=123） */
function extractId(url: URL, prefix: string): string | null {
  const path = url.pathname
  if (!path.startsWith(prefix + '/')) return null
  const id = path.substring(prefix.length + 1)
  return decodeURIComponent(id) || null
}

/** 主路由分发。 */
async function handleRequest(request: Request): Promise<Response> {
  const url = new URL(request.url)
  const path = url.pathname
  const method = request.method
  const origin = url.origin

  // CORS 预检
  if (method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders() })
  }

  try {
    // ---- 健康检查 ----
    if (path === '/api/health' && method === 'GET') {
      return jsonOk({ status: 'ok', time: Date.now() })
    }

    // ---- 图片代理 ----
    if (path === '/api/image-proxy' && method === 'GET') {
      const targetUrl = url.searchParams.get('url')
      if (!targetUrl) return jsonError(400, 'missing url param')
      return await proxyImage(targetUrl)
    }

    // ---- 业务 API ----
    if (path === '/api/latest' && method === 'GET') {
      const page = parseInt(url.searchParams.get('page') || '1', 10) || 1
      const category = url.searchParams.get('category') || ''
      return jsonOk(await latest(page, category, origin))
    }

    if (path === '/api/ranking' && method === 'GET') {
      const time = url.searchParams.get('time') || 'all'
      const category = url.searchParams.get('category') || ''
      const page = parseInt(url.searchParams.get('page') || '1', 10) || 1
      return jsonOk(await ranking(time, category, page, origin))
    }

    if (path === '/api/search' && method === 'GET') {
      const q = url.searchParams.get('q') || ''
      const page = parseInt(url.searchParams.get('page') || '1', 10) || 1
      const order = url.searchParams.get('order') || 'latest'
      const time = url.searchParams.get('time') || 'all'
      return jsonOk(await search(q, page, order, time, origin))
    }

    const comicId = extractId(url, '/api/comic')
    if (comicId !== null && method === 'GET') {
      return jsonOk(await albumDetail(comicId, origin))
    }

    const chapterId = extractId(url, '/api/chapter')
    if (chapterId !== null && method === 'GET') {
      return jsonOk(await chapterImages(chapterId, origin))
    }

    if (path === '/api/favorites' && method === 'GET') {
      const page = parseInt(url.searchParams.get('page') || '1', 10) || 1
      return jsonOk(await favorites(page, origin))
    }

    const favoriteId = extractId(url, '/api/favorite')
    if (favoriteId !== null) {
      if (method === 'POST') {
        const ok = await addFavorite(favoriteId)
        return jsonOk({ success: ok, message: ok ? null : '添加收藏失败' })
      }
      if (method === 'DELETE') {
        const ok = await removeFavorite(favoriteId)
        return jsonOk({ success: ok, message: ok ? null : '移除收藏失败' })
      }
    }

    if (path === '/api/login' && method === 'POST') {
      const body = (await request.json()) as { username: string; password: string }
      if (!body.username || !body.password) {
        return jsonError(400, 'username and password required')
      }
      const ok = await login(body.username, body.password)
      return jsonOk({ success: ok, message: ok ? null : '登录失败，请检查账号密码' })
    }

    if (path === '/api/logout' && method === 'POST') {
      // 禁漫无显式 logout 接口，前端清本地 session 即可
      return jsonOk({ success: true, message: null })
    }

    // 根路径返回简单信息
    if (path === '/' || path === '') {
      return jsonOk({
        name: 'jmtt-proxy',
        version: '1.0.0',
        docs: '/api/health, /api/latest, /api/search, /api/comic/:id, /api/chapter/:id',
      })
    }

    return jsonError(404, `Not Found: ${method} ${path}`)
  } catch (e: any) {
    console.error('[worker] unhandled error:', e)
    return jsonError(500, e?.message || 'Internal Server Error')
  }
}

export default {
  async fetch(request: Request): Promise<Response> {
    return handleRequest(request)
  },
}
