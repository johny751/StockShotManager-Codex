package com.stockshot.manager.util

import org.junit.Assert.*
import org.junit.Test

class ProfitCalcTest {
    @Test fun sameSellPriceHasDifferentReturnsForDifferentBuyPrices() {
        assertEquals(20.0, ProfitCalc.profitRate(10.0, 12.0)!!, 0.0)
        assertEquals(-40.0, ProfitCalc.profitRate(20.0, 12.0)!!, 0.0)
    }

    @Test fun zeroPriceHasNoDisplayedProfit() {
        assertNull(ProfitCalc.profitRate(0.0, 12.0))
        assertNull(ProfitCalc.profitRate(10.0, 0.0))
        assertEquals("--", ProfitCalc.displayRate(0.0, 12.0))
    }

    @Test fun percentIsRoundedToTwoDecimals() {
        assertEquals(3.33, ProfitCalc.profitRate(3.0, 3.1)!!, 0.0)
        assertEquals(-3.33, ProfitCalc.profitRate(3.0, 2.9)!!, 0.0)
    }
}
