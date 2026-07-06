/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/** 底部 Tab 导航：首页/搜索/收藏/设置。 */
import { useRoute, useRouter } from 'vue-router';
import { computed } from 'vue';
import { Home, Search, Heart, Settings } from 'lucide-vue-next';
const router = useRouter();
const route = useRoute();
const tabs = [
    { name: 'home', label: '首页', icon: Home, path: '/' },
    { name: 'search', label: '搜索', icon: Search, path: '/search' },
    { name: 'favorites', label: '收藏', icon: Heart, path: '/favorites' },
    { name: 'settings', label: '设置', icon: Settings, path: '/settings' },
];
const activeName = computed(() => {
    // 当前路由匹配的 tab（comic/reader 等子页归到首页）
    const name = route.name;
    if (name === 'comic' || name === 'reader')
        return 'home';
    return name;
});
function go(name, path) {
    if (route.path !== path)
        router.push(path);
}
const __VLS_ctx = {
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.nav, __VLS_intrinsics.nav)({
    ...{ class: "fixed bottom-0 left-0 right-0 z-30 flex items-center justify-around backdrop-blur-md" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['fixed']} */ ;
/** @type {__VLS_StyleScopedClasses['bottom-0']} */ ;
/** @type {__VLS_StyleScopedClasses['left-0']} */ ;
/** @type {__VLS_StyleScopedClasses['right-0']} */ ;
/** @type {__VLS_StyleScopedClasses['z-30']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-around']} */ ;
/** @type {__VLS_StyleScopedClasses['backdrop-blur-md']} */ ;
for (const [tab] of __VLS_vFor((__VLS_ctx.tabs))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                return __VLS_ctx.go(tab.name, tab.path);
                // @ts-ignore
                [tabs, go,];
            } },
        key: (tab.name),
        ...{ class: "flex flex-1 flex-col items-center gap-0.5 py-2 transition-colors" },
        ...{ style: ({ color: __VLS_ctx.activeName === tab.name ? 'var(--accent)' : 'var(--text-muted)' }) },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-0.5']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
    const __VLS_0 = (tab.icon);
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
        size: (22),
        strokeWidth: (__VLS_ctx.activeName === tab.name ? 2.5 : 2),
    }));
    const __VLS_2 = __VLS_1({
        size: (22),
        strokeWidth: (__VLS_ctx.activeName === tab.name ? 2.5 : 2),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "text-[10px]" },
    });
    /** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
    (tab.label);
    // @ts-ignore
    [activeName, activeName,];
}
// @ts-ignore
[];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
