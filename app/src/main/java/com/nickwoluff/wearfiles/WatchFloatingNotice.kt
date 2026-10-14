package com.nickwoluff.wearfiles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedAlignment
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.angularSize
import androidx.wear.compose.foundation.background
import androidx.wear.compose.foundation.clearAndSetSemantics
import androidx.wear.compose.foundation.curvedBox
import androidx.wear.compose.foundation.radialSize
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

private val noticeEasing = Easing { ((cos((it + 1) * PI) / 2) + 0.5).toFloat() }
private val noticeBackground = Color(0xFF333333)
private val noticeBorder = Color(0xFF666666)
private const val noticeBackgroundAlpha = 179f / 255f

private data class NoticeBoundary(val index: Int, val advance: Float)

/** 只遮罩官方文字层，保留提示背景和底下页面的原有透明度。 */
private fun Modifier.noticeEdgeFade(brush: Brush?): Modifier = if (brush == null) this else
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(brush, blendMode = BlendMode.DstIn)
    }

/** 官方组件绘制背景和文字；长文本通过裁剪窗口和移动锚点连续滚动。 */
@Composable
internal fun WatchFloatingNotice(notice: WatchNotice, onFinished: () -> Unit) {
    val onFinishedNow by rememberUpdatedState(onFinished)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onFinishedNow()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val round = LocalConfiguration.current.isScreenRound
    val density = LocalDensity.current
    val accessibility = LocalAccessibilityManager.current
    val textMeasurer = rememberTextMeasurer()
    val textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
    val measured = textMeasurer.measure(notice.message, textStyle, softWrap = false)
    val messageWidth = measured.size.width.toFloat()
    // 只记录字素边界和宽度，不自行绘制字形。保留代理对和组合字符。
    val boundaries = remember(notice.message, textMeasurer, textStyle) {
        val iterator = BreakIterator.getCharacterInstance().apply { setText(notice.message) }
        buildList {
            add(NoticeBoundary(0, 0f))
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                add(NoticeBoundary(end, textMeasurer.measure(notice.message.substring(0, end),
                    textStyle, softWrap = false).size.width.toFloat()))
                end = iterator.next()
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().semantics(mergeDescendants = true) {
        liveRegion = LiveRegionMode.Polite
        contentDescription = notice.message
    }, contentAlignment = Alignment.Center) {
        // 参考尺寸：28dp 填充 + 两侧 1.5dp 灰边，距屏边 10dp。
        val radius = with(density) {
            (min(constraints.maxWidth, constraints.maxHeight) / 2f - 25.5.dp.toPx()).coerceAtLeast(1f)
        }
        val padding = with(density) { 16.dp.toPx() }
        val fadeMargin = with(density) { 18.dp.toPx() }
        val maximumLength = if (round) radius * (PI * 120 / 180).toFloat()
            else with(density) { (maxWidth - 51.dp).toPx() }.coerceAtLeast(1f)
        val pathLength = (messageWidth + padding).coerceAtMost(maximumLength)
        val sweep = Math.toDegrees((pathLength / radius).toDouble()).toFloat().coerceIn(15f, 120f)
        // 官方逆时针文字的基线半径大于背景中心线半径，按实际字体测量换算锚点。
        val textRadius = radius + measured.firstBaseline - measured.size.height / 2f
        val viewportLength = if (round) textRadius * Math.toRadians(sweep.toDouble()).toFloat() else pathLength
        val scrolling = messageWidth + padding > viewportLength
        val initialOffset = padding / 2 + fadeMargin
        val finalOffset = viewportLength - messageWidth - padding / 2 - fadeMargin
        val scrollMillis = with(density) {
            ((initialOffset - finalOffset) / 82.dp.toPx() * 1000).roundToInt().coerceAtLeast(1200)
        }
        val expansion = remember { Animatable(0f) }
        val textAlpha = remember { Animatable(0f) }
        val opacity = remember { Animatable(1f) }
        val offset = remember { Animatable(initialOffset) }
        LaunchedEffect(notice.id, scrolling, viewportLength) {
            expansion.snapTo(0f)
            textAlpha.snapTo(0f)
            opacity.snapTo(1f)
            offset.snapTo(initialOffset)
            coroutineScope {
                launch { expansion.animateTo(1f, tween(250, easing = noticeEasing)) }
                launch { delay(50); textAlpha.animateTo(1f, tween(150, easing = noticeEasing)) }
            }
            if (scrolling) {
                delay(350)
                offset.animateTo(finalOffset, tween(scrollMillis, easing = LinearEasing))
                delay(450)
            } else delay(1000)
            val duration = if (scrolling) 250L + 350 + scrollMillis + 450 else 1250L
            val recommended = accessibility?.calculateRecommendedTimeoutMillis(duration,
                containsIcons = false, containsText = true, containsControls = false) ?: duration
            delay((recommended - duration).coerceAtLeast(0L))
            opacity.animateTo(0f, tween(220, easing = noticeEasing))
            onFinishedNow()
        }
        val borderColor = noticeBorder.copy(alpha = noticeBackgroundAlpha * opacity.value)
        val backgroundColor = noticeBackground.copy(alpha = noticeBackgroundAlpha * opacity.value)
        val textColor = Color.White.copy(alpha = textAlpha.value * opacity.value)
        if (round) {
            val shownSweep = (sweep * expansion.value).coerceAtLeast(0.01f)
            CurvedLayout(Modifier.fillMaxSize().padding(10.dp), anchor = 90f,
                angularDirection = CurvedDirection.Angular.CounterClockwise) {
                curvedBox(CurvedModifier.angularSize(shownSweep).radialSize(31.dp)
                    .background(borderColor, StrokeCap.Round),
                    radialAlignment = CurvedAlignment.Radial.Center) {
                    curvedBox(CurvedModifier.angularSize(shownSweep).radialSize(28.dp)
                        .background(backgroundColor, StrokeCap.Round)) {}
                }
            }
            // 这里仅定义裁剪范围；背景、字体和沿弧排版全部由官方组件渲染。
            val viewport = remember(sweep) {
                GenericShape { size, _ ->
                    val outer = min(size.width, size.height) / 2
                    val cx = size.width / 2
                    val cy = size.height / 2
                    moveTo(cx, cy)
                    arcTo(Rect(cx - outer, cy - outer, cx + outer, cy + outer),
                        90f - sweep / 2, sweep, false)
                    close()
                }
            }
            val currentOffset = if (scrolling) offset.value else (viewportLength - messageWidth) / 2
            // 只向官方文字组件提交视口附近的连续子串，避免超长文字绕圆多圈重叠。
            val overscan = with(density) { 20.dp.toPx() }
            val first = if (scrolling) (boundaries.indexOfFirst { it.advance + currentOffset >= -overscan } - 1)
                .coerceIn(0, (boundaries.lastIndex - 1).coerceAtLeast(0)) else 0
            val last = if (scrolling) boundaries.indexOfFirst { it.advance + currentOffset > viewportLength + overscan }
                .let { if (it < 0) boundaries.lastIndex else it.coerceAtLeast(first + 1) } else boundaries.lastIndex
            val visibleText = notice.message.substring(boundaries[first].index, boundaries[last].index)
            val anchor = 90f + sweep / 2 - Math.toDegrees(
                ((currentOffset + boundaries[first].advance) / textRadius).toDouble()).toFloat()
            val edgeFade = remember(scrolling, sweep, textRadius, fadeMargin) {
                if (!scrolling) null else {
                    val start = (90f - sweep / 2) / 360f
                    val end = (90f + sweep / 2) / 360f
                    val fade = Math.toDegrees((fadeMargin / textRadius).toDouble()).toFloat()
                        .coerceAtMost(sweep / 2) / 360f
                    Brush.sweepGradient(
                        0f to Color.Transparent, start to Color.Transparent,
                        (start + fade) to Color.White, (end - fade) to Color.White,
                        end to Color.Transparent, 1f to Color.Transparent)
                }
            }
            Box(Modifier.fillMaxSize().noticeEdgeFade(edgeFade).clip(viewport),
                contentAlignment = Alignment.Center) {
                CurvedLayout(Modifier.fillMaxSize().padding(10.dp), anchor = anchor,
                    anchorType = AnchorType.Start, angularDirection = CurvedDirection.Angular.CounterClockwise) {
                    curvedBox(CurvedModifier.radialSize(31.dp), radialAlignment = CurvedAlignment.Radial.Center) {
                        curvedText(visibleText, modifier = CurvedModifier.clearAndSetSemantics {},
                            maxSweepAngle = 360f, background = Color.Transparent, color = textColor,
                            style = CurvedTextStyle(textStyle).copy(
                                warpOffset = CurvedTextStyle.WarpOffset.None), overflow = TextOverflow.Visible)
                    }
                }
            }
        } else {
            val lineWidth = with(density) { pathLength.toDp() }
            val edgeFade = remember(scrolling, pathLength, padding, fadeMargin) {
                if (!scrolling) null else {
                    val fade = (fadeMargin / (pathLength - padding).coerceAtLeast(1f)).coerceAtMost(0.5f)
                    Brush.horizontalGradient(0f to Color.Transparent, fade to Color.White,
                        (1f - fade) to Color.White, 1f to Color.Transparent)
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                .width(lineWidth * expansion.value + 31.dp).height(31.dp)
                .clip(RoundedCornerShape(50)).background(borderColor).padding(1.5.dp),
                contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(backgroundColor)
                    .padding(horizontal = 22.dp).noticeEdgeFade(edgeFade).clipToBounds(),
                    contentAlignment = Alignment.CenterStart) {
                    val textOffset = if (scrolling) offset.value - padding / 2 else 0f
                    Text(notice.message, style = textStyle, color = textColor, maxLines = 1,
                        softWrap = false, overflow = TextOverflow.Visible,
                        modifier = Modifier.offset { IntOffset(textOffset.roundToInt(), 0) }
                            .wrapContentWidth(Alignment.Start, unbounded = true).clearAndSetSemantics {})
                }
            }
        }
    }
}
