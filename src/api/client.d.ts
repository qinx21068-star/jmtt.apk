/** 读取 Worker URL（localStorage 持久化）。 */
export declare function getWorkerUrl(): string;
/** 设置 Worker URL（空字符串=用默认）。 */
export declare function setWorkerUrl(url: string): void;
/** GET 请求。 */
export declare function get<T>(path: string, params?: Record<string, string | number | undefined>): Promise<T>;
/** POST 请求。 */
export declare function post<T>(path: string, body?: unknown): Promise<T>;
/** DELETE 请求。 */
export declare function del<T>(path: string): Promise<T>;
