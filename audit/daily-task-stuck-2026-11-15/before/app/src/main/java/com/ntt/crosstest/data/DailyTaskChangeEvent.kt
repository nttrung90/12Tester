package com.ntt.crosstest.data

sealed interface DailyTaskChangeEvent {
    val ownerId: String
    val generation: Long
    val businessDate: String
    val mutationVersion: Long
    val triggerSource: String

    data class Updated(
        override val ownerId: String,
        override val generation: Long,
        override val businessDate: String,
        override val mutationVersion: Long,
        override val triggerSource: String = "",
        val status: DailyTaskStatus
    ) : DailyTaskChangeEvent

    data class StalePending(
        override val ownerId: String,
        override val generation: Long,
        override val businessDate: String,
        override val mutationVersion: Long,
        override val triggerSource: String = "",
        val cause: Throwable? = null
    ) : DailyTaskChangeEvent
}
