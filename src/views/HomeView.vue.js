/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/**
 * 首页：最新 / 周/月/总排行切换 + 分类筛选 + 漫画网格 + 无限滚动。
 */
import { ref, watch, computed, onMounted } from 'vue';
import { latest, ranking } from '@/api/jm';
import { useBlockFilter } from '@/composables/useBlockFilter';
import { useInfiniteScroll } from '@/composables/useInfiniteScroll';
import ComicGrid from '@/components/ComicGrid.vue';
import LoadingGrid from '@/components/LoadingGrid.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';
const tabs = [
    { key: 'latest', label: '最新', time: null },
    { key: 'week', label: '周榜', time: 'week' },
    { key: 'month', label: '月榜', time: 'month' },
    { key: 'all', label: '总榜', time: 'all' },
];
const categories = [
    { slug: '', label: '全部' },
    { slug: 'doujin', label: '同人' },
    { slug: 'single', label: '单本' },
    { slug: 'short', label: '短篇' },
    { slug: 'hanman', label: '汉化' },
    { slug: 'meiman', label: '美漫' },
    { slug: 'doujin_cosplay', label: 'Cosplay' },
    { slug: '3D', label: '3D' },
];
const currentTab = ref('latest');
const currentCategory = ref('');
const items = ref([]);
const page = ref(1);
const total = ref(null);
const loading = ref(false);
const error = ref(null);
const initialized = ref(false);
// 应用屏蔽词过滤
const filteredItems = useBlockFilter(items);
const hasMore = computed(() => {
    if (total.value === null)
        return true;
    return items.value.length < total.value;
});
async function loadPage(reset = false) {
    if (loading.value)
        return;
    if (!reset && !hasMore.value)
        return;
    loading.value = true;
    error.value = null;
    try {
        const targetPage = reset ? 1 : page.value;
        const cat = currentCategory.value;
        const tab = tabs.find((t) => t.key === currentTab.value);
        let result;
        if (tab.key === 'latest') {
            result = await latest(targetPage, cat);
        }
        else {
            result = await ranking(tab.time, cat, targetPage);
        }
        if (reset) {
            items.value = result.items;
        }
        else {
            items.value = [...items.value, ...result.items];
        }
        page.value = targetPage + 1;
        total.value = result.total;
    }
    catch (e) {
        error.value = e?.message || '加载失败';
    }
    finally {
        loading.value = false;
        initialized.value = true;
    }
}
const { sentinel, loading: scrollLoading } = useInfiniteScroll(() => loadPage(false));
function selectTab(t) {
    if (currentTab.value === t)
        return;
    currentTab.value = t;
}
function selectCategory(slug) {
    if (currentCategory.value === slug)
        return;
    currentCategory.value = slug;
}
// 切换 Tab 或分类时重新加载
watch([currentTab, currentCategory], () => {
    items.value = [];
    page.value = 1;
    total.value = null;
    loadPage(true);
});
onMounted(() => {
    loadPage(true);
});
const __VLS_ctx = {
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "px-3 pt-3" },
});
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-3']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "mb-3 flex gap-2 overflow-x-auto scrollbar-hidden" },
});
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-x-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['scrollbar-hidden']} */ ;
for (const [tab] of __VLS_vFor((__VLS_ctx.tabs))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                return __VLS_ctx.selectTab(tab.key);
                // @ts-ignore
                [tabs, selectTab,];
            } },
        key: (tab.key),
        ...{ class: "flex-shrink-0 rounded-full px-4 py-1.5 text-sm transition-all" },
        ...{ style: (__VLS_ctx.currentTab === tab.key
                ? {
                    background: 'linear-gradient(135deg, #d946ef 0%, #c026d3 100%)',
                    color: 'white',
                }
                : {
                    background: 'var(--bg-card)',
                    color: 'var(--text-secondary)',
                }) },
    });
    /** @type {__VLS_StyleScopedClasses['flex-shrink-0']} */ ;
    /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['px-4']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
    /** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
    (tab.label);
    // @ts-ignore
    [currentTab,];
}
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "mb-3 flex gap-2 overflow-x-auto scrollbar-hidden" },
});
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-x-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['scrollbar-hidden']} */ ;
for (const [cat] of __VLS_vFor((__VLS_ctx.categories))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                return __VLS_ctx.selectCategory(cat.slug);
                // @ts-ignore
                [categories, selectCategory,];
            } },
        key: (cat.slug || 'all'),
        ...{ class: "flex-shrink-0 rounded-full border px-3 py-1 text-xs transition-all" },
        ...{ style: (__VLS_ctx.currentCategory === cat.slug
                ? {
                    borderColor: 'var(--accent)',
                    background: 'var(--accent-soft)',
                    color: 'var(--accent)',
                }
                : {
                    borderColor: 'var(--border-subtle)',
                    color: 'var(--text-muted)',
                }) },
    });
    /** @type {__VLS_StyleScopedClasses['flex-shrink-0']} */ ;
    /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['border']} */ ;
    /** @type {__VLS_StyleScopedClasses['px-3']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
    /** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
    (cat.label);
    // @ts-ignore
    [currentCategory,];
}
if (__VLS_ctx.filteredItems.length > 0) {
    const __VLS_0 = ComicGrid;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
        comics: (__VLS_ctx.filteredItems),
    }));
    const __VLS_2 = __VLS_1({
        comics: (__VLS_ctx.filteredItems),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
}
if (!__VLS_ctx.initialized && __VLS_ctx.loading) {
    const __VLS_5 = LoadingGrid;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent1(__VLS_5, new __VLS_5({
        count: (12),
    }));
    const __VLS_7 = __VLS_6({
        count: (12),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
}
else if (__VLS_ctx.error && __VLS_ctx.items.length === 0) {
    const __VLS_10 = ErrorState;
    // @ts-ignore
    const __VLS_11 = __VLS_asFunctionalComponent1(__VLS_10, new __VLS_10({
        ...{ 'onRetry': {} },
        message: (__VLS_ctx.error),
    }));
    const __VLS_12 = __VLS_11({
        ...{ 'onRetry': {} },
        message: (__VLS_ctx.error),
    }, ...__VLS_functionalComponentArgsRest(__VLS_11));
    let __VLS_15;
    const __VLS_16 = {
        /** @type {typeof __VLS_15.retry} */
        onRetry: (...[$event]) => {
            if (!!(!__VLS_ctx.initialized && __VLS_ctx.loading))
                throw 0;
            if (!(__VLS_ctx.error && __VLS_ctx.items.length === 0))
                throw 0;
            return __VLS_ctx.loadPage(true);
            // @ts-ignore
            [filteredItems, filteredItems, initialized, loading, error, error, items, loadPage,];
        },
    };
    var __VLS_13;
    var __VLS_14;
}
else if (__VLS_ctx.initialized && !__VLS_ctx.loading && __VLS_ctx.filteredItems.length === 0) {
    const __VLS_17 = EmptyState;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent1(__VLS_17, new __VLS_17({
        text: "暂无数据",
    }));
    const __VLS_19 = __VLS_18({
        text: "暂无数据",
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
}
if (__VLS_ctx.initialized && __VLS_ctx.filteredItems.length > 0) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ref: "sentinel",
        ...{ class: "flex items-center justify-center py-4 text-xs" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-4']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
    if (__VLS_ctx.scrollLoading) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    }
    else if (!__VLS_ctx.hasMore) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    }
    else {
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    }
}
// @ts-ignore
[filteredItems, filteredItems, initialized, initialized, loading, scrollLoading, hasMore,];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
