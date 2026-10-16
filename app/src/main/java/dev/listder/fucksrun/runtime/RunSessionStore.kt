package dev.listder.fucksrun.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.listder.fucksrun.protocol.CanonicalJson
import dev.listder.fucksrun.protocol.ChunkTask
import dev.listder.fucksrun.protocol.FinishTask
import dev.listder.fucksrun.protocol.LatLon
import dev.listder.fucksrun.protocol.RunContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One atomic, encrypted snapshot. Token, session salt and signed payloads never enter backups.
 * commit() is synchronous: callers must not send a write until its boundary is durable.
 */
class RunSessionStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "run-session.enc"))

    @Synchronized
    internal fun load(): RunSnapshot? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.readFully()
        require(bytes.size > 13 && bytes[0].toInt() == 1) { "无法识别已保存的任务" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val json = JSONObject(String(cipher.doFinal(bytes, 13, bytes.size - 13), Charsets.UTF_8))
        require(json.getInt("version") == 1) { "已保存任务的版本不兼容" }
        val config = json.optJSONObject("config")?.let {
            RunConfig(token = it.getString("token"), targetKm = it.getDouble("targetKm"),
                customRoute = it.optJSONArray("customRoute")?.let { points ->
                    List(points.length()) { index ->
                        val point = points.getJSONObject(index)
                        LatLon(point.getDouble("latitude"), point.getDouble("longitude"))
                    }
                }.orEmpty())
        }
        val session = json.optJSONObject("context")?.let {
            RunContext.fromJson(CanonicalJson.parseObject(it.getJSONObject("raw").toString()),
                it.getString("studentNumber"), it.getLong("startMs"))
        }
        val tasksJson = json.getJSONArray("tasks")
        val tasks = (0 until tasksJson.length()).map { index ->
            val task = tasksJson.getJSONObject(index)
            val payload = CanonicalJson.parseObject(task.getJSONObject("payload").toString())
            when (task.getString("path")) {
                "/upload_running_chunk" -> ChunkTask(task.getLong("scheduledMs"), payload,
                    task.getInt("seq"), task.getString("chunkHash"))
                "/finish_running_record" -> FinishTask(task.getLong("scheduledMs"), payload)
                else -> error("已保存任务包含未知接口")
            }
        }
        val nextIndex = json.getInt("nextTaskIndex")
        require(nextIndex in 0..tasks.size) { "已保存的分片进度无效" }
        return RunSnapshot(
            taskId = json.getString("taskId"), config = config,
            phase = RunPhase.valueOf(json.getString("phase")), context = session, tasks = tasks,
            nextTaskIndex = nextIndex, trackPoints = json.getInt("trackPoints"),
            lastChunkSeq = json.getInt("lastChunkSeq"), lastChunkHash = json.getString("lastChunkHash"),
            inFlight = json.nullableString("inFlight"), pauseRequested = json.getBoolean("pauseRequested"),
            stopRequested = json.getBoolean("stopRequested"), studentName = json.getString("studentName"),
            studentNumber = json.getString("studentNumber"), message = json.getString("message"),
            error = json.nullableString("error"),
        )
    }

    @Synchronized
    internal fun commit(snapshot: RunSnapshot) {
        val config = snapshot.config?.let {
            JSONObject().put("token", it.token)
                .put("targetKm", it.targetKm)
                .put("customRoute", JSONArray(it.customRoute.map { point ->
                    JSONObject().put("latitude", point.latitude).put("longitude", point.longitude)
                }))
        }
        val session = snapshot.context?.let {
            // Preserve original response fields and all checkpoint attributes for exact recovery.
            val raw = it.raw + mapOf("record_id" to it.recordId, "run_session_id" to it.runSessionId,
                "session_salt" to it.sessionSalt, "checkpoints" to it.checkpoints.map { point -> point.toJson() })
            JSONObject().put("raw", JSONObject(CanonicalJson.compact(raw)))
                .put("studentNumber", it.studentNumber).put("startMs", it.startMs)
        }
        val tasks = JSONArray()
        snapshot.tasks.forEach { task ->
            val value = JSONObject().put("scheduledMs", task.scheduledMs).put("path", task.path)
                .put("payload", JSONObject(CanonicalJson.compact(task.payload)))
            if (task is ChunkTask) value.put("seq", task.seq).put("chunkHash", task.chunkHash)
            tasks.put(value)
        }
        val json = JSONObject().put("version", 1).put("taskId", snapshot.taskId)
            .put("config", config ?: JSONObject.NULL).put("context", session ?: JSONObject.NULL)
            .put("phase", snapshot.phase.name).put("tasks", tasks)
            .put("nextTaskIndex", snapshot.nextTaskIndex).put("trackPoints", snapshot.trackPoints)
            .put("lastChunkSeq", snapshot.lastChunkSeq).put("lastChunkHash", snapshot.lastChunkHash)
            .put("inFlight", snapshot.inFlight ?: JSONObject.NULL)
            .put("pauseRequested", snapshot.pauseRequested).put("stopRequested", snapshot.stopRequested)
            .put("studentName", snapshot.studentName).put("studentNumber", snapshot.studentNumber)
            .put("message", snapshot.message).put("error", snapshot.error ?: JSONObject.NULL)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        check(cipher.iv.size == 12)
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1))
            stream.write(cipher.iv)
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
        check(!file.baseFile.exists()) { "无法清除已保存的任务" }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private companion object { const val KEY_ALIAS = "fucksrun.run-session.v1" }
}
