/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/**
 * 收藏页：本地收藏（IndexedDB）+ 服务端收藏（API）Tab 切换。
 *
 * - 本地收藏：始终可用，无需登录，可在详情页取消收藏
 * - 服务端收藏：需登录禁漫账号，分页加载
 */
import { ref, computed, onMounted } from 'vue';
import { useFavoritesStore } from '@/stores/favorites';
import { favorites as apiFavorites, login, logout } from '@/api/jm';
import ComicGrid from '@/components/ComicGrid.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';
import LoadingGrid from '@/components/LoadingGrid.vue';
import { useInfiniteScroll } from '@/composables/useInfiniteScroll';
import { LogOut, LogIn, Heart } from 'lucide-vue-next';
const tab = ref('local');
const tabs = [
    { key: 'local', label: '本地收藏' },
    { key: 'server', label: '服务端收藏' },
];
// ---- 本地收藏 ----
const favoritesStore = useFavoritesStore();
const localItems = computed(() => favoritesStore.favorites);
// ---- 服务端收藏 ----
const isLoggedIn = ref(false);
const loginLoading = ref(false);
const loginError = ref(null);
const username = ref('');
const password = ref('');
const serverItems = ref([]);
const serverPage = ref(1);
const serverTotal = ref(null);
const serverLoading = ref(false);
const serverError = ref(null);
const serverInitialized = ref(false);
const serverHasMore = computed(() => {
    if (serverTotal.value === null)
        return true;
    return serverItems.value.length < serverTotal.value;
});
async function doLogin() {
    if (!username.value || !password.value)
        return;
    loginLoading.value = true;
    loginError.value = null;
    try {
        await login(username.value, password.value);
        isLoggedIn.value = true;
        await loadServerFavorites(true);
    }
    catch (e) {
        loginError.value = e?.message || '登录失败';
    }
    finally {
        loginLoading.value = false;
    }
}
async function doLogout() {
    try {
        await logout();
    }
    catch {
        // 忽略登出错误
    }
    isLoggedIn.value = false;
    username.value = '';
    password.value = '';
    serverItems.value = [];
    serverPage.value = 1;
    serverTotal.value = null;
    serverInitialized.value = false;
}
async function loadServerFavorites(reset = false) {
    if (serverLoading.value)
        return;
    if (!reset && !serverHasMore.value)
        return;
    serverLoading.value = true;
    serverError.value = null;
    try {
        const targetPage = reset ? 1 : serverPage.value;
        const result = await apiFavorites(targetPage);
        serverItems.value = reset ? result.items : [...serverItems.value, ...result.items];
        serverPage.value = targetPage + 1;
        serverTotal.value = result.total;
    }
    catch (e) {
        serverError.value = e?.message || '加载失败';
    }
    finally {
        serverLoading.value = false;
        serverInitialized.value = true;
    }
}
const { sentinel: serverSentinel, loading: serverScrollLoading } = useInfiniteScroll(() => loadServerFavorites(false));
onMounted(async () => {
    // 首次进入确保本地收藏已加载
    if (!favoritesStore.loaded) {
        await favoritesStore.load();
    }
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
    ...{ class: "mb-3 flex gap-2" },
});
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
for (const [t] of __VLS_vFor((__VLS_ctx.tabs))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                return __VLS_ctx.tab = t.key;
                // @ts-ignore
                [tabs, tab,];
            } },
        key: (t.key),
        ...{ class: "flex-1 rounded-full py-1.5 text-sm transition-all" },
        ...{ style: (__VLS_ctx.tab === t.key
                ? {
                    background: 'linear-gradient(135deg, #d946ef 0%, #c026d3 100%)',
                    color: 'white',
                }
                : { background: 'var(--bg-card)', color: 'var(--text-secondary)' }) },
    });
    /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
    /** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
    (t.label);
    // @ts-ignore
    [tab,];
}
if (__VLS_ctx.tab === 'local') {
    if (__VLS_ctx.localItems.length > 0) {
        const __VLS_0 = ComicGrid;
        // @ts-ignore
        const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
            comics: (__VLS_ctx.localItems),
        }));
        const __VLS_2 = __VLS_1({
            comics: (__VLS_ctx.localItems),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    }
    else {
        const __VLS_5 = EmptyState;
        // @ts-ignore
        const __VLS_6 = __VLS_asFunctionalComponent1(__VLS_5, new __VLS_5({
            icon: "💔",
            text: "还没有收藏，去首页找找喜欢的吧",
        }));
        const __VLS_7 = __VLS_6({
            icon: "💔",
            text: "还没有收藏，去首页找找喜欢的吧",
        }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    }
}
else {
    if (!__VLS_ctx.isLoggedIn) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "card mx-auto mt-6 max-w-md p-5" },
        });
        /** @type {__VLS_StyleScopedClasses['card']} */ ;
        /** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
        /** @type {__VLS_StyleScopedClasses['mt-6']} */ ;
        /** @type {__VLS_StyleScopedClasses['max-w-md']} */ ;
        /** @type {__VLS_StyleScopedClasses['p-5']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "mb-4 flex flex-col items-center text-center" },
        });
        /** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
        /** @type {__VLS_StyleScopedClasses['flex']} */ ;
        /** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
        /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-center']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "mb-2 flex h-12 w-12 items-center justify-center rounded-full" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
        /** @type {__VLS_StyleScopedClasses['flex']} */ ;
        /** @type {__VLS_StyleScopedClasses['h-12']} */ ;
        /** @type {__VLS_StyleScopedClasses['w-12']} */ ;
        /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
        let __VLS_10;
        /** @ts-ignore @type { | typeof __VLS_components.LogIn} */
        LogIn;
        // @ts-ignore
        const __VLS_11 = __VLS_asFunctionalComponent1(__VLS_10, new __VLS_10({
            size: (22),
        }));
        const __VLS_12 = __VLS_11({
            size: (22),
        }, ...__VLS_functionalComponentArgsRest(__VLS_11));
        __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({
            ...{ class: "font-serif text-base font-semibold" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['font-serif']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-base']} */ ;
        /** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
            ...{ class: "mt-1 text-xs" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "space-y-3" },
        });
        /** @type {__VLS_StyleScopedClasses['space-y-3']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            value: (__VLS_ctx.username),
            type: "text",
            placeholder: "用户名",
            ...{ class: "w-full rounded-lg border px-3 py-2.5 text-sm outline-none" },
            ...{ style: ({
                    background: 'var(--bg-card)',
                    borderColor: 'var(--border-subtle)',
                    color: 'var(--text-primary)',
                }) },
        });
        /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
        /** @type {__VLS_StyleScopedClasses['border']} */ ;
        /** @type {__VLS_StyleScopedClasses['px-3']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-2.5']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
        /** @type {__VLS_StyleScopedClasses['outline-none']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            ...{ onKeydown: (__VLS_ctx.doLogin) },
            type: "password",
            placeholder: "密码",
            ...{ class: "w-full rounded-lg border px-3 py-2.5 text-sm outline-none" },
            ...{ style: ({
                    background: 'var(--bg-card)',
                    borderColor: 'var(--border-subtle)',
                    color: 'var(--text-primary)',
                }) },
        });
        (__VLS_ctx.password);
        /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
        /** @type {__VLS_StyleScopedClasses['border']} */ ;
        /** @type {__VLS_StyleScopedClasses['px-3']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-2.5']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
        /** @type {__VLS_StyleScopedClasses['outline-none']} */ ;
        if (__VLS_ctx.loginError) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
                ...{ class: "text-xs" },
                ...{ style: {} },
            });
            /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
            (__VLS_ctx.loginError);
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (__VLS_ctx.doLogin) },
            ...{ class: "w-full rounded-lg py-2.5 text-sm text-white transition-all active:scale-95 disabled:opacity-50" },
            ...{ style: {} },
            disabled: (__VLS_ctx.loginLoading || !__VLS_ctx.username || !__VLS_ctx.password),
        });
        /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
        /** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-2.5']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-white']} */ ;
        /** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
        /** @type {__VLS_StyleScopedClasses['active:scale-95']} */ ;
        /** @type {__VLS_StyleScopedClasses['disabled:opacity-50']} */ ;
        (__VLS_ctx.loginLoading ? '登录中…' : '登录');
    }
    else {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "mb-3 flex items-center justify-between rounded-lg px-3 py-2" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
        /** @type {__VLS_StyleScopedClasses['flex']} */ ;
        /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
        /** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
        /** @type {__VLS_StyleScopedClasses['px-3']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-2']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "flex items-center gap-1.5 text-xs" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['flex']} */ ;
        /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
        let __VLS_15;
        /** @ts-ignore @type { | typeof __VLS_components.Heart} */
        Heart;
        // @ts-ignore
        const __VLS_16 = __VLS_asFunctionalComponent1(__VLS_15, new __VLS_15({
            size: (12),
            ...{ style: {} },
        }));
        const __VLS_17 = __VLS_16({
            size: (12),
            ...{ style: {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_16));
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        (__VLS_ctx.username);
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (__VLS_ctx.doLogout) },
            ...{ class: "flex items-center gap-1 text-xs" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['flex']} */ ;
        /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
        /** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
        let __VLS_20;
        /** @ts-ignore @type { | typeof __VLS_components.LogOut} */
        LogOut;
        // @ts-ignore
        const __VLS_21 = __VLS_asFunctionalComponent1(__VLS_20, new __VLS_20({
            size: (12),
        }));
        const __VLS_22 = __VLS_21({
            size: (12),
        }, ...__VLS_functionalComponentArgsRest(__VLS_21));
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        if (__VLS_ctx.serverItems.length > 0) {
            const __VLS_25 = ComicGrid;
            // @ts-ignore
            const __VLS_26 = __VLS_asFunctionalComponent1(__VLS_25, new __VLS_25({
                comics: (__VLS_ctx.serverItems),
            }));
            const __VLS_27 = __VLS_26({
                comics: (__VLS_ctx.serverItems),
            }, ...__VLS_functionalComponentArgsRest(__VLS_26));
        }
        if (!__VLS_ctx.serverInitialized && __VLS_ctx.serverLoading) {
            const __VLS_30 = LoadingGrid;
            // @ts-ignore
            const __VLS_31 = __VLS_asFunctionalComponent1(__VLS_30, new __VLS_30({
                count: (12),
            }));
            const __VLS_32 = __VLS_31({
                count: (12),
            }, ...__VLS_functionalComponentArgsRest(__VLS_31));
        }
        else if (__VLS_ctx.serverError && __VLS_ctx.serverItems.length === 0) {
            const __VLS_35 = ErrorState;
            // @ts-ignore
            const __VLS_36 = __VLS_asFunctionalComponent1(__VLS_35, new __VLS_35({
                ...{ 'onRetry': {} },
                message: (__VLS_ctx.serverError),
            }));
            const __VLS_37 = __VLS_36({
                ...{ 'onRetry': {} },
                message: (__VLS_ctx.serverError),
            }, ...__VLS_functionalComponentArgsRest(__VLS_36));
            let __VLS_40;
            const __VLS_41 = {
                /** @type {typeof __VLS_40.retry} */
                onRetry: (...[$event]) => {
                    if (!!(__VLS_ctx.tab === 'local'))
                        throw 0;
                    if (!!(!__VLS_ctx.isLoggedIn))
                        throw 0;
                    if (!!(!__VLS_ctx.serverInitialized && __VLS_ctx.serverLoading))
                        throw 0;
                    if (!(__VLS_ctx.serverError && __VLS_ctx.serverItems.length === 0))
                        throw 0;
                    return __VLS_ctx.loadServerFavorites(true);
                    // @ts-ignore
                    [tab, localItems, localItems, isLoggedIn, username, username, username, doLogin, doLogin, password, password, loginError, loginError, loginLoading, loginLoading, doLogout, serverItems, serverItems, serverItems, serverInitialized, serverLoading, serverError, serverError, loadServerFavorites,];
                },
            };
            var __VLS_38;
            var __VLS_39;
        }
        else if (__VLS_ctx.serverInitialized && !__VLS_ctx.serverLoading && __VLS_ctx.serverItems.length === 0) {
            const __VLS_42 = EmptyState;
            // @ts-ignore
            const __VLS_43 = __VLS_asFunctionalComponent1(__VLS_42, new __VLS_42({
                icon: "💔",
                text: "服务端没有收藏",
            }));
            const __VLS_44 = __VLS_43({
                icon: "💔",
                text: "服务端没有收藏",
            }, ...__VLS_functionalComponentArgsRest(__VLS_43));
        }
        if (__VLS_ctx.serverInitialized && __VLS_ctx.serverItems.length > 0) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ref: "serverSentinel",
                ...{ class: "flex items-center justify-center py-4 text-xs" },
                ...{ style: {} },
            });
            /** @type {__VLS_StyleScopedClasses['flex']} */ ;
            /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
            /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
            /** @type {__VLS_StyleScopedClasses['py-4']} */ ;
            /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
            if (__VLS_ctx.serverScrollLoading) {
                __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
            }
            else if (!__VLS_ctx.serverHasMore) {
                __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
            }
            else {
                __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
            }
        }
    }
}
// @ts-ignore
[serverItems, serverItems, serverInitialized, serverInitialized, serverLoading, serverScrollLoading, serverHasMore,];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
