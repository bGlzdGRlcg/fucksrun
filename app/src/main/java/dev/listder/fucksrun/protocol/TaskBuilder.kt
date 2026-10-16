package dev.listder.fucksrun.protocol

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Generates every immutable upload payload before scheduling, matching prepare_tasks in Python. */
object TaskBuilder {
    fun prepareTasks(
        ctx: RunContext,
        track: List<LatLon>,
        km: Double,
        pace: Int? = null,
        cadence: Int? = null,
    ): List<RunTask> {
        require(track.size >= 2) { "轨迹至少包含两个点" }
        require(track.all { it.isValid() }) { "轨迹坐标无效" }
        require(km.isFinite() && km in 0.1..50.0) { "目标公里数必须在 0.1–50 之间" }
        val actualPace = pace ?: Random.nextInt(300, 451)
        val actualCadence = cadence ?: Random.nextInt(168, 193)
        require(actualPace > 0 && actualCadence > 0) { "配速与步频必须大于零" }
        val duration = (km * actualPace).toInt()
        val durationMs = duration * 1000L
        val n = track.size
        val chunkWindows = max(1, ceil(durationMs / 30000.0).toInt())
        val count = max(1, ceil(n.toDouble() / chunkWindows).toInt())
        val tasks = mutableListOf<RunTask>()
        var previous = ""
        var seq = 0
        fun timestamp(index: Int): Long = ctx.startMs + durationMs * index / (n - 1)
        for (i in track.indices step count) {
            val end = min(i + count, n)
            seq++
            val t0 = timestamp(i)
            var t1 = timestamp(end - 1)
            if (t0 == t1) t1 = t0 + 1000
            val points = (i until end).map { j ->
                TrackPoint(WireValues.rounded(track[j].latitude, 6),
                    WireValues.rounded(track[j].longitude, 6), timestamp(j).toString())
            }
            val pointValues = points.map(TrackPoint::toJson)
            val first = points.first().pointString()
            val last = points.last().pointString()
            val hash = Signing.chunkHash(ctx.recordId, ctx.runSessionId, seq, t0, t1, pointValues, previous)
            val payload = linkedMapOf<String, Any?>(
                "record_id" to ctx.recordId,
                "run_session_id" to ctx.runSessionId,
                "chunk_seq" to seq,
                "chunk_start_time" to WireValues.iso(t0),
                "chunk_end_time" to WireValues.iso(t1),
                "prev_chunk_hash" to previous,
                "sign_version" to Signing.SIGN_VERSION,
                "points" to pointValues,
                "point_count" to points.size,
                "first_point" to first,
                "last_point" to last,
                "chunk_hash" to hash,
            )
            payload["sign"] = Signing.sign(listOf(ctx.recordId, ctx.studentNumber, ctx.runSessionId,
                seq, t0, t1, first, last, previous, hash, points.size, ctx.sessionSalt, Signing.SIGN_SUFFIX))
            tasks += ChunkTask(t1 + 3000, payload, seq, hash)
            previous = hash
        }
        val endMs = ctx.startMs + durationMs
        val checkpointData = ctx.checkpoints.mapIndexed { i, cp ->
            val index = Random.nextInt(track.size)
            val hit = track[index]
            LinkedHashMap(cp.toJson()).apply {
                put("latitude", WireValues.rounded(hit.latitude, 6))
                put("longitude", WireValues.rounded(hit.longitude, 6))
                put("reached", true)
                put("reached_type", "normal")
                put("hit_accuracy", 10.0)
                put("hit_distance_m", 0.0)
                put("fuzzy_confirm_count", 0)
                put("reached_at", timestamp(index))
            }
        }
        val secondsPerKm = (duration / km).toInt()
        val finish = linkedMapOf<String, Any?>(
            "record_id" to ctx.recordId,
            "run_session_id" to ctx.runSessionId,
            "client_end_time" to WireValues.iso(endMs),
            "client_distance" to km,
            "client_duration" to duration,
            "client_pace" to "${secondsPerKm / 60}'${secondsPerKm % 60}\"",
            "client_cadence" to actualCadence,
            "client_location_signal_summary" to emptyMap<String, Any?>(),
            "client_risk_flags" to emptyList<Any?>(),
            "client_checkpoint_data" to checkpointData,
            "last_chunk_seq" to seq,
            "last_chunk_hash" to previous,
        )
        finish["sign"] = Signing.sign(listOf(ctx.recordId, ctx.studentNumber, ctx.runSessionId,
            endMs, WireValues.fixed(km, 3), duration, seq, previous, ctx.sessionSalt, Signing.SIGN_SUFFIX))
        tasks += FinishTask(endMs + 10000, finish)
        return tasks
    }
}
