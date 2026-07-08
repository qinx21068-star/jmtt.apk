import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'
import router from './router'

// ============================================================================
// 强制清理旧版 Service Worker 和缓存
// ============================================================================
// 历史问题：
// 1. 旧版 SW 用 NetworkFirst 缓存了 /api/* 响应，Worker 不可达时把 HTML 404
//    也缓存下来 → 前端报"响应解析失败: <!DOCTYPE html>"
// 2. 旧版 SW 用 precache 缓存了旧 index.html 和 JS，即使部署新代码，
//    旧 SW 仍返回旧 JS → ReaderImage 的 IntersectionObserver bug 仍存在 → 阅读器黑屏
//
// 解决：每次启动主动注销所有 SW + 删所有 caches，让浏览器重新拉最新资源。
// 短期内会失去离线能力，但确保用户能拿到最新代码。等所有用户都更新到新版后
// 可以移除这段强制清理逻辑。
if ('serviceWorker' in navigator) {
  navigator.serviceWorker
    .getRegistrations()
    .then((regs) => {
      for (const r of regs) {
        r.unregister()
      }
    })
    .catch(() => {})
}
if ('caches' in window) {
  caches.keys().then((keys) => {
    for (const k of keys) caches.delete(k).catch(() => {})
  }).catch(() => {})
}

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
