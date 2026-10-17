package com.halo.moontone.ui

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

internal object UiActions {
    fun visibleNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Compose can reuse node IDs after navigation or lazy-list scrolling.
        // Read the current tree rather than UiAutomation's previous snapshot.
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser && predicate(node)) result.add(node)
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(automation.rootInActiveWindow)
        return result
    }

    fun waitFor(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(50) {
            visibleNodes(predicate).firstOrNull()?.let { return it }
            Thread.sleep(100)
        }
        fail("Visible node not found: $description")
    }

    private fun fail(message: String): Nothing {
        val all = visibleNodes { !it.isPassword && !it.text.isNullOrBlank() }
        val labels = (all.take(4) + all.takeLast(4)).map { it.text.toString().take(80) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/moontone-ui-failure.png")).use { it.readBytes() }
        throw AssertionError("$message; visible labels=$labels")
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var control = node
        while (!control.isClickable) control = control.parent ?: throw AssertionError("Node has no clickable ancestor")
        return control
    }

    fun clickDescription(description: String) {
        val node = waitFor(description) { it.contentDescription?.toString() == description }
        check(clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    fun clickText(text: String) {
        repeat(8) {
            visibleNodes { it.text?.toString() == text }.firstOrNull()?.let { node ->
                check(clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            val scroll = visibleNodes { it.isScrollable }.firstOrNull()
            if (scroll != null) {
                scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            Thread.sleep(250)
        }
        fail("Clickable text not found: $text")
    }

    fun waitText(text: String) = waitFor(text) { it.text?.toString() == text }

    fun waitToggle(text: String, checked: Boolean): AccessibilityNodeInfo {
        return waitFor("$text checked=$checked") { node ->
            if (node.text?.toString() != text) false else {
                var control = node
                while (!control.isCheckable) control = control.parent ?: return@waitFor false
                control.isChecked == checked
            }
        }
    }
}
