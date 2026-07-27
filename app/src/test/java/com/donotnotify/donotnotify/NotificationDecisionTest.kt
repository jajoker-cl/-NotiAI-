package com.donotnotify.donotnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Precedence matrix for the pure [RuleMatcher.planNotificationDecision]. */
class NotificationDecisionTest {

    private fun deny(t: String) =
        BlockerRule(packageName = "p", titleFilter = t, ruleType = RuleType.DENYLIST)

    private fun allow(t: String) =
        BlockerRule(packageName = "p", titleFilter = t, ruleType = RuleType.ALLOWLIST)

    private fun stack(t: String) =
        BlockerRule(packageName = "p", titleFilter = t, ruleType = RuleType.STACK)

    @Test
    fun `stack only, matching, not ongoing - stacks, not blocked`() {
        val d = RuleMatcher.planNotificationDecision(listOf(stack("News")), "p", "News x", null, false)
        assertFalse(d.isBlocked)
        assertTrue(d.shouldStack)
        assertEquals(listOf(0), d.matchedRuleIndices)
    }

    @Test
    fun `stack only, non-matching - neither blocked nor stacked`() {
        val d = RuleMatcher.planNotificationDecision(listOf(stack("News")), "p", "Other", null, false)
        assertFalse(d.isBlocked)
        assertFalse(d.shouldStack)
        assertTrue(d.matchedRuleIndices.isEmpty())
    }

    @Test
    fun `denylist wins over stack`() {
        val rules = listOf(stack("Promo"), deny("Promo"))
        val d = RuleMatcher.planNotificationDecision(rules, "p", "Promo!", null, false)
        assertTrue(d.isBlocked)
        assertFalse(d.shouldStack)
        assertNull(d.matchedStackRule)
    }

    @Test
    fun `allowlist gating wins over stack`() {
        val rules = listOf(allow("OTP"), stack("Promo"))
        val d = RuleMatcher.planNotificationDecision(rules, "p", "Promo!", null, false)
        assertTrue(d.isBlocked) // allowlist present but unmatched
        assertFalse(d.shouldStack)
    }

    @Test
    fun `first enabled stack match wins`() {
        val first = stack("A")
        val second = stack("A")
        val d = RuleMatcher.planNotificationDecision(listOf(first, second), "p", "A here", null, false)
        assertTrue(d.shouldStack)
        assertSame(first, d.matchedStackRule)
        assertEquals(listOf(0), d.matchedRuleIndices)
    }

    @Test
    fun `ongoing notification is excluded from stacking`() {
        val d = RuleMatcher.planNotificationDecision(listOf(stack("News")), "p", "News x", null, true)
        assertFalse(d.shouldStack)
    }

    @Test
    fun `stack never gates - other package unaffected`() {
        val d = RuleMatcher.planNotificationDecision(listOf(stack("News")), "other", "News", null, false)
        assertFalse(d.isBlocked)
        assertFalse(d.shouldStack)
    }

    @Test
    fun `disabled stack rule ignored`() {
        val rule = stack("News").copy(isEnabled = false)
        val d = RuleMatcher.planNotificationDecision(listOf(rule), "p", "News", null, false)
        assertFalse(d.shouldStack)
    }
}
