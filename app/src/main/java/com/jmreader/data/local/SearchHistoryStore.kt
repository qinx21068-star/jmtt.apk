package com.jmreader.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 搜索历史：记录用户最近输入的搜索词，最多保留 20 条，按最近使用排序。
 *
 * 实现：用单个 stringPreferencesKey 存关键词，按换行分隔，最近使用的在前。
 * 这样既能保留顺序（最近在前），又能在搜索框下拉里直接显示。
 * 添加时去重 + 提到最前；超过 20 条自动截断旧的。
 */
private val Context.searchHistoryStore by preferencesDataStore(name = "search_history")

class SearchHistoryStore(private val context: Context) {
    private val orderedKey = stringPreferencesKey("ordered_terms")

    /** 所有历史搜索词（按最近使用排序，最近在前，最多 20 条）。 */
    val allTerms: Flow<List<String>> = context.searchHistoryStore.data.map { prefs ->
        prefs[orderedKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }

    /** 添加搜索词（去重，提到最前，超过 20 条截断旧的）。 */
    suspend fun add(term: String) {
        val t = term.trim()
        if (t.isEmpty()) return
        context.searchHistoryStore.edit { prefs ->
            val current = prefs[orderedKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            // 去重：先移除已存在的相同词（注意 \n 不能出现在词里，否则会破坏顺序）
            val safe = t.replace('\n', ' ')
            val filtered = current.filterNot { it == safe }
            val updated = (listOf(safe) + filtered).take(20)
            prefs[orderedKey] = updated.joinToString("\n")
        }
    }

    /** 清空历史。 */
    suspend fun clear() {
        context.searchHistoryStore.edit { prefs ->
            prefs.remove(orderedKey)
        }
    }

    /** 删除单条。归一化方式与 [add] 一致，避免传入带换行/空白的 term 删除失败。 */
    suspend fun remove(term: String) {
        val safe = term.trim().replace('\n', ' ')
        if (safe.isEmpty()) return
        context.searchHistoryStore.edit { prefs ->
            val current = prefs[orderedKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            val updated = current.filterNot { it == safe }
            prefs[orderedKey] = updated.joinToString("\n")
        }
    }
}
