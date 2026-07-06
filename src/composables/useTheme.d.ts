export type ThemeMode = 'system' | 'light' | 'dark';
export declare function useTheme(): {
    mode: any;
    setMode: (m: ThemeMode) => void;
    isDark: any;
};
