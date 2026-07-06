/**
 * IndexedDB 数据层（Dexie 封装）。
 *
 * 存储用户本地数据：收藏、阅读历史、设置、屏蔽词、搜索历史、漫画缓存。
 * 所有数据仅存于本地，不上传任何服务器。
 */
import Dexie from 'dexie';
class JmDatabase extends Dexie {
    favorites;
    history;
    settings;
    blockedTags;
    blockedNames;
    blockedAuthors;
    searchHistory;
    comicCache;
    constructor() {
        super('jmtt-pwa');
        this.version(1).stores({
            favorites: 'id, added_at, name',
            history: 'chapter_id, comic_id, updated_at',
            settings: 'key',
            blockedTags: 'tag, added_at',
            blockedNames: 'name, added_at',
            blockedAuthors: 'author, added_at',
            searchHistory: 'keyword, searched_at',
            comicCache: 'id, cached_at',
        });
    }
}
const db = new JmDatabase();
export default db;
