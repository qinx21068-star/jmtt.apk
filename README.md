# 本子天国 · PWA 网页版

iOS / Android 通用的禁漫漫画阅读器 PWA 应用。无需上架应用商店，无需 Apple Developer 账号，无需越狱——用 Safari 打开网址，添加到主屏幕即可像原生 App 一样使用。

> 本项目是 [JMReader Android 版](https://github.com/qinx21068-star/jmtt.apk) 的 PWA 网页版分支，加密协议与图片解码算法完全移植自 Android 端。

## 功能特性

- **首页**：最新 / 周榜 / 月榜 / 总榜切换，8 大分类筛选，无限滚动
- **搜索**：关键词 / 本子号直搜，4 种排序，搜索历史记录
- **详情**：模糊封面背景、章节列表、收藏、继续阅读
- **阅读器**：全屏浏览、点击切控件、章节抽屉、上下章切换、阅读进度记忆
- **收藏**：本地收藏（IndexedDB）+ 服务端收藏（需登录禁漫账号）
- **设置**：主题切换（跟随系统 / 浅色 / 深色）、阅读方向、屏蔽词管理（标签 / 标题 / 作者）
- **图片解码**：前端 Canvas 实现禁漫 scramble 切片还原
- **离线优先**：PWA Service Worker 缓存，弱网可用
- **iOS 适配**：刘海 / Home Bar 安全区、滚动惯性、全屏沉浸

## 技术栈

| 层 | 技术 |
|---|---|
| 前端框架 | Vue 3 + TypeScript + Vite |
| 状态管理 | Pinia |
| 路由 | Vue Router（hash 模式，兼容 GitHub Pages） |
| 样式 | Tailwind CSS + CSS 变量 |
| 本地存储 | Dexie.js（IndexedDB） |
| PWA | vite-plugin-pwa（Workbox） |
| 代理后端 | Cloudflare Workers（nodejs_compat） |
| 加解密 | node:crypto（AES-256-ECB）+ 纯 JS MD5 |

## 架构概览

```
┌─────────────┐     ┌──────────────────────┐     ┌──────────────┐
│  iOS Safari │────▶│  Cloudflare Worker   │────▶│  禁漫 API    │
│  / PWA App  │ API │  (加解密 / 域名轮换)  │     │  (加密响应)  │
└─────────────┘     └──────────────────────┘     └──────────────┘
       │                    │
       │ 图片代理            │ 图片字节转发 + CORS
       ▼                    ▼
┌─────────────┐     ┌──────────────────────┐
│  IndexedDB  │     │  禁漫图片 CDN        │
│  收藏/历史  │     │  (scramble 加密图)   │
└─────────────┘     └──────────────────────┘
```

**为什么需要 Worker 代理？**
1. CORS 限制：github.io 等静态托管无法直连禁漫 API
2. 密钥安全：加解密密钥不能暴露在前端 JS
3. 响应解密：API 返回 AES-256-ECB 加密的 base64 数据
4. 图片 CORS：图片 CDN 也需代理转发并加 CORS 头
5. 域名轮换：禁漫 API 域名经常变更，Worker 端自动轮换 + 动态拉取最新域名

## 目录结构

```
jmtt-pwa/
├── src/                    # 前端源码
│   ├── api/                # API 客户端 + 类型 + 图片解码
│   ├── components/         # 公共组件（卡片、网格、骨架屏等）
│   ├── composables/        # 无限滚动、屏蔽过滤
│   ├── db/                 # Dexie 数据层（收藏/历史/设置/屏蔽词）
│   ├── router/             # 路由配置
│   ├── stores/             # Pinia stores
│   ├── views/              # 7 个页面视图
│   ├── App.vue
│   └── main.ts
├── worker/                 # Cloudflare Worker 代理
│   ├── src/
│   │   ├── crypto.ts       # 禁漫加解密（md5/AES-ECB）
│   │   ├── domains.ts      # API/图片域名池 + 动态刷新
│   │   ├── jmclient.ts     # 禁漫 API 客户端
│   │   ├── imageproxy.ts   # 图片代理转发
│   │   └── index.ts        # Worker 入口路由
│   ├── wrangler.toml
│   └── package.json
└── package.json
```

## 部署指南

### 第一步：部署 Cloudflare Worker 代理

1. 安装 Wrangler CLI 并登录：
   ```bash
   cd worker
   npm install
   npx wrangler login
   ```

2. 部署：
   ```bash
   npx wrangler deploy
   ```

3. 记下 Worker 地址，形如 `https://jmtt-proxy.<你的子域>.workers.dev`

> Worker 免费额度：每天 10 万次请求，个人使用完全够用。

### 第二步：部署前端到 GitHub Pages

1. Fork 本仓库

2. 修改 `vite.config.ts` 中的 `base` 为你的 GitHub Pages 路径（若仓库名不是 `用户名.github.io`）：
   ```ts
   base: '/jmtt.apk/',  // 仓库名
   ```

3. 构建并推送：
   ```bash
   cd ..
   npm install
   npm run build
   ```
   将 `dist/` 目录内容推送到 `gh-pages` 分支，或配置 GitHub Actions 自动部署。

4. 访问 `https://<用户名>.github.io/<仓库名>/`

### 第三步：配置 Worker 地址

打开网页版后，进入 **设置 → 网络代理**，填入第一步获得的 Worker 地址，点击「测试连接」确认正常后保存。

## iOS 使用方法（无需上架）

1. 用 **Safari** 打开部署好的网址（Chrome 不支持添加 PWA 到主屏幕）
2. 点击底部 **分享** 按钮
3. 选择 **添加到主屏幕**
4. 桌面会出现「本子天国」图标，点开即可全屏沉浸使用，和原生 App 体验一致

> 首次打开会有 5 秒免责声明强制阅读倒计时。

## 本地开发

```bash
# 前端
npm install
npm run dev          # http://localhost:5173

# Worker（本地调试）
cd worker
npx wrangler dev     # http://localhost:8787
```

开发时前端默认会请求同源的 `/api/*`，若 Worker 跑在 8787 端口，在前端「设置」里填入 `http://localhost:8787` 即可。

## 免责声明

本项目仅供个人学习、技术交流和研究使用。

- 本项目为第三方开源客户端，不提供任何漫画内容资源，仅作为访问接口的工具
- 使用者必须遵守所在国家/地区的法律法规
- 本项目不鼓励、不支持任何侵犯版权的行为，请支持正版
- 项目作者不承担任何因使用本软件产生的法律责任
- 未成年人禁止使用本软件

使用本软件即表示您已阅读并同意本声明。如不同意，请立即停止使用并删除。

## 许可证

MIT
