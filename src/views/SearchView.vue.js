/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/**
 * 搜索页：关键词搜索 + 排序 + 搜索历史。
 */
import { ref, onMounted, computed } from 'vue';
import { search } from '@/api/jm';
import { useBlockFilter } from '@/composables/useBlockFilter';
import { useInfiniteScroll } from '@/composables/useInfiniteScroll';
import ComicGrid from '@/components/ComicGrid.vue';
import LoadingGrid from '@/components/LoadingGrid.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';
import { Search, X, Clock } from 'lucide-vue-next';
import { listSearchHistory, addSearchHistory, removeSearchHistory, clearSearchHistory, } from '@/db/repositories/searchHistory';
const keyword = ref('');
const submittedKeyword = ref('');
const order = ref('latest');
const items = ref([]);
const page = ref(1);
const total = ref(null);
const loading = ref(false);
const error = ref(null);
const initialized = ref(false);
const history = ref([]);
const orders = [
    { key: 'latest', label: '最新' },
    { key: 'views', label: '观看' },
    { key: 'likes', label: '评论' },
    { key: 'picture', label: '图片数' },
];
const filteredItems = useBlockFilter(items);
const hasMore = computed(() => {
    if (total.value === null)
        return true;
    return items.value.length < total.value;
});
async function doSearch(kw, reset = true) {
    const q = (kw ?? keyword.value).trim();
    if (!q)
        return;
    if (loading.value)
        return;
    keyword.value = q;
    submittedKeyword.value = q;
    loading.value = true;
    error.value = null;
    initialized.value = false;
    if (reset) {
        items.value = [];
        page.value = 1;
        total.value = null;
    }
    try {
        const result = await search(q, page.value, order.value, 'all');
        if (reset) {
            items.value = result.items;
        }
        else {
            items.value = [...items.value, ...result.items];
        }
        page.value += 1;
        total.value = result.total;
        await addSearchHistory(q);
        await loadHistory();
    }
    catch (e) {
        error.value = e?.message || '搜索失败';
    }
    finally {
        loading.value = false;
        initialized.value = true;
    }
}
const { sentinel, loading: scrollLoading } = useInfiniteScroll(() => loadMore());
async function loadMore() {
    if (!submittedKeyword.value)
        return;
    if (loading.value || !hasMore.value)
        return;
    page.value = page.value + 1;
    await doSearch(submittedKeyword.value, false);
}
function selectOrder(o) {
    if (order.value === o)
        return;
    order.value = o;
    if (submittedKeyword.value)
        doSearch();
}
async function loadHistory() {
    history.value = await listSearchHistory();
}
async function useHistory(kw) {
    await doSearch(kw);
}
async function deleteHistory(kw, ev) {
    ev.stopPropagation();
    await removeSearchHistory(kw);
    await loadHistory();
}
async function clearAllHistory() {
    await clearSearchHistory();
    await loadHistory();
}
onMounted(loadHistory);
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
    ...{ class: "mb-3 flex gap-2" },
});
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "relative flex-1" },
});
/** @type {__VLS_StyleScopedClasses['relative']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
let __VLS_0;
/** @ts-ignore @type { | typeof __VLS_components.Search} */
Search;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
    size: (16),
    ...{ class: "absolute left-3 top-1/2 -translate-y-1/2" },
    ...{ style: {} },
}));
const __VLS_2 = __VLS_1({
    size: (16),
    ...{ class: "absolute left-3 top-1/2 -translate-y-1/2" },
    ...{ style: {} },
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
/** @type {__VLS_StyleScopedClasses['absolute']} */ ;
/** @type {__VLS_StyleScopedClasses['left-3']} */ ;
/** @type {__VLS_StyleScopedClasses['top-1/2']} */ ;
/** @type {__VLS_StyleScopedClasses['-translate-y-1/2']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    ...{ onKeydown: (...[$event]) => {
            return __VLS_ctx.doSearch();
            // @ts-ignore
            [doSearch,];
        } },
    value: (__VLS_ctx.keyword),
    type: "text",
    placeholder: "搜索漫画 / 作者 / 本子号",
    ...{ class: "w-full rounded-full border py-2 pl-9 pr-4 text-sm outline-none transition-colors" },
    ...{ style: ({
            background: 'var(--bg-card)',
            borderColor: 'var(--border-subtle)',
            color: 'var(--text-primary)',
        }) },
});
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pl-9']} */ ;
/** @type {__VLS_StyleScopedClasses['pr-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['outline-none']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (...[$event]) => {
            return __VLS_ctx.doSearch();
            // @ts-ignore
            [doSearch, keyword,];
        } },
    ...{ class: "rounded-full px-4 text-sm text-white" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-white']} */ ;
if (__VLS_ctx.submittedKeyword) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mb-3 flex gap-2 overflow-x-auto scrollbar-hidden" },
    });
    /** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['overflow-x-auto']} */ ;
    /** @type {__VLS_StyleScopedClasses['scrollbar-hidden']} */ ;
    for (const [o] of __VLS_vFor((__VLS_ctx.orders))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.submittedKeyword))
                        throw 0;
                    return __VLS_ctx.selectOrder(o.key);
                    // @ts-ignore
                    [submittedKeyword, orders, selectOrder,];
                } },
            key: (o.key),
            ...{ class: "flex-shrink-0 rounded-full px-3 py-1 text-xs transition-all" },
            ...{ style: (__VLS_ctx.order === o.key
                    ? { background: 'var(--accent-soft)', color: 'var(--accent)' }
                    : { background: 'var(--bg-card)', color: 'var(--text-muted)' }) },
        });
        /** @type {__VLS_StyleScopedClasses['flex-shrink-0']} */ ;
        /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['px-3']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-1']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
        /** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
        (o.label);
        // @ts-ignore
        [order,];
    }
}
if (!__VLS_ctx.submittedKeyword && __VLS_ctx.history.length > 0) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mb-4" },
    });
    /** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mb-2 flex items-center justify-between" },
    });
    /** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "flex items-center gap-1 text-xs" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
    let __VLS_5;
    /** @ts-ignore @type { | typeof __VLS_components.Clock} */
    Clock;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent1(__VLS_5, new __VLS_5({
        size: (12),
    }));
    const __VLS_7 = __VLS_6({
        size: (12),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (__VLS_ctx.clearAllHistory) },
        ...{ class: "text-xs" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "flex flex-wrap gap-2" },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
    for (const [kw] of __VLS_vFor((__VLS_ctx.history))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ onClick: (...[$event]) => {
                    if (!(!__VLS_ctx.submittedKeyword && __VLS_ctx.history.length > 0))
                        throw 0;
                    return __VLS_ctx.useHistory(kw);
                    // @ts-ignore
                    [submittedKeyword, history, history, clearAllHistory, useHistory,];
                } },
            key: (kw),
            ...{ class: "group flex items-center gap-1 rounded-full border px-3 py-1 text-xs transition-colors" },
            ...{ style: ({
                    borderColor: 'var(--border-subtle)',
                    color: 'var(--text-secondary)',
                }) },
        });
        /** @type {__VLS_StyleScopedClasses['group']} */ ;
        /** @type {__VLS_StyleScopedClasses['flex']} */ ;
        /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
        /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['border']} */ ;
        /** @type {__VLS_StyleScopedClasses['px-3']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-1']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
        /** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        (kw);
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(!__VLS_ctx.submittedKeyword && __VLS_ctx.history.length > 0))
                        throw 0;
                    return __VLS_ctx.deleteHistory(kw, $event);
                    // @ts-ignore
                    [deleteHistory,];
                } },
            ...{ class: "opacity-50 transition-opacity group-hover:opacity-100" },
        });
        /** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
        /** @type {__VLS_StyleScopedClasses['transition-opacity']} */ ;
        /** @type {__VLS_StyleScopedClasses['group-hover:opacity-100']} */ ;
        let __VLS_10;
        /** @ts-ignore @type { | typeof __VLS_components.X} */
        X;
        // @ts-ignore
        const __VLS_11 = __VLS_asFunctionalComponent1(__VLS_10, new __VLS_10({
            size: (10),
        }));
        const __VLS_12 = __VLS_11({
            size: (10),
        }, ...__VLS_functionalComponentArgsRest(__VLS_11));
        // @ts-ignore
        [];
    }
}
if (__VLS_ctx.filteredItems.length > 0) {
    const __VLS_15 = ComicGrid;
    // @ts-ignore
    const __VLS_16 = __VLS_asFunctionalComponent1(__VLS_15, new __VLS_15({
        comics: (__VLS_ctx.filteredItems),
    }));
    const __VLS_17 = __VLS_16({
        comics: (__VLS_ctx.filteredItems),
    }, ...__VLS_functionalComponentArgsRest(__VLS_16));
}
if (!__VLS_ctx.initialized && __VLS_ctx.loading) {
    const __VLS_20 = LoadingGrid;
    // @ts-ignore
    const __VLS_21 = __VLS_asFunctionalComponent1(__VLS_20, new __VLS_20({
        count: (12),
    }));
    const __VLS_22 = __VLS_21({
        count: (12),
    }, ...__VLS_functionalComponentArgsRest(__VLS_21));
}
else if (__VLS_ctx.error && __VLS_ctx.items.length === 0) {
    const __VLS_25 = ErrorState;
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent1(__VLS_25, new __VLS_25({
        ...{ 'onRetry': {} },
        message: (__VLS_ctx.error),
    }));
    const __VLS_27 = __VLS_26({
        ...{ 'onRetry': {} },
        message: (__VLS_ctx.error),
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
    let __VLS_30;
    const __VLS_31 = {
        /** @type {typeof __VLS_30.retry} */
        onRetry: (...[$event]) => {
            if (!!(!__VLS_ctx.initialized && __VLS_ctx.loading))
                throw 0;
            if (!(__VLS_ctx.error && __VLS_ctx.items.length === 0))
                throw 0;
            return __VLS_ctx.doSearch();
            // @ts-ignore
            [doSearch, filteredItems, filteredItems, initialized, loading, error, error, items,];
        },
    };
    var __VLS_28;
    var __VLS_29;
}
else if (__VLS_ctx.submittedKeyword && __VLS_ctx.initialized && !__VLS_ctx.loading && __VLS_ctx.filteredItems.length === 0) {
    const __VLS_32 = EmptyState;
    // @ts-ignore
    const __VLS_33 = __VLS_asFunctionalComponent1(__VLS_32, new __VLS_32({
        text: "没有找到相关漫画",
    }));
    const __VLS_34 = __VLS_33({
        text: "没有找到相关漫画",
    }, ...__VLS_functionalComponentArgsRest(__VLS_33));
}
if (__VLS_ctx.submittedKeyword && __VLS_ctx.filteredItems.length > 0) {
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
[submittedKeyword, submittedKeyword, filteredItems, filteredItems, initialized, loading, scrollLoading, hasMore,];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
