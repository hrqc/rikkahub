package me.rerere.rikkahub.data.mobileagent

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneClickAnchorEventPolicyTest {
    @Test fun `only target content and text events retain a reference`() {
        for (event in listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)) {
            assertFalse(shouldClearPhoneClickAnchor(event, 7, 7, false))
        }
        for (event in listOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, AccessibilityEvent.TYPE_TOUCH_INTERACTION_END, 0)) {
            assertTrue(shouldClearPhoneClickAnchor(event, 7, 7, false))
        }
    }

    @Test fun `same window and foreign structural events both revoke the context`() {
        for (event in listOf(AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            for (source in listOf(-1, 7, 8)) assertTrue(shouldClearPhoneClickAnchor(event, source, 7, false))
            assertTrue(shouldClearPhoneClickAnchor(event, 7, null, false))
        }
    }

    @Test fun `only the verified own overlay is ignored and foreign nonstructural content is unrelated`() {
        for (event in listOf(AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            assertFalse(shouldClearPhoneClickAnchor(event, 8, 7, true))
        }
        assertFalse(shouldClearPhoneClickAnchor(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 8, 7, false))
        assertFalse(shouldClearPhoneClickAnchor(AccessibilityEvent.TYPE_VIEW_CLICKED, 8, 7, false))
        assertTrue(shouldClearPhoneClickAnchor(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 8, 7, false))
    }
}
