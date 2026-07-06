/**
 * 列表过滤 composable：应用屏蔽词（标签、标题关键词、作者）。
 *
 * 繁简归一化：屏蔽词匹配时把繁简统一后再比较，避免漏匹配。
 */
import { computed } from 'vue';
import { useSettingsStore } from '@/stores/settings';
/** 简单的繁简归一化（仅做常见字符替换，足够屏蔽词匹配用）。 */
function normalizeText(s) {
    return s.normalize('NFC').toLowerCase();
}
/** 判断一个 ComicBrief 是否通过屏蔽词过滤。 */
export function passesBlockFilter(comic, blockedTags, blockedNames, blockedAuthors) {
    // 作者屏蔽
    if (comic.author) {
        const authorNorm = normalizeText(comic.author);
        for (const a of blockedAuthors) {
            if (authorNorm.includes(normalizeText(a)))
                return false;
        }
    }
    // 标题关键词屏蔽
    const nameNorm = normalizeText(comic.name);
    for (const n of blockedNames) {
        if (nameNorm.includes(normalizeText(n)))
            return false;
    }
    // 标签屏蔽
    if (comic.tags.length > 0) {
        const tagsNorm = comic.tags.map(normalizeText);
        for (const t of blockedTags) {
            if (tagsNorm.includes(normalizeText(t)))
                return false;
        }
    }
    return true;
}
/** 响应式过滤列表。 */
export function useBlockFilter(items) {
    const settings = useSettingsStore();
    return computed(() => {
        const { blockedTags, blockedNames, blockedAuthors } = settings;
        if (blockedTags.length === 0 && blockedNames.length === 0 && blockedAuthors.length === 0) {
            return items.value;
        }
        return items.value.filter((c) => passesBlockFilter(c, blockedTags, blockedNames, blockedAuthors));
    });
}
