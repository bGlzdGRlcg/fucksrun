package dev.listder.fucksrun.protocol

import java.math.BigDecimal

/** Server polyline styling and point objects, including all unknown wire fields. */
data class RunningRecordTrackSegment(
    val points: List<Map<String, Any?>>,
    val color: String?,
    val width: Double?,
    val arrowLine: Boolean?,
    val raw: Map<String, Any?>,
) {
    companion object {
        fun fromJson(value: Any?): RunningRecordTrackSegment {
            val obj = JsonValues.objectValue(value) ?: error("记录轨迹段不是对象")
            return RunningRecordTrackSegment(
                points = JsonValues.arrayValue(obj["points"]).orEmpty().mapNotNull(JsonValues::objectValue),
                color = JsonValues.string(obj["color"]),
                width = JsonValues.double(obj["width"]),
                arrowLine = RecordValues.boolean(obj["arrowLine"]),
                raw = obj,
            )
        }
    }
}

/** Nullable metrics distinguish absent/invalid server values from actual zero values. */
data class RunningRecordSummary(
    val recordId: String,
    val distance: Double?,
    val duration: Long?,
    val pace: String?,
    val cadence: Int?,
    val startTime: String?,
    val endTime: String?,
    val isCompleted: Boolean?,
    val reachedCheckpoints: Int?,
    val totalCheckpoints: Int?,
    val runSessionId: String?,
    val hasCheating: Boolean?,
    val checkpointData: List<Map<String, Any?>>,
    val tracks: List<RunningRecordTrackSegment>,
    val runningRecordRaw: Map<String, Any?>,
    val raw: Map<String, Any?>,
) {
    companion object {
        fun fromJson(value: Any?): RunningRecordSummary {
            val obj = JsonValues.objectValue(value) ?: error("跑步记录不是对象")
            val record = JsonValues.objectValue(obj["running_record"]) ?: obj
            val id = JsonValues.string(record["id"] ?: record["record_id"])
                ?.takeIf(String::isNotBlank) ?: error("跑步记录缺少有效 ID")
            val checkpoints = JsonValues.arrayValue(record["checkpoint_data"])
                ?.mapNotNull(JsonValues::objectValue).orEmpty()
            val trackValues = JsonValues.arrayValue(obj["tracks"])
                ?: JsonValues.arrayValue(record["tracks"])
            return RunningRecordSummary(
                recordId = id,
                distance = JsonValues.double(record["distance"]),
                duration = RecordValues.long(record["duration"]),
                pace = JsonValues.string(record["pace"]),
                cadence = RecordValues.int(record["cadence"]),
                startTime = JsonValues.string(record["start_time"] ?: record["startTime"] ?: record["created_at"]),
                endTime = JsonValues.string(record["end_time"] ?: record["endTime"] ?: record["finished_at"]),
                isCompleted = RecordValues.boolean(record["is_completed"]),
                reachedCheckpoints = RecordValues.int(record["reached_checkpoints"])
                    ?: checkpoints.takeIf { "checkpoint_data" in record }
                        ?.count { RecordValues.boolean(it["reached"]) == true },
                totalCheckpoints = RecordValues.int(record["total_checkpoints"])
                    ?: checkpoints.takeIf { "checkpoint_data" in record }?.size,
                runSessionId = JsonValues.string(record["run_session_id"] ?: obj["run_session_id"]),
                hasCheating = RecordValues.boolean(record["has_cheating"]),
                checkpointData = checkpoints,
                tracks = trackValues.orEmpty().mapNotNull { item ->
                    JsonValues.objectValue(item)?.let(RunningRecordTrackSegment::fromJson)
                },
                runningRecordRaw = record,
                raw = obj,
            )
        }
    }
}

/** [raw] retains the complete detail response; [record] retains its record envelope. */
data class RunningRecordDetail(
    val record: RunningRecordSummary,
    val tracks: List<RunningRecordTrackSegment>,
    val raw: Map<String, Any?>,
)

internal object RecordValues {
    fun long(value: Any?): Long? {
        val text = JsonValues.string(value) ?: return null
        return try {
            BigDecimal(text).longValueExact()
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun int(value: Any?): Int? = long(value)
        ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

    fun boolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        is Number -> when (JsonValues.double(value)) {
            1.0 -> true
            0.0 -> false
            else -> null
        }
        else -> null
    }
}
