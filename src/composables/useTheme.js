import { ref, watchEffect, onMounted, computed } from 'vue';
const STORAGE_KEY = 'jmtt-theme';
export function useTheme() {
    const mode = ref('system');
    const getStoredMode = () => {
        const saved = localStorage.getItem(STORAGE_KEY);
        if (saved === 'light' || saved === 'dark' || saved === 'system')
            return saved;
        return 'system';
    };
    const systemPrefersDark = () => window.matchMedia('(prefers-color-scheme: dark)').matches;
    const resolvedTheme = computed(() => {
        if (mode.value === 'system')
            return systemPrefersDark() ? 'dark' : 'light';
        return mode.value;
    });
    const applyTheme = () => {
        const t = resolvedTheme.value;
        document.documentElement.classList.remove('light', 'dark');
        document.documentElement.classList.add(t);
    };
    const setMode = (m) => {
        mode.value = m;
        localStorage.setItem(STORAGE_KEY, m);
    };
    onMounted(() => {
        mode.value = getStoredMode();
        applyTheme();
        // 监听系统主题变化（仅 system 模式生效）
        window
            .matchMedia('(prefers-color-scheme: dark)')
            .addEventListener('change', () => {
            if (mode.value === 'system')
                applyTheme();
        });
    });
    watchEffect(() => {
        applyTheme();
    });
    return {
        mode,
        setMode,
        isDark: computed(() => resolvedTheme.value === 'dark'),
    };
}
