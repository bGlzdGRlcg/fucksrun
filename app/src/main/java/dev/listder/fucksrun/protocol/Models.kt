package dev.listder.fucksrun.protocol

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** A server checkpoint, retaining unknown fields in the finish report. */
data class Point(
    val latitude: Double,
    val longitude: Double,
    val radius: Double = 10.0,
    val raw: Map<String, Any?> = emptyMap(),
) {
    val location: LatLon get() = LatLon(latitude, longitude)
    fun toJson(): Map<String, Any?> = if (raw.isNotEmpty()) raw else linkedMapOf(
        "latitude" to latitude, "longitude" to longitude, "radius" to radius,
    )

    companion object {
        fun fromJson(value: Any?): Point {
            val obj = JsonValues.objectValue(value) ?: error("打卡点不是对象")
            val latitude = JsonValues.double(obj["latitude"]) ?: error("打卡点缺少有效纬度")
            val longitude = JsonValues.double(obj["longitude"]) ?: error("打卡点缺少有效经度")
            require(LatLon(latitude, longitude).isValid()) { "打卡点坐标无效" }
            val radius = if ("radius" in obj) JsonValues.double(obj["radius"])
                ?: error("打卡点半径无效") else 10.0
            return Point(latitude, longitude, radius, obj)
        }
    }
}

data class UserInfo(val studentNumber: String, val name: String, val raw: Map<String, Any?>) {
    companion object {
        fun fromJson(value: Any?): UserInfo {
            val obj = JsonValues.objectValue(value) ?: error("用户信息不是对象")
            return UserInfo(
                JsonValues.string(obj["student_number"]).orEmpty(),
                JsonValues.string(obj["student_name"] ?: obj["name"] ?: obj["real_name"] ?: obj["nickname"]).orEmpty(),
                obj,
            )
        }
    }
}

data class RunContext(
    val recordId: Any,
    val runSessionId: String,
    val sessionSalt: String,
    val studentNumber: String,
    val startMs: Long,
    val checkpoints: List<Point>,
    val raw: Map<String, Any?> = emptyMap(),
) {
    init {
        require(recordId is String || recordId is Number) { "记录 ID 无效" }
        require(recordId.toString().isNotBlank() && runSessionId.isNotBlank() && sessionSalt.isNotBlank()) {
            "创建响应缺少会话字段"
        }
        require(studentNumber.isNotBlank()) { "当前未绑定学生身份" }
        require(checkpoints.isNotEmpty()) { "平台未返回打卡点" }
    }

    companion object {
        fun fromJson(value: Any?, studentNumber: String, startMs: Long): RunContext {
            val obj = JsonValues.objectValue(value) ?: error("创建响应不是对象")
            val id = obj["record_id"] ?: error("创建响应缺少 record_id")
            val session = JsonValues.string(obj["run_session_id"]) ?: error("创建响应缺少 run_session_id")
            val salt = JsonValues.string(obj["session_salt"]) ?: error("创建响应缺少 session_salt")
            val checkpoints = JsonValues.arrayValue(obj["checkpoints"]) ?: error("创建响应缺少 checkpoints")
            return RunContext(id, session, salt, studentNumber, startMs, checkpoints.map(Point::fromJson), obj)
        }
    }
}

data class LatLon(val latitude: Double, val longitude: Double) {
    fun isValid(): Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/** Drawing data, separate from timestamped upload points. Colors use #RRGGBBAA, not Android ARGB. */
data class TrackSegment(
    val points: List<LatLon>,
    val color: String = "#00FF00DD",
    val width: Int = 6,
    val arrowLine: Boolean = true,
)

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: String,
    val accuracy: Double = 10.0,
    val color: String = "#00FF00DD",
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "latitude" to latitude, "longitude" to longitude, "timestamp" to timestamp,
        "accuracy" to accuracy, "color" to color,
    )
    fun pointString(): String = String.format(Locale.US, "%.6f,%.6f,%s", latitude, longitude, timestamp)
}

sealed interface RunTask {
    val scheduledMs: Long
    val path: String
    val payload: Map<String, Any?>
}

data class ChunkTask(
    override val scheduledMs: Long,
    override val payload: Map<String, Any?>,
    val seq: Int,
    val chunkHash: String,
) : RunTask {
    override val path: String get() = "/upload_running_chunk"
}

data class FinishTask(
    override val scheduledMs: Long,
    override val payload: Map<String, Any?>,
) : RunTask {
    override val path: String get() = "/finish_running_record"
}

data class ApiResponse(val code: Int?, val msg: String, val data: Any?, val raw: Map<String, Any?>) {
    fun requireSuccess(): ApiResponse {
        check(code == 200) { "平台返回失败: ${msg.ifBlank { "未知响应" }}" }
        return this
    }
    companion object {
        fun fromJson(value: Any?): ApiResponse {
            val obj = JsonValues.objectValue(value) ?: error("API 响应不是对象")
            val code = JsonValues.double(obj["code"])?.takeIf {
                it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE
            }?.toInt()
            return ApiResponse(code, JsonValues.string(obj["msg"] ?: obj["message"]).orEmpty(), obj["data"], obj)
        }
    }
}

internal object WireValues {
    private val isoFormat = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    fun iso(ms: Long): String = isoFormat.format(Instant.ofEpochMilli(ms))
    fun rounded(value: Double, places: Int): Double {
        val result = BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).toDouble()
        return if (result == 0.0 && java.lang.Double.doubleToRawLongBits(value) < 0) -0.0 else result
    }

    // Python's f-string fixed formatting rounds the exact binary float, ties to even.
    fun fixed(value: Double, places: Int): String {
        val result = BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toPlainString()
        return if (!result.startsWith('-') && java.lang.Double.doubleToRawLongBits(value) < 0) "-$result" else result
    }
}
