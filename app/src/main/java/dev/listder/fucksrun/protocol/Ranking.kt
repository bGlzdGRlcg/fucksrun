package dev.listder.fucksrun.protocol

/** Compact leaderboard response used by the home screen and the full ranking contract. */
data class RankingUser(
    val id: String?,
    val name: String,
    val totalDistance: Double?,
    val departmentName: String?,
    val schoolName: String?,
    val gradeName: String?,
    val gender: String?,
) {
    companion object {
        fun fromJson(value: Any?): RankingUser {
            val obj = JsonValues.objectValue(value) ?: error("排行榜用户不是对象")
            return RankingUser(
                id = JsonValues.string(obj["id"] ?: obj["user_id"]),
                name = JsonValues.string(obj["student_name"] ?: obj["name"] ?: obj["real_name"] ?: obj["nickname"]).orEmpty(),
                totalDistance = JsonValues.double(obj["total_distance"] ?: obj["distance"]),
                departmentName = JsonValues.string(obj["department_name"] ?: obj["department"]),
                schoolName = JsonValues.string(obj["school_name"] ?: obj["school"]),
                gradeName = JsonValues.string(obj["grade_name"] ?: obj["grade"]),
                gender = JsonValues.string(obj["gender"]),
            )
        }
    }
}

data class RankingEntry(
    val rank: Int,
    val user: RankingUser,
)

data class CurrentUserRanking(
    val rank: Int?,
    val user: RankingUser?,
)

data class RankingPage(
    val entries: List<RankingEntry>,
    val currentUser: CurrentUserRanking?,
    val totalPages: Int,
    val totalCount: Int,
) {
    companion object {
        fun fromJson(value: Any?, rankOffset: Int = 0): RankingPage {
            val root = JsonValues.objectValue(value)
            val data = when {
                root != null -> JsonValues.objectValue(root["data"]) ?: root
                JsonValues.arrayValue(value) != null -> mapOf("rank_list" to JsonValues.arrayValue(value).orEmpty())
                else -> error("排行榜响应不是对象")
            }
            val rawEntries = JsonValues.arrayValue(data["rank_list"]).orEmpty()
            val entries = rawEntries.mapIndexedNotNull { index, item ->
                val obj = JsonValues.objectValue(item) ?: return@mapIndexedNotNull null
                val user = RankingUser.fromJson(obj["user"] ?: obj)
                val rank = RecordValues.int(obj["rank"]) ?: (rankOffset + index + 1)
                RankingEntry(rank.coerceAtLeast(1), user)
            }
            val current = JsonValues.objectValue(data["current_user_rank"])?.let { obj ->
                CurrentUserRanking(RecordValues.int(obj["rank"]),
                    JsonValues.objectValue(obj["user"])?.let(RankingUser::fromJson))
            }
            val pagination = JsonValues.objectValue(data["pagination"])
            return RankingPage(
                entries = entries,
                currentUser = current,
                totalPages = (RecordValues.int(pagination?.get("total_pages"))
                    ?: RecordValues.int(data["total_pages"]) ?: 1).coerceAtLeast(1),
                totalCount = (RecordValues.int(pagination?.get("total_count"))
                    ?: RecordValues.int(data["total_count"]) ?: entries.size).coerceAtLeast(0),
            )
        }
    }
}

