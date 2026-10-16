package dev.listder.fucksrun.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.listder.fucksrun.protocol.LatLon
import dev.listder.fucksrun.protocol.TrackPlanner
import dev.listder.fucksrun.protocol.TrackSegment
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A north-up, fit-to-content offline preview. Eight-digit segment colors are #RRGGBBAA.
 * Widths are logical screen pixels (dp); geometry uses a uniform, latitude-corrected scale.
 * The displayed distance is measured from the supplied points, including chunk boundaries.
 */
@Composable
fun TrackPreview(segments: List<TrackSegment>, modifier: Modifier = Modifier) {
    val geometry = remember(segments) { previewGeometry(segments) }
    val background = MiuixTheme.colorScheme.background
    val startColor = if (background.luminance() > 0.5f) Color(0xFF167A3E) else Color(0xFF70E59A)
    val endColor = MiuixTheme.colorScheme.error

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp)).background(background),
            contentAlignment = Alignment.Center,
        ) {
            if (geometry != null) {
                Canvas(Modifier.matchParentSize().semantics {
                    contentDescription = "轨迹预览"
                }) {
                    val padding = 24.dp.toPx() + geometry.maxWidth.dp.toPx() / 2
                    val availableWidth = size.width - padding * 2
                    val availableHeight = size.height - padding * 2
                    if (availableWidth <= 0 || availableHeight <= 0) return@Canvas
                    val scale = min(
                        availableWidth / max(geometry.bounds.width, 1f),
                        availableHeight / max(geometry.bounds.height, 1f),
                    )
                    val center = geometry.bounds.center
                    val translation = Offset(size.width / 2 - center.x * scale, size.height / 2 - center.y * scale)
                    withTransform({
                        translate(translation.x, translation.y)
                        scale(scale, scale, pivot = Offset.Zero)
                    }) {
                        for (segment in geometry.segments) {
                            val width = segment.width.dp.toPx() / scale
                            drawPath(segment.path, segment.color,
                                style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            segment.arrow?.let { arrow ->
                                val length = 9.dp.toPx() / scale
                                val halfWidth = 4.dp.toPx() / scale
                                val back = arrow.point - arrow.direction * length
                                val normal = Offset(-arrow.direction.y, arrow.direction.x) * halfWidth
                                drawLine(segment.color, back + normal, arrow.point,
                                    strokeWidth = width, cap = StrokeCap.Round)
                                drawLine(segment.color, back - normal, arrow.point,
                                    strokeWidth = width, cap = StrokeCap.Round)
                            }
                        }
                    }
                    val start = geometry.start * scale + translation
                    val end = geometry.end * scale + translation
                    drawCircle(background, 10.dp.toPx(), start)
                    drawCircle(startColor, 8.dp.toPx(), start)
                    drawCircle(background, 5.dp.toPx(), start)
                    drawCircle(background, 6.dp.toPx(), end)
                    drawCircle(endColor, 4.dp.toPx(), end)
                }
            }
        }
    }
}

private data class PreviewArrow(val point: Offset, val direction: Offset)
private data class PreviewSegment(val path: Path, val color: Color, val width: Int, val arrow: PreviewArrow?)
private data class PreviewGeometry(
    val segments: List<PreviewSegment>,
    val bounds: Rect,
    val start: Offset,
    val end: Offset,
    val distanceMeters: Double,
    val pointCount: Int,
    val maxWidth: Int,
)

private fun previewGeometry(segments: List<TrackSegment>): PreviewGeometry? {
    var origin: LatLon? = null
    var minLatitude = Double.POSITIVE_INFINITY
    var maxLatitude = Double.NEGATIVE_INFINITY
    for (segment in segments) for (point in segment.points) {
        if (!point.isValid()) continue
        if (origin == null) origin = point
        minLatitude = min(minLatitude, point.latitude)
        maxLatitude = max(maxLatitude, point.latitude)
    }
    val reference = origin ?: return null
    val longitudeScale = 111320 * cos((minLatitude + maxLatitude) / 2 * PI / 180)
    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var start: Offset? = null
    var end = Offset.Zero
    var previous: LatLon? = null
    var distanceMeters = 0.0
    var pointCount = 0
    var maxWidth = 0
    val paths = ArrayList<PreviewSegment>(segments.size)
    for (segment in segments) {
        if (segment.points.isEmpty()) continue
        val path = Path()
        var connected = false
        for (point in segment.points) {
            if (!point.isValid()) {
                connected = false
                previous = null
                continue
            }
            val position = Offset(
                ((point.longitude - reference.longitude) * longitudeScale).toFloat(),
                (-(point.latitude - reference.latitude) * 111320).toFloat(),
            )
            if (connected) path.lineTo(position.x, position.y) else path.moveTo(position.x, position.y)
            connected = true
            previous?.let { distanceMeters += TrackPlanner.distance(it, point) }
            previous = point
            if (start == null) start = position
            end = position
            minX = min(minX, position.x)
            maxX = max(maxX, position.x)
            minY = min(minY, position.y)
            maxY = max(maxY, position.y)
            pointCount++
        }
        val arrow = if (segment.arrowLine) {
            val measure = PathMeasure().apply { setPath(path, false) }
            if (measure.length > 0) {
                val midpoint = measure.length / 2
                PreviewArrow(measure.getPosition(midpoint), measure.getTangent(midpoint))
            } else null
        } else null
        paths += PreviewSegment(path, segmentColor(segment.color), segment.width, arrow)
        maxWidth = max(maxWidth, segment.width)
    }
    return PreviewGeometry(paths, Rect(minX, minY, maxX, maxY), start ?: return null, end,
        distanceMeters, pointCount, maxWidth)
}

/** The original drawing API puts alpha last; Android's parseColor puts it first. */
private fun segmentColor(value: String): Color {
    require(value.startsWith('#') && (value.length == 7 || value.length == 9)) {
        "轨迹颜色必须是 #RRGGBB 或 #RRGGBBAA"
    }
    val rgba = value.substring(1).toLong(16)
    val argb = if (value.length == 9) {
        (rgba ushr 8).toInt() or ((rgba and 0xff).toInt() shl 24)
    } else {
        rgba.toInt() or (0xff shl 24)
    }
    return Color(argb)
}
