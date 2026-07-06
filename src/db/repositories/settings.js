/** 设置仓库（key-value 存储）。 */
import db from '../database';
export async function getSetting(key, defaultValue) {
    const entry = await db.settings.get(key);
    return entry ? entry.value : defaultValue;
}
export async function setSetting(key, value) {
    await db.settings.put({ key, value });
}
export async function deleteSetting(key) {
    await db.settings.delete(key);
}
