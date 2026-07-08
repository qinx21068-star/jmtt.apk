/**
 * 图片代理：转发禁漫图片 CDN 字节，加 CORS 头，失败时遍历所有图片域名重试。
 *
 * Worker 环境无 Canvas / Image，无法做 scramble 图片解码。
 * scramble 解码在前端 Canvas 中完成（见 src/components/JmImage.vue）。
 *
 * URL 形如：
 *   /api/image-proxy?url=https://cdn-msp.xxx/media/photos/413446/00047.webp
 *   &aid=413446&scramble_id=220980&filename=00047
 *
 * 前端根据 aid / scramble_id / filename 自行判断是否需要解码。
 *
 * 重要：禁漫图片 CDN 单域名经常抽风（403/5xx/超时），必须遍历所有候选域名
 * 直到成功，而不是只重试一次就 502（之前就是这样导致阅读器全黑）。
 */
import { imageDomains, currentImageDomain, rotateImageDomain } from './domains'

/** 从原始 URL 提取路径，把域名替换为指定图片域名。 */
function rebuildUrl(originalUrl: string, host: string): string {
  try {
    const u = new URL(originalUrl)
    return `https://${host}${u.pathname}${u.search}`
  } catch {
    return originalUrl
  }
}

const IMAGE_UA =
  'Mozilla/5.0 (Linux; Android 9; V1938CT) AppleWebKit/537.36 Chrome/91.0.4472.114 Safari/537.36'

/**
 * 转发图片字节，遍历所有图片域名直到成功。
 * @param originalUrl 原始图片 URL（前端传入）
 * @returns Response（图片字节 + CORS 头）
 */
export async function proxyImage(originalUrl: string): Promise<Response> {
  // 候选域名顺序：当前域名优先，然后轮换其他域名。去重保序。
  const firstHost = currentImageDomain()
  const orderedHosts: string[] = [firstHost]
  for (const h of imageDomains) {
    if (!orderedHosts.includes(h)) orderedHosts.push(h)
  }

  let lastErr = ''
  for (let i = 0; i < orderedHosts.length; i++) {
    const host = orderedHosts[i]
    const targetUrl = rebuildUrl(originalUrl, host)
    try {
      const resp = await fetch(targetUrl, {
        method: 'GET',
        headers: {
          'User-Agent': IMAGE_UA,
          Referer: 'https://www.jmcomic.me',
        },
        cf: {
          // Cloudflare 缓存图片，减少对禁漫 CDN 的请求
          cacheTtl: 60 * 60 * 24 * 7, // 7 天
          cacheEverything: true,
        },
      })

      if (!resp.ok) {
        lastErr = `HTTP ${resp.status}`
        console.warn(`[imageproxy] ${targetUrl} 失败: ${lastErr}，尝试下一个域名`)
        // 切换到下一个域名继续重试
        rotateImageDomain()
        continue
      }

      const bytes = await resp.arrayBuffer()
      // 成功：若不是第一个域名，把全局当前域名切到这个可用的
      if (i > 0) {
        // 把 imageDomainIndex 移到当前成功的 host
        const idx = imageDomains.indexOf(host)
        if (idx >= 0) {
          // 通过 rotateImageDomain 把索引推进到 idx
          // （imageDomainIndex 是模块级私有变量，这里用 rotate 推进）
          for (let j = 0; j < idx; j++) rotateImageDomain()
        }
      }
      return new Response(bytes, {
        status: 200,
        headers: {
          'Content-Type': resp.headers.get('Content-Type') || 'image/webp',
          'Cache-Control': 'public, max-age=604800, immutable',
          'Access-Control-Allow-Origin': '*',
        },
      })
    } catch (e) {
      lastErr = e instanceof Error ? e.message : String(e)
      console.warn(`[imageproxy] ${targetUrl} 异常: ${lastErr}，尝试下一个域名`)
      rotateImageDomain()
    }
  }

  // 所有域名都失败
  return new Response(
    JSON.stringify({
      error: `图片加载失败（已尝试 ${orderedHosts.length} 个域名）: ${lastErr}`,
      tried: orderedHosts,
    }),
    {
      status: 502,
      headers: {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
      },
    },
  )
}
