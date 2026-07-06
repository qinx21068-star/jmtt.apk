/**
 * 禁漫移动端 API 客户端（Worker 端实现）。
 *
 * 移植自 jmcomic python 库的 JmApiClient，关键点：
 * - 每个 GET 请求带 header: token = md5(ts+secret), tokenparam = "ts,ver"
 * - 响应 json["data"] 是 base64+AES-ECB 密文，用 ts 解密得到真实 JSON
 * - /chapter_view_template 用 secret_2，且返回 HTML（解析 var scramble_id）
 * - 域名经常被墙，做轮换重试
 *
 * 全失败时调用 refreshApiDomains() 拉取最新域名再试一轮。
 */
import {
  APP_TOKEN_SECRET,
  APP_TOKEN_SECRET_2,
  SCRAMBLE_220980,
  decodeRespData,
  md5hex,
  token,
  tokenparam,
  ts,
} from './crypto'
import {
  apiDomainList,
  currentDomain,
  currentImageDomain,
  refreshApiDomains,
  rotateDomain,
} from './domains'

const UA =
  'Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) ' +
  'AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36'

/** Worker 端给前端的统一响应格式。 */
export interface ApiResponse<T> {
  code: number
  data: T | null
  errorMsg: string | null
}

export interface ComicBrief {
  id: string
  name: string
  author: string | null
  tags: string[]
  cover: string | null
  likes: string | null
  views: string | null
}

export interface PageResult<T> {
  page: number
  total: number | null
  items: T[]
}

export interface Chapter {
  id: string
  title: string
  sort: number
}

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

export interface ChapterImages {
  id: string
  title: string | null
  scramble_id: string
  images: string[]
}

// scramble_id 缓存（按 photoId）。Worker 同一隔离区可复用。
const scrambleCache = new Map<string, number>()

/**
 * 请求一个 API 接口，自动加 token 头、解密响应、域名轮换重试。
 *
 * @param path    接口路径，如 "/search"
 * @param query   查询参数
 * @param secret  token 密钥（普通接口 APP_TOKEN_SECRET，scramble 接口 APP_TOKEN_SECRET_2）
 * @param decrypt 是否解密响应 data 字段（scramble 接口返回 HTML，不解密）
 */
async function reqApi(
  path: string,
  query: Record<string, string> = {},
  secret: string = APP_TOKEN_SECRET,
  decrypt: boolean = true,
): Promise<string> {
  // 第一轮：用当前域名池
  const errs1: string[] = []
  const r1 = await reqApiOnce(path, query, secret, decrypt, errs1)
  if (r1 !== null) return r1

  // 全失败 → 拉取最新 API 域名，更新后再试一轮
  const refreshed = await refreshApiDomains()
  if (refreshed) {
    console.log(`[jmclient] 域名更新后重试 ${path}`)
    const errs2: string[] = []
    const r2 = await reqApiOnce(path, query, secret, decrypt, errs2)
    if (r2 !== null) return r2
    throw new Error(
      `所有域名均请求失败: ${path}\n` +
        `首轮(${errs1.length}域名全失败): ${errs1.join('; ')}\n` +
        `已拉取最新域名并重试，仍失败(${errs2.length}域名): ${errs2.join('; ')}`,
    )
  }
  throw new Error(
    `所有域名均请求失败: ${path}\n` +
      `首轮(${errs1.length}域名全失败): ${errs1.join('; ')}\n` +
      `且无法拉取最新域名（字节CDN不可达）`,
  )
}

/** 单轮遍历当前域名池请求；成功返回响应，全部失败返回 null。 */
async function reqApiOnce(
  path: string,
  query: Record<string, string>,
  secret: string,
  decrypt: boolean,
  errs: string[],
): Promise<string | null> {
  const snapshot = apiDomainList()
  if (snapshot.length === 0) return null
  let idx = 0
  for (let attempt = 0; attempt < snapshot.length; attempt++) {
    const domain = snapshot[idx]
    const url = new URL(`https://${domain}/${path.replace(/^\//, '')}`)
    Object.entries(query).forEach(([k, v]) => url.searchParams.set(k, v))

    const t = ts()
    const tok = token(t, secret)
    const tp = tokenparam(t)

    try {
      const resp = await fetch(url.toString(), {
        method: 'GET',
        headers: {
          'User-Agent': UA,
          token: tok,
          tokenparam: tp,
        },
      })
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
      const body = await resp.text()
      if (!body) throw new Error('空响应')
      if (!decrypt) return body

      // 响应外层: {"code":200,"data":"<密文>","errorMsg":null}
      const outer = JSON.parse(body)
      const code = outer.code ?? -1
      if (code !== 200) {
        throw new Error(`禁漫返回错误: ${outer.errorMsg || `code=${code}`}`)
      }
      const data = outer.data || ''
      if (!data) return '{}'
      return decodeRespData(data, t)
    } catch (e: any) {
      const brief = e?.message || String(e)
      console.warn(`[jmclient] 请求 ${path} @ ${domain} 失败: ${brief}`)
      errs.push(`${domain}: ${brief}`)
      idx = (idx + 1) % snapshot.length
      rotateDomain()
    }
  }
  return null
}

/** POST 请求（用于登录、收藏等）。 */
async function postApi(
  path: string,
  form: Record<string, string> = {},
  secret: string = APP_TOKEN_SECRET,
): Promise<any> {
  const r1 = await postApiOnce(path, form, secret)
  if (r1 !== null) return r1
  if (await refreshApiDomains()) {
    console.log(`[jmclient] 域名更新后重试 POST ${path}`)
    const r2 = await postApiOnce(path, form, secret)
    if (r2 !== null) return r2
  }
  throw new Error(`所有域名均请求失败: POST ${path}`)
}

async function postApiOnce(
  path: string,
  form: Record<string, string>,
  secret: string,
): Promise<any | null> {
  const snapshot = apiDomainList()
  if (snapshot.length === 0) return null
  let idx = 0
  for (let attempt = 0; attempt < snapshot.length; attempt++) {
    const domain = snapshot[idx]
    const url = `https://${domain}/${path.replace(/^\//, '')}`
    const t = ts()
    const tok = token(t, secret)
    const tp = tokenparam(t)

    const body = new URLSearchParams()
    Object.entries(form).forEach(([k, v]) => body.append(k, v))

    try {
      const resp = await fetch(url, {
        method: 'POST',
        headers: {
          'User-Agent': UA,
          token: tok,
          tokenparam: tp,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: body.toString(),
      })
      const text = await resp.text()
      if (!text) throw new Error('空响应')
      const outer = JSON.parse(text)
      if (outer.code !== 200) {
        throw new Error(outer.errorMsg || `HTTP ${resp.status}`)
      }
      const data = outer.data || ''
      return data ? JSON.parse(decodeRespData(data, t)) : {}
    } catch (e: any) {
      console.warn(`[jmclient] POST ${path} @ ${domain} 失败: ${e?.message}`)
      idx = (idx + 1) % snapshot.length
      rotateDomain()
    }
  }
  return null
}

// ------------------------------------------------------------------------
// 业务接口
// ------------------------------------------------------------------------

/** 归一化 JM 本子号："JM123456" / "jm123456" → "123456" */
function normalizeJmId(text: string): string {
  const t = text.trim()
  if (t.length >= 3) {
    const c0 = t[0]
    const c1 = t[1]
    if (
      (c0 === 'J' || c0 === 'j') &&
      (c1 === 'M' || c1 === 'm') &&
      /^[0-9]+$/.test(t.substring(2))
    ) {
      return t.substring(2)
    }
  }
  return t
}

function mapOrder(order: string): string {
  switch (order) {
    case 'latest':
      return 'mr'
    case 'views':
      return 'mv'
    case 'likes':
      return 'tf'
    case 'picture':
      return 'mp'
    default:
      return order || 'mr'
  }
}

function mapTime(time: string): string {
  switch (time) {
    case 'all':
      return 'a'
    case 'today':
      return 't'
    case 'week':
      return 'w'
    case 'month':
      return 'm'
    default:
      return time || 'a'
  }
}

/** 构造 Worker 图片代理 URL（前端访问）。 */
function buildImageProxyUrl(
  workerOrigin: string,
  photoIdOrAid: string,
  filename: string,
  scrambleId: number | null,
): string {
  const origin = currentImageDomain()
  const targetUrl = `https://${origin}/media/photos/${photoIdOrAid}/${filename}`
  const proxy = new URL('/api/image-proxy', workerOrigin)
  proxy.searchParams.set('url', targetUrl)
  if (scrambleId !== null) {
    proxy.searchParams.set('aid', photoIdOrAid)
    proxy.searchParams.set('scramble_id', scrambleId.toString())
    proxy.searchParams.set('filename', filename.replace(/\.\w+$/, ''))
  }
  return proxy.toString()
}

/** 构造封面 URL（Worker 图片代理形式）。 */
function buildCoverUrl(workerOrigin: string, albumId: string): string | null {
  if (!albumId) return null
  const origin = currentImageDomain()
  const targetUrl = `https://${origin}/media/albums/${albumId}.jpg`
  const proxy = new URL('/api/image-proxy', workerOrigin)
  proxy.searchParams.set('url', targetUrl)
  return proxy.toString()
}

/** 把搜索/收藏列表项中的 tags 字段解析为数组。 */
function parseItemTags(item: any): string[] {
  const tags: string[] = []
  const tagsVal = item.tags
  if (Array.isArray(tagsVal)) {
    tagsVal.forEach((t: string) => {
      const s = String(t || '').trim()
      if (s && !tags.includes(s)) tags.push(s)
    })
  } else if (typeof tagsVal === 'string' && tagsVal.trim()) {
    tagsVal
      .split(/[ ,、]/)
      .map((s) => s.trim())
      .filter((s) => s && !tags.includes(s))
      .forEach((s) => tags.push(s))
  }
  // category / category_sub 的 title 作为兜底标签
  const catTitle = item.category?.title?.trim()
  if (catTitle && !tags.includes(catTitle)) tags.push(catTitle)
  const subCatTitle = item.category_sub?.title?.trim()
  if (subCatTitle && !tags.includes(subCatTitle)) tags.push(subCatTitle)
  return tags
}

function parseSearchPage(json: any, page: number, workerOrigin: string): PageResult<ComicBrief> {
  const total = parseInt(json.total, 10)
  const content = json.content || json.list || []
  const items: ComicBrief[] = []
  for (const it of content) {
    const id = String(it.id || '')
    if (!id) continue
    items.push({
      id,
      name: it.name || '',
      author: it.author || null,
      tags: parseItemTags(it),
      cover: buildCoverUrl(workerOrigin, id),
      likes: it.likes || null,
      views: it.total_views || null,
    })
  }
  return { page, total: Number.isNaN(total) ? null : total, items }
}

// ------------------------------------------------------------------------
// 对外暴露的 API
// ------------------------------------------------------------------------

export async function search(
  q: string,
  page: number,
  order: string,
  time: string,
  workerOrigin: string,
): Promise<PageResult<ComicBrief>> {
  const normalized = normalizeJmId(q)
  const params = {
    main_tag: '0',
    search_query: normalized,
    page: page.toString(),
    o: mapOrder(order),
    t: mapTime(time),
  }
  const json = JSON.parse(await reqApi('/search', params))

  // 本子号直搜：禁漫返回 redirect_aid
  const redirectAid = json.redirect_aid ? String(json.redirect_aid) : ''
  if (redirectAid && page === 1) {
    try {
      const detail = await albumDetail(redirectAid, workerOrigin)
      return {
        page: 1,
        total: 1,
        items: [
          {
            id: detail.id,
            name: detail.name,
            author: detail.author,
            tags: detail.tags,
            cover: detail.cover,
            likes: detail.likes,
            views: detail.views,
          },
        ],
      }
    } catch (e) {
      console.warn('[jmclient] redirect_aid 取详情失败:', e)
      return { page, total: 0, items: [] }
    }
  }
  return parseSearchPage(json, page, workerOrigin)
}

export async function categoriesFilter(
  page: number,
  time: string,
  category: string,
  order: string,
  workerOrigin: string,
): Promise<PageResult<ComicBrief>> {
  const o = time === 'a' || !time ? order : `${order}_${time}`
  const params = {
    page: page.toString(),
    order: '',
    c: category || '0',
    o,
  }
  const json = JSON.parse(await reqApi('/categories/filter', params))
  return parseSearchPage(json, page, workerOrigin)
}

export async function latest(
  page: number,
  category: string,
  workerOrigin: string,
): Promise<PageResult<ComicBrief>> {
  return categoriesFilter(page, 'a', category, 'mr', workerOrigin)
}

export async function ranking(
  time: string,
  category: string,
  page: number,
  workerOrigin: string,
): Promise<PageResult<ComicBrief>> {
  return categoriesFilter(page, mapTime(time), category, 'mv', workerOrigin)
}

export async function albumDetail(
  albumId: string,
  workerOrigin: string,
): Promise<ComicDetail> {
  const json = JSON.parse(await reqApi('/album', { id: albumId }))
  const data = json.data || json
  const id = String(data.id || albumId)
  const name = data.name || ''
  const author = Array.isArray(data.author)
    ? data.author.filter((a: string) => a).join(' ') || null
    : data.author || null
  const tags = Array.isArray(data.tags)
    ? data.tags.filter((t: string) => t)
    : []

  const chapters: Chapter[] = []
  const series = data.series
  if (Array.isArray(series) && series.length > 0) {
    series.forEach((ch: any, i: number) => {
      chapters.push({
        id: String(ch.id || ''),
        title: ch.name || `第${ch.sort || i}话`,
        sort: parseInt(ch.sort, 10) || i,
      })
    })
  }
  if (chapters.length === 0) {
    chapters.push({ id: albumId, title: name || '正文', sort: 0 })
  }

  return {
    id,
    name,
    author,
    description: data.description || null,
    tags,
    cover: buildCoverUrl(workerOrigin, id),
    likes: data.likes || null,
    views: data.total_views || null,
    chapters,
  }
}

/** 获取 scramble_id（带缓存）。请求 /chapter_view_template 解析 HTML。 */
async function getScrambleId(photoId: string, aidFromResp: string): Promise<number> {
  const cached = scrambleCache.get(photoId)
  if (cached !== undefined) return cached
  const aid = parseInt(aidFromResp, 10) || parseInt(photoId, 10) || 0
  try {
    const html = await reqApi(
      '/chapter_view_template',
      {
        id: photoId,
        mode: 'vertical',
        page: '0',
        app_img_shunt: '1',
        express: 'off',
        v: ts(),
      },
      APP_TOKEN_SECRET_2,
      false,
    )
    const match = /var\s+scramble_id\s*=\s*(\d+)\s*;/.exec(html)
    const sid = match ? parseInt(match[1], 10) : SCRAMBLE_220980
    scrambleCache.set(photoId, sid)
    return sid
  } catch (e) {
    console.warn(`[jmclient] 获取 scramble_id 失败，用默认 ${SCRAMBLE_220980}:`, e)
    return SCRAMBLE_220980
  }
}

export async function chapterImages(
  photoId: string,
  workerOrigin: string,
): Promise<ChapterImages> {
  const json = JSON.parse(await reqApi('/chapter', { id: photoId }))
  const data = json.data || json
  const id = String(data.id || photoId)
  const name = data.name || ''
  const scrambleId = await getScrambleId(photoId, id)

  const images: string[] = []
  if (Array.isArray(data.images)) {
    for (const fn of data.images) {
      if (fn) images.push(buildImageProxyUrl(workerOrigin, id, fn, scrambleId))
    }
  }

  return {
    id: photoId,
    title: name || null,
    scramble_id: scrambleId.toString(),
    images,
  }
}

export async function favorites(
  page: number,
  workerOrigin: string,
): Promise<PageResult<ComicBrief>> {
  const json = JSON.parse(
    await reqApi('/favorite', {
      page: page.toString(),
      folder_id: '0',
      o: 'mr',
    }),
  )
  const total = parseInt(json.total, 10)
  const list = json.list || []
  const items: ComicBrief[] = []
  for (const it of list) {
    const id = String(it.id || '')
    if (!id) continue
    items.push({
      id,
      name: it.name || '',
      author: it.author || null,
      tags: parseItemTags(it),
      cover: buildCoverUrl(workerOrigin, id),
      likes: null,
      views: null,
    })
  }
  return { page, total: Number.isNaN(total) ? null : total, items }
}

export async function addFavorite(albumId: string): Promise<boolean> {
  try {
    await postApi('/favorite', { aid: albumId })
    return true
  } catch (e) {
    console.warn('[jmclient] 添加收藏失败:', e)
    return false
  }
}

export async function removeFavorite(albumId: string): Promise<boolean> {
  try {
    await postApi('/favorite', { aid: albumId, fid: '0' })
    return true
  } catch (e) {
    console.warn('[jmclient] 移除收藏失败:', e)
    return false
  }
}

export async function login(username: string, password: string): Promise<boolean> {
  try {
    const r = await postApi('/login', { username, password })
    return !!(r.s || r.uid)
  } catch (e) {
    console.warn('[jmclient] 登录失败:', e)
    return false
  }
}

// 重新导出 md5hex 供 imageproxy 使用
export { md5hex }
