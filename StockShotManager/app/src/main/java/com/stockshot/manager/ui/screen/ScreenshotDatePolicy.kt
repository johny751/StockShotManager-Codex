package com.stockshot.manager.ui.screen

import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Dates are civil dates; Material3 encodes them at midnight UTC, regardless of the device zone. */
internal class ScreenshotDatePolicy(availableDates: List<String>, val today: LocalDate) {
    val yearRange: IntRange = 1..today.year
    private val calendarDates = availableDates.mapNotNull(::parseIsoDate)
        .filter { it.year in yearRange && !it.isAfter(today) }.toSet() + today
    private val calendarYears = calendarDates.mapTo(mutableSetOf()) { it.year }

    fun isSelectableYear(year: Int, input: Boolean): Boolean =
        year in yearRange && (input || year in calendarYears)

    fun isSelectableDate(utcMillis: Long, input: Boolean): Boolean {
        val date = utcMillisToLocalDate(utcMillis)
        return date.year in yearRange && !date.isAfter(today) && (input || date in calendarDates)
    }

    fun parseInput(text: String): Long? = parseIsoDate(text.trim())
        ?.takeIf { it.year in yearRange && !it.isAfter(today) }
        ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

    fun selectionDate(utcMillis: Long?, input: Boolean): String? = utcMillis
        ?.takeIf { isSelectableDate(it, input) }?.let(::utcMillisToDate)
}

/**
 * Intercept Material3's pencil/calendar switch synchronously. An input-only date must never remain
 * selected or confirmable in calendar mode, including a selection restored by rememberSaveable.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal class ScreenshotDatePickerState(
    private val delegate: DatePickerState,
    private val policy: ScreenshotDatePolicy,
) : DatePickerState by delegate {
    private val calendarDates = selectableDates(input = false)
    private val inputDates = selectableDates(input = true)

    override val selectableDates: SelectableDates
        get() = if (displayMode == DisplayMode.Input) inputDates else calendarDates

    override var selectedDateMillis: Long?
        get() = delegate.selectedDateMillis?.takeIf {
            policy.isSelectableDate(it, displayMode == DisplayMode.Input)
        }
        set(value) {
            delegate.selectedDateMillis = value?.takeIf {
                policy.isSelectableDate(it, displayMode == DisplayMode.Input)
            }
        }

    override var displayMode: DisplayMode
        get() = delegate.displayMode
        set(value) {
            val previousSelection = selectedDateMillis
            delegate.displayMode = value
            selectedDateMillis = previousSelection
        }

    private fun selectableDates(input: Boolean) = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean =
            policy.isSelectableDate(utcTimeMillis, input)

        override fun isSelectableYear(year: Int): Boolean = policy.isSelectableYear(year, input)
    }
}

private fun parseIsoDate(text: String): LocalDate? {
    if (!text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return null
    return runCatching { LocalDate.parse(text) }.getOrNull()
}

private fun utcMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

internal fun utcMillisToDate(millis: Long): String = utcMillisToLocalDate(millis).toString()
