import { ref, watchEffect, onMounted, computed } from 'vue'

export type ThemeMode = 'system' | 'light' | 'dark'

const STORAGE_KEY = 'jmtt-theme'

export function useTheme() {
  const mode = ref<ThemeMode>('system')

  const getStoredMode = (): ThemeMode => {
    const saved = localStorage.getItem(STORAGE_KEY) as ThemeMode | null
    if (saved === 'light' || saved === 'dark' || saved === 'system') return saved
    return 'system'
  }

  const systemPrefersDark = () =>
    window.matchMedia('(prefers-color-scheme: dark)').matches

  const resolvedTheme = computed<'light' | 'dark'>(() => {
    if (mode.value === 'system') return systemPrefersDark() ? 'dark' : 'light'
    return mode.value
  })

  const applyTheme = () => {
    const t = resolvedTheme.value
    document.documentElement.classList.remove('light', 'dark')
    document.documentElement.classList.add(t)
  }

  const setMode = (m: ThemeMode) => {
    mode.value = m
    localStorage.setItem(STORAGE_KEY, m)
  }

  onMounted(() => {
    mode.value = getStoredMode()
    applyTheme()
    // 监听系统主题变化（仅 system 模式生效）
    window
      .matchMedia('(prefers-color-scheme: dark)')
      .addEventListener('change', () => {
        if (mode.value === 'system') applyTheme()
      })
  })

  watchEffect(() => {
    applyTheme()
  })

  return {
    mode,
    setMode,
    isDark: computed(() => resolvedTheme.value === 'dark'),
  }
}
