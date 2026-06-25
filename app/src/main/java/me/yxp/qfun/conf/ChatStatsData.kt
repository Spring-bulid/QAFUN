package me.yxp.qfun.conf

import kotlinx.serialization.Serializable

@Serializable
data class ChatStatsData(
    val groups: Map<String, GroupStats> = emptyMap()
)

@Serializable
data class GroupStats(
    val name: String = "",
    val total: Long = 0L,
    val startTime: Long = 0L,
    val members: Map<String, Long> = emptyMap(),
    val hours: List<Long> = List(24) { 0L }
)
