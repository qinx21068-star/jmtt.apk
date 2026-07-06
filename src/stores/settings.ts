/**
 * 设置 store：主题、Worker URL、阅读方向、屏蔽词、免责声明状态。
 *
 * 所有设置持久化到 IndexedDB，启动时加载到内存。
 * 主题/Worker URL 也同步到 localStorage（首屏渲染前快速读取，避免闪烁）。
 */
import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import type { ReaderDirection, ThemeMode } from '@/api/types'
import { getWorkerUrl, setWorkerUrl } from '@/api/client'
import * as settingsRepo from '@/db/repositories/settings'
import * as blockedRepo from '@/db/repositories/blocked'

const STORAGE_KEY_THEME = 'jmtt-theme'
const STORAGE_KEY_DISCLAIMER = 'jmtt-disclaimer-accepted'

export const useSettingsStore = defineStore('settings', () => {
  // ---- 主题 ----
  const theme = ref<ThemeMode>(
    (localStorage.getItem(STORAGE_KEY_THEME) as ThemeMode) || 'system',
  )
  function setTheme(mode: ThemeMode) {
    theme.value = mode
    localStorage.setItem(STORAGE_KEY_THEME, mode)
    applyThemeToDocument()
  }
  function applyThemeToDocument() {
    const resolved =
      theme.value === 'system'
        ? window.matchMedia('(prefers-color-scheme: dark)').matches
          ? 'dark'
          : 'light'
        : theme.value
    document.documentElement.classList.remove('light', 'dark')
    document.documentElement.classList.add(resolved)
  }

  // ---- Worker URL ----
  const workerUrl = ref(getWorkerUrl())
  function setWorkerUrlValue(url: string) {
    setWorkerUrl(url)
    workerUrl.value = getWorkerUrl()
  }

  // ---- 阅读方向 ----
  const readerDirection = ref<ReaderDirection>('vertical')
  async function setReaderDirection(dir: ReaderDirection) {
    readerDirection.value = dir
    await settingsRepo.setSetting('readerDirection', dir)
  }

  // ---- 免责声明 ----
  const disclaimerAccepted = ref<boolean>(
    localStorage.getItem(STORAGE_KEY_DISCLAIMER) === '1',
  )
  function acceptDisclaimer() {
    disclaimerAccepted.value = true
    localStorage.setItem(STORAGE_KEY_DISCLAIMER, '1')
  }

  // ---- 屏蔽词 ----
  const blockedTags = ref<string[]>([])
  const blockedNames = ref<string[]>([])
  const blockedAuthors = ref<string[]>([])
  async function reloadBlocked() {
    const all = await blockedRepo.loadAllBlocked()
    blockedTags.value = all.tags
    blockedNames.value = all.names
    blockedAuthors.value = all.authors
  }
  async function addBlockedTag(tag: string) {
    await blockedRepo.addBlockedTag(tag)
    await reloadBlocked()
  }
  async function removeBlockedTag(tag: string) {
    await blockedRepo.removeBlockedTag(tag)
    await reloadBlocked()
  }
  async function addBlockedName(name: string) {
    await blockedRepo.addBlockedName(name)
    await reloadBlocked()
  }
  async function removeBlockedName(name: string) {
    await blockedRepo.removeBlockedName(name)
    await reloadBlocked()
  }
  async function addBlockedAuthor(author: string) {
    await blockedRepo.addBlockedAuthor(author)
    await reloadBlocked()
  }
  async function removeBlockedAuthor(author: string) {
    await blockedRepo.removeBlockedAuthor(author)
    await reloadBlocked()
  }

  /** 初始化：从 IndexedDB 加载持久化设置。 */
  async function init() {
    applyThemeToDocument()
    // 监听系统主题变化（仅 system 模式生效）
    window
      .matchMedia('(prefers-color-scheme: dark)')
      .addEventListener('change', () => {
        if (theme.value === 'system') applyThemeToDocument()
      })
    // 从 IndexedDB 加载
    readerDirection.value =
      (await settingsRepo.getSetting<ReaderDirection>('readerDirection', 'vertical'))
    await reloadBlocked()
  }

  // 监听主题变化实时应用
  watch(theme, applyThemeToDocument)

  return {
    theme,
    setTheme,
    workerUrl,
    setWorkerUrl: setWorkerUrlValue,
    readerDirection,
    setReaderDirection,
    disclaimerAccepted,
    acceptDisclaimer,
    blockedTags,
    blockedNames,
    blockedAuthors,
    reloadBlocked,
    addBlockedTag,
    removeBlockedTag,
    addBlockedName,
    removeBlockedName,
    addBlockedAuthor,
    removeBlockedAuthor,
    init,
  }
})
