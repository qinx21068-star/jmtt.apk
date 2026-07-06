/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/**
 * 阅读器：全屏图片浏览 + 章节切换 + 进度记忆。
 *
 * - 上下滚动浏览（默认）
 * - 图片懒加载 + scramble 解码（JmImage 组件处理）
 * - 点击屏幕中央切换控件显示
 * - 自动记录滚动位置到 IndexedDB
 * - 章节切换：上一章/下一章
 */
import { ref, onMounted, onUnmounted, computed, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, ChevronUp, ChevronDown, List, X } from 'lucide-vue-next';
import { chapterImages } from '@/api/jm';
import { comicDetail } from '@/api/jm';
import { getHistory, saveHistory } from '@/db/repositories/history';
import ReaderImage from '@/components/ReaderImage.vue';
import ErrorState from '@/components/ErrorState.vue';
const route = useRoute();
const router = useRouter();
const chapterId = computed(() => String(route.params.chapterId || ''));
const images = ref([]);
const chapterInfo = ref(null);
const comic = ref(null);
const loading = ref(true);
const error = ref(null);
const showControls = ref(false);
const showChapterList = ref(false);
const scrollContainer = ref(null);
let saveTimer = null;
const currentIndex = ref(0);
const totalPages = computed(() => images.value.length);
async function loadChapter(id) {
    loading.value = true;
    error.value = null;
    images.value = [];
    chapterInfo.value = null;
    try {
        const info = await chapterImages(id);
        chapterInfo.value = info;
        images.value = info.images;
        // 同时加载漫画详情（用于章节切换）
        if (!comic.value) {
            // 尝试从历史记录找到 comic_id
            const history = await getHistory(id);
            if (history) {
                comic.value = await comicDetail(history.comic_id);
            }
        }
        // 恢复阅读进度
        const history = await getHistory(id);
        await nextTick();
        if (history && scrollContainer.value) {
            requestAnimationFrame(() => {
                if (scrollContainer.value) {
                    scrollContainer.value.scrollTop = history.scroll;
                }
            });
        }
        else if (scrollContainer.value) {
            scrollContainer.value.scrollTop = 0;
        }
    }
    catch (e) {
        error.value = e?.message || '加载失败';
    }
    finally {
        loading.value = false;
    }
}
function toggleControls() {
    showControls.value = !showControls.value;
}
function back() {
    if (comic.value) {
        router.push({ name: 'comic', params: { id: comic.value.id } });
    }
    else {
        router.back();
    }
}
async function goPrevChapter() {
    if (!comic.value)
        return;
    const chapters = comic.value.chapters;
    const idx = chapters.findIndex((c) => c.id === chapterId.value);
    if (idx > 0) {
        const prevId = chapters[idx - 1].id;
        router.replace({ name: 'reader', params: { chapterId: prevId } });
        await loadChapter(prevId);
    }
}
async function goNextChapter() {
    if (!comic.value)
        return;
    const chapters = comic.value.chapters;
    const idx = chapters.findIndex((c) => c.id === chapterId.value);
    if (idx >= 0 && idx < chapters.length - 1) {
        const nextId = chapters[idx + 1].id;
        router.replace({ name: 'reader', params: { chapterId: nextId } });
        await loadChapter(nextId);
    }
}
function selectChapter(id) {
    showChapterList.value = false;
    router.replace({ name: 'reader', params: { chapterId: id } });
    loadChapter(id);
}
/** 滚动事件：记录当前位置 + 计算当前页码。 */
function onScroll() {
    if (!scrollContainer.value)
        return;
    const el = scrollContainer.value;
    // 当前页码估算
    const ratio = el.scrollTop / (el.scrollHeight - el.clientHeight || 1);
    currentIndex.value = Math.floor(ratio * totalPages.value);
    // 防抖保存历史
    if (saveTimer)
        window.clearTimeout(saveTimer);
    saveTimer = window.setTimeout(() => {
        saveProgress();
    }, 500);
}
async function saveProgress() {
    if (!comic.value || !scrollContainer.value || !chapterInfo.value)
        return;
    await saveHistory({
        chapter_id: chapterId.value,
        comic_id: comic.value.id,
        comic_name: comic.value.name,
        comic_cover: comic.value.cover,
        page: currentIndex.value,
        scroll: scrollContainer.value.scrollTop,
    });
}
onMounted(() => loadChapter(chapterId.value));
onUnmounted(() => {
    if (saveTimer)
        window.clearTimeout(saveTimer);
    saveProgress();
});
const __VLS_ctx = {
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "fixed inset-0 z-50 flex flex-col" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['fixed']} */ ;
/** @type {__VLS_StyleScopedClasses['inset-0']} */ ;
/** @type {__VLS_StyleScopedClasses['z-50']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
let __VLS_0;
/** @ts-ignore @type { | typeof __VLS_components.transition | typeof __VLS_components.Transition | typeof __VLS_components.transition | typeof __VLS_components.Transition} */
transition;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
    name: "slide-down",
}));
const __VLS_2 = __VLS_1({
    name: "slide-down",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
const { default: __VLS_5 } = __VLS_3.slots;
if (__VLS_ctx.showControls) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "absolute left-0 right-0 top-0 z-20 flex items-center justify-between px-3 py-2 backdrop-blur-md" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['absolute']} */ ;
    /** @type {__VLS_StyleScopedClasses['left-0']} */ ;
    /** @type {__VLS_StyleScopedClasses['right-0']} */ ;
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
        ...{ class: "rounded-full p-2 text-white" },
    });
    /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['p-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-white']} */ ;
    let __VLS_6;
    /** @ts-ignore @type { | typeof __VLS_components.ArrowLeft} */
    ArrowLeft;
    // @ts-ignore
    const __VLS_7 = __VLS_asFunctionalComponent1(__VLS_6, new __VLS_6({
        size: (22),
    }));
    const __VLS_8 = __VLS_7({
        size: (22),
    }, ...__VLS_functionalComponentArgsRest(__VLS_7));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "flex-1 truncate px-3 text-center text-xs text-white/80" },
    });
    /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['truncate']} */ ;
    /** @type {__VLS_StyleScopedClasses['px-3']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-white/80']} */ ;
    (__VLS_ctx.comic?.name || '加载中');
    if (__VLS_ctx.chapterInfo?.title) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        (__VLS_ctx.chapterInfo.title);
    }
    if (__VLS_ctx.comic && __VLS_ctx.comic.chapters.length > 1) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.showControls))
                        throw 0;
                    if (!(__VLS_ctx.comic && __VLS_ctx.comic.chapters.length > 1))
                        throw 0;
                    return __VLS_ctx.showChapterList = !__VLS_ctx.showChapterList;
                    // @ts-ignore
                    [showControls, back, comic, comic, comic, chapterInfo, chapterInfo, showChapterList, showChapterList,];
                } },
            ...{ class: "rounded-full p-2 text-white" },
        });
        /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['p-2']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-white']} */ ;
        let __VLS_11;
        /** @ts-ignore @type { | typeof __VLS_components.List} */
        List;
        // @ts-ignore
        const __VLS_12 = __VLS_asFunctionalComponent1(__VLS_11, new __VLS_11({
            size: (22),
        }));
        const __VLS_13 = __VLS_12({
            size: (22),
        }, ...__VLS_functionalComponentArgsRest(__VLS_12));
    }
}
// @ts-ignore
[];
var __VLS_3;
if (__VLS_ctx.error) {
    const __VLS_16 = ErrorState;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent1(__VLS_16, new __VLS_16({
        ...{ 'onRetry': {} },
        message: (__VLS_ctx.error),
    }));
    const __VLS_18 = __VLS_17({
        ...{ 'onRetry': {} },
        message: (__VLS_ctx.error),
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    let __VLS_21;
    const __VLS_22 = {
        /** @type {typeof __VLS_21.retry} */
        onRetry: (...[$event]) => {
            if (!(__VLS_ctx.error))
                throw 0;
            return __VLS_ctx.loadChapter(__VLS_ctx.chapterId);
            // @ts-ignore
            [error, error, loadChapter, chapterId,];
        },
    };
    var __VLS_19;
    var __VLS_20;
}
else if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "flex flex-1 items-center justify-center" },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "text-sm text-white/60" },
    });
    /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-white/60']} */ ;
}
else {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ onScroll: (__VLS_ctx.onScroll) },
        ...{ onClick: (__VLS_ctx.toggleControls) },
        ref: "scrollContainer",
        ...{ class: "flex-1 overflow-y-auto" },
    });
    /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mx-auto flex max-w-3xl flex-col items-center" },
    });
    /** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['max-w-3xl']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    for (const [img, idx] of __VLS_vFor((__VLS_ctx.images))) {
        const __VLS_23 = ReaderImage;
        // @ts-ignore
        const __VLS_24 = __VLS_asFunctionalComponent1(__VLS_23, new __VLS_23({
            ...{ 'onClick': {} },
            key: (idx),
            src: (img),
        }));
        const __VLS_25 = __VLS_24({
            ...{ 'onClick': {} },
            key: (idx),
            src: (img),
        }, ...__VLS_functionalComponentArgsRest(__VLS_24));
        let __VLS_28;
        const __VLS_29 = {
            /** @type {typeof __VLS_28.click} */
            onClick: (__VLS_ctx.toggleControls),
        };
        var __VLS_26;
        var __VLS_27;
        // @ts-ignore
        [loading, onScroll, toggleControls, toggleControls, images,];
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "flex w-full items-center justify-center gap-3 py-6 text-white/80" },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-6']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-white/80']} */ ;
    if (__VLS_ctx.comic && __VLS_ctx.comic.chapters.findIndex((c) => c.id === __VLS_ctx.chapterId) > 0) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (__VLS_ctx.goPrevChapter) },
            ...{ class: "rounded-full border border-white/30 px-4 py-2 text-xs" },
        });
        /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['border']} */ ;
        /** @type {__VLS_StyleScopedClasses['border-white/30']} */ ;
        /** @type {__VLS_StyleScopedClasses['px-4']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-2']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
        let __VLS_30;
        /** @ts-ignore @type { | typeof __VLS_components.ChevronUp} */
        ChevronUp;
        // @ts-ignore
        const __VLS_31 = __VLS_asFunctionalComponent1(__VLS_30, new __VLS_30({
            size: (14),
            ...{ class: "inline" },
        }));
        const __VLS_32 = __VLS_31({
            size: (14),
            ...{ class: "inline" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_31));
        /** @type {__VLS_StyleScopedClasses['inline']} */ ;
    }
    if (__VLS_ctx.comic &&
        __VLS_ctx.comic.chapters.findIndex((c) => c.id === __VLS_ctx.chapterId) <
            __VLS_ctx.comic.chapters.length - 1) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (__VLS_ctx.goNextChapter) },
            ...{ class: "rounded-full bg-white/20 px-4 py-2 text-xs" },
        });
        /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['bg-white/20']} */ ;
        /** @type {__VLS_StyleScopedClasses['px-4']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-2']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
        let __VLS_35;
        /** @ts-ignore @type { | typeof __VLS_components.ChevronDown} */
        ChevronDown;
        // @ts-ignore
        const __VLS_36 = __VLS_asFunctionalComponent1(__VLS_35, new __VLS_35({
            size: (14),
            ...{ class: "inline" },
        }));
        const __VLS_37 = __VLS_36({
            size: (14),
            ...{ class: "inline" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_36));
        /** @type {__VLS_StyleScopedClasses['inline']} */ ;
    }
}
let __VLS_40;
/** @ts-ignore @type { | typeof __VLS_components.transition | typeof __VLS_components.Transition | typeof __VLS_components.transition | typeof __VLS_components.Transition} */
transition;
// @ts-ignore
const __VLS_41 = __VLS_asFunctionalComponent1(__VLS_40, new __VLS_40({
    name: "slide-up",
}));
const __VLS_42 = __VLS_41({
    name: "slide-up",
}, ...__VLS_functionalComponentArgsRest(__VLS_41));
const { default: __VLS_45 } = __VLS_43.slots;
if (__VLS_ctx.showControls && !__VLS_ctx.loading && !__VLS_ctx.error) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "absolute bottom-0 left-0 right-0 z-20 flex items-center justify-center px-3 py-2 backdrop-blur-md" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['absolute']} */ ;
    /** @type {__VLS_StyleScopedClasses['bottom-0']} */ ;
    /** @type {__VLS_StyleScopedClasses['left-0']} */ ;
    /** @type {__VLS_StyleScopedClasses['right-0']} */ ;
    /** @type {__VLS_StyleScopedClasses['z-20']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['px-3']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['backdrop-blur-md']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "text-xs text-white/80" },
    });
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-white/80']} */ ;
    (Math.min(__VLS_ctx.currentIndex + 1, __VLS_ctx.totalPages));
    (__VLS_ctx.totalPages);
}
// @ts-ignore
[showControls, comic, comic, comic, comic, comic, error, chapterId, chapterId, loading, goPrevChapter, goNextChapter, currentIndex, totalPages, totalPages,];
var __VLS_43;
let __VLS_46;
/** @ts-ignore @type { | typeof __VLS_components.transition | typeof __VLS_components.Transition | typeof __VLS_components.transition | typeof __VLS_components.Transition} */
transition;
// @ts-ignore
const __VLS_47 = __VLS_asFunctionalComponent1(__VLS_46, new __VLS_46({
    name: "fade",
}));
const __VLS_48 = __VLS_47({
    name: "fade",
}, ...__VLS_functionalComponentArgsRest(__VLS_47));
const { default: __VLS_51 } = __VLS_49.slots;
if (__VLS_ctx.showChapterList) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.showChapterList))
                    throw 0;
                return __VLS_ctx.showChapterList = false;
                // @ts-ignore
                [showChapterList, showChapterList,];
            } },
        ...{ class: "absolute inset-0 z-30 flex justify-end" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['absolute']} */ ;
    /** @type {__VLS_StyleScopedClasses['inset-0']} */ ;
    /** @type {__VLS_StyleScopedClasses['z-30']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ onClick: () => { } },
        ...{ class: "h-full w-72 overflow-y-auto px-4 py-4" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['h-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['w-72']} */ ;
    /** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
    /** @type {__VLS_StyleScopedClasses['px-4']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-4']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mb-3 flex items-center justify-between" },
    });
    /** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({
        ...{ class: "font-serif text-base text-white" },
    });
    /** @type {__VLS_StyleScopedClasses['font-serif']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-base']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-white']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.showChapterList))
                    throw 0;
                return __VLS_ctx.showChapterList = false;
                // @ts-ignore
                [showChapterList,];
            } },
        ...{ class: "text-white/70" },
    });
    /** @type {__VLS_StyleScopedClasses['text-white/70']} */ ;
    let __VLS_52;
    /** @ts-ignore @type { | typeof __VLS_components.X} */
    X;
    // @ts-ignore
    const __VLS_53 = __VLS_asFunctionalComponent1(__VLS_52, new __VLS_52({
        size: (18),
    }));
    const __VLS_54 = __VLS_53({
        size: (18),
    }, ...__VLS_functionalComponentArgsRest(__VLS_53));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "space-y-1" },
    });
    /** @type {__VLS_StyleScopedClasses['space-y-1']} */ ;
    for (const [ch] of __VLS_vFor((__VLS_ctx.comic?.chapters || []))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.showChapterList))
                        throw 0;
                    return __VLS_ctx.selectChapter(ch.id);
                    // @ts-ignore
                    [comic, selectChapter,];
                } },
            key: (ch.id),
            ...{ class: "block w-full rounded-lg px-3 py-2 text-left text-sm transition-colors" },
            ...{ style: (ch.id === __VLS_ctx.chapterId
                    ? { background: 'var(--accent-soft)', color: 'var(--accent)' }
                    : { color: 'var(--text-secondary)' }) },
        });
        /** @type {__VLS_StyleScopedClasses['block']} */ ;
        /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
        /** @type {__VLS_StyleScopedClasses['px-3']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-2']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-left']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
        /** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
        (ch.title);
        // @ts-ignore
        [chapterId,];
    }
}
// @ts-ignore
[];
var __VLS_49;
// @ts-ignore
[];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
