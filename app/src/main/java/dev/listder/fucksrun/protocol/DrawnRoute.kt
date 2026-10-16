package dev.listder.fucksrun.protocol

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.ceil
import kotlin.math.min

/** Saved AMap geometry uses [longitude, latitude]; runtime points use [LatLon]. */
class DrawnRoute private constructor(val path: List<LatLon>, val raw: Map<String, Any?>) {
    /** Exact path traversal, without adding GPS jitter or changing the saved geometry. */
    fun generate(distanceKm: Double): List<LatLon> {
        val meters = distanceKm * 1000
        require(meters.isFinite() && meters > 0) { "目标里程必须大于零" }
        val intervals = ceil(meters / POINT_SPACING_METERS)
        require(intervals < Int.MAX_VALUE - 1.0) { "轨迹点数超出可表示范围" }
        val cursor = Cursor(path)
        val points = ArrayList<LatLon>(intervals.toInt() + 1)
        points.add(cursor.position)
        var covered = 0.0
        while (covered < meters) {
            val step = min(POINT_SPACING_METERS, meters - covered)
            points.add(cursor.advance(step))
            covered += step
        }
        return points
    }

    companion object {
        private const val POINT_SPACING_METERS = 2.0
        fun parse(json: String): DrawnRoute {
            require(json.length < 1_500_000) { "线路数据过大" }
            val data = CanonicalJson.parseObject(json)
            val mode = data["mode"] ?: "manual"
            require(mode in listOf("manual", "road", "draw", "track")) { "线路模式错误" }
            for (key in listOf("closed", "fixed", "reverse")) {
                require(key !in data || data[key] is Boolean) { "线路 $key 格式错误" }
            }
            require(mode != "track" || data["closed"] == true) { "操场线路必须闭合" }
            val path = coordinates(data["path"], 10_000)
            require(path.size >= 2) { "请至少添加两个线路点" }
            require((1 until path.size).any { distance(path[it - 1], path[it]) > .01 }) { "线路没有有效路段" }
            val meters = length(path)
            require(meters in 5.0..100_000.0) { "线路长度必须在 5 米到 100 公里之间" }
            if ("anchors" in data) coordinates(data["anchors"], 32)
            data["startPoint"]?.let(::coordinate)
            data["jitterMeters"]?.let {
                val value = (it as? Number)?.toDouble()
                require(value != null && value.isFinite() && value in 0.0..16.0) { "位置偏移须在 0 到 16 米之间" }
            }
            require(mode != "track" || loopStartIndex(path) >= 0) { "操场线路必须包含闭合跑道" }
            return DrawnRoute(path, data)
        }

        private fun coordinate(value: Any?): LatLon {
            val pair = value as? List<*> ?: error("线路坐标格式错误")
            require(pair.size == 2) { "线路坐标格式错误" }
            val longitude = (pair[0] as? Number)?.toDouble() ?: error("线路经度格式错误")
            val latitude = (pair[1] as? Number)?.toDouble() ?: error("线路纬度格式错误")
            return LatLon(latitude, longitude).also { require(it.isValid()) { "线路含无效经纬度" } }
        }

        private fun coordinates(value: Any?, maximum: Int): List<LatLon> {
            val values = value as? List<*> ?: error("线路坐标列表格式错误")
            require(values.size <= maximum) { "线路点数量超过 $maximum" }
            return values.map(::coordinate)
        }

        private fun length(points: List<LatLon>): Double {
            var meters = 0.0
            for (i in 1 until points.size) meters += distance(points[i - 1], points[i])
            return meters
        }

        private fun loopStartIndex(points: List<LatLon>): Int {
            if (points.size < 3) return -1
            for (i in 0 until points.lastIndex) {
                if (distance(points[i], points.last()) < .01) return i
            }
            return -1
        }

        private fun distance(a: LatLon, b: LatLon): Double {
            val latitude = Math.toRadians(b.latitude - a.latitude)
            val longitude = Math.toRadians(b.longitude - a.longitude)
            val sinLatitude = sin(latitude / 2)
            val sinLongitude = sin(longitude / 2)
            val h = sinLatitude * sinLatitude + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sinLongitude * sinLongitude
            return 12_756_274.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }

    /** Port of example RouteGeometry.Cursor (MIT, Copyright (c) 2026 Inverx).
     * A one-time entry is consumed once, then its interior loop repeats. Open paths retrace.
     * The permission and warranty notice accompanies the copied route-editor.html asset.
     */
    private class Cursor(path: List<LatLon>) {
        private val points = ArrayList<LatLon>(path.size).apply {
            for (i in 1 until path.size) {
                if (distance(path[i - 1], path[i]) > .01) {
                    if (isEmpty()) add(path[i - 1])
                    add(path[i])
                }
            }
        }
        private var index = 0
        private var direction = 1
        private val loopStart = loopStartIndex(points)
        var position = points.firstOrNull() ?: error("线路没有有效路段")
            private set

        fun advance(meters: Double): LatLon {
            var remaining = meters
            while (remaining > 1e-7) {
                var next = index + direction
                if (next !in points.indices) {
                    if (loopStart >= 0) {
                        index = loopStart
                        position = points[loopStart]
                        direction = 1
                    } else direction = -direction
                    next = index + direction
                }
                val target = points[next]
                val segment = distance(position, target)
                if (segment <= remaining + 1e-7) {
                    position = target
                    index = next
                    remaining -= segment
                } else {
                    val fraction = remaining / segment
                    position = LatLon(position.latitude + (target.latitude - position.latitude) * fraction,
                        position.longitude + (target.longitude - position.longitude) * fraction)
                    remaining = 0.0
                }
            }
            return position
        }
    }
}
