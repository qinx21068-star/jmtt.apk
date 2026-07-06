export declare function getSetting<T>(key: string, defaultValue: T): Promise<T>;
export declare function setSetting(key: string, value: unknown): Promise<void>;
export declare function deleteSetting(key: string): Promise<void>;
