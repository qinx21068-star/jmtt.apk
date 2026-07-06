/**
 * 无限滚动 composable。
 *
 * 用 IntersectionObserver 监听底部触发器元素，进入视口时调用 onLoadMore。
 */
import { onMounted, onUnmounted, ref } from 'vue';
export function useInfiniteScroll(onLoadMore, options = {}) {
    const { distance = 200, enabled } = options;
    const sentinel = ref(null);
    const loading = ref(false);
    let observer = null;
    async function check() {
        if (enabled && !enabled.value)
            return;
        if (loading.value)
            return;
        loading.value = true;
        try {
            await onLoadMore();
        }
        finally {
            loading.value = false;
        }
    }
    onMounted(() => {
        if (!sentinel.value)
            return;
        observer = new IntersectionObserver((entries) => {
            for (const entry of entries) {
                if (entry.isIntersecting) {
                    check();
                }
            }
        }, { rootMargin: `0px 0px ${distance}px 0px` });
        observer.observe(sentinel.value);
    });
    onUnmounted(() => {
        observer?.disconnect();
        observer = null;
    });
    return { sentinel, loading, check };
}
