package com.kyant.backdrop

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize

// 本仓适配（v1）：上游 1.0.0 使用 Kotlin context receiver（`context(node: DelegatableNode)`），
// Kotlin 2.0.21 K2 编译器不支持 —— 机械改写为显式参数，语义与上游完全一致。
internal fun DrawScope.recordLayer(
    node: DelegatableNode,
    layer: GraphicsLayer,
    size: IntSize = this.size.toIntSize(),
    block: DrawScope.() -> Unit
) {
    layer.record(
        density = node.requireDensity(),
        layoutDirection = layoutDirection,
        size = size
    ) {
        this@recordLayer.draw(
            density = drawContext.density,
            layoutDirection = drawContext.layoutDirection,
            canvas = drawContext.canvas,
            size = drawContext.size,
            graphicsLayer = drawContext.graphicsLayer,
            block = block
        )
    }
}
