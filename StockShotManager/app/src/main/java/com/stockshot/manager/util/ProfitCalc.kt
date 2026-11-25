package com.stockshot.manager.util

import kotlin.math.abs

/** 鑾峰埄姣斾緥璁＄畻涓庢牸寮忓寲 */
object ProfitCalc {

    /**
     * 璁＄畻鑾峰埄姣斾緥銆備拱鍏ヤ环鎴栧崠鍑轰环涓?0 鏃惰繑鍥?null锛堣〃绀轰笉鍙绠楋級銆?
     * 鍏紡锛?鍗栧嚭浠?- 涔板叆浠? / 涔板叆浠?脳 100锛屼繚鐣欎袱浣嶅皬鏁般€?
     */
    fun profitRate(buy: Double, sell: Double): Double? {
        if (buy <= 0.0 || sell <= 0.0) return null
        val rate = (sell - buy) / buy * 100.0
        return Math.round(rate * 100.0) / 100.0
    }

    /** 鏄剧ず鏂囨湰锛氫笉鍙绠楁樉绀?"--"锛屽惁鍒欏甫绗﹀彿鐧惧垎姣?*/
    fun displayRate(buy: Double, sell: Double): String {
        val r = profitRate(buy, sell) ?: return "--"
        val sign = if (r > 0) "+" else ""
        return "$sign${trimZero(r)}%"
    }

    private fun trimZero(v: Double): String {
        val s = String.format("%.2f", abs(v))
        val signed = if (v < 0) "-$s" else s
        return signed
    }
}
