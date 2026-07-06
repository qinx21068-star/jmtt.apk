/** 设置仓库（key-value 存储）。 */
import db from '../database'

export async function getSetting<T>(key: string, defaultValue: T): Promise<T> {
  const entry = await db.settings.get(key)
  return entry ? (entry.value as T) : defaultValue
}

export async function setSetting(key: string, value: unknown): Promise<void> {
  await db.settings.put({ key, value })
}

export async function deleteSetting(key: string): Promise<void> {
  await db.settings.delete(key)
}
