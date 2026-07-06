/**
 * 无限滚动 composable。
 *
 * 用 IntersectionObserver 监听底部触发器元素，进入视口时调用 onLoadMore。
 */
import { type Ref } from 'vue';
export declare function useInfiniteScroll(onLoadMore: () => Promise<void> | void, options?: {
    distance?: number;
    enabled?: Ref<boolean>;
}): {
    sentinel: any;
    loading: any;
    check: () => Promise<void>;
};
