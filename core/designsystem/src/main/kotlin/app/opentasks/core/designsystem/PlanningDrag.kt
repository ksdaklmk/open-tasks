package app.opentasks.core.designsystem

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

data class RootDragState<T>(
    val payload: T,
    val sourceBounds: Rect,
    val startInRoot: Offset,
    val accumulatedOffset: Offset = Offset.Zero,
) {
    val positionInRoot: Offset
        get() = startInRoot + accumulatedOffset

    fun movedBy(delta: Offset): RootDragState<T> = copy(
        accumulatedOffset = accumulatedOffset + delta,
    )
}

fun <T> dragTargetAt(
    positionInRoot: Offset,
    targets: Iterable<T>,
    bounds: Map<T, Rect>,
    eligible: (T) -> Boolean = { true },
): T? = targets.firstOrNull { target ->
    eligible(target) && bounds[target]?.contains(positionInRoot) == true
}

@Composable
fun Modifier.rootLongPressDragSource(
    key: Any?,
    enabled: Boolean = true,
    onStart: (Offset, Rect) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    var sourceBounds by remember(key) { mutableStateOf(Rect.Zero) }
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnCancel by rememberUpdatedState(onCancel)
    if (!enabled) return this

    return onGloballyPositioned {
        sourceBounds = Rect(
            offset = it.positionInRoot(),
            size = Size(it.size.width.toFloat(), it.size.height.toFloat()),
        )
    }.pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { currentOnStart(sourceBounds.topLeft + it, sourceBounds) },
            onDragEnd = { currentOnDrop() },
            onDragCancel = { currentOnCancel() },
            onDrag = { _, dragAmount -> currentOnDrag(dragAmount) },
        )
    }
}

@Composable
fun BoxScope.RootDragPreview(
    state: RootDragState<*>,
    containerBounds: Rect,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(AbsoluteAlignment.TopLeft)
            .absoluteOffset {
                IntOffset(
                    x = (state.sourceBounds.left - containerBounds.left +
                        state.accumulatedOffset.x).roundToInt(),
                    y = (state.sourceBounds.top - containerBounds.top +
                        state.accumulatedOffset.y).roundToInt(),
                )
            }
            .width(with(density) { state.sourceBounds.width.toDp() })
            .height(with(density) { state.sourceBounds.height.toDp() })
            .then(modifier),
        propagateMinConstraints = true,
    ) {
        content()
    }
}
