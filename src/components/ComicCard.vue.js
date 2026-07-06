/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/** 漫画卡片：封面 + 标题 + 作者。点击跳转详情。 */
import { useRouter } from 'vue-router';
import JmImage from './JmImage.vue';
const props = defineProps();
const router = useRouter();
function goDetail() {
    router.push({ name: 'comic', params: { id: props.comic.id } });
}
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
    ...{ onClick: (__VLS_ctx.goDetail) },
    ...{ class: "card group cursor-pointer overflow-hidden transition-transform active:scale-95" },
});
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['group']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-transform']} */ ;
/** @type {__VLS_StyleScopedClasses['active:scale-95']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "relative" },
});
/** @type {__VLS_StyleScopedClasses['relative']} */ ;
const __VLS_0 = JmImage;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
    src: (__VLS_ctx.comic.cover),
    alt: (__VLS_ctx.comic.name),
    ratio: (3 / 4),
    rounded: (0),
}));
const __VLS_2 = __VLS_1({
    src: (__VLS_ctx.comic.cover),
    alt: (__VLS_ctx.comic.name),
    ratio: (3 / 4),
    rounded: (0),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
if (__VLS_ctx.comic.tags && __VLS_ctx.comic.tags.length > 0) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "absolute left-1.5 top-1.5 rounded-full bg-black/70 px-2 py-0.5 text-[10px] text-white backdrop-blur-sm" },
    });
    /** @type {__VLS_StyleScopedClasses['absolute']} */ ;
    /** @type {__VLS_StyleScopedClasses['left-1.5']} */ ;
    /** @type {__VLS_StyleScopedClasses['top-1.5']} */ ;
    /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['bg-black/70']} */ ;
    /** @type {__VLS_StyleScopedClasses['px-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-white']} */ ;
    /** @type {__VLS_StyleScopedClasses['backdrop-blur-sm']} */ ;
    (__VLS_ctx.comic.tags[0]);
}
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "p-2" },
});
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({
    ...{ class: "line-clamp-2 text-xs font-medium leading-tight" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['line-clamp-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-tight']} */ ;
(__VLS_ctx.comic.name);
if (__VLS_ctx.comic.author) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
        ...{ class: "mt-1 truncate text-[10px]" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['truncate']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
    (__VLS_ctx.comic.author);
}
// @ts-ignore
[goDetail, comic, comic, comic, comic, comic, comic, comic, comic,];
const __VLS_export = (await import('vue')).defineComponent({
    __typeProps: {},
});
export default {};
