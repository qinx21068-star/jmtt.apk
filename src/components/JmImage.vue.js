/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/**
 * 禁漫图片组件：懒加载 + scramble 解码。
 *
 * 用法：
 *   <JmImage :src="coverUrl" alt="..." :ratio="3/4" />
 *
 * 若 src 是 Worker 图片代理 URL 且含 scramble 参数，自动解码后显示。
 * 用 IntersectionObserver 懒加载，仅加载可视区域 + 1 屏预加载。
 */
import { ref, watch, onMounted, onUnmounted } from 'vue';
import { decodeImageCached, getScrambleNum, parseImageProxyUrl } from '@/api/image';
const props = withDefaults(defineProps(), {
    alt: '',
    ratio: 0,
    rounded: 8,
});
const container = ref(null);
const imgEl = ref(null);
const loaded = ref(false);
const error = ref(false);
const displaySrc = ref('');
const inView = ref(false);
let observer = null;
async function tryLoad() {
    if (!props.src || displaySrc.value)
        return;
    // 检查是否需要 scramble 解码
    const parsed = parseImageProxyUrl(props.src);
    if (parsed && parsed.aid && parsed.scrambleId && parsed.filename) {
        const num = await getScrambleNum(parsed.scrambleId, parsed.aid, parsed.filename);
        displaySrc.value = await decodeImageCached(props.src, num);
    }
    else {
        displaySrc.value = props.src;
    }
}
function onImgLoad() {
    loaded.value = true;
}
function onImgError() {
    error.value = true;
    loaded.value = true;
}
watch(() => props.src, () => {
    displaySrc.value = '';
    loaded.value = false;
    error.value = false;
    if (inView.value)
        tryLoad();
});
onMounted(() => {
    if (!container.value)
        return;
    observer = new IntersectionObserver((entries) => {
        for (const e of entries) {
            if (e.isIntersecting) {
                inView.value = true;
                tryLoad();
                observer?.disconnect();
            }
        }
    }, { rootMargin: '200px' });
    observer.observe(container.value);
});
onUnmounted(() => {
    observer?.disconnect();
});
const __VLS_defaults = {
    alt: '',
    ratio: 0,
    rounded: 8,
};
const __VLS_ctx = {
    ...{},
    ...{},
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ref: "container",
    ...{ class: "relative overflow-hidden bg-ink-800" },
    ...{ style: ({
            borderRadius: `${__VLS_ctx.rounded}px`,
            aspectRatio: __VLS_ctx.ratio ? String(__VLS_ctx.ratio) : undefined,
        }) },
});
/** @type {__VLS_StyleScopedClasses['relative']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-ink-800']} */ ;
if (!__VLS_ctx.loaded && !__VLS_ctx.error) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "absolute inset-0 animate-pulse bg-ink-700/50" },
    });
    /** @type {__VLS_StyleScopedClasses['absolute']} */ ;
    /** @type {__VLS_StyleScopedClasses['inset-0']} */ ;
    /** @type {__VLS_StyleScopedClasses['animate-pulse']} */ ;
    /** @type {__VLS_StyleScopedClasses['bg-ink-700/50']} */ ;
}
if (__VLS_ctx.error || !__VLS_ctx.src) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "absolute inset-0 flex items-center justify-center text-ink-400" },
    });
    /** @type {__VLS_StyleScopedClasses['absolute']} */ ;
    /** @type {__VLS_StyleScopedClasses['inset-0']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-ink-400']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.svg, __VLS_intrinsics.svg)({
        width: "32",
        height: "32",
        viewBox: "0 0 24 24",
        fill: "none",
    });
    __VLS_asFunctionalElement1(__VLS_intrinsics.path)({
        d: "M21 19V5a2 2 0 00-2-2H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2zM8.5 11l1.5 2 2-3 3.5 5H6l2.5-4z",
        fill: "currentColor",
        opacity: "0.5",
    });
}
if (__VLS_ctx.displaySrc && !__VLS_ctx.error) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.img)({
        ...{ onLoad: (__VLS_ctx.onImgLoad) },
        ...{ onError: (__VLS_ctx.onImgError) },
        ref: "imgEl",
        src: (__VLS_ctx.displaySrc),
        alt: (__VLS_ctx.alt),
        loading: "lazy",
        decoding: "async",
        ...{ class: "h-full w-full object-cover transition-opacity duration-300" },
        ...{ class: (__VLS_ctx.loaded ? 'opacity-100' : 'opacity-0') },
    });
    /** @type {__VLS_StyleScopedClasses['h-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['object-cover']} */ ;
    /** @type {__VLS_StyleScopedClasses['transition-opacity']} */ ;
    /** @type {__VLS_StyleScopedClasses['duration-300']} */ ;
}
// @ts-ignore
[rounded, ratio, ratio, loaded, loaded, error, error, error, src, displaySrc, displaySrc, onImgLoad, onImgError, alt,];
const __VLS_export = (await import('vue')).defineComponent({
    __defaults: __VLS_defaults,
    __typeProps: {},
});
export default {};
