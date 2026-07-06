/**
 * 列表过滤 composable：应用屏蔽词（标签、标题关键词、作者）。
 *
 * 繁简归一化：屏蔽词匹配时把繁简统一后再比较，避免漏匹配。
 */
import { type Ref } from 'vue';
import type { ComicBrief } from '@/api/types';
/** 判断一个 ComicBrief 是否通过屏蔽词过滤。 */
export declare function passesBlockFilter(comic: ComicBrief, blockedTags: string[], blockedNames: string[], blockedAuthors: string[]): boolean;
/** 响应式过滤列表。 */
export declare function useBlockFilter(items: Ref<ComicBrief[]>): any;
