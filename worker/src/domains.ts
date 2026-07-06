/**
 * 禁漫 API 域名池管理。
 *
 * 禁漫会定期换 API 域名，旧域名会 404，必须动态更新才能长期可用。
 * - 内置最新已知有效域名（2026-07 实测多客户端汇总）
 * - 启动时及请求全失败时通过 refreshApiDomains() 动态拉取最新域名覆盖此列表
 *
 * Worker 环境无内存持久化（每个请求可能是新实例），用 module-level 变量做"软缓存"，
 * 同一隔离区内的请求可复用。全失败兜底时强制刷新。
 */

// ---- API 域名池 ----
// 内置最新已知有效域名（避免首次启动若字节 CDN 不可达就没有可用域名）
const apiDomains: string[] = [
  'www.cdnhjk.net',
  'www.cdngwc.cc',
  'www.cdngwc.net',
  'www.cdngwc.club',
  'www.cdnutc.me',
  'www.cdnhth.net',
  'www.cdnhth.club',
  'www.cdnbea.net',
]

let domainIndex = 0

// 获取最新 API 域名的服务器（字节跳动 CDN，3 个镜像容灾）
const apiDomainServerUrls = [
  'https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt',
  'https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt',
  'https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt',
]

// ---- 图片 CDN 域名池 ----
const imageDomains = [
  'cdn-msp.jmapiproxy1.cc',
  'cdn-msp.jmapiproxy2.cc',
  'cdn-msp2.jmapiproxy2.cc',
  'cdn-msp3.jmapiproxy2.cc',
  'cdn-msp.jmapinodeudzn.net',
  'cdn-msp3.jmapinodeudzn.net',
]

let imageDomainIndex = 0

/** 当前 API 域名池快照。 */
export function apiDomainList(): string[] {
  return [...apiDomains]
}

/** 当前正在使用的 API 域名。 */
export function currentDomain(): string {
  return apiDomains[domainIndex] || apiDomains[0] || ''
}

/** 切换到下一个 API 域名（某域名失败时调用）。 */
export function rotateDomain(): string {
  domainIndex = (domainIndex + 1) % apiDomains.length
  return currentDomain()
}

/** 当前图片域名。 */
export function currentImageDomain(): string {
  return imageDomains[imageDomainIndex] || imageDomains[0]
}

/** 切换到下一个图片域名（某域名失败时调用）。 */
export function rotateImageDomain(): string {
  imageDomainIndex = (imageDomainIndex + 1) % imageDomains.length
  return currentImageDomain()
}

/**
 * 请求域名服务器，拉取禁漫最新 API 域名列表并更新池。
 * @param forced true=强制刷新（用于全失败兜底）
 * @return 是否成功更新
 */
export async function refreshApiDomains(): Promise<boolean> {
  for (const url of apiDomainServerUrls) {
    try {
      const resp = await fetch(url, { method: 'GET' })
      if (!resp.ok) continue
      const text = await resp.text()
      if (!text) continue
      const { decodeDomainServerResp } = await import('./crypto')
      const json = JSON.parse(decodeDomainServerResp(text))
      const list: string[] = (json.Server || []).filter((s: string) => s)
      if (list.length > 0) {
        apiDomains.length = 0
        apiDomains.push(...list)
        domainIndex = 0
        console.log(`[domains] API 域名已更新: ${list.join(', ')}`)
        return true
      }
    } catch (e) {
      console.warn(`[domains] 拉取最新域名失败 ${url}:`, e)
    }
  }
  return false
}
