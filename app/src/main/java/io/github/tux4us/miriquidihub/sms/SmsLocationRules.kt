package io.github.tux4us.miriquidihub.sms

/** Entscheidungsregeln der SMS-Ortung ohne Android-Abhängigkeiten. */
object SmsLocationRules {

    /** SMS können bei schlechtem Empfang stark verspätet zugestellt werden. */
    const val MAX_REQUEST_AGE_S = 24 * 60 * 60L
    /** Zulässige Abweichung, wenn die Uhr des anfragenden Geräts vorgeht. */
    const val MAX_CLOCK_AHEAD_S = 24 * 60 * 60L
    /** So lange werden beantwortete Anfragen gemerkt. Muss das gesamte Gültigkeitsfenster abdecken. */
    const val SEEN_RETENTION_S = MAX_REQUEST_AGE_S + MAX_CLOCK_AHEAD_S + 60 * 60L

    const val MAX_ANSWERS_PER_HOUR = 6
    const val MIN_ANSWER_GAP_S = 60L

    /** So lange wird auf Antworten zu einer eigenen Anfrage gewartet. */
    const val PENDING_RETENTION_S = 72 * 60 * 60L

    const val GOOD_ACCURACY_M = 50f
    const val CURRENT_FIX_AGE_MS = 2 * 60 * 1000L

    enum class Verdict { ACCEPT, EXPIRED, REPLAY, RATE_LIMITED }

    /** Beantwortete Anfrage. [sentAtSeconds] gehört zur Anfrage, [answeredAtSeconds] zur eigenen Uhr. */
    data class Seen(val requestId: Int, val sentAtSeconds: Long, val answeredAtSeconds: Long)

    fun checkRequest(requestId: Int, sentAtSeconds: Long, nowSeconds: Long, seen: List<Seen>): Verdict = when {
        nowSeconds - sentAtSeconds > MAX_REQUEST_AGE_S -> Verdict.EXPIRED
        sentAtSeconds - nowSeconds > MAX_CLOCK_AHEAD_S -> Verdict.EXPIRED
        seen.any { it.requestId == requestId && it.sentAtSeconds == sentAtSeconds } -> Verdict.REPLAY
        seen.count { isWithin(nowSeconds, it.answeredAtSeconds, 3600) } >= MAX_ANSWERS_PER_HOUR -> Verdict.RATE_LIMITED
        seen.any { isWithin(nowSeconds, it.answeredAtSeconds, MIN_ANSWER_GAP_S) } -> Verdict.RATE_LIMITED
        else -> Verdict.ACCEPT
    }

    fun pruneSeen(seen: List<Seen>, nowSeconds: Long): List<Seen> =
        seen.filter { nowSeconds - it.answeredAtSeconds <= SEEN_RETENTION_S }

    private fun isWithin(now: Long, then: Long, seconds: Long) = now - then in 0 until seconds

    /** True, wenn ein vorhandener Standort ohne zweite Messung gesendet werden kann. */
    fun isCurrentFix(ageMs: Long, accuracyMeters: Float?): Boolean =
        ageMs in -CURRENT_FIX_AGE_MS..CURRENT_FIX_AGE_MS &&
            accuracyMeters != null && accuracyMeters <= GOOD_ACCURACY_M

    /**
     * SMS können sich überholen: Eine spät eintreffende ältere Position darf eine neuere nicht ersetzen.
     */
    fun shouldReplacePosition(storedFixTimeSeconds: Long?, newFixTimeSeconds: Long): Boolean =
        storedFixTimeSeconds == null || newFixTimeSeconds >= storedFixTimeSeconds
}
