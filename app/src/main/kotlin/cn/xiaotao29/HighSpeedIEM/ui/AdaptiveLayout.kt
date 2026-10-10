package cn.xiaotao29.HighSpeedIEM.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal enum class AppLayoutMode { Portrait, Landscape, Compact }

/** Window pixels, rather than device orientation, also cover resized/multi-window apps. */
internal fun appLayoutMode(widthPx: Int, heightPx: Int, uiMode: Int): AppLayoutMode = when {
    uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_WATCH ||
        widthPx in 1..600 || heightPx in 1..600 -> AppLayoutMode.Compact
    widthPx > heightPx -> AppLayoutMode.Landscape
    else -> AppLayoutMode.Portrait
}

/** A restored page may have a different device list than when it was saved. */
internal fun restoreChannelHeights(savedKeys: List<*>?, savedHeights: IntArray?, currentKeys: List<Any>): IntArray? =
    savedHeights?.takeIf { savedKeys == currentKeys && it.size == currentKeys.size + 1 }

/** Native single-column lists and adaptive grids share cards and a saved logical scroll anchor. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun <T> ChannelList(
    items: List<T>,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    tag: String,
    padding: PaddingValues = PaddingValues(16.dp),
    itemKey: (T) -> Any,
    animateItems: Boolean = false,
    eagerPortrait: Boolean = false,
    heading: @Composable () -> Unit,
    card: @Composable (Int, T, Modifier) -> Unit,
) {
    // Preserve the same card controls at large font sizes by reducing column count.
    val minimumWidth = 320.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val portraitScroll = rememberScrollState()
    val itemKeys = items.map(itemKey)
    // rememberSaveable inputs invalidate live state, but do not validate a
    // restored SaveableStateHolder value. Device removal while this page is
    // hidden used to restore a shorter array on return and crash measurement.
    val heights = rememberSaveable(itemKeys, saver = Saver<IntArray, Any>(
        save = { listOf(itemKeys, it) },
        restore = { saved ->
            val state = saved as? List<*>
            restoreChannelHeights(state?.getOrNull(0) as? List<*>, state?.getOrNull(1) as? IntArray, itemKeys)
        },
    )) { IntArray(items.size + 1) }
    var previousLandscape by rememberSaveable { mutableStateOf(landscape) }
    LaunchedEffect(landscape, itemKeys) {
        if (previousLandscape != landscape) {
            if (eagerPortrait) {
                // The device cards contain nested focus targets. Keeping the portrait
                // Column preserves their established keyboard traversal.
                if (landscape) {
                    var offset = portraitScroll.value
                    var index = 0
                    while (index < heights.lastIndex && heights[index] > 0 && offset >= heights[index]) {
                        offset -= heights[index++]
                    }
                    gridState.scrollToItem(index, offset)
                } else {
                    // Wait until the restored portrait has measured its current card heights.
                    withFrameNanos { }
                    withFrameNanos { }
                    portraitScroll.scrollTo(heights.take(gridState.firstVisibleItemIndex).sum() +
                        gridState.firstVisibleItemScrollOffset)
                }
            } else if (landscape) gridState.scrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            else listState.scrollToItem(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            previousLandscape = landscape
        }
    }
    if (landscape) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minimumWidth),
            state = gridState,
            modifier = modifier.passiveScroll().testTag(tag),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "heading", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") { heading() }
            gridItemsIndexed(items, key = { _, item -> itemKey(item) }, contentType = { _, _ -> "card" }) { index, item ->
                card(index, item, if (animateItems) Modifier.animateItem(
                    fadeInSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec()) else Modifier)
            }
        }
    } else if (eagerPortrait) {
        Column(modifier.passiveScroll().verticalScroll(portraitScroll).padding(padding).testTag(tag)) {
            Box(Modifier.onSizeChanged { heights[0] = it.height }) { heading() }
            items.forEachIndexed { index, item ->
                key(itemKey(item)) {
                    Box(Modifier.onSizeChanged {
                        // A removed node can still deliver a final measurement.
                        if (index + 1 in heights.indices) heights[index + 1] = it.height
                    }) { card(index, item, Modifier) }
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.passiveScroll().testTag(tag),
            contentPadding = padding,
        ) {
            item(key = "heading", contentType = "heading") { heading() }
            itemsIndexed(items, key = { _, item -> itemKey(item) }, contentType = { _, _ -> "card" }) { index, item ->
                card(index, item, if (animateItems) Modifier.animateItem(
                    fadeInSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec()) else Modifier)
            }
        }
    }
}
