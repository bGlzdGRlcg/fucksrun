package dev.listder.fucksrun.protocol

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Synchronous transport: callers must use an IO dispatcher, never the UI thread. */
class ApiClient(
    client: OkHttpClient = defaultHttpClient(),
) {
    private val base = DEFAULT_BASE_URL.toHttpUrl()
    // In particular, never replay create/chunk/finish after a lost response or redirect.
    private val transport = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()

    fun getUserInfo(token: String): UserInfo = UserInfo.fromJson(
        request("/login/wechat/info", token).requireSuccess().data,
    )

    /** A page of records; a short page indicates that the caller has reached the end. */
    fun getRunningRecords(
        token: String,
        page: Int,
        pageSize: Int,
        recordType: String = "normal",
    ): List<RunningRecordSummary> {
        require(page > 0 && pageSize > 0) { "记录页码和每页数量必须大于零" }
        val response = request("/get_student_running_record", token, query = linkedMapOf(
            "page" to page.toString(), "page_size" to pageSize.toString(), "record_type" to recordType,
        ))
        return runningRecordEntries(response).map(RunningRecordSummary::fromJson)
    }
    fun getRanking(token: String, page: Int = 1, pageSize: Int = 100, rankOffset: Int = 0): RankingPage {
        require(page > 0 && pageSize > 0 && rankOffset >= 0) { "排行榜分页参数无效" }
        val query = linkedMapOf(
            "page" to page.toString(), "page_size" to pageSize.toString(), "rank_type" to "class",
        )
        val response = request("/get_student_running_record_detail", token, query = query).requireSuccess()
        return RankingPage.fromJson(response.data ?: response.raw, rankOffset = rankOffset)
    }

    fun getAllClassRanking(token: String, pageSize: Int = 100): RankingPage {
        require(pageSize > 0) { "排行榜每页数量必须大于零" }
        var page = 1
        var totalPages = 1
        var totalCount = 0
        var currentUser: CurrentUserRanking? = null
        val entries = ArrayList<RankingEntry>()
        do {
            val result = getRanking(token, page, pageSize, rankOffset = entries.size)
            entries += result.entries
            currentUser = result.currentUser ?: currentUser
            totalPages = result.totalPages
            totalCount = result.totalCount
            page++
        } while (page <= totalPages && entries.size < totalCount)
        return RankingPage(entries.distinctBy { Triple(it.rank, it.user.id, it.user.name) }, currentUser, totalPages, totalCount)
    }

    fun getRunningRecordDetail(token: String, recordId: String): RunningRecordDetail {
        require(recordId.isNotBlank()) { "记录 ID 不能为空" }
        val response = request("/get_student_running_record", token, query = linkedMapOf(
            "record_id" to recordId, "include_tracks" to "true",
        ))
        val record = runningRecordEntries(response).asSequence()
            .map(RunningRecordSummary::fromJson).firstOrNull { it.recordId == recordId }
            ?: error("未找到跑步记录 $recordId")
        return RunningRecordDetail(record, record.tracks, response.raw)
    }

    /** Uses the current discard endpoint once; completed records may be rejected by the server. */
    fun deleteRunningRecord(
        token: String,
        recordId: String,
        runSessionId: String? = null,
        reason: String = "delete running record",
    ): ApiResponse {
        val id = recordId.toLongOrNull() ?: error("删除记录需要整数 record_id")
        val payload = linkedMapOf<String, Any?>("record_id" to id, "reason" to reason)
        if (!runSessionId.isNullOrBlank()) payload["run_session_id"] = runSessionId
        val response = try {
            request("/discard_running_record", token, payload, signed = true)
        } catch (error: IOException) {
            throw IOException("删除记录失败；平台可能不允许删除已完成记录：${error.message}", error)
        }
        check(response.code == 200) {
            "删除记录被平台拒绝（已完成记录可能不允许删除）：${response.msg.ifBlank { "未知响应" }}"
        }
        val data = JsonValues.objectValue(response.data)
        check(data?.get("discarded") != false && data?.get("already_completed") != true) {
            "平台未作废此记录：${response.msg.ifBlank { "记录已完成或不允许删除" }}"
        }
        return response
    }

    private fun runningRecordEntries(response: ApiResponse): List<Any?> {
        if (response.code != null) response.requireSuccess()
        val data = JsonValues.objectValue(response.data)
        return JsonValues.arrayValue(data?.get("records"))
            ?: JsonValues.arrayValue(response.raw["records"])
            ?: error("获取跑步记录失败：${response.msg.ifBlank { "响应缺少 records" }}")
    }

    fun createRun(token: String, studentNumber: String, startMs: Long? = null): RunContext {
        require(studentNumber.isNotBlank()) { "当前未绑定学生身份" }
        val response = request("/create_running_record", token, linkedMapOf("record_type" to "normal"))
        return RunContext.fromJson(response.requireSuccess().data, studentNumber, startMs ?: System.currentTimeMillis())
    }

    fun submit(token: String, task: RunTask): ApiResponse =
        request(task.path, token, task.payload, signed = true).requireSuccess()

    fun discardRun(
        token: String,
        ctx: RunContext,
        reason: String = "user discarded unfinished run",
    ): ApiResponse {
        val recordId = ctx.recordId.toString().toLongOrNull() ?: error("丢弃记录需要整数 record_id")
        return request("/discard_running_record", token, linkedMapOf(
            "record_id" to recordId, "run_session_id" to ctx.runSessionId, "reason" to reason,
        ), signed = true).requireSuccess()
    }

    fun request(
        path: String,
        token: String,
        payload: Map<String, Any?>? = null,
        signed: Boolean = false,
        query: Map<String, String> = emptyMap(),
    ): ApiResponse {
        val url = base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/') + "/" + path.trimStart('/'))
            .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
        val cleanToken = token.trim().removePrefix("Bearer ").trim()
        require(cleanToken.isNotEmpty()) { "Token 不能为空" }
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Authorization", "Bearer $cleanToken")
        val body = payload?.let(CanonicalJson::compact)
        if (body != null) {
            val delegate = body.toRequestBody(JSON_MEDIA_TYPE)
            builder.post(object : RequestBody() {
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
            })
        }
        if (signed) {
            require(body != null) { "签名请求必须包含 JSON body" }
            val timestamp = System.currentTimeMillis().toString()
            val nonce = buildString(8) { repeat(8) { append(NONCE_ALPHABET[random.nextInt(NONCE_ALPHABET.length)]) } }
            builder.header("X-Timestamp", timestamp).header("X-Nonce", nonce)
                .header("X-Signature", Signing.requestSignature(body, timestamp, nonce, cleanToken))
        }
        return transport.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string() ?: throw IOException("API 响应为空")
            if (!response.isSuccessful) {
                val errorMessage = runCatching {
                    val errorBody = CanonicalJson.parseObject(text)
                    JsonValues.string(errorBody["msg"] ?: errorBody["message"] ?: errorBody["detail"])
                }.getOrNull()
                throw IOException("HTTP ${response.code}: ${url.encodedPath}" +
                    errorMessage?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty())
            }
            ApiResponse.fromJson(CanonicalJson.parseObject(text))
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://campusrun.mzwu.edu.cn/api"
        const val USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 " +
            "MicroMessenger/8.0.10(0x18000a2a) NetType/WIFI Language/zh_CN"
        const val REFERER = "https://servicewechat.com/wx952e3e81d1b047d4/0/page-frame.html"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val NONCE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val random = SecureRandom()
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }
}
