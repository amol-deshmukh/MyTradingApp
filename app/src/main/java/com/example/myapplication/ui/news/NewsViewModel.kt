package com.example.myapplication.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.TradingRepository
import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.NewsCategory
import com.example.myapplication.data.model.NewsItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NewsUiState(
    val items: List<NewsItem> = emptyList(),
    val filter: NewsCategory? = null,
    val connection: ConnectionState = ConnectionState.OFFLINE,
)

class NewsViewModel(private val repository: TradingRepository) : ViewModel() {
    private val filter = MutableStateFlow<NewsCategory?>(null)

    // The SSE connection lives exactly as long as the UI collects this state (plus a 5s grace for
    // configuration changes). Leaving the screen closes it; coming back resumes from Last-Event-ID.
    val state: StateFlow<NewsUiState> = channelFlow {
        launch { repository.streamNews() }
        combine(repository.news, filter, repository.newsConnection) { news, f, conn ->
            NewsUiState(if (f == null) news else news.filter { it.category == f }, f, conn)
        }.collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewsUiState())

    fun setFilter(category: NewsCategory?) {
        filter.value = category
    }
}
