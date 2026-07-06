export declare function listSearchHistory(): Promise<string[]>;
export declare function addSearchHistory(keyword: string): Promise<void>;
export declare function removeSearchHistory(keyword: string): Promise<void>;
export declare function clearSearchHistory(): Promise<void>;
