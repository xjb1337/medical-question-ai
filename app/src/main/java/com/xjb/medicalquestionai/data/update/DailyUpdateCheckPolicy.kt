package com.xjb.medicalquestionai.data.update

import java.time.Instant
import java.time.ZoneId

internal object DailyUpdateCheckPolicy {
    enum class Attempt { FIRST, NETWORK_RECOVERY }

    fun dayKey(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toString()

    fun isDue(lastAttemptDay: String?, today: String): Boolean = lastAttemptDay != today

    fun nextAttempt(lastAttemptDay: String?, pendingNetworkDay: String?, recoveryAttemptDay: String?,
                    today: String, online: Boolean): Attempt? = when {
        isDue(lastAttemptDay, today) -> Attempt.FIRST
        online && pendingNetworkDay == today && recoveryAttemptDay != today -> Attempt.NETWORK_RECOVERY
        else -> null
    }

    fun shouldPrompt(versionCode: Long, ignoredVersionCode: Long?, dismissedVersionCode: Long?): Boolean =
        versionCode != ignoredVersionCode && versionCode != dismissedVersionCode
}
