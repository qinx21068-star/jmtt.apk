/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/**
 * 设置页：主题 / Worker URL / 阅读方向 / 屏蔽词管理 / 关于入口。
 */
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Sun, Moon, Monitor, Check, ChevronRight, Plus, X, Loader2, Server, Info, ArrowDownUp, Tag, Type, User, } from 'lucide-vue-next';
import { useSettingsStore } from '@/stores/settings';
import { get, getWorkerUrl } from '@/api/client';
const settings = useSettingsStore();
const router = useRouter();
const themes = [
    { key: 'system', label: '跟随系统', icon: Monitor },
    { key: 'light', label: '浅色', icon: Sun },
    { key: 'dark', label: '深色', icon: Moon },
];
const directions = [
    { key: 'vertical', label: '上下滚动' },
    { key: 'horizontal', label: '左右翻页' },
];
// ---- Worker URL ----
const workerUrlInput = ref(settings.workerUrl);
const workerTesting = ref(false);
const workerTestResult = ref(null);
async function saveWorkerUrl() {
    settings.setWorkerUrl(workerUrlInput.value);
    workerTestResult.value = null;
}
async function testWorker() {
    // 先保存再测试
    settings.setWorkerUrl(workerUrlInput.value);
    workerTesting.value = true;
    workerTestResult.value = null;
    try {
        const data = await get('/api/health');
        workerTestResult.value = { ok: true, msg: `连接正常 (${data.time}ms)` };
    }
    catch (e) {
        workerTestResult.value = { ok: false, msg: e?.message || '连接失败' };
    }
    finally {
        workerTesting.value = false;
    }
}
const blockTabs = [
    { key: 'tag', label: '标签', icon: Tag },
    { key: 'name', label: '标题', icon: Type },
    { key: 'author', label: '作者', icon: User },
];
const currentBlockTab = ref('tag');
const blockInput = ref('');
function currentBlockList() {
    if (currentBlockTab.value === 'tag')
        return settings.blockedTags;
    if (currentBlockTab.value === 'name')
        return settings.blockedNames;
    return settings.blockedAuthors;
}
async function addBlock() {
    const v = blockInput.value.trim();
    if (!v)
        return;
    if (currentBlockTab.value === 'tag')
        await settings.addBlockedTag(v);
    else if (currentBlockTab.value === 'name')
        await settings.addBlockedName(v);
    else
        await settings.addBlockedAuthor(v);
    blockInput.value = '';
}
async function removeBlock(item) {
    if (currentBlockTab.value === 'tag')
        await settings.removeBlockedTag(item);
    else if (currentBlockTab.value === 'name')
        await settings.removeBlockedName(item);
    else
        await settings.removeBlockedAuthor(item);
}
function goAbout() {
    router.push({ name: 'about' });
}
onMounted(() => {
    workerUrlInput.value = getWorkerUrl();
});
const __VLS_ctx = {
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "px-3 pt-3 pb-6" },
});
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['pb-6']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "mb-5" },
});
/** @type {__VLS_StyleScopedClasses['mb-5']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({
    ...{ class: "mb-2 px-1 font-serif text-sm font-semibold" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-serif']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "card p-3" },
});
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "grid grid-cols-3 gap-2" },
});
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
for (const [t] of __VLS_vFor((__VLS_ctx.themes))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                return __VLS_ctx.settings.setTheme(t.key);
                // @ts-ignore
                [themes, settings,];
            } },
        key: (t.key),
        ...{ class: "flex flex-col items-center gap-1.5 rounded-lg border py-3 transition-all" },
        ...{ style: (__VLS_ctx.settings.theme === t.key
                ? {
                    borderColor: 'var(--accent)',
                    background: 'var(--accent-soft)',
                }
                : { borderColor: 'var(--border-subtle)' }) },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
    /** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
    /** @type {__VLS_StyleScopedClasses['border']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-3']} */ ;
    /** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
    const __VLS_0 = (t.icon);
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
        size: (20),
        ...{ style: ({ color: __VLS_ctx.settings.theme === t.key ? 'var(--accent)' : 'var(--text-muted)' }) },
    }));
    const __VLS_2 = __VLS_1({
        size: (20),
        ...{ style: ({ color: __VLS_ctx.settings.theme === t.key ? 'var(--accent)' : 'var(--text-muted)' }) },
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "text-xs" },
        ...{ style: ({
                color: __VLS_ctx.settings.theme === t.key ? 'var(--accent)' : 'var(--text-secondary)',
            }) },
    });
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
    (t.label);
    if (__VLS_ctx.settings.theme === t.key) {
        let __VLS_5;
        /** @ts-ignore @type { | typeof __VLS_components.Check} */
        Check;
        // @ts-ignore
        const __VLS_6 = __VLS_asFunctionalComponent1(__VLS_5, new __VLS_5({
            size: (12),
            ...{ style: {} },
        }));
        const __VLS_7 = __VLS_6({
            size: (12),
            ...{ style: {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    }
    // @ts-ignore
    [settings, settings, settings, settings,];
}
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "mb-5" },
});
/** @type {__VLS_StyleScopedClasses['mb-5']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({
    ...{ class: "mb-2 px-1 font-serif text-sm font-semibold" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-serif']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "card p-3" },
});
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "mb-2 flex items-center gap-2" },
});
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
let __VLS_10;
/** @ts-ignore @type { | typeof __VLS_components.Server} */
Server;
// @ts-ignore
const __VLS_11 = __VLS_asFunctionalComponent1(__VLS_10, new __VLS_10({
    size: (14),
    ...{ style: {} },
}));
const __VLS_12 = __VLS_11({
    size: (14),
    ...{ style: {} },
}, ...__VLS_functionalComponentArgsRest(__VLS_11));
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "text-xs" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    value: (__VLS_ctx.workerUrlInput),
    type: "text",
    placeholder: "https://your-worker.workers.dev",
    ...{ class: "mb-2 w-full rounded-lg border px-3 py-2 text-sm outline-none" },
    ...{ style: ({
            background: 'var(--bg-card)',
            borderColor: 'var(--border-subtle)',
            color: 'var(--text-primary)',
        }) },
});
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['outline-none']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "flex gap-2" },
});
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (__VLS_ctx.saveWorkerUrl) },
    ...{ class: "flex-1 rounded-lg border py-2 text-xs transition-colors" },
    ...{ style: ({
            borderColor: 'var(--border-subtle)',
            color: 'var(--text-secondary)',
        }) },
});
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (__VLS_ctx.testWorker) },
    ...{ class: "flex flex-1 items-center justify-center gap-1 rounded-lg py-2 text-xs text-white transition-all active:scale-95 disabled:opacity-50" },
    ...{ style: {} },
    disabled: (__VLS_ctx.workerTesting),
});
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-white']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
/** @type {__VLS_StyleScopedClasses['active:scale-95']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled:opacity-50']} */ ;
if (__VLS_ctx.workerTesting) {
    let __VLS_15;
    /** @ts-ignore @type { | typeof __VLS_components.Loader2} */
    Loader2;
    // @ts-ignore
    const __VLS_16 = __VLS_asFunctionalComponent1(__VLS_15, new __VLS_15({
        size: (12),
        ...{ class: "animate-spin" },
    }));
    const __VLS_17 = __VLS_16({
        size: (12),
        ...{ class: "animate-spin" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_16));
    /** @type {__VLS_StyleScopedClasses['animate-spin']} */ ;
}
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
(__VLS_ctx.workerTesting ? '测试中…' : '测试连接');
if (__VLS_ctx.workerTestResult) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
        ...{ class: "mt-2 text-xs" },
        ...{ style: ({ color: __VLS_ctx.workerTestResult.ok ? '#10b981' : '#ef4444' }) },
    });
    /** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
    (__VLS_ctx.workerTestResult.ok ? '✓ ' : '✗ ');
    (__VLS_ctx.workerTestResult.msg);
}
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
    ...{ class: "mt-2 text-[11px] leading-relaxed" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[11px]']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-relaxed']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "mb-5" },
});
/** @type {__VLS_StyleScopedClasses['mb-5']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({
    ...{ class: "mb-2 px-1 font-serif text-sm font-semibold" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-serif']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "card p-3" },
});
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "mb-2 flex items-center gap-2" },
});
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
let __VLS_20;
/** @ts-ignore @type { | typeof __VLS_components.ArrowDownUp} */
ArrowDownUp;
// @ts-ignore
const __VLS_21 = __VLS_asFunctionalComponent1(__VLS_20, new __VLS_20({
    size: (14),
    ...{ style: {} },
}));
const __VLS_22 = __VLS_21({
    size: (14),
    ...{ style: {} },
}, ...__VLS_functionalComponentArgsRest(__VLS_21));
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "grid grid-cols-2 gap-2" },
});
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
for (const [d] of __VLS_vFor((__VLS_ctx.directions))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                return __VLS_ctx.settings.setReaderDirection(d.key);
                // @ts-ignore
                [settings, workerUrlInput, saveWorkerUrl, testWorker, workerTesting, workerTesting, workerTesting, workerTestResult, workerTestResult, workerTestResult, workerTestResult, directions,];
            } },
        key: (d.key),
        ...{ class: "rounded-lg border py-2.5 text-sm transition-all" },
        ...{ style: (__VLS_ctx.settings.readerDirection === d.key
                ? {
                    borderColor: 'var(--accent)',
                    background: 'var(--accent-soft)',
                    color: 'var(--accent)',
                }
                : {
                    borderColor: 'var(--border-subtle)',
                    color: 'var(--text-secondary)',
                }) },
    });
    /** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
    /** @type {__VLS_StyleScopedClasses['border']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-2.5']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
    /** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
    (d.label);
    // @ts-ignore
    [settings,];
}
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "mb-5" },
});
/** @type {__VLS_StyleScopedClasses['mb-5']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({
    ...{ class: "mb-2 px-1 font-serif text-sm font-semibold" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-serif']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "card p-3" },
});
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "mb-3 flex gap-1.5" },
});
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
for (const [t] of __VLS_vFor((__VLS_ctx.blockTabs))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                return __VLS_ctx.currentBlockTab = t.key;
                // @ts-ignore
                [blockTabs, currentBlockTab,];
            } },
        key: (t.key),
        ...{ class: "flex flex-1 items-center justify-center gap-1 rounded-lg py-1.5 text-xs transition-all" },
        ...{ style: (__VLS_ctx.currentBlockTab === t.key
                ? { background: 'var(--accent-soft)', color: 'var(--accent)' }
                : { background: 'var(--bg-card)', color: 'var(--text-muted)' }) },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
    /** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
    /** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
    const __VLS_25 = (t.icon);
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent1(__VLS_25, new __VLS_25({
        size: (12),
    }));
    const __VLS_27 = __VLS_26({
        size: (12),
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    (t.label);
    // @ts-ignore
    [currentBlockTab,];
}
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "mb-3 flex gap-2" },
});
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    ...{ onKeydown: (__VLS_ctx.addBlock) },
    value: (__VLS_ctx.blockInput),
    type: "text",
    placeholder: (`添加${__VLS_ctx.currentBlockTab === 'tag' ? '标签' : __VLS_ctx.currentBlockTab === 'name' ? '标题关键词' : '作者名'}`),
    ...{ class: "flex-1 rounded-lg border px-3 py-1.5 text-sm outline-none" },
    ...{ style: ({
            background: 'var(--bg-card)',
            borderColor: 'var(--border-subtle)',
            color: 'var(--text-primary)',
        }) },
});
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['outline-none']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (__VLS_ctx.addBlock) },
    ...{ class: "flex items-center justify-center rounded-lg px-3 text-white transition-all active:scale-95" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-white']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
/** @type {__VLS_StyleScopedClasses['active:scale-95']} */ ;
let __VLS_30;
/** @ts-ignore @type { | typeof __VLS_components.Plus} */
Plus;
// @ts-ignore
const __VLS_31 = __VLS_asFunctionalComponent1(__VLS_30, new __VLS_30({
    size: (16),
}));
const __VLS_32 = __VLS_31({
    size: (16),
}, ...__VLS_functionalComponentArgsRest(__VLS_31));
if (__VLS_ctx.currentBlockList().length > 0) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "flex flex-wrap gap-1.5" },
    });
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
    for (const [item] of __VLS_vFor((__VLS_ctx.currentBlockList()))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            key: (item),
            ...{ class: "group flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs" },
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
        /** @type {__VLS_StyleScopedClasses['px-2.5']} */ ;
        /** @type {__VLS_StyleScopedClasses['py-1']} */ ;
        /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        (item);
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.currentBlockList().length > 0))
                        throw 0;
                    return __VLS_ctx.removeBlock(item);
                    // @ts-ignore
                    [currentBlockTab, currentBlockTab, addBlock, addBlock, blockInput, currentBlockList, currentBlockList, removeBlock,];
                } },
            ...{ class: "opacity-50 transition-opacity group-hover:opacity-100" },
            ...{ style: {} },
        });
        /** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
        /** @type {__VLS_StyleScopedClasses['transition-opacity']} */ ;
        /** @type {__VLS_StyleScopedClasses['group-hover:opacity-100']} */ ;
        let __VLS_35;
        /** @ts-ignore @type { | typeof __VLS_components.X} */
        X;
        // @ts-ignore
        const __VLS_36 = __VLS_asFunctionalComponent1(__VLS_35, new __VLS_35({
            size: (11),
        }));
        const __VLS_37 = __VLS_36({
            size: (11),
        }, ...__VLS_functionalComponentArgsRest(__VLS_36));
        // @ts-ignore
        [];
    }
}
else {
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
        ...{ class: "py-3 text-center text-xs" },
        ...{ style: {} },
    });
    /** @type {__VLS_StyleScopedClasses['py-3']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
}
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "mb-5" },
});
/** @type {__VLS_StyleScopedClasses['mb-5']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (__VLS_ctx.goAbout) },
    ...{ class: "card flex w-full items-center justify-between p-4 transition-colors" },
});
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "flex items-center gap-2" },
});
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
let __VLS_40;
/** @ts-ignore @type { | typeof __VLS_components.Info} */
Info;
// @ts-ignore
const __VLS_41 = __VLS_asFunctionalComponent1(__VLS_40, new __VLS_40({
    size: (18),
    ...{ style: {} },
}));
const __VLS_42 = __VLS_41({
    size: (18),
    ...{ style: {} },
}, ...__VLS_functionalComponentArgsRest(__VLS_41));
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "text-sm" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
let __VLS_45;
/** @ts-ignore @type { | typeof __VLS_components.ChevronRight} */
ChevronRight;
// @ts-ignore
const __VLS_46 = __VLS_asFunctionalComponent1(__VLS_45, new __VLS_45({
    size: (16),
    ...{ style: {} },
}));
const __VLS_47 = __VLS_46({
    size: (16),
    ...{ style: {} },
}, ...__VLS_functionalComponentArgsRest(__VLS_46));
// @ts-ignore
[goAbout,];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
