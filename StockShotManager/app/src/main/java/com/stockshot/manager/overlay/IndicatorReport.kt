package com.stockshot.manager.overlay

import com.stockshot.indicator.DetectionStatus
import com.stockshot.indicator.IndicatorMatchResult
import java.util.Locale

internal object IndicatorReport {
    fun label(status: DetectionStatus): String = when (status) {
        DetectionStatus.MATCH -> "符合"
        DetectionStatus.NO_MATCH -> "不符合"
        DetectionStatus.UNKNOWN -> "无法判断"
    }

    fun describe(result: IndicatorMatchResult): String = buildString {
        val d = result.diagnostics
        append("指标识别：").append(label(result.status)).append('\n')
        append("红绿点：").append(label(result.trendStatus))
        append("；资金：").append(label(result.capitalStatus)).append('\n')
        append(result.reason).append('\n')
        append(if (d.capitalMethod == "median") "方案一 · 中位位置" else "方案二 · 逐列比较")
        if (d.capitalMethod == "pointwise") append(" / ").append(when (d.capitalRule) {
            "majority" -> "满足比例至少 "+String.format(Locale.ROOT,"%.0f%%",d.majorityThreshold*100)
            "signal" -> "信号出现时"
            else -> "信号之前整段每列满足"
        })
        append('\n').append(if (d.signalSelection == "latest") "只检查最新一对" else "从左到右，首个符合即停止")
        append("；候选 ").append(d.candidatePairCount).append(" 对，已检查 ").append(d.checkedPairCount).append(" 对\n")
        val s = d.capitalStats
        if (s.totalColumns > 0) {
            append("当前窗口：").append(s.totalColumns).append(" 列；可读 ").append(s.validColumns)
            append("，满足 ").append(s.satisfiedColumns).append("，不满足 ").append(s.violatingColumns)
            append("，缺失 ").append(s.missingColumns).append('\n')
            if (s.violatingColumns > 0 && s.missingColumns > 0) append("反例与缺失可能出现在同一列，计数可重叠。\n")
            append("满足占整窗 ").append(String.format(Locale.ROOT,"%.1f%%",s.satisfiedColumns*100f/s.totalColumns))
            append("；最长连续满足 ").append(s.longestSatisfiedRun).append(" 列\n")
            if (d.capitalMethod == "median") append("逐列统计仅供诊断；方案一仍按中位位置判定。\n")
        } else append("没有可检查的资金窗口。\n")
        append("规则评分：").append(String.format(Locale.ROOT,"%.0f/100",result.confidence*100))
        append("（不是正确概率）\n")
        d.messages.distinct().forEach { append("• ").append(it).append('\n') }
        append("图例：青框=趋势扫描；蓝框=资金扫描；橙框=当前资金窗口；红/绿小框=候选点；白圈=最终点对。\n")
        append("窗口底部：绿=满足，红=不满足，灰=缺失；红列灰短线表示另一条必需线缺失。双指缩放，单指拖动，双击复位。")
    }
}
