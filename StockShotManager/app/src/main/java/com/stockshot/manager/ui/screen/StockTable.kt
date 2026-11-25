package com.stockshot.manager.ui.screen

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Preserve the original six columns' proportions before adding the remarks column. */
internal class StockTableColumns(viewportWidth: Dp, search: Boolean) {
    private val contentWidth = (viewportWidth - 24.dp).coerceAtLeast(0.dp)
    private val totalWeight = if (search) 1.02f else 1.04f
    private fun column(weight: Float) = contentWidth * (weight / totalWeight)

    val index = column(0.10f)
    val codeOrDate = column(if (search) 0.26f else 0.22f)
    val name = column(if (search) 0.22f else 0.24f)
    val buy = column(if (search) 0.14f else 0.15f)
    val sell = buy
    val profit = column(if (search) 0.16f else 0.18f)
    val remarks = buy + sell
    val frozen = index + codeOrDate + name
    val scrollingViewport = (contentWidth - frozen).coerceAtLeast(0.dp)
    val scrollingContent = buy + sell + profit + remarks
}

@Stable
internal class StockTableScrollState {
    var offset by mutableFloatStateOf(0f)
        private set
    var maxOffset by mutableFloatStateOf(0f)
        private set

    // Shared gesture state has no layout-owned bounds, unlike horizontalScroll's ScrollState.
    val gestures = ScrollableState { delta ->
        val previous = offset
        offset = (previous - delta).coerceIn(0f, maxOffset)
        previous - offset
    }

    fun updateBounds(max: Float) {
        maxOffset = max.coerceAtLeast(0f)
        offset = offset.coerceIn(0f, maxOffset)
    }
}

@Composable
internal fun StockTableContainer(
    modifier: Modifier = Modifier,
    search: Boolean = false,
    content: @Composable (StockTableColumns, StockTableScrollState) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val columns = remember(maxWidth, search) { StockTableColumns(maxWidth, search) }
        val scroll = remember { StockTableScrollState() }
        val maxOffset = with(LocalDensity.current) {
            (columns.scrollingContent.roundToPx() - columns.scrollingViewport.roundToPx()).toFloat()
        }
        SideEffect { scroll.updateBounds(maxOffset) }
        content(columns, scroll)
    }
}

/** One row measures both panes together, so wrapped cells raise the entire row. */
@Composable
internal fun StockTableRow(
    columns: StockTableColumns,
    scroll: StockTableScrollState,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 12.dp,
    frozen: @Composable RowScope.() -> Unit,
    scrolling: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.width(columns.frozen),
            verticalAlignment = Alignment.CenterVertically,
            content = frozen,
        )
        Layout(
            modifier = Modifier
                .width(columns.scrollingViewport)
                .clipToBounds()
                .scrollable(scroll.gestures, Orientation.Horizontal)
                .semantics {
                    horizontalScrollAxisRange = ScrollAxisRange(
                        value = { scroll.offset },
                        maxValue = { scroll.maxOffset },
                    )
                },
            content = {
                Row(verticalAlignment = Alignment.CenterVertically, content = scrolling)
            },
        ) { measurables, constraints ->
            val contentWidth = columns.scrollingContent.roundToPx()
            val row = measurables.single().measure(
                Constraints(
                    minWidth = contentWidth,
                    maxWidth = contentWidth,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                )
            )
            layout(constraints.maxWidth, row.height) {
                row.placeRelative(-scroll.offset.roundToInt(), 0)
            }
        }
    }
}
