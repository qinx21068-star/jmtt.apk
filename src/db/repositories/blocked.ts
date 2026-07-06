/** 屏蔽词仓库：标签、标题关键词、作者。 */
import db from '../database'

// ---- 标签屏蔽 ----
export async function listBlockedTags(): Promise<string[]> {
  const arr = await db.blockedTags.orderBy('added_at').toArray()
  return arr.map((e) => e.tag)
}

export async function addBlockedTag(tag: string): Promise<void> {
  const t = tag.trim()
  if (!t) return
  await db.blockedTags.put({ tag: t, added_at: Date.now() })
}

export async function removeBlockedTag(tag: string): Promise<void> {
  await db.blockedTags.delete(tag)
}

// ---- 标题关键词屏蔽 ----
export async function listBlockedNames(): Promise<string[]> {
  const arr = await db.blockedNames.orderBy('added_at').toArray()
  return arr.map((e) => e.name)
}

export async function addBlockedName(name: string): Promise<void> {
  const n = name.trim()
  if (!n) return
  await db.blockedNames.put({ name: n, added_at: Date.now() })
}

export async function removeBlockedName(name: string): Promise<void> {
  await db.blockedNames.delete(name)
}

// ---- 作者屏蔽 ----
export async function listBlockedAuthors(): Promise<string[]> {
  const arr = await db.blockedAuthors.orderBy('added_at').toArray()
  return arr.map((e) => e.author)
}

export async function addBlockedAuthor(author: string): Promise<void> {
  const a = author.trim()
  if (!a) return
  await db.blockedAuthors.put({ author: a, added_at: Date.now() })
}

export async function removeBlockedAuthor(author: string): Promise<void> {
  await db.blockedAuthors.delete(author)
}

/**
 * 加载所有屏蔽词到内存，供列表过滤用。
 * 返回 {tags, names, authors}，均为数组。
 */
export async function loadAllBlocked(): Promise<{
  tags: string[]
  names: string[]
  authors: string[]
}> {
  const [tags, names, authors] = await Promise.all([
    listBlockedTags(),
    listBlockedNames(),
    listBlockedAuthors(),
  ])
  return { tags, names, authors }
}
