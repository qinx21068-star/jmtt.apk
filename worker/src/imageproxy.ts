/**
 * 图片代理：转发禁漫图片 CDN 字节，加 CORS 头，失败时切换图片域名重试。
 *
 * Worker 环境无 Canvas / Image，无法做 scramble 图片解码。
 * scramble 解码在前端 Canvas 中完成（见 src/components/JmImage.vue）。
 *
 * URL 形如：
 *   /api/image-proxy?url=https://cdn-msp.xxx/media/photos/413446/00047.webp
 *   &aid=413446&scramble_id=220980&filename=00047
 *
 * 前端根据 aid / scramble_id / filename 自行判断是否需要解码。
 */
import { currentImageDomain, rotateImageDomain } from './domains'

/** 从原始 URL 提取路径，把域名替换为当前图片域名。 */
function rebuildUrl(originalUrl: string): string {
  try {
    const u = new URL(originalUrl)
    // 把 host 替换为当前图片域名（避免原 URL 域名失效）
    const newHost = currentImageDomain()
    return `https://${newHost}${u.pathname}${u.search}`
  } catch {
    return originalUrl
  }
}

/**
 * 转发图片字节，失败时切换图片域名重试。
 * @param originalUrl 原始图片 URL（前端传入）
 * @returns Response（图片字节 + CORS 头）
 */
export async function proxyImage(originalUrl: string): Promise<Response> {
  const targetUrl = rebuildUrl(originalUrl)

  try {
    const resp = await fetch(targetUrl, {
      method: 'GET',
      headers: {
        'User-Agent':
          'Mozilla/5.0 (Linux; Android 9; V1938CT) AppleWebKit/537.36 Chrome/91.0.4472.114 Safari/537.36',
        Referer: 'https://www.jmcomic.me',
      },
      cf: {
        // Cloudflare 缓存图片，减少对禁漫 CDN 的请求
        cacheTtl: 60 * 60 * 24 * 7, // 7 天
        cacheEverything: true,
      },
    })

    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`)
    }

    const bytes = await resp.arrayBuffer()
    return new Response(bytes, {
      status: 200,
      headers: {
        'Content-Type': resp.headers.get('Content-Type') || 'image/webp',
        'Cache-Control': 'public, max-age=604800, immutable',
        'Access-Control-Allow-Origin': '*',
      },
    })
  } catch (e) {
    // 切换图片域名重试一次
    const errMsg = e instanceof Error ? e.message : String(e)
    console.warn(`[imageproxy] ${targetUrl} 失败: ${errMsg}，切换域名重试`)
    const newHost = rotateImageDomain()
    const retryUrl = (() => {
      try {
        const u = new URL(targetUrl)
        return `https://${newHost}${u.pathname}${u.search}`
      } catch {
        return targetUrl
      }
    })()

    try {
      const resp = await fetch(retryUrl, {
        method: 'GET',
        headers: {
          'User-Agent':
            'Mozilla/5.0 (Linux; Android 9; V1938CT) AppleWebKit/537.36 Chrome/91.0.4472.114 Safari/537.36',
          Referer: 'https://www.jmcomic.me',
        },
        cf: {
          cacheTtl: 60 * 60 * 24 * 7,
          cacheEverything: true,
        },
      })
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
      const bytes = await resp.arrayBuffer()
      return new Response(bytes, {
        status: 200,
        headers: {
          'Content-Type': resp.headers.get('Content-Type') || 'image/webp',
          'Cache-Control': 'public, max-age=604800, immutable',
          'Access-Control-Allow-Origin': '*',
        },
      })
    } catch (e2) {
      const errMsg2 = e2 instanceof Error ? e2.message : String(e2)
      return new Response(
        JSON.stringify({ error: `图片加载失败: ${errMsg2}` }),
        {
          status: 502,
          headers: {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': '*',
          },
        },
      )
    }
  }
}
