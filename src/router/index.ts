import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('@/views/SearchView.vue'),
  },
  {
    path: '/comic/:id',
    name: 'comic',
    component: () => import('@/views/ComicDetailView.vue'),
  },
  {
    path: '/reader/:chapterId',
    name: 'reader',
    component: () => import('@/views/ReaderView.vue'),
  },
  {
    path: '/favorites',
    name: 'favorites',
    component: () => import('@/views/FavoritesView.vue'),
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/SettingsView.vue'),
  },
  {
    path: '/about',
    name: 'about',
    component: () => import('@/views/AboutView.vue'),
  },
  {
    path: '/debug',
    name: 'debug',
    component: () => import('@/views/DebugView.vue'),
  },
]

const router = createRouter({
  // GitHub Pages 不支持 SPA history 模式的服务端重定向，用 hash 路由避免刷新 404
  history: createWebHashHistory(),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    return { top: 0 }
  },
})

export default router
