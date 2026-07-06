/// <reference types="../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/**
 * App 根组件：路由出口 + 免责声明弹窗 + 路由初始化。
 *
 * - 启动时初始化设置 store（加载持久化设置）
 * - 首次访问弹免责声明（5 秒倒计时强制阅读）
 * - 阅读器路由隐藏底部导航
 */
import { onMounted, ref, computed } from 'vue';
import { useRoute } from 'vue-router';
import { useSettingsStore } from '@/stores/settings';
import { useFavoritesStore } from '@/stores/favorites';
import AppHeader from '@/components/AppHeader.vue';
import BottomNav from '@/components/BottomNav.vue';
import DisclaimerDialog from '@/components/DisclaimerDialog.vue';
const settings = useSettingsStore();
const favorites = useFavoritesStore();
const route = useRoute();
const showDisclaimer = ref(false);
// 阅读器全屏，不显示顶栏和底栏
const isFullscreen = computed(() => route.name === 'reader');
const showHeader = computed(() => !isFullscreen.value);
const showBottomNav = computed(() => !isFullscreen.value);
onMounted(async () => {
    await settings.init();
    await favorites.load();
    // 首次访问弹免责声明
    if (!settings.disclaimerAccepted) {
        showDisclaimer.value = true;
    }
});
function onAcceptDisclaimer() {
    settings.acceptDisclaimer();
    showDisclaimer.value = false;
}
function onDismissDisclaimer() {
    // 不同意：关闭页面（移动端无法关闭，跳到空白页）
    showDisclaimer.value = false;
    window.location.href = 'about:blank';
}
const __VLS_ctx = {
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "app-container" },
});
/** @type {__VLS_StyleScopedClasses['app-container']} */ ;
if (__VLS_ctx.showHeader) {
    const __VLS_0 = AppHeader;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({}));
    const __VLS_2 = __VLS_1({}, ...__VLS_functionalComponentArgsRest(__VLS_1));
}
__VLS_asFunctionalElement1(__VLS_intrinsics.main, __VLS_intrinsics.main)({
    ...{ class: "relative" },
    ...{ class: (__VLS_ctx.showHeader ? 'pt-0' : '') },
    ...{ style: ({
            paddingBottom: __VLS_ctx.showBottomNav ? 'calc(60px + var(--safe-bottom))' : '0',
        }) },
});
/** @type {__VLS_StyleScopedClasses['relative']} */ ;
let __VLS_5;
/** @ts-ignore @type { | typeof __VLS_components.routerView | typeof __VLS_components.RouterView | typeof __VLS_components['router-view']} */
routerView;
// @ts-ignore
const __VLS_6 = __VLS_asFunctionalComponent1(__VLS_5, new __VLS_5({}));
const __VLS_7 = __VLS_6({}, ...__VLS_functionalComponentArgsRest(__VLS_6));
if (__VLS_ctx.showBottomNav) {
    const __VLS_10 = BottomNav;
    // @ts-ignore
    const __VLS_11 = __VLS_asFunctionalComponent1(__VLS_10, new __VLS_10({}));
    const __VLS_12 = __VLS_11({}, ...__VLS_functionalComponentArgsRest(__VLS_11));
}
if (__VLS_ctx.showDisclaimer) {
    const __VLS_15 = DisclaimerDialog;
    // @ts-ignore
    const __VLS_16 = __VLS_asFunctionalComponent1(__VLS_15, new __VLS_15({
        ...{ 'onAccept': {} },
        ...{ 'onDismiss': {} },
        forceCountdown: (true),
    }));
    const __VLS_17 = __VLS_16({
        ...{ 'onAccept': {} },
        ...{ 'onDismiss': {} },
        forceCountdown: (true),
    }, ...__VLS_functionalComponentArgsRest(__VLS_16));
    let __VLS_20;
    const __VLS_21 = {
        /** @type {typeof __VLS_20.accept} */
        onAccept: (__VLS_ctx.onAcceptDisclaimer),
    };
    const __VLS_22 = {
        /** @type {typeof __VLS_20.dismiss} */
        onDismiss: (__VLS_ctx.onDismissDisclaimer),
    };
    var __VLS_18;
    var __VLS_19;
}
// @ts-ignore
[showHeader, showHeader, showBottomNav, showBottomNav, showDisclaimer, onAcceptDisclaimer, onDismissDisclaimer,];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
