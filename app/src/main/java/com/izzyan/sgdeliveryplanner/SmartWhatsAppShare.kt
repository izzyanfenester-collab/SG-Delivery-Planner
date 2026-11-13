package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

internal enum class SmartShareKind(val key: String, val title: String) {
    REPORT("report", "Share Delivery Report"), ROUTE("route", "Share Photo")
}
internal data class SmartGroup(val name: String, val packageName: String)
internal class SmartSharePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("smart_whatsapp_share", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var autoReturn: Boolean
        get() = prefs.getBoolean("auto_return", true)
        set(value) { prefs.edit().putBoolean("auto_return", value).apply() }
    fun group(kind: SmartShareKind): SmartGroup? {
        val name = prefs.getString(kind.key + "_name", null) ?: return null
        val pkg = prefs.getString(kind.key + "_package", null) ?: return null
        return SmartGroup(name, pkg).takeIf { name.isNotBlank() && WhatsAppChoice.entries.any { it.packageName == pkg } }
    }
    fun saveVerified(kind: SmartShareKind, name: String, choice: WhatsAppChoice) {
        require(name.isNotBlank() && name.length <= 100 && name.none { it.isISOControl() })
        prefs.edit().putString(kind.key + "_name", name).putString(kind.key + "_package", choice.packageName).apply()
    }
    fun remove(kind: SmartShareKind) {
        prefs.edit().remove(kind.key + "_name").remove(kind.key + "_package").apply()
    }
    fun eligibleGroup(kind: SmartShareKind, choice: WhatsAppChoice?): SmartGroup? =
        group(kind)?.takeIf { choice != null && it.packageName == choice.packageName }
}

internal fun smartAccessibilityEnabled(context: Context): Boolean {
    val component = ComponentName(context, SmartWhatsAppAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == component }
}
internal fun smartNetworkAvailable(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val cap = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
internal enum class SmartShareStage { OPENING, SEARCH_OPENED, SEARCH_PREPARED, MANUAL_REQUIRED, CANCELLED }
internal data class SmartShareRequest(
    val id: String, val kind: SmartShareKind, val choice: WhatsAppChoice, val group: SmartGroup,
    val stream: Uri, val autoReturn: Boolean, val expiresAt: Long
)

/** All state is ephemeral, main-thread owned. No pending request is persisted or replayed.
 * No send confirmation can be inferred from a UI click or a screen transition. */
internal object SmartShareSession {
    private val handler = Handler(Looper.getMainLooper())
    var connected by mutableStateOf(false)
        private set
    var request by mutableStateOf<SmartShareRequest?>(null)
        private set
    var stage by mutableStateOf(SmartShareStage.CANCELLED)
        private set
    var status by mutableStateOf("")
        private set
    var lastKind by mutableStateOf<SmartShareKind?>(null)
        private set
    val active: Boolean get() = request != null
    fun serviceConnected(value: Boolean) {
        connected = value
        if (!value && active) finish("Accessibility disabled. Review the image and send manually.")
    }
    fun begin(kind: SmartShareKind, choice: WhatsAppChoice, group: SmartGroup, stream: Uri, autoReturn: Boolean): SmartShareRequest? {
        if (active || !connected || group.packageName != choice.packageName || stream.scheme != "content") return null
        val next = SmartShareRequest(UUID.randomUUID().toString(),kind,choice,group,stream,autoReturn,SystemClock.elapsedRealtime()+20_000L)
        request = next; lastKind = kind; stage = SmartShareStage.OPENING; status = "Opening WhatsApp…"
        handler.postDelayed({
            if (request?.id == next.id) finish("WhatsApp search timed out. Choose the group and Send manually.")
        },20_000L)
        return next
    }
    fun searchOpened(id: String) {
        if (request?.id != id) return
        stage = SmartShareStage.SEARCH_OPENED; status = "Finding configured group…"
    }
    fun searchPrepared(id: String) {
        if (request?.id != id) return
        stage = SmartShareStage.SEARCH_PREPARED
        // Search text is not proof of group identity or an image attachment.
        status = "Group search prepared. Verify recipient and image, then Send in WhatsApp."
        request = null
    }
    fun finish(message: String) {
        request = null; stage = SmartShareStage.MANUAL_REQUIRED; status = message
    }
    fun cancel() {
        request = null; stage = SmartShareStage.CANCELLED; status = "Assistance cancelled. Nothing was automatically sent."
    }
    fun manual(kind: SmartShareKind, message: String) {
        if (!active) { lastKind = kind; finish(message) }
    }
    fun clearIdleStatus() {
        if (!active) { status = ""; lastKind = null }
    }
}

/** Use the existing ACTION_SEND image/URI and preference, never an unofficial WhatsApp protocol. */
internal fun openSmartWhatsAppShare(context: Context, send: Intent, kind: SmartShareKind): WhatsAppImageShareFallbackRequest? {
    val prefs = SmartSharePreferences(context)
    if (SmartShareSession.active) {
        Toast.makeText(context,"A WhatsApp sharing request is already active.",Toast.LENGTH_SHORT).show()
        return null
    }
    if (!prefs.enabled) {
        SmartShareSession.clearIdleStatus()
        return openDefaultWhatsAppImageShare(context,send,kind.title)
    }
    val choice = WhatsAppPreferences(context).default
    val group = prefs.eligibleGroup(kind, choice)
    val reason = when {
        choice == null -> "Choose a default WhatsApp app in Settings."
        group == null -> "Configure and verify the group for this WhatsApp app."
        !smartAccessibilityEnabled(context) || !SmartShareSession.connected -> "Enable SG Delivery Share Assist in Accessibility Settings."
        !smartNetworkAvailable(context) -> "Internet unavailable. WhatsApp may queue manual sends."
        else -> null
    }
    if (reason != null) {
        SmartShareSession.manual(kind,reason + " Manual sharing available.")
        Toast.makeText(context,reason + " Opening normal sharing.",Toast.LENGTH_LONG).show()
        return openDefaultWhatsAppImageShare(context,send,kind.title)
    }
    val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    val valid = send.action == Intent.ACTION_SEND && send.type in setOf("image/png","image/jpeg") &&
        stream?.scheme == "content" && stream.authority == context.packageName + ".excel-files" &&
        send.clipData?.getItemAt(0)?.uri == stream && send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
    if (!valid || stream == null) {
        SmartShareSession.manual(kind,"Image attachment could not be verified. Manual sharing available.")
        return openDefaultWhatsAppImageShare(context,send,kind.title)
    }
    val request = SmartShareSession.begin(kind,requireNotNull(choice),requireNotNull(group),stream,prefs.autoReturn)
    if (request == null) return openDefaultWhatsAppImageShare(context,send,kind.title)
    if (tryWhatsAppImageShare(context,send,choice)) return null
    SmartShareSession.finish("WhatsApp unavailable. Manual sharing available.")
    return WhatsAppImageShareFallbackRequest(Intent(send),kind.title,choice)
}
