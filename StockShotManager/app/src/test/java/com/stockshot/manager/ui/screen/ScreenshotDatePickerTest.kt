package com.stockshot.manager.ui.screen

import android.app.Application
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ScreenshotDatePickerTest {
    private val today = LocalDate.parse("2026-11-25")
    private val policy = ScreenshotDatePolicy(listOf("2026-09-28"), today)

    @Test
    fun emptyPastDateIsDisabledInCalendarButAcceptedInInput() {
        val state = newState()
        val emptyDate = millis("2025-01-02")
        assertFalse(state.selectableDates.isSelectableYear(2025))
        assertFalse(state.selectableDates.isSelectableDate(emptyDate))
        state.selectedDateMillis = emptyDate
        assertNull(state.selectedDateMillis)

        state.displayMode = DisplayMode.Input
        assertTrue(state.selectableDates.isSelectableYear(2025))
        assertTrue(state.selectableDates.isSelectableDate(emptyDate))
        state.selectedDateMillis = policy.parseInput("2025-01-02")
        assertEquals("2025-01-02", policy.selectionDate(state.selectedDateMillis, input = true))
    }

    @Test
    fun returningToCalendarClearsInputOnlySelectionBeforeConfirmation() {
        val state = newState()
        state.displayMode = DisplayMode.Input
        state.selectedDateMillis = policy.parseInput("2025-01-02")
        assertNotNull(state.selectedDateMillis)

        state.displayMode = DisplayMode.Picker
        assertNull(state.selectedDateMillis)
        assertNull(policy.selectionDate(state.selectedDateMillis, input = false))
        assertFalse(state.selectableDates.isSelectableDate(millis("2025-01-02")))
        state.displayMode = DisplayMode.Input
        assertNull(state.selectedDateMillis)
    }

    @Test
    fun existingPageSurvivesBothModeSwitches() {
        val state = newState()
        state.displayMode = DisplayMode.Input
        assertEquals(millis("2026-09-28"), state.selectedDateMillis)
        state.displayMode = DisplayMode.Picker
        assertEquals("2026-09-28", policy.selectionDate(state.selectedDateMillis, input = false))
    }

    @Test
    fun restoredCalendarStateCannotConfirmAnInputOnlyDate() {
        val restored = DatePickerState(
            locale = Locale.US,
            initialSelectedDateMillis = millis("2025-01-02"),
            yearRange = policy.yearRange,
        )
        val state = ScreenshotDatePickerState(restored, policy)
        assertNull(state.selectedDateMillis)
        assertNull(policy.selectionDate(state.selectedDateMillis, input = false))
        state.displayMode = DisplayMode.Input
        assertNull(state.selectedDateMillis)
    }

    @Test
    fun strictInputRejectsImpossibleIncompleteAndFutureDates() {
        val state = newState()
        state.displayMode = DisplayMode.Input
        for (text in listOf("", "2026-11-", "2026-2-03", "2026-02-29", "2026-02-30",
            "1900-02-29", "2026-13-01", "0000-01-01", "2026-11-26")) {
            assertNull(text, policy.parseInput(text))
            state.selectedDateMillis = policy.parseInput(text)
            assertNull(text, state.selectedDateMillis)
        }
        assertEquals(millis("2024-02-29"), policy.parseInput("2024-02-29"))
        assertEquals(millis("2000-02-29"), policy.parseInput("2000-02-29"))
        assertEquals(millis("1899-12-31"), policy.parseInput("1899-12-31"))
    }

    @Test
    fun emptyHistoryStillAllowsTodayAndManualPastDates() {
        val emptyPolicy = ScreenshotDatePolicy(emptyList(), today)
        val state = ScreenshotDatePickerState(
            DatePickerState(
                locale = Locale.US,
                initialDisplayedMonthMillis = millis(today.toString()),
                yearRange = emptyPolicy.yearRange,
            ),
            emptyPolicy,
        )
        assertTrue(state.selectableDates.isSelectableDate(millis(today.toString())))
        assertFalse(state.selectableDates.isSelectableDate(millis("2026-11-24")))
        state.displayMode = DisplayMode.Input
        state.selectedDateMillis = emptyPolicy.parseInput("1990-01-01")
        assertEquals("1990-01-01", emptyPolicy.selectionDate(state.selectedDateMillis, input = true))
        assertFalse(state.selectableDates.isSelectableDate(millis("2026-11-26")))
    }

    @Test
    fun civilTodayUsesUtcDateEncodingWithoutTimezoneDayShift() {
        // In Shanghai this instant is already Nov 25, while UTC is still Nov 24.
        val clock = Clock.fixed(Instant.parse("2026-11-24T16:30:00Z"), ZoneId.of("Asia/Shanghai"))
        val localPolicy = ScreenshotDatePolicy(emptyList(), LocalDate.now(clock))
        val todayMillis = millis("2026-11-25")
        assertEquals(todayMillis, localPolicy.parseInput("2026-11-25"))
        assertEquals("2026-11-25", utcMillisToDate(todayMillis))
        assertTrue(localPolicy.isSelectableDate(todayMillis, input = true))
        assertNull(localPolicy.parseInput("2026-11-26"))
        assertEquals("2026-11-24", utcMillisToDate(todayMillis - 1))
    }

    @Test
    fun materialStateCanonicalizesValidSelectionToMidnightUtc() {
        val state = newState()
        state.selectedDateMillis = millis("2026-09-28") + 12 * 60 * 60 * 1000
        assertEquals(millis("2026-09-28"), state.selectedDateMillis)
        assertEquals("2026-09-28", policy.selectionDate(state.selectedDateMillis, input = false))
    }

    private fun newState(): ScreenshotDatePickerState = ScreenshotDatePickerState(
        DatePickerState(
            locale = Locale.US,
            initialSelectedDateMillis = millis("2026-09-28"),
            yearRange = policy.yearRange,
        ),
        policy,
    )

    private fun millis(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
