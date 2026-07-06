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
const SCRAMBLE_220980 = 220980;
const SCRAMBLE_268850 = 268850;
const SCRAMBLE_421926 = 421926;
/** 简单 MD5 实现（用 Web Crypto 不支持 MD5，用 SubtleCrypto 也不行，这里用纯 JS）。
 *  仅用于 scramble 分割数计算，性能要求不高。 */
async function md5hex(input) {
    // 用浏览器内置的 crypto.subtle 不支持 MD5。
    // 这里用纯 JS 实现（来自 blueimp/md5 简化版）。
    // 为避免引入大依赖，使用以下轻量实现。
    return md5(input);
}
// ---- blueimp/md5 简化实现（仅 ASCII 输入够用，禁漫 aid+filename 都是 ASCII）----
function md5(str) {
    function rotateLeft(x, c) {
        return (x << c) | (x >>> (32 - c));
    }
    function addUnsigned(x, y) {
        const x4 = x & 0x80000000;
        const y4 = y & 0x80000000;
        const x8 = x & 0x00800000;
        const y8 = y & 0x00800000;
        const result = (x & 0x007fffff) + (y & 0x007fffff);
        if (x4 & y4)
            return result ^ 0x80000000 ^ x8 ^ y8;
        if (x4 | y4) {
            if (result & 0x40000000)
                return result ^ 0xc0000000 ^ x8 ^ y8;
            return result ^ 0x40000000 ^ x8 ^ y8;
        }
        return result ^ x8 ^ y8;
    }
    function F(x, y, z) {
        return (x & y) | (~x & z);
    }
    function G(x, y, z) {
        return (x & z) | (y & ~z);
    }
    function H(x, y, z) {
        return x ^ y ^ z;
    }
    function I(x, y, z) {
        return y ^ (x | ~z);
    }
    function FF(a, b, c, d, x, s, t) {
        a = addUnsigned(a, addUnsigned(addUnsigned(F(b, c, d), x), t));
        return addUnsigned(rotateLeft(a, s), b);
    }
    function GG(a, b, c, d, x, s, t) {
        a = addUnsigned(a, addUnsigned(addUnsigned(G(b, c, d), x), t));
        return addUnsigned(rotateLeft(a, s), b);
    }
    function HH(a, b, c, d, x, s, t) {
        a = addUnsigned(a, addUnsigned(addUnsigned(H(b, c, d), x), t));
        return addUnsigned(rotateLeft(a, s), b);
    }
    function II(a, b, c, d, x, s, t) {
        a = addUnsigned(a, addUnsigned(addUnsigned(I(b, c, d), x), t));
        return addUnsigned(rotateLeft(a, s), b);
    }
    function convertToWordArray(str) {
        let lWordCount;
        const lMessageLength = str.length;
        const lNumberOfWords_temp1 = lMessageLength + 8;
        const lNumberOfWords_temp2 = (lNumberOfWords_temp1 - (lNumberOfWords_temp1 % 64)) / 64;
        const lNumberOfWords = (lNumberOfWords_temp2 + 1) * 16;
        const lWordArray = new Array(lNumberOfWords - 1);
        let lBytePosition = 0;
        let lByteCount = 0;
        while (lByteCount < lMessageLength) {
            lWordCount = (lByteCount - (lByteCount % 4)) / 4;
            lBytePosition = (lByteCount % 4) * 8;
            lWordArray[lWordCount] = lWordArray[lWordCount] | (str.charCodeAt(lByteCount) << lBytePosition);
            lByteCount++;
        }
        lWordCount = (lByteCount - (lByteCount % 4)) / 4;
        lBytePosition = (lByteCount % 4) * 8;
        lWordArray[lWordCount] = lWordArray[lWordCount] | (0x80 << lBytePosition);
        lWordArray[lNumberOfWords - 2] = lMessageLength << 3;
        lWordArray[lNumberOfWords - 1] = lMessageLength >>> 29;
        return lWordArray;
    }
    function wordToHex(lValue) {
        let wordToHexValue = '';
        let wordToHexValue_temp = '';
        let lByte;
        let lCount;
        for (lCount = 0; lCount <= 3; lCount++) {
            lByte = (lValue >>> (lCount * 8)) & 255;
            wordToHexValue_temp = '0' + lByte.toString(16);
            wordToHexValue = wordToHexValue + wordToHexValue_temp.substr(wordToHexValue_temp.length - 2, 2);
        }
        return wordToHexValue;
    }
    let x;
    let k;
    let AA;
    let BB;
    let CC;
    let DD;
    let a;
    let b;
    let c;
    let d;
    const S11 = 7;
    const S12 = 12;
    const S13 = 17;
    const S14 = 22;
    const S21 = 5;
    const S22 = 9;
    const S23 = 14;
    const S24 = 20;
    const S31 = 4;
    const S32 = 11;
    const S33 = 16;
    const S34 = 23;
    const S41 = 6;
    const S42 = 10;
    const S43 = 15;
    const S44 = 21;
    const utf8Str = unescape(encodeURIComponent(str));
    x = convertToWordArray(utf8Str);
    a = 0x67452301;
    b = 0xefcdab89;
    c = 0x98badcfe;
    d = 0x10325476;
    for (k = 0; k < x.length; k += 16) {
        AA = a;
        BB = b;
        CC = c;
        DD = d;
        a = FF(a, b, c, d, x[k], S11, 0xd76aa478);
        d = FF(d, a, b, c, x[k + 1], S12, 0xe8c7b756);
        c = FF(c, d, a, b, x[k + 2], S13, 0x242070db);
        b = FF(b, c, d, a, x[k + 3], S14, 0xc1bdceee);
        a = FF(a, b, c, d, x[k + 4], S11, 0xf57c0faf);
        d = FF(d, a, b, c, x[k + 5], S12, 0x4787c62a);
        c = FF(c, d, a, b, x[k + 6], S13, 0xa8304613);
        b = FF(b, c, d, a, x[k + 7], S14, 0xfd469501);
        a = FF(a, b, c, d, x[k + 8], S11, 0x698098d8);
        d = FF(d, a, b, c, x[k + 9], S12, 0x8b44f7af);
        c = FF(c, d, a, b, x[k + 10], S13, 0xffff5bb1);
        b = FF(b, c, d, a, x[k + 11], S14, 0x895cd7be);
        a = FF(a, b, c, d, x[k + 12], S11, 0x6b901122);
        d = FF(d, a, b, c, x[k + 13], S12, 0xfd987193);
        c = FF(c, d, a, b, x[k + 14], S13, 0xa679438e);
        b = FF(b, c, d, a, x[k + 15], S14, 0x49b40821);
        a = GG(a, b, c, d, x[k + 1], S21, 0xf61e2562);
        d = GG(d, a, b, c, x[k + 6], S22, 0xc040b340);
        c = GG(c, d, a, b, x[k + 11], S23, 0x265e5a51);
        b = GG(b, c, d, a, x[k], S24, 0xe9b6c7aa);
        a = GG(a, b, c, d, x[k + 5], S21, 0xd62f105d);
        d = GG(d, a, b, c, x[k + 10], S22, 0x2441453);
        c = GG(c, d, a, b, x[k + 15], S23, 0xd8a1e681);
        b = GG(b, c, d, a, x[k + 4], S24, 0xe7d3fbc8);
        a = GG(a, b, c, d, x[k + 9], S21, 0x21e1cde6);
        d = GG(d, a, b, c, x[k + 14], S22, 0xc33707d6);
        c = GG(c, d, a, b, x[k + 3], S23, 0xf4d50d87);
        b = GG(b, c, d, a, x[k + 8], S24, 0x455a14ed);
        a = GG(a, b, c, d, x[k + 13], S21, 0xa9e3e905);
        d = GG(d, a, b, c, x[k + 2], S22, 0xfcefa3f8);
        c = GG(c, d, a, b, x[k + 7], S23, 0x676f02d9);
        b = GG(b, c, d, a, x[k + 12], S24, 0x8d2a4c8a);
        a = HH(a, b, c, d, x[k + 5], S31, 0xfffa3942);
        d = HH(d, a, b, c, x[k + 8], S32, 0x8771f681);
        c = HH(c, d, a, b, x[k + 11], S33, 0x6d9d6122);
        b = HH(b, c, d, a, x[k + 14], S34, 0xfde5380c);
        a = HH(a, b, c, d, x[k + 1], S31, 0xa4beea44);
        d = HH(d, a, b, c, x[k + 4], S32, 0x4bdecfa9);
        c = HH(c, d, a, b, x[k + 7], S33, 0xf6bb4b60);
        b = HH(b, c, d, a, x[k + 10], S34, 0xbebfbc70);
        a = HH(a, b, c, d, x[k + 13], S31, 0x289b7ec6);
        d = HH(d, a, b, c, x[k], S32, 0xeaa127fa);
        c = HH(c, d, a, b, x[k + 3], S33, 0xd4ef3085);
        b = HH(b, c, d, a, x[k + 6], S34, 0x4881d05);
        a = HH(a, b, c, d, x[k + 9], S31, 0xd9d4d039);
        d = HH(d, a, b, c, x[k + 12], S32, 0xe6db99e5);
        c = HH(c, d, a, b, x[k + 15], S33, 0x1fa27cf8);
        b = HH(b, c, d, a, x[k + 2], S34, 0xc4ac5665);
        a = II(a, b, c, d, x[k], S41, 0xf4292244);
        d = II(d, a, b, c, x[k + 7], S42, 0x432aff97);
        c = II(c, d, a, b, x[k + 14], S43, 0xab9423a7);
        b = II(b, c, d, a, x[k + 5], S44, 0xfc93a039);
        a = II(a, b, c, d, x[k + 12], S41, 0x655b59c3);
        d = II(d, a, b, c, x[k + 3], S42, 0x8f0ccc92);
        c = II(c, d, a, b, x[k + 10], S43, 0xffeff47d);
        b = II(b, c, d, a, x[k + 1], S44, 0x85845dd1);
        a = II(a, b, c, d, x[k + 8], S41, 0x6fa87e4f);
        d = II(d, a, b, c, x[k + 15], S42, 0xfe2ce6e0);
        c = II(c, d, a, b, x[k + 6], S43, 0xa3014314);
        b = II(b, c, d, a, x[k + 13], S44, 0x4e0811a1);
        a = II(a, b, c, d, x[k + 4], S41, 0xf7537e82);
        d = II(d, a, b, c, x[k + 11], S42, 0xbd3af235);
        c = II(c, d, a, b, x[k + 2], S43, 0x2ad7d2bb);
        b = II(b, c, d, a, x[k + 9], S44, 0xeb86d391);
        a = addUnsigned(a, AA);
        b = addUnsigned(b, BB);
        c = addUnsigned(c, CC);
        d = addUnsigned(d, DD);
    }
    return (wordToHex(a) + wordToHex(b) + wordToHex(c) + wordToHex(d)).toLowerCase();
}
/** 计算分割数。aid=章节 photo_id，filename=图片文件名（不含扩展名，如 "00047"）。 */
export async function getScrambleNum(scrambleId, aid, filename) {
    if (aid < scrambleId)
        return 0;
    if (aid < SCRAMBLE_268850)
        return 10;
    const x = aid < SCRAMBLE_421926 ? 10 : 8;
    const s = await md5hex(`${aid}${filename}`);
    const last = s.charCodeAt(s.length - 1);
    return (last % x) * 2 + 2;
}
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
export async function decodeImage(srcUrl, num) {
    if (num === 0)
        return srcUrl;
    const resp = await fetch(srcUrl);
    if (!resp.ok)
        throw new Error(`图片加载失败: ${resp.status}`);
    const blob = await resp.blob();
    const bitmap = await createImageBitmap(blob);
    const w = bitmap.width;
    const h = bitmap.height;
    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext('2d');
    if (!ctx) {
        bitmap.close?.();
        return srcUrl;
    }
    // 黑色背景
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, w, h);
    const over = h % num;
    const moveBase = Math.floor(h / num);
    for (let i = 0; i < num; i++) {
        let move = moveBase;
        let ySrc = h - move * (i + 1) - over;
        let yDst = move * i;
        if (i === 0) {
            move += over;
        }
        else {
            yDst += over;
        }
        // 从源 (0, ySrc, w, ySrc+move) 贴到目标 (0, yDst, w, yDst+move)
        ctx.drawImage(bitmap, 0, ySrc, w, move, 0, yDst, w, move);
    }
    bitmap.close?.();
    return new Promise((resolve) => {
        canvas.toBlob((outBlob) => {
            if (outBlob) {
                resolve(URL.createObjectURL(outBlob));
            }
            else {
                resolve(srcUrl);
            }
        }, 'image/jpeg', 0.92);
    });
}
/** 从 Worker 图片代理 URL 解析 scramble 参数。 */
export function parseImageProxyUrl(url) {
    try {
        const u = new URL(url, window.location.origin);
        if (u.pathname !== '/api/image-proxy')
            return null;
        const targetUrl = u.searchParams.get('url') || '';
        const aid = parseInt(u.searchParams.get('aid') || '0', 10) || 0;
        const scrambleId = parseInt(u.searchParams.get('scramble_id') || '0', 10) || 0;
        const filename = u.searchParams.get('filename') || '';
        return { targetUrl, aid, scrambleId, filename };
    }
    catch {
        return null;
    }
}
/** 缓存：避免同一图片重复解码。key=原图URL，value=解码后 blob URL。 */
const decodeCache = new Map();
/** 解码图片（带缓存）。 */
export async function decodeImageCached(srcUrl, num) {
    if (num === 0)
        return srcUrl;
    const cached = decodeCache.get(srcUrl);
    if (cached)
        return cached;
    const decoded = await decodeImage(srcUrl, num);
    if (decoded !== srcUrl) {
        decodeCache.set(srcUrl, decoded);
    }
    return decoded;
}
