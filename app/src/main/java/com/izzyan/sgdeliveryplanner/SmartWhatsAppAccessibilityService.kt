package com.izzyan.sgdeliveryplanner

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Narrow experimental adapter. It only searches the explicitly launched SEND
 * recipient picker. Search text is never treated as proof of a unique group.
 * WhatsApp does not expose a reliable stable group ID or the attached content URI
 * in this screen; never select a recipient or click Send based on a name.
 */
class SmartWhatsAppAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); SmartShareSession.serviceConnected(true) }
    override fun onInterrupt() { SmartShareSession.cancel() }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        SmartShareSession.serviceConnected(false)
        return super.onUnbind(intent)
    }
    override fun onDestroy() { SmartShareSession.serviceConnected(false); super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val request = SmartShareSession.request ?: return
        if (event?.packageName?.toString() != request.choice.packageName) return
        if (SystemClock.elapsedRealtime() >= request.expiresAt) {
            SmartShareSession.finish("Search expired. Manual sharing available."); return
        }
        val prefs = SmartSharePreferences(this)
        if (!prefs.enabled || WhatsAppPreferences(this).default != request.choice ||
            prefs.eligibleGroup(request.kind,request.choice) != request.group) {
            SmartShareSession.finish("Sharing settings changed. Manual sharing available."); return
        }
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != request.choice.packageName) return
            // Never inspect unrelated chat content. Only query fixed, known
            // picker labels and search view IDs; unsupported UI times out.
            if (!hasPickerTitle(root)) return
            val pkg = request.choice.packageName
            val field = uniqueNode(root, listOf("$pkg:id/search_src_text", "$pkg:id/search_input", "$pkg:id/search_edit_text")) {
                it.isVisibleToUser && it.isEditable && it.isEnabled &&
                    it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            }
            if (field != null) {
                try {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request.group.name)
                    }
                    if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) {
                        SmartShareSession.searchPrepared(request.id)
                        Toast.makeText(this,"Verify the exact group and image, then Send. No automatic message sent.",Toast.LENGTH_LONG).show()
                    } else SmartShareSession.finish("Search unavailable. Choose the group manually.")
                } finally { field.recycle() }
                return
            }
            if (SmartShareSession.stage != SmartShareStage.OPENING) return
            val button = uniqueNode(root,listOf("$pkg:id/menuitem_search", "$pkg:id/search", "$pkg:id/search_button")) {
                it.isVisibleToUser && it.isEnabled && it.isClickable &&
                    it.contentDescription?.toString() == "Search"
            }
            if (button != null) {
                try {
                    // State changes before the action to prevent repeated clicks.
                    SmartShareSession.searchOpened(request.id)
                    if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        SmartShareSession.finish("Search could not be opened. Choose the group manually.")
                } finally { button.recycle() }
            }
        } catch (_: Exception) {
            SmartShareSession.finish("WhatsApp screen unsupported. Manual sharing available.")
        } finally { root.recycle() }
    }

    private fun hasPickerTitle(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText("Send to")
        return try {
            nodes.any { it.isVisibleToUser && it.text?.toString() in setOf("Send to", "Send to…", "Send to...") }
        } finally { nodes.forEach { it.recycle() } }
    }
    private fun uniqueNode(root: AccessibilityNodeInfo, ids: List<String>,
        predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val nodes = ids.flatMap { root.findAccessibilityNodeInfosByViewId(it) }
        val matches = nodes.filter(predicate)
        val result = if (matches.size == 1) matches.single() else null
        nodes.filter { it !== result }.forEach { it.recycle() }
        return result
    }
}
