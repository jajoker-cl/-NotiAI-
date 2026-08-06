package com.donotnotify.donotnotify

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize

/**
 * Metadata attached to AI-generated rules.
 *
 * Tracks the origin, confidence, and lifecycle of a rule that was automatically
 * generated from an AI judgment result. Supports the PENDING → CONFIRMED
 * quality-upgrade workflow: rules start as PENDING and are promoted to CONFIRMED
 * once their hitCount reaches [PROMOTION_THRESHOLD].
 *
 * @param source       Human-readable origin label (e.g. "ai_auto", "ai_manual").
 * @param confidence   The AI's confidence score when the rule was created.
 * @param reason       The AI's human-readable reason that triggered rule creation.
 * @param createdAtMs  Epoch millis when the rule was generated.
 * @param status       Current lifecycle status — [PENDING] or [CONFIRMED].
 */
@Keep
@Parcelize
data class AiMetadata(
    val source: String = "ai_auto",
    val confidence: Float = 0f,
    val reason: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val status: String = PENDING
) : Parcelable {

    companion object {
        /** Rule has been auto-generated but hasn't been validated by repeated hits yet. */
        const val PENDING = "PENDING"

        /** Rule has been validated by enough real hits to trust it. */
        const val CONFIRMED = "CONFIRMED"

        /**
         * Minimum hitCount before a PENDING rule is automatically promoted to CONFIRMED.
         */
        const val PROMOTION_THRESHOLD = 3
    }
}
