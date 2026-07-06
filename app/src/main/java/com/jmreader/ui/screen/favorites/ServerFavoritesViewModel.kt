package com.jmreader.ui.screen.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.repository.Resource
import com.jmreader.ui.viewmodel.BaseListViewModel
import kotlinx.coroutines.flow.first

class ServerFavoritesViewModel(container: AppContainer) : BaseListViewModel(container) {
    init { refresh() }

    override suspend fun loadPage(page: Int): Resource<Pair<List<ComicBriefDto>, Int?>> {
        // 未登录时直接返回错误，避免调用 /favorite 接口长时间无响应导致一直转圈
        // （未登录访问 /favorite 时禁漫移动端可能返回空响应或非 JSON 内容，reqApi 会重试所有域名后抛错，
        // 多域名轮询+域名刷新重试会让单次请求耗时数分钟，体验上是「一直转圈」）
        val user = runCatching { container.settingsStore.settings.first().loggedInUser }.getOrNull()
        if (user.isNullOrBlank()) {
            return Resource.Error("未登录账号，请在设置中登录后查看站点收藏")
        }
        return when (val r = container.repository.serverFavorites(page)) {
            is Resource.Success -> Resource.Success(r.data.items to r.data.total)
            is Resource.Error -> r
            Resource.Loading -> Resource.Loading
        }
    }
}

class ServerFavoritesVMFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ServerFavoritesViewModel(container) as T
}
