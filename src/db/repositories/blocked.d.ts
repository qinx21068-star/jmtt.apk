export declare function listBlockedTags(): Promise<string[]>;
export declare function addBlockedTag(tag: string): Promise<void>;
export declare function removeBlockedTag(tag: string): Promise<void>;
export declare function listBlockedNames(): Promise<string[]>;
export declare function addBlockedName(name: string): Promise<void>;
export declare function removeBlockedName(name: string): Promise<void>;
export declare function listBlockedAuthors(): Promise<string[]>;
export declare function addBlockedAuthor(author: string): Promise<void>;
export declare function removeBlockedAuthor(author: string): Promise<void>;
/**
 * 加载所有屏蔽词到内存，供列表过滤用。
 * 返回 {tags, names, authors}，均为数组。
 */
export declare function loadAllBlocked(): Promise<{
    tags: string[];
    names: string[];
    authors: string[];
}>;
