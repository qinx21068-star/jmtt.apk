/**
 * 禁漫图片 scramble 解码工具。
 *
 * 移植自 Android JmImageDecoder：
 * - getScrambleNum(): 根据 scramble_id / aid / filename 计算分割数
 * - decodeImage(): 把原图按高度切成 num 块，从下往上取、从上往下贴，还原原图
 *
 * 规则：
 *   aid < scramble_id        → 0（不分割）
 *   aid < 268850             → 10
 *   aid < 421926             → x=10, num = (md5("{aid}{filename}")[-1] % 10) * 2 + 2
 *   aid >= 421926            → x=8,  num = (md5("{aid}{filename}")[-1] % 8)  * 2 + 2
 *
 * 前端用 Canvas 实现，浏览器原生支持。
 */
/** 计算分割数。aid=章节 photo_id，filename=图片文件名（不含扩展名，如 "00047"）。 */
export declare function getScrambleNum(scrambleId: number, aid: number, filename: string): Promise<number>;
/**
 * 解码图片：把原图按 num 块重排。
 *
 * 算法（移植自 Android JmImageDecoder.scramble）：
 *   over = h % num
 *   for i in range(num):
 *       move = floor(h / num)
 *       y_src = h - move*(i+1) - over
 *       y_dst = move * i
 *       if i == 0: move += over
 *       else: y_dst += over
 *       drawImage(src, 0,y_src,w,y_src+move, 0,y_dst,w,y_dst+move)
 *
 * @param srcUrl 原图 URL（Worker 图片代理 URL）
 * @param num 分割数（0=不分割，直接返回原 URL）
 * @return 解码后的 blob URL（需要时释放）；不分割时返回原 URL
 */
export declare function decodeImage(srcUrl: string, num: number): Promise<string>;
/** 从 Worker 图片代理 URL 解析 scramble 参数。 */
export declare function parseImageProxyUrl(url: string): {
    targetUrl: string;
    aid: number;
    scrambleId: number;
    filename: string;
} | null;
/** 解码图片（带缓存）。 */
export declare function decodeImageCached(srcUrl: string, num: number): Promise<string>;
