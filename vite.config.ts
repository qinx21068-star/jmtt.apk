import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import { VitePWA } from 'vite-plugin-pwa'

// https://vite.dev/config/
export default defineConfig({
  // 部署到 GitHub Pages 的子路径 /jmtt.apk/，必须设置 base
  base: '/jmtt.apk/',
  build: {
    sourcemap: false,
  },
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'icons/apple-touch-icon.png'],
      manifest: {
        name: '本子天国',
        short_name: '本子天国',
        description: 'JMReader PWA - iOS 友好的漫画阅读器',
        theme_color: '#1a1625',
        background_color: '#1a1625',
        display: 'standalone',
        orientation: 'portrait',
        start_url: './',
        scope: './',
        lang: 'zh-CN',
        icons: [
          {
            src: 'icons/pwa-192.png',
            sizes: '192x192',
            type: 'image/png',
          },
          {
            src: 'icons/pwa-512.png',
            sizes: '512x512',
            type: 'image/png',
          },
          {
            src: 'icons/maskable-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,ico,woff,woff2}'],
        // 不缓存图片 API 响应（图片走 Worker 代理 + 浏览器 HTTP 缓存）
        navigateFallback: 'index.html',
        // 关键：skipWaiting + clientsClaim 让新 SW 立即接管，
        // 否则用户必须关闭所有标签页才能拿到新代码，导致阅读器图片修复无法生效
        skipWaiting: true,
        clientsClaim: true,
        // 不缓存 /api/* 响应：之前 NetworkFirst 会把 Worker 不可达时的 HTML 404
        // 错误响应也缓存下来，导致即使 Worker 修好，SW 仍返回缓存的 HTML
        // → 前端报"响应解析失败: <!DOCTYPE html>"。
        // 移除 runtimeCaching 后，API 请求直连 Worker，不走 SW 缓存。
        runtimeCaching: [],
        // 清理旧 SW 留下的缓存（api-cache 等），避免旧错误响应残留
        cleanupOutdatedCaches: true,
      },
      devOptions: {
        enabled: false,
      },
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
