package com.donotnotify.donotnotify

/**
 * Result of an AI-powered notification judgment.
 *
 * @param isSpam   True if the AI considers this notification spam/junk.
 * @param confidence Confidence score in [0, 1] — higher means more certain.
 * @param reason   Human-readable explanation of the judgment.
 */
data class AiJudgment(
    val isSpam: Boolean,
    val confidence: Float,
    val reason: String
) {
    companion object {
        /** Default result used when the AI call times out or fails — always permits the notification. */
        val FAIL_OPEN = AiJudgment(
            isSpam = false,
            confidence = 0f,
            reason = "AI unavailable — allowing notification"
        )
    }
}
