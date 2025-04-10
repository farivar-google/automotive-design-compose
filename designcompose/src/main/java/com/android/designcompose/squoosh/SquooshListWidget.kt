/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.designcompose.squoosh

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.designcompose.CustomizationContext
import com.android.designcompose.GetDesignNodeData
import com.android.designcompose.LayoutInfoColumn
import com.android.designcompose.LayoutInfoGrid
import com.android.designcompose.LayoutInfoRow
import com.android.designcompose.LazyContentSpan
import com.android.designcompose.ListContent
import com.android.designcompose.SpanCache
import com.android.designcompose.calcLayoutInfo
import com.android.designcompose.common.NodeQuery
import com.android.designcompose.definition.layout.GridLayoutType
import com.android.designcompose.definition.layout.GridSpan
import com.android.designcompose.definition.view.ViewStyle
import com.android.designcompose.getCustomComposable
import com.android.designcompose.getScrollCallbacks
import com.android.designcompose.itemSpacingAbs
import com.android.designcompose.utils.pointsAsDp

// For a list widget whose layout is set to horizontal or vertical, add the widget's children into
// composableList so that they can be composed separately.
private fun addRowColumnContent(
    listWidgetContent: ListContent,
    resolvedView: SquooshResolvedNode,
    style: ViewStyle,
    customizations: CustomizationContext,
    layoutIdAllocator: SquooshLayoutIdAllocator,
    parentComps: ParentComponentData?,
    composableList: ComposableList?,
) {
    val content = listWidgetContent { LazyContentSpan() }
    var count = content.count

    var overflowNodeId: String? = null
    if (style.nodeStyle.hasMaxChildren() && style.nodeStyle.maxChildren < count) {
        count = style.nodeStyle.maxChildren
        if (style.nodeStyle.hasOverflowNodeId()) overflowNodeId = style.nodeStyle.overflowNodeId
    }

    var previousReplacementChild: SquooshResolvedNode? = null
    for (idx in 0..<count) {
        val childComponent =
            @Composable {
                if (overflowNodeId != null && idx == count - 1) {
                    // This is the last item we can show and there are more, and there
                    // is an
                    // overflow node, so show the overflow node here
                    val customComposable = customizations.getCustomComposable()
                    if (customComposable != null) {
                        customComposable(
                            Modifier,
                            style.nodeStyle.overflowNodeName,
                            NodeQuery.NodeId(style.nodeStyle.overflowNodeId),
                            listOf(), // parentComponents,
                            null,
                        )
                    }
                } else {
                    content.itemContent(idx)
                }
            }
        val replacementChild =
            generateReplacementListChildNode(resolvedView, idx, layoutIdAllocator)
        if (previousReplacementChild != null)
            previousReplacementChild.nextSibling = replacementChild
        else resolvedView.firstChild = replacementChild
        previousReplacementChild = replacementChild

        composableList?.addChild(
            SquooshChildComposable(
                component = @Composable { childComponent() },
                node = replacementChild,
                parentComponents = parentComps,
            )
        )
    }
}

// Given the list of possible content that goes into this grid layout, try to find a matching
// item based on node name and variant properties, and return its span
private fun getSpan(
    gridSpanContent: List<GridSpan>,
    getDesignNodeData: GetDesignNodeData,
): LazyContentSpan {
    val nodeData = getDesignNodeData()
    val cachedSpan = SpanCache.getSpan(nodeData)
    if (cachedSpan != null) return cachedSpan

    gridSpanContent.forEach { item ->
        // If not looking for a variant, just find a node name match
        if (nodeData.variantProperties.isEmpty()) {
            if (nodeData.nodeName == item.nodeName)
                return LazyContentSpan(span = item.span, maxLineSpan = item.maxSpan)
        } else {
            var spanFound: LazyContentSpan? = null
            var matchesLeft = nodeData.variantProperties.size
            item.nodeVariantMap.forEach {
                val property = it.key.trim()
                val value = it.value.trim()
                val variantPropertyValue = nodeData.variantProperties[property]
                if (value == variantPropertyValue) {
                    // We have a match. Decrement the number of matches left we are
                    // looking for
                    --matchesLeft
                    // If we matched everything, we have a possible match. If the number of
                    // properties and values in propertyValueList is the same as the number of
                    // variant properties then we are done. Otherwise, this is a possible match, and
                    // save it in spanFound. If we don't have any exact matches, return spanFound
                    if (matchesLeft == 0) {
                        if (nodeData.variantProperties.size == item.nodeVariantCount) {
                            val span =
                                if (item.maxSpan) LazyContentSpan(maxLineSpan = true)
                                else LazyContentSpan(span = item.span)
                            SpanCache.setSpan(nodeData, span)
                            return span
                        } else
                            spanFound =
                                LazyContentSpan(span = item.span, maxLineSpan = item.maxSpan)
                    }
                }
            }
            if (spanFound != null) {
                SpanCache.setSpan(nodeData, spanFound!!)
                return spanFound!!
            }
        }
    }
    SpanCache.setSpan(nodeData, LazyContentSpan(span = 1))
    return LazyContentSpan(span = 1)
}

// Given the frame size, number of columns/rows, and spacing between them, return a list of
// column/row widths/heights
private fun calculateCellsCrossAxisSizeImpl(
    gridSize: Int,
    slotCount: Int,
    spacing: Int,
): List<Int> {
    val gridSizeWithoutSpacing = gridSize - spacing * (slotCount - 1)
    val slotSize = gridSizeWithoutSpacing / slotCount
    val remainingPixels = gridSizeWithoutSpacing % slotCount
    return List(slotCount) { slotSize + if (it < remainingPixels) 1 else 0 }
}

// Given the grid layout type and main axis size, return the number of columns/rows
private fun calculateColumnRowCount(layoutInfo: LayoutInfoGrid, gridMainAxisSize: Int): Int {
    val count =
        if (
            layoutInfo.layout == GridLayoutType.GRID_LAYOUT_TYPE_FIXED_COLUMNS ||
                layoutInfo.layout == GridLayoutType.GRID_LAYOUT_TYPE_FIXED_ROWS
        ) {
            layoutInfo.numColumnsRows
        } else {
            gridMainAxisSize /
                (layoutInfo.minColumnRowSize + itemSpacingAbs(layoutInfo.mainAxisSpacing))
        }
    return if (count > 0) count else 1
}

// For a list widget whose layout is set to grid, add a LazyVerticalGrid or LazyHorizontalGrid into
// the composableList. Since this composable performs layout on its own, take the layout properties
// specified in the widget data and pass it to the grid view.
private fun addGridContent(
    layoutInfo: LayoutInfoGrid,
    listWidgetContent: ListContent,
    resolvedView: SquooshResolvedNode,
    style: ViewStyle,
    customizations: CustomizationContext,
    parentComps: ParentComponentData?,
    composableList: ComposableList?,
) {
    val gridComposable =
        @Composable {
            val (gridMainAxisSize, setGridMainAxisSize) = remember { mutableStateOf(0) }

            // Content for the lazy content parameter. This uses the grid layout but also supports
            // limiting the number of children to style.max_children, and using an overflow node if
            // one is specified.
            val lazyItemContent: LazyGridScope.() -> Unit = {
                val lContent = listWidgetContent { nodeData ->
                    getSpan(layoutInfo.gridSpanContent, nodeData)
                }

                // If the main axis size has not yet been set, and spacing is set to auto, show the
                // initial content composable. This avoids rendering the content in one position
                // for the first frame and then in another on the second frame after the main axis
                // size has been set.
                val showInitContent =
                    (gridMainAxisSize <= 0 && layoutInfo.mainAxisSpacing.hasAuto())

                if (showInitContent)
                    items(
                        count = 1,
                        span = {
                            val span = lContent.initialSpan?.invoke() ?: LazyContentSpan()
                            GridItemSpan(if (span.maxLineSpan) maxLineSpan else span.span)
                        },
                    ) {
                        lContent.initialContent()
                    }
                else {
                    var count = lContent.count
                    var overflowNodeId: String? = null
                    if (style.nodeStyle.hasMaxChildren() && style.nodeStyle.maxChildren < count) {
                        count = style.nodeStyle.maxChildren
                        if (style.nodeStyle.hasOverflowNodeId())
                            overflowNodeId = style.nodeStyle.overflowNodeId
                    }
                    items(
                        count,
                        key = { index ->
                            if (overflowNodeId != null && index == count - 1)
                            // Overflow node key
                            "overflow"
                            else lContent.key?.invoke(index) ?: index
                        },
                        span = { index ->
                            if (overflowNodeId != null && index == count - 1)
                            // Overflow node always spans 1 column/row for now
                            GridItemSpan(1)
                            else {
                                val span = lContent.span?.invoke(index) ?: LazyContentSpan()
                                GridItemSpan(if (span.maxLineSpan) maxLineSpan else span.span)
                            }
                        },
                        contentType = { index ->
                            if (overflowNodeId != null && index == count - 1)
                            // Overflow node content type
                            "overflow"
                            else lContent.contentType.invoke(index)
                        },
                        itemContent = { index ->
                            if (overflowNodeId != null && index == count - 1) {
                                // This is the last item we can show and there are more, and there
                                // is an
                                // overflow node, so show the overflow node here
                                val customComposable = customizations.getCustomComposable()
                                if (customComposable != null) {
                                    customComposable(
                                        Modifier,
                                        style.nodeStyle.overflowNodeName,
                                        NodeQuery.NodeId(style.nodeStyle.overflowNodeId),
                                        listOf(), // parentComps,
                                        null,
                                    )
                                }
                            } else {
                                lContent.itemContent(index)
                            }
                        },
                    )
                }
            }

            val density = LocalDensity.current.density
            val lazyGridState = rememberLazyGridState()
            val setScrollableStateCallback =
                customizations.getScrollCallbacks(resolvedView.view.name)?.setScrollableState
            LaunchedEffect(lazyGridState, setScrollableStateCallback) {
                setScrollableStateCallback?.invoke(lazyGridState)
            }

            if (
                layoutInfo.layout == GridLayoutType.GRID_LAYOUT_TYPE_FIXED_COLUMNS ||
                    layoutInfo.layout == GridLayoutType.GRID_LAYOUT_TYPE_AUTO_COLUMNS
            ) {
                val columnCount = calculateColumnRowCount(layoutInfo, gridMainAxisSize)
                val horizontalSpacing =
                    if (layoutInfo.mainAxisSpacing.hasFixed()) layoutInfo.mainAxisSpacing.fixed
                    else 0
                val verticalSpacing = layoutInfo.crossAxisSpacing
                LazyVerticalGrid(
                    state = lazyGridState,
                    columns =
                        object : GridCells {
                            override fun Density.calculateCrossAxisCellSizes(
                                availableSize: Int,
                                spacing: Int,
                            ): List<Int> {
                                val mainAxisSize = (availableSize.toFloat() / density).toInt()
                                setGridMainAxisSize(mainAxisSize)
                                return calculateCellsCrossAxisSizeImpl(
                                    availableSize,
                                    columnCount,
                                    spacing,
                                )
                            }
                        },
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            if (layoutInfo.mainAxisSpacing.hasFixed())
                                layoutInfo.mainAxisSpacing.fixed.dp
                            else if (layoutInfo.mainAxisSpacing.hasAuto()) {
                                if (columnCount > 1)
                                    ((gridMainAxisSize -
                                            (layoutInfo.mainAxisSpacing.auto.height *
                                                columnCount)) / (columnCount - 1))
                                        .dp
                                else layoutInfo.mainAxisSpacing.auto.width.dp
                            } else horizontalSpacing.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(verticalSpacing.dp),
                    userScrollEnabled = layoutInfo.scrollingEnabled,
                    contentPadding =
                        PaddingValues(
                            layoutInfo.padding.start.pointsAsDp(density),
                            layoutInfo.padding.top.pointsAsDp(density),
                            layoutInfo.padding.end.pointsAsDp(density),
                            layoutInfo.padding.bottom.pointsAsDp(density),
                        ),
                ) {
                    lazyItemContent()
                }
            } else {
                val rowCount = calculateColumnRowCount(layoutInfo, gridMainAxisSize)
                val horizontalSpacing = layoutInfo.crossAxisSpacing
                val verticalSpacing =
                    if (layoutInfo.mainAxisSpacing.hasFixed()) layoutInfo.mainAxisSpacing.fixed
                    else 0

                LazyHorizontalGrid(
                    state = lazyGridState,
                    rows =
                        object : GridCells {
                            override fun Density.calculateCrossAxisCellSizes(
                                availableSize: Int,
                                spacing: Int,
                            ): List<Int> {
                                val mainAxisSize = (availableSize.toFloat() / density).toInt()
                                setGridMainAxisSize(mainAxisSize)
                                return calculateCellsCrossAxisSizeImpl(
                                    availableSize,
                                    rowCount,
                                    spacing,
                                )
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(horizontalSpacing.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            if (layoutInfo.mainAxisSpacing.hasFixed())
                                layoutInfo.mainAxisSpacing.fixed.dp
                            else if (layoutInfo.mainAxisSpacing.hasAuto()) {
                                if (rowCount > 1)
                                    ((gridMainAxisSize -
                                            (layoutInfo.mainAxisSpacing.auto.height * rowCount)) /
                                            (rowCount - 1))
                                        .dp
                                else layoutInfo.mainAxisSpacing.auto.width.dp
                            } else verticalSpacing.dp
                        ),
                    userScrollEnabled = layoutInfo.scrollingEnabled,
                ) {
                    lazyItemContent()
                }
            }
        }
    composableList?.addChild(
        SquooshChildComposable(
            component = { ctx -> gridComposable() },
            node = resolvedView,
            parentComponents = parentComps,
        )
    )
    resolvedView.needsChildRender = true
}

internal fun addListWidget(
    listWidgetContent: ListContent,
    resolvedView: SquooshResolvedNode,
    style: ViewStyle,
    customizations: CustomizationContext,
    layoutIdAllocator: SquooshLayoutIdAllocator,
    parentComps: ParentComponentData?,
    composableList: ComposableList?,
) {
    val layoutInfo = calcLayoutInfo(resolvedView.view, resolvedView.style)
    when (layoutInfo) {
        is LayoutInfoRow -> {
            addRowColumnContent(
                listWidgetContent,
                resolvedView,
                style,
                customizations,
                layoutIdAllocator,
                parentComps,
                composableList,
            )
        }
        is LayoutInfoColumn -> {
            addRowColumnContent(
                listWidgetContent,
                resolvedView,
                style,
                customizations,
                layoutIdAllocator,
                parentComps,
                composableList,
            )
        }
        is LayoutInfoGrid -> {
            addGridContent(
                layoutInfo,
                listWidgetContent,
                resolvedView,
                style,
                customizations,
                parentComps,
                composableList,
            )
        }
        else -> {
            Log.e(TAG, "Invalid layout for node ${resolvedView.view.name}")
        }
    }
}
