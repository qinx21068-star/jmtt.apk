/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/** 顶部导航栏：标题 + 主题切换 + 搜索入口。 */
import { useRouter } from 'vue-router';
import { computed } from 'vue';
import { Search, Sun, Moon, Monitor } from 'lucide-vue-next';
import { useSettingsStore } from '@/stores/settings';
const props = defineProps();
const settings = useSettingsStore();
const router = useRouter();
const themeIcon = computed(() => {
    if (settings.theme === 'light')
        return Sun;
    if (settings.theme === 'dark')
        return Moon;
    return Monitor;
});
function toggleTheme() {
    const order = ['system', 'light', 'dark'];
    const idx = order.indexOf(settings.theme);
    settings.setTheme(order[(idx + 1) % order.length]);
}
function goSearch() {
    router.push({ name: 'search' });
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
__VLS_asFunctionalElement1(__VLS_intrinsics.header, __VLS_intrinsics.header)({
    ...{ class: "sticky top-0 z-30 flex items-center justify-between px-4 py-3 backdrop-blur-md" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['sticky']} */ ;
/** @type {__VLS_StyleScopedClasses['top-0']} */ ;
/** @type {__VLS_StyleScopedClasses['z-30']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-3']} */ ;
/** @type {__VLS_StyleScopedClasses['backdrop-blur-md']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h1, __VLS_intrinsics.h1)({
    ...{ class: "font-serif text-xl font-semibold tracking-wide" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['font-serif']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
(props.title || '本子天国');
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "flex items-center gap-1" },
});
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
if (props.showSearch !== false) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (__VLS_ctx.goSearch) },
        ...{ class: "rounded-full p-2 transition-colors hover:bg-white/10" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['p-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
    /** @type {__VLS_StyleScopedClasses['hover:bg-white/10']} */ ;
    let __VLS_0;
    /** @ts-ignore @type { | typeof __VLS_components.Search} */
    Search;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
        size: (20),
    }));
    const __VLS_2 = __VLS_1({
        size: (20),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
}
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (__VLS_ctx.toggleTheme) },
    ...{ class: "rounded-full p-2 transition-colors hover:bg-white/10" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-white/10']} */ ;
const __VLS_5 = (__VLS_ctx.themeIcon);
// @ts-ignore
const __VLS_6 = __VLS_asFunctionalComponent1(__VLS_5, new __VLS_5({
    size: (20),
}));
const __VLS_7 = __VLS_6({
    size: (20),
}, ...__VLS_functionalComponentArgsRest(__VLS_6));
// @ts-ignore
[goSearch, toggleTheme, themeIcon,];
const __VLS_export = (await import('vue')).defineComponent({
    __typeProps: {},
});
export default {};
