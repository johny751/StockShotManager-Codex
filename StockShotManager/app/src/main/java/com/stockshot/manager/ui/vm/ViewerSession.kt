package com.stockshot.manager.ui.vm

import com.stockshot.manager.data.entity.StockRecord

/**
 * 全屏查看器的一次会话数据。由主界面/搜索页构建后交给查看器。
 * fromSearch=true 时顶部额外显示日期信息，切换顺序按列表原顺序（日期倒序）。
 */
data class ViewerSession(
    val records: List<StockRecord> = emptyList(),
    val startIndex: Int = 0,
    val fromSearch: Boolean = false,
)
