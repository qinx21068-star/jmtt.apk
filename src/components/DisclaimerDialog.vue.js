/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/template-helpers.d.ts" />
/// <reference types="../../../../root/.npm/_npx/2db181330ea4b15b/node_modules/@vue/language-core/types/props-fallback.d.ts" />
/**
 * 免责声明对话框。
 *
 * - forceCountdown=true：首次启动，5 秒倒计时强制阅读，"不同意"按钮退出
 * - forceCountdown=false：关于页查看，无倒计时，"关闭"按钮即可
 */
import { ref, onMounted, onUnmounted, computed } from 'vue';
import { ShieldAlert } from 'lucide-vue-next';
const props = withDefaults(defineProps(), {
    forceCountdown: false,
});
const emit = defineEmits();
const remaining = ref(props.forceCountdown ? 5 : 0);
let timer = null;
onMounted(() => {
    if (props.forceCountdown && remaining.value > 0) {
        timer = window.setInterval(() => {
            remaining.value -= 1;
            if (remaining.value <= 0 && timer) {
                window.clearInterval(timer);
                timer = null;
            }
        }, 1000);
    }
});
onUnmounted(() => {
    if (timer)
        window.clearInterval(timer);
});
const canAct = computed(() => remaining.value === 0);
const buttonText = computed(() => {
    if (remaining.value > 0)
        return `请阅读 ${remaining}s`;
    return props.forceCountdown ? '我已阅读并同意' : '关闭';
});
function onAccept() {
    if (!canAct.value)
        return;
    emit('accept');
}
function onDismiss() {
    if (!canAct.value)
        return;
    emit('dismiss');
}
const __VLS_defaults = {
    forceCountdown: false,
};
const __VLS_ctx = {
    ...{},
    ...{},
    ...{},
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "fixed inset-0 z-50 flex items-center justify-center p-4" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['fixed']} */ ;
/** @type {__VLS_StyleScopedClasses['inset-0']} */ ;
/** @type {__VLS_StyleScopedClasses['z-50']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "card max-h-[85vh] w-full max-w-md overflow-hidden animate-slide-up" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-[85vh]']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-md']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['animate-slide-up']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "flex items-center gap-2 border-b px-5 py-4" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['px-5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-4']} */ ;
let __VLS_0;
/** @ts-ignore @type { | typeof __VLS_components.ShieldAlert} */
ShieldAlert;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
    size: (20),
    ...{ class: "text-accent-500" },
}));
const __VLS_2 = __VLS_1({
    size: (20),
    ...{ class: "text-accent-500" },
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
/** @type {__VLS_StyleScopedClasses['text-accent-500']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({
    ...{ class: "font-serif text-lg font-semibold" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['font-serif']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "overflow-y-auto px-5 py-4" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-4']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "space-y-3 text-sm leading-relaxed" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['space-y-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-relaxed']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.ol, __VLS_intrinsics.ol)({
    ...{ class: "space-y-2 pl-4" },
});
/** @type {__VLS_StyleScopedClasses['space-y-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pl-4']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
    ...{ class: "font-serif italic" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['font-serif']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "flex gap-2 border-t px-5 py-3" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['px-5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-3']} */ ;
if (__VLS_ctx.forceCountdown) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (__VLS_ctx.onDismiss) },
        ...{ class: "btn-ghost flex-1 text-sm" },
        disabled: (!__VLS_ctx.canAct),
        ...{ style: ({ opacity: __VLS_ctx.canAct ? 1 : 0.5 }) },
    });
    /** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
}
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (__VLS_ctx.onAccept) },
    ...{ class: "flex-1 text-sm" },
    ...{ class: (__VLS_ctx.forceCountdown ? 'btn-primary' : 'btn-ghost') },
    disabled: (!__VLS_ctx.canAct),
    ...{ style: ({ opacity: __VLS_ctx.canAct ? 1 : 0.5 }) },
});
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
(__VLS_ctx.buttonText);
// @ts-ignore
[forceCountdown, forceCountdown, onDismiss, canAct, canAct, canAct, canAct, onAccept, buttonText,];
const __VLS_export = (await import('vue')).defineComponent({
    __typeEmits: {},
    __defaults: __VLS_defaults,
    __typeProps: {},
});
export default {};
