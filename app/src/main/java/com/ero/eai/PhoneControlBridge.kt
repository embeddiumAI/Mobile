package com.ero.eai

import android.content.Context
import android.content.ComponentName
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

object PhoneControlBridge {
    internal val commands = Channel<PhoneControlCommand>(Channel.UNLIMITED)
    val instructions = Channel<String>(Channel.UNLIMITED)
    @Volatile var service: PhoneControlService? = null
        internal set
    @Volatile var active: Boolean = false
        private set
    @Volatile var stopped: Boolean = false
        private set
    @Volatile var onStop: (() -> Unit)? = null
    @Volatile var onInstruction: ((String) -> Unit)? = null

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, PhoneControlService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) if (splitter.next().equals(expected, ignoreCase = true)) return true
        return false
    }

    fun startTask() {
        check(service != null) { "Enable eAI Phone Control in Android Accessibility settings first." }
        service?.clearPendingCommands()
        stopped = false
        active = true
        while (instructions.tryReceive().isSuccess) { }
        service?.showControlPanel("Phone agent is starting")
    }

    fun finishTask(message: String = "Phone task ended") {
        active = false
        service?.showControlPanel(message)
    }

    fun stopTask() {
        val wasActive = active
        stopped = true
        active = false
        service?.clearPendingCommands()
        service?.denyPendingAction()
        if (wasActive) onStop?.invoke()
        service?.showControlPanel("Stopped")
    }

    fun submitInstruction(instruction: String): Boolean {
        val clean = instruction.trim().take(1000)
        if (clean.isBlank()) return false
        if (active) {
            redirect(clean)
            return true
        }
        val start = onInstruction ?: return false
        start(clean)
        return true
    }

    fun redirect(instruction: String) {
        val clean = instruction.trim().take(1000)
        if (clean.isNotEmpty() && active) instructions.trySend(clean)
    }

    fun updateStatus(status: String) {
        service?.showControlPanel(status.take(120))
    }

    internal suspend fun runTool(name: String, args: JSONObject = JSONObject()): PhoneControlActionResult {
        check(active && !stopped) { "Phone task stopped" }
        val result = CompletableDeferred<PhoneControlActionResult>()
        commands.send(PhoneControlCommand(name, args, result))
        return withTimeout(30_000) { result.await() }
    }
}

internal data class PhoneControlCommand(
    val name: String,
    val args: JSONObject,
    val result: CompletableDeferred<PhoneControlActionResult>
)

internal data class PhoneControlActionResult(val text: String, val imagePath: String? = null)
