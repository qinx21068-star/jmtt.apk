/** 默认 Worker 地址（用户可覆盖）。 */
const DEFAULT_WORKER_URL = '';
const STORAGE_KEY_WORKER_URL = 'jmtt-worker-url';
/** 读取 Worker URL（localStorage 持久化）。 */
export function getWorkerUrl() {
    return localStorage.getItem(STORAGE_KEY_WORKER_URL) || DEFAULT_WORKER_URL;
}
/** 设置 Worker URL（空字符串=用默认）。 */
export function setWorkerUrl(url) {
    const trimmed = url.trim();
    if (trimmed) {
        localStorage.setItem(STORAGE_KEY_WORKER_URL, trimmed.replace(/\/+$/, ''));
    }
    else {
        localStorage.removeItem(STORAGE_KEY_WORKER_URL);
    }
}
/** 拼装完整 Worker URL。 */
function buildUrl(path, params) {
    const base = getWorkerUrl() || '';
    const url = new URL(path, base ? `${base}/` : window.location.origin);
    if (params) {
        for (const [k, v] of Object.entries(params)) {
            if (v !== undefined && v !== null && v !== '') {
                url.searchParams.set(k, String(v));
            }
        }
    }
    return url.toString();
}
/** GET 请求。 */
export async function get(path, params) {
    const resp = await fetch(buildUrl(path, params), {
        method: 'GET',
        headers: { Accept: 'application/json' },
    });
    return parseResponse(resp);
}
/** POST 请求。 */
export async function post(path, body) {
    const resp = await fetch(buildUrl(path), {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
        },
        body: body ? JSON.stringify(body) : undefined,
    });
    return parseResponse(resp);
}
/** DELETE 请求。 */
export async function del(path) {
    const resp = await fetch(buildUrl(path), {
        method: 'DELETE',
        headers: { Accept: 'application/json' },
    });
    return parseResponse(resp);
}
/** 解析 Worker 统一响应格式。 */
async function parseResponse(resp) {
    if (!resp.ok && resp.status !== 400 && resp.status !== 404 && resp.status !== 500) {
        throw new Error(`HTTP ${resp.status}: ${resp.statusText}`);
    }
    const text = await resp.text();
    let json;
    try {
        json = JSON.parse(text);
    }
    catch {
        throw new Error(`响应解析失败: ${text.slice(0, 200)}`);
    }
    if (json.code !== 200 || json.data === null) {
        throw new Error(json.errorMsg || `请求失败 (code=${json.code})`);
    }
    return json.data;
}
