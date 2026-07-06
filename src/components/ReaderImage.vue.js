/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/**
 * 阅读器图片组件：全宽自适应 + scramble 解码 + 懒加载。
 *
 * 专为阅读器设计（与 JmImage 区别：不固定宽高比，图片原始比例显示）。
 */
import { ref, watch, onMounted, onUnmounted } from 'vue';
import { decodeImageCached, getScrambleNum, parseImageProxyUrl } from '@/api/image';
const props = defineProps();
const container = ref(null);
const displaySrc = ref('');
const loaded = ref(false);
const error = ref(false);
const inView = ref(false);
let observer = null;
async function tryLoad() {
    if (!props.src || displaySrc.value)
        return;
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
    }, { rootMargin: '300px' });
    observer.observe(container.value);
});
onUnmounted(() => {
    observer?.disconnect();
});
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
    ...{ class: "w-full" },
});
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
if (!__VLS_ctx.loaded && !__VLS_ctx.error) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "flex h-40 w-full items-center justify-center bg-black/40" },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['h-40']} */ ;
    /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['bg-black/40']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "h-6 w-6 animate-spin rounded-full border-2 border-white/20 border-t-white/60" },
    });
    /** @type {__VLS_StyleScopedClasses['h-6']} */ ;
    /** @type {__VLS_StyleScopedClasses['w-6']} */ ;
    /** @type {__VLS_StyleScopedClasses['animate-spin']} */ ;
    /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['border-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['border-white/20']} */ ;
    /** @type {__VLS_StyleScopedClasses['border-t-white/60']} */ ;
}
if (__VLS_ctx.displaySrc && !__VLS_ctx.error) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.img)({
        ...{ onLoad: (__VLS_ctx.onImgLoad) },
        ...{ onError: (__VLS_ctx.onImgError) },
        src: (__VLS_ctx.displaySrc),
        loading: "lazy",
        decoding: "async",
        ...{ class: "block w-full" },
        ...{ class: (__VLS_ctx.loaded ? 'opacity-100' : 'opacity-0') },
    });
    /** @type {__VLS_StyleScopedClasses['block']} */ ;
    /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
}
if (__VLS_ctx.error) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "flex h-40 w-full items-center justify-center bg-black/40 text-white/40" },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['h-40']} */ ;
    /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['bg-black/40']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-white/40']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "text-xs" },
    });
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
}
// @ts-ignore
[loaded, loaded, error, error, error, displaySrc, displaySrc, onImgLoad, onImgError,];
const __VLS_export = (await import('vue')).defineComponent({
    __typeProps: {},
});
export default {};
