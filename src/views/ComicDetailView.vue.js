/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/**
 * 漫画详情页：封面 + 信息 + 章节列表 + 操作按钮。
 */
import { ref, onMounted, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, Heart, BookOpen, ChevronRight } from 'lucide-vue-next';
import { comicDetail } from '@/api/jm';
import { useFavoritesStore } from '@/stores/favorites';
import { getLastChapterOfComic, saveHistory } from '@/db/repositories/history';
import JmImage from '@/components/JmImage.vue';
import ErrorState from '@/components/ErrorState.vue';
import LoadingDetail from '@/components/LoadingDetail.vue';
const route = useRoute();
const router = useRouter();
const favorites = useFavoritesStore();
const comic = ref(null);
const loading = ref(true);
const error = ref(null);
const lastChapterId = ref(null);
const comicId = computed(() => String(route.params.id || ''));
const isFavorited = computed(() => comic.value ? favorites.isFavorited(comic.value.id) : false);
async function loadDetail() {
    loading.value = true;
    error.value = null;
    try {
        comic.value = await comicDetail(comicId.value);
        // 查找阅读历史
        const last = await getLastChapterOfComic(comicId.value);
        lastChapterId.value = last?.chapter_id || null;
    }
    catch (e) {
        error.value = e?.message || '加载失败';
    }
    finally {
        loading.value = false;
    }
}
async function toggleFavorite() {
    if (!comic.value)
        return;
    await favorites.toggleFavorite({
        id: comic.value.id,
        name: comic.value.name,
        author: comic.value.author,
        tags: comic.value.tags,
        cover: comic.value.cover,
        likes: null,
        views: null,
    });
}
async function startReading(chapterId) {
    if (!comic.value)
        return;
    const targetId = chapterId || comic.value.chapters[0]?.id;
    if (!targetId)
        return;
    // 记录历史（首次进入时记录，滚动位置由阅读器更新）
    await saveHistory({
        chapter_id: targetId,
        comic_id: comic.value.id,
        comic_name: comic.value.name,
        comic_cover: comic.value.cover,
        page: 0,
        scroll: 0,
    });
    router.push({ name: 'reader', params: { chapterId: targetId } });
}
function back() {
    if (window.history.length > 1)
        router.back();
    else
        router.push({ name: 'home' });
}
onMounted(loadDetail);
const __VLS_ctx = {
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "min-h-screen pb-20" },
});
/** @type {__VLS_StyleScopedClasses['min-h-screen']} */ ;
/** @type {__VLS_StyleScopedClasses['pb-20']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "sticky top-0 z-20 flex items-center justify-between px-3 py-2 backdrop-blur-md" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['sticky']} */ ;
/** @type {__VLS_StyleScopedClasses['top-0']} */ ;
/** @type {__VLS_StyleScopedClasses['z-20']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['backdrop-blur-md']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (__VLS_ctx.back) },
    ...{ class: "rounded-full p-2" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
let __VLS_0;
/** @ts-ignore @type { | typeof __VLS_components.ArrowLeft} */
ArrowLeft;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
    size: (22),
}));
const __VLS_2 = __VLS_1({
    size: (22),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
if (__VLS_ctx.error) {
    const __VLS_5 = ErrorState;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent1(__VLS_5, new __VLS_5({
        ...{ 'onRetry': {} },
        message: (__VLS_ctx.error),
    }));
    const __VLS_7 = __VLS_6({
        ...{ 'onRetry': {} },
        message: (__VLS_ctx.error),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    let __VLS_10;
    const __VLS_11 = {
        /** @type {typeof __VLS_10.retry} */
        onRetry: (__VLS_ctx.loadDetail),
    };
    var __VLS_8;
    var __VLS_9;
}
else if (__VLS_ctx.loading || !__VLS_ctx.comic) {
    const __VLS_12 = LoadingDetail;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent1(__VLS_12, new __VLS_12({}));
    const __VLS_14 = __VLS_13({}, ...__VLS_functionalComponentArgsRest(__VLS_13));
}
else {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "relative" },
    });
    /** @type {__VLS_StyleScopedClasses['relative']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "absolute inset-0 overflow-hidden" },
    });
    /** @type {__VLS_StyleScopedClasses['absolute']} */ ;
    /** @type {__VLS_StyleScopedClasses['inset-0']} */ ;
    /** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
    if (__VLS_ctx.comic.cover) {
        const __VLS_17 = JmImage;
        // @ts-ignore
        const __VLS_18 = __VLS_asFunctionalComponent1(__VLS_17, new __VLS_17({
            src: (__VLS_ctx.comic.cover),
            alt: (__VLS_ctx.comic.name),
            rounded: (0),
            ...{ class: "h-full w-full object-cover opacity-30 blur-2xl" },
        }));
        const __VLS_19 = __VLS_18({
            src: (__VLS_ctx.comic.cover),
            alt: (__VLS_ctx.comic.name),
            rounded: (0),
            ...{ class: "h-full w-full object-cover opacity-30 blur-2xl" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_18));
        /** @type {__VLS_StyleScopedClasses['h-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['object-cover']} */ ;
        /** @type {__VLS_StyleScopedClasses['opacity-30']} */ ;
        /** @type {__VLS_StyleScopedClasses['blur-2xl']} */ ;
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "absolute inset-0" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['absolute']} */ ;
    /** @type {__VLS_StyleScopedClasses['inset-0']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "relative flex gap-4 px-4 pb-4 pt-6" },
    });
    /** @type {__VLS_StyleScopedClasses['relative']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
    /** @type {__VLS_StyleScopedClasses['px-4']} */ ;
    /** @type {__VLS_StyleScopedClasses['pb-4']} */ ;
    /** @type {__VLS_StyleScopedClasses['pt-6']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "flex-shrink-0" },
    });
    /** @type {__VLS_StyleScopedClasses['flex-shrink-0']} */ ;
    if (__VLS_ctx.comic.cover) {
        const __VLS_22 = JmImage;
        // @ts-ignore
        const __VLS_23 = __VLS_asFunctionalComponent1(__VLS_22, new __VLS_22({
            src: (__VLS_ctx.comic.cover),
            alt: (__VLS_ctx.comic.name),
            ratio: (3 / 4),
            rounded: (8),
            ...{ class: "w-28 shadow-card" },
        }));
        const __VLS_24 = __VLS_23({
            src: (__VLS_ctx.comic.cover),
            alt: (__VLS_ctx.comic.name),
            ratio: (3 / 4),
            rounded: (8),
            ...{ class: "w-28 shadow-card" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_23));
        /** @type {__VLS_StyleScopedClasses['w-28']} */ ;
        /** @type {__VLS_StyleScopedClasses['shadow-card']} */ ;
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "flex flex-1 flex-col justify-end" },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({
        ...{ class: "font-serif text-lg font-semibold leading-tight" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['font-serif']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
    /** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
    /** @type {__VLS_StyleScopedClasses['leading-tight']} */ ;
    (__VLS_ctx.comic.name);
    if (__VLS_ctx.comic.author) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
            ...{ class: "mt-1 text-xs" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
        (__VLS_ctx.comic.author);
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mt-2 flex gap-2 text-[10px]" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
    if (__VLS_ctx.comic.likes) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        (__VLS_ctx.comic.likes);
    }
    if (__VLS_ctx.comic.views) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        (__VLS_ctx.comic.views);
    }
    if (__VLS_ctx.comic.tags.length > 0) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "mb-3 flex flex-wrap gap-1.5 px-4" },
        });
        /** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
        /** @type {__VLS_StyleScopedClasses['flex']} */ ;
        /** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
        /** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
        /** @type {__VLS_StyleScopedClasses['px-4']} */ ;
        for (const [tag] of __VLS_vFor((__VLS_ctx.comic.tags))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                key: (tag),
                ...{ class: "rounded-full px-2.5 py-0.5 text-[11px]" },
                ...{ style: {} },
            });
            /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
            /** @type {__VLS_StyleScopedClasses['px-2.5']} */ ;
            /** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
            /** @type {__VLS_StyleScopedClasses['text-[11px]']} */ ;
            (tag);
            // @ts-ignore
            [back, error, error, loadDetail, loading, comic, comic, comic, comic, comic, comic, comic, comic, comic, comic, comic, comic, comic, comic, comic, comic,];
        }
    }
    if (__VLS_ctx.comic.description) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "mb-4 px-4" },
        });
        /** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
        /** @type {__VLS_StyleScopedClasses['px-4']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
            ...{ class: "text-xs leading-relaxed" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
        /** @type {__VLS_StyleScopedClasses['leading-relaxed']} */ ;
        (__VLS_ctx.comic.description);
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mb-4 flex gap-2 px-4" },
    });
    /** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['px-4']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (__VLS_ctx.toggleFavorite) },
        ...{ class: "flex flex-1 items-center justify-center gap-1.5 rounded-xl py-2.5 text-sm transition-all active:scale-95" },
        ...{ style: (__VLS_ctx.isFavorited
                ? { background: 'var(--accent-soft)', color: 'var(--accent)' }
                : { border: '1px solid var(--border-subtle)', color: 'var(--text-secondary)' }) },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
    /** @type {__VLS_StyleScopedClasses['rounded-xl']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-2.5']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
    /** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
    /** @type {__VLS_StyleScopedClasses['active:scale-95']} */ ;
    let __VLS_27;
    /** @ts-ignore @type { | typeof __VLS_components.Heart} */
    Heart;
    // @ts-ignore
    const __VLS_28 = __VLS_asFunctionalComponent1(__VLS_27, new __VLS_27({
        size: (16),
        fill: (__VLS_ctx.isFavorited ? 'currentColor' : 'none'),
    }));
    const __VLS_29 = __VLS_28({
        size: (16),
        fill: (__VLS_ctx.isFavorited ? 'currentColor' : 'none'),
    }, ...__VLS_functionalComponentArgsRest(__VLS_28));
    (__VLS_ctx.isFavorited ? '已收藏' : '收藏');
    if (__VLS_ctx.lastChapterId) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.error))
                        throw 0;
                    if (!!(__VLS_ctx.loading || !__VLS_ctx.comic))
                        throw 0;
                    if (!(__VLS_ctx.lastChapterId))
                        throw 0;
                    return __VLS_ctx.startReading(__VLS_ctx.lastChapterId);
                    // @ts-ignore
                    [comic, comic, toggleFavorite, isFavorited, isFavorited, isFavorited, lastChapterId, lastChapterId, startReading,];
                } },
            ...{ class: "flex flex-1 items-center justify-center gap-1.5 rounded-xl py-2.5 text-sm text-white transition-all active:scale-95" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['flex']} */ ;
        /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
        /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
        /** @type {__VLS_StyleScopedClasses['rounded-xl']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-2.5']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-white']} */ ;
        /** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
        /** @type {__VLS_StyleScopedClasses['active:scale-95']} */ ;
        let __VLS_32;
        /** @ts-ignore @type { | typeof __VLS_components.BookOpen} */
        BookOpen;
        // @ts-ignore
        const __VLS_33 = __VLS_asFunctionalComponent1(__VLS_32, new __VLS_32({
            size: (16),
        }));
        const __VLS_34 = __VLS_33({
            size: (16),
        }, ...__VLS_functionalComponentArgsRest(__VLS_33));
    }
    else {
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.error))
                        throw 0;
                    if (!!(__VLS_ctx.loading || !__VLS_ctx.comic))
                        throw 0;
                    if (!!(__VLS_ctx.lastChapterId))
                        throw 0;
                    return __VLS_ctx.startReading();
                    // @ts-ignore
                    [startReading,];
                } },
            ...{ class: "flex flex-1 items-center justify-center gap-1.5 rounded-xl py-2.5 text-sm text-white transition-all active:scale-95" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['flex']} */ ;
        /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
        /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
        /** @type {__VLS_StyleScopedClasses['rounded-xl']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-2.5']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-white']} */ ;
        /** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
        /** @type {__VLS_StyleScopedClasses['active:scale-95']} */ ;
        let __VLS_37;
        /** @ts-ignore @type { | typeof __VLS_components.BookOpen} */
        BookOpen;
        // @ts-ignore
        const __VLS_38 = __VLS_asFunctionalComponent1(__VLS_37, new __VLS_37({
            size: (16),
        }));
        const __VLS_39 = __VLS_38({
            size: (16),
        }, ...__VLS_functionalComponentArgsRest(__VLS_38));
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "px-4 pb-6" },
    });
    /** @type {__VLS_StyleScopedClasses['px-4']} */ ;
    /** @type {__VLS_StyleScopedClasses['pb-6']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({
        ...{ class: "mb-2 font-serif text-base font-semibold" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['font-serif']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-base']} */ ;
    /** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "ml-1 text-xs font-normal" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['ml-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
    /** @type {__VLS_StyleScopedClasses['font-normal']} */ ;
    (__VLS_ctx.comic.chapters.length);
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "space-y-1" },
    });
    /** @type {__VLS_StyleScopedClasses['space-y-1']} */ ;
    for (const [ch] of __VLS_vFor((__VLS_ctx.comic.chapters))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.error))
                        throw 0;
                    if (!!(__VLS_ctx.loading || !__VLS_ctx.comic))
                        throw 0;
                    return __VLS_ctx.startReading(ch.id);
                    // @ts-ignore
                    [comic, comic, startReading,];
                } },
            key: (ch.id),
            ...{ class: "flex w-full items-center justify-between rounded-lg px-3 py-2.5 text-left transition-colors hover:bg-white/5" },
            ...{ style: ({
                    background: __VLS_ctx.lastChapterId === ch.id ? 'var(--accent-soft)' : 'var(--bg-card)',
                }) },
        });
        /** @type {__VLS_StyleScopedClasses['flex']} */ ;
        /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
        /** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
        /** @type {__VLS_StyleScopedClasses['px-3']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-2.5']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-left']} */ ;
        /** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
        /** @type {__VLS_StyleScopedClasses['hover:bg-white/5']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "min-w-0 flex-1" },
        });
        /** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
        /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
            ...{ class: "truncate text-sm" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['truncate']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
        (ch.title);
        if (__VLS_ctx.lastChapterId === ch.id) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
                ...{ class: "text-[10px]" },
                ...{ style: {} },
            });
            /** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
        }
        let __VLS_42;
        /** @ts-ignore @type { | typeof __VLS_components.ChevronRight} */
        ChevronRight;
        // @ts-ignore
        const __VLS_43 = __VLS_asFunctionalComponent1(__VLS_42, new __VLS_42({
            size: (16),
            ...{ style: {} },
        }));
        const __VLS_44 = __VLS_43({
            size: (16),
            ...{ style: {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_43));
        // @ts-ignore
        [lastChapterId, lastChapterId,];
    }
}
// @ts-ignore
[];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
