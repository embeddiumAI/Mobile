package com.ero.eai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.coroutines.resume

class PhoneControlService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var panel: FrameLayout? = null
    private var expandedPanel: LinearLayout? = null
    private var collapsedIcon: TextView? = null
    private var statusText: TextView? = null
    private var stateBadge: TextView? = null
    private var instructionInput: EditText? = null
    private var approvalButton: TextView? = null
    private var instructionButton: TextView? = null
    private var stopButton: TextView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0
    private var dragging = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var pendingApproval: CompletableDeferred<Boolean>? = null
    private var cachedNodes: List<AccessibilityNodeInfo> = emptyList()
    private var cachedLabels: List<String> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        PhoneControlBridge.service = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        serviceScope.launch {
            PhoneControlBridge.commands.receiveAsFlow().collect { command ->
                try {
                    if (!PhoneControlBridge.active || PhoneControlBridge.stopped) error("Phone task stopped")
                    command.result.complete(runCommand(command))
                } catch (error: Exception) {
                    command.result.complete(PhoneControlActionResult("Action failed: ${error.message ?: "unknown error"}"))
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        PhoneControlBridge.stopTask()
    }

    override fun onDestroy() {
        pendingApproval?.complete(false)
        removePanel()
        if (PhoneControlBridge.service === this) PhoneControlBridge.service = null
        serviceScope.cancel()
        super.onDestroy()
    }

    fun showControlPanel(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showControlPanel(message) }
            return
        }
        val root = panel ?: createPanel().also { panel = it }
        statusText?.text = message
        stateBadge?.text = if (PhoneControlBridge.active) "ACTIVE" else "READY"
        instructionButton?.text = if (PhoneControlBridge.active) "Redirect" else "Start task"
        stopButton?.visibility = if (PhoneControlBridge.active || pendingApproval != null) android.view.View.VISIBLE else android.view.View.GONE
        if (root.parent == null) {
            windowParams = panelLayoutParams()
            runCatching { windowManager?.addView(root, windowParams) }
        }
    }

    fun denyPendingAction() {
        pendingApproval?.complete(false)
    }

    private fun createPanel(): FrameLayout {
        val root = FrameLayout(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(20, 20, 25))
                setCornerRadius(dp(16).toFloat())
                setStroke(dp(1), android.graphics.Color.rgb(57, 54, 67))
            }
            elevation = dp(10).toFloat()
            isFocusableInTouchMode = true
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mark = TextView(this).apply {
            text = "e"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(126, 94, 230)); setCornerRadius(dp(9).toFloat())
            }
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(26), dp(26)))
        val title = TextView(this).apply {
            text = "Phone agent"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(9), 0, 0, 0)
        }
        header.addView(title)
        stateBadge = TextView(this).apply {
            text = "READY"
            textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.rgb(178, 158, 255))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(43, 36, 61)); setCornerRadius(dp(9).toFloat())
            }
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        statusText = TextView(this).apply {
            text = "Ready for a task"
            textSize = 12f
            setTextColor(android.graphics.Color.rgb(182, 180, 191))
            setPadding(0, dp(9), 0, dp(10))
        }
        container.addView(header)
        header.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(stateBadge)
        val minimizeButton = smallIconButton("–") { setCollapsed(true) }
        header.addView(minimizeButton, LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(5) })
        val closeButton = smallIconButton("×") { closePanel() }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(3) })
        setDragHandle(header)
        setDragHandle(mark)
        setDragHandle(title)
        container.addView(statusText)
        val entryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        instructionInput = EditText(this).apply {
            hint = "What should I do?"
            textSize = 12f
            setSingleLine(true)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.rgb(143, 142, 152))
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(32, 32, 39)); cornerRadius = dp(11).toFloat()
            }
        }
        entryRow.addView(instructionInput, LinearLayout.LayoutParams(0, dp(38), 1f))
        instructionButton = button("Start task") {
            val instruction = instructionInput?.text?.toString().orEmpty()
            if (instruction.isBlank()) {
                showControlPanel("Enter a task or new instruction")
            } else {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(instructionInput?.windowToken, 0)
                instructionInput?.clearFocus()
                val submitted = PhoneControlBridge.submitInstruction(instruction)
                pendingApproval?.complete(false)
                instructionInput?.text?.clear()
                showControlPanel(when {
                    !submitted -> "Open eAI to start a new task"
                    PhoneControlBridge.active -> "Instruction sent to the agent"
                    else -> "Starting your task…"
                })
            }
        }
        entryRow.addView(instructionButton, LinearLayout.LayoutParams(dp(88), dp(38)).apply { leftMargin = dp(6) })
        container.addView(entryRow)
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        approvalButton = button("Allow this tap") { pendingApproval?.complete(true) }
        approvalButton?.visibility = android.view.View.GONE
        actionRow.addView(approvalButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
        stopButton = button("Stop task") { PhoneControlBridge.stopTask(); pendingApproval?.complete(false) }
        stopButton?.visibility = android.view.View.GONE
        actionRow.addView(stopButton, LinearLayout.LayoutParams(dp(84), dp(32)))
        actionRow.setPadding(0, dp(5), 0, 0)
        container.addView(actionRow)
        expandedPanel = container
        root.addView(container, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        collapsedIcon = TextView(this).apply {
            text = "e"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = GradientDrawable().apply { setColor(android.graphics.Color.rgb(126, 94, 230)); setCornerRadius(dp(17).toFloat()) }
            elevation = dp(10).toFloat()
            visibility = android.view.View.GONE
            setOnTouchListener { _, event -> handleDrag(event, expandOnTap = true) }
        }
        root.addView(collapsedIcon, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
        return root
    }

    private fun button(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 11f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(android.graphics.Color.WHITE)
        setPadding(dp(5), 0, dp(5), 0)
        background = GradientDrawable().apply {
            setColor(if (label == "Stop task") android.graphics.Color.rgb(54, 45, 52) else android.graphics.Color.rgb(108, 79, 204))
            setCornerRadius(dp(11).toFloat())
        }
        setOnClickListener { click() }
    }

    private fun smallIconButton(icon: String, click: () -> Unit) = TextView(this).apply {
        text = icon
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(android.graphics.Color.rgb(210, 207, 220))
        background = GradientDrawable().apply { setColor(android.graphics.Color.rgb(39, 38, 45)); setCornerRadius(dp(9).toFloat()) }
        setOnClickListener { click() }
    }

    private fun panelLayoutParams() = WindowManager.LayoutParams(
        dp(panelWidthDp()), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = dp(12)
        y = dp(72)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }

    private fun removePanel() {
        val root = panel ?: return
        runCatching { if (root.parent != null) windowManager?.removeView(root) }
        panel = null
        expandedPanel = null
        collapsedIcon = null
        statusText = null
        stateBadge = null
        instructionInput = null
        approvalButton = null
        instructionButton = null
        stopButton = null
        windowParams = null
    }

    private fun setCollapsed(value: Boolean) {
        collapsed = value
        expandedPanel?.visibility = if (value) android.view.View.GONE else android.view.View.VISIBLE
        collapsedIcon?.visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
        val root = panel ?: return
        val params = windowParams ?: return
        params.width = if (value) dp(48) else dp(panelWidthDp())
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        runCatching { windowManager?.updateViewLayout(root, params) }
    }

    private fun closePanel() {
        if (PhoneControlBridge.active) PhoneControlBridge.stopTask()
        removePanel()
    }

    private fun panelWidthDp(): Int = minOf(resources.configuration.screenWidthDp - 24, 320).coerceAtLeast(280)

    private fun setDragHandle(view: android.view.View) {
        view.setOnTouchListener { _, event -> handleDrag(event) }
    }

    private fun handleDrag(event: MotionEvent, expandOnTap: Boolean = false): Boolean {
        val params = windowParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWindowX = params.x
                dragStartWindowY = params.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) dragging = true
                if (dragging) {
                    val windowWidth = if (collapsed) dp(48) else dp(panelWidthDp())
                    val maxX = (resources.displayMetrics.widthPixels - windowWidth - dp(8)).coerceAtLeast(0)
                    params.x = (dragStartWindowX - dx.toInt()).coerceIn(0, maxX)
                    val windowHeight = if (collapsed) dp(48) else panel?.height?.takeIf { it > 0 } ?: dp(180)
                    val maxY = (resources.displayMetrics.heightPixels - windowHeight - dp(24)).coerceAtLeast(dp(24))
                    params.y = (dragStartWindowY + dy.toInt()).coerceIn(dp(24), maxY)
                    runCatching { windowManager?.updateViewLayout(panel, params) }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val didDrag = dragging
                dragging = false
                if (expandOnTap && event.actionMasked == MotionEvent.ACTION_UP && !didDrag) setCollapsed(false)
                return true
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private suspend fun runCommand(command: PhoneControlCommand): PhoneControlActionResult = when (command.name) {
        "open_app" -> PhoneControlActionResult(openApp(command.args.optString("name")))
        "screen" -> inspectScreenWithScreenshot()
        "tap" -> PhoneControlActionResult(tapElement(command.args.optInt("id", -1)))
        "tap_point" -> PhoneControlActionResult(tapPoint(command.args.optDouble("x", -1.0), command.args.optDouble("y", -1.0)))
        "swipe" -> PhoneControlActionResult(swipe(command.args))
        "type" -> PhoneControlActionResult(typeText(command.args.optInt("id", -1), command.args.optString("text").take(500)))
        "scroll" -> PhoneControlActionResult(scroll(command.args.optString("direction", "down")))
        "back" -> {
            PhoneControlActionResult(if (performGlobalAction(GLOBAL_ACTION_BACK)) "Pressed Back" else "Back action was unavailable")
        }
        "wait" -> {
            kotlinx.coroutines.delay(command.args.optLong("milliseconds", 700L).coerceIn(250L, 2500L))
            PhoneControlActionResult("Waited")
        }
        else -> PhoneControlActionResult("Unknown phone action")
    }

    private suspend fun inspectScreenWithScreenshot(): PhoneControlActionResult {
        val content = inspectScreen()
        val path = if (Build.VERSION.SDK_INT >= 30) captureScreenshot() else null
        val note = if (path == null) "\n(Screenshot is unavailable on this Android version; use the visible accessibility controls.)" else "\n(A screenshot of the current screen is attached.)"
        return PhoneControlActionResult(content + note, path)
    }

    private suspend fun captureScreenshot(): String? = withTimeoutOrNull(8_000L) {
        suspendCancellableCoroutine { continuation ->
            val executor = java.util.concurrent.Executor { command -> mainHandler.post(command) }
            try {
                takeScreenshot(0, executor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val path = runCatching {
                            val hardware: HardwareBuffer = result.hardwareBuffer
                            val source = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            hardware.close()
                            val bitmap = source ?: error("Android returned an empty screenshot")
                            val maxWidth = 768
                            val scaled = if (bitmap.width > maxWidth) {
                                val nextHeight = (bitmap.height * (maxWidth.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
                                Bitmap.createScaledBitmap(bitmap, maxWidth, nextHeight, true).also { if (it !== bitmap) bitmap.recycle() }
                            } else bitmap
                            val file = java.io.File(cacheDir, "phone-screen-${System.currentTimeMillis()}.jpg")
                            val saved = file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 76, it) }
                            scaled.recycle()
                            if (saved) file.absolutePath else null
                        }.getOrNull()
                        if (continuation.isActive) continuation.resume(path)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private suspend fun tapPoint(normalizedX: Double, normalizedY: Double): String {
        val point = gesturePoint(normalizedX, normalizedY)
        val path = Path().apply { moveTo(point.first, point.second); lineTo(point.first + 1f, point.second + 1f) }
        return dispatchAgentGesture(path, 90L, "Tapped screen at (${"%.2f".format(normalizedX)}, ${"%.2f".format(normalizedY)})")
    }

    private suspend fun swipe(args: JSONObject): String {
        val start = gesturePoint(args.optDouble("from_x", -1.0), args.optDouble("from_y", -1.0))
        val end = gesturePoint(args.optDouble("to_x", -1.0), args.optDouble("to_y", -1.0))
        val duration = args.optLong("duration_ms", 450L).coerceIn(120L, 2500L)
        val path = Path().apply { moveTo(start.first, start.second); lineTo(end.first, end.second) }
        return dispatchAgentGesture(path, duration, "Swiped across the screen")
    }

    private fun gesturePoint(x: Double, y: Double): Pair<Float, Float> {
        if (x !in 0.0..1.0 || y !in 0.0..1.0) error("Screen x and y must be normalized from 0.0 to 1.0")
        val metrics = resources.displayMetrics
        return ((x * (metrics.widthPixels - 1)).toFloat()) to ((y * (metrics.heightPixels - 1)).toFloat())
    }

    private suspend fun dispatchAgentGesture(path: Path, duration: Long, success: String): String = suspendCancellableCoroutine { continuation ->
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, duration)).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (continuation.isActive) continuation.resume(success)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (continuation.isActive) continuation.resume("The screen gesture was cancelled")
            }
        }, mainHandler)
        if (!dispatched && continuation.isActive) continuation.resume("Android could not dispatch the screen gesture")
    }

    private fun openApp(query: String): String {
        val requested = query.trim()
        if (requested.isBlank()) error("Name an app to open")
        val mainLauncher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(mainLauncher, 0)
            .map { it.activityInfo to it.loadLabel(packageManager).toString() }
            .filter { (_, label) -> label.contains(requested, ignoreCase = true) }
            .sortedBy { (_, label) -> if (label.equals(requested, ignoreCase = true)) 0 else 1 }
        val (activity, label) = matches.firstOrNull() ?: error("Could not find an installed app named ‘$requested’")
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(activity.packageName, activity.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return "Opened app: $label"
    }

    private fun inspectScreen(): String {
        val root = rootInActiveWindow ?: error("No active app screen is available")
        cachedNodes.forEach { runCatching { it.recycle() } }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val labels = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && nodes.size < 100) {
            val (node, depth) = queue.removeFirst()
            if (!node.isVisibleToUser) continue
            val isEditable = node.isEditable
            val label = when {
                isEditable -> node.contentDescription?.toString()?.takeIf(String::isNotBlank)
                    ?: (if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.takeIf(String::isNotBlank) else null)
                    ?: "text field"
                else -> node.text?.toString()?.takeIf(String::isNotBlank)
                    ?: node.contentDescription?.toString()?.takeIf(String::isNotBlank)
            }
            if (!label.isNullOrBlank() || node.isClickable || isEditable || node.isScrollable) {
                val id = nodes.size
                nodes += AccessibilityNodeInfo.obtain(node)
                labels += label.orEmpty()
                val properties = buildList {
                    if (node.isClickable) add("button")
                    if (isEditable) add(if (node.isPassword) "password field (protected)" else "text field")
                    if (node.isScrollable) add("scrollable")
                    if (!node.isEnabled) add("disabled")
                }.joinToString(", ")
                val visibleLabel = if (node.isPassword) "[protected password field]" else label.orEmpty().replace(Regex("\\s+"), " ").take(180)
                lines += "#$id ${"  ".repeat(depth.coerceAtMost(8))}${visibleLabel.ifBlank { "(unlabeled control)" }}${if (properties.isBlank()) "" else " [$properties]"}"
            }
            if (depth < 14) for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it to depth + 1) }
            }
        }
        cachedNodes = nodes
        cachedLabels = labels
        root.recycle()
        return if (lines.isEmpty()) "No readable controls found on this screen." else lines.joinToString("\n")
    }

    private suspend fun tapElement(id: Int): String {
        val node = cachedNodes.getOrNull(id) ?: error("That screen element is stale. Inspect the screen again first.")
        if (!node.refresh() || !node.isEnabled || !node.isVisibleToUser) error("That control is unavailable or changed. Inspect the screen again.")
        if (node.isPassword) error("The agent cannot interact with password fields")
        val refreshedLabel = if (node.isEditable) {
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)
                ?: (if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.takeIf(String::isNotBlank) else null)
                ?: "text field"
        } else {
            node.text?.toString()?.takeIf(String::isNotBlank)
                ?: node.contentDescription?.toString()?.takeIf(String::isNotBlank)
                ?: cachedLabels.getOrNull(id).orEmpty()
        }
        if (refreshedLabel != cachedLabels.getOrNull(id).orEmpty()) error("The screen changed. Inspect the screen again before tapping.")
        val label = refreshedLabel.ifBlank { "unlabeled control" }
        val sensitive = Regex("(?i)\\b(send|submit|post|publish|buy|purchase|pay|transfer|delete|remove|confirm|allow|grant|place order|sign out|log out|sign in|log in|save|accept|agree|enable|install)\\b").containsMatchIn(label)
        if (sensitive) {
            showControlPanel("Allow the agent to tap ‘${label.take(55)}’?")
            approvalButton?.visibility = android.view.View.VISIBLE
            val approval = CompletableDeferred<Boolean>()
            pendingApproval = approval
            val allowed = try { withTimeout(60_000) { approval.await() } } finally {
                pendingApproval = null
                approvalButton?.visibility = android.view.View.GONE
            }
            if (!allowed || PhoneControlBridge.stopped) error("The user did not approve this tap")
        }
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped element #$id: ${label.take(80)}"
            current = current.parent
        }
        error("That element did not accept a tap")
    }

    private fun typeText(id: Int, text: String): String {
        if (text.isBlank()) error("No text was provided")
        val node = cachedNodes.getOrNull(id) ?: error("That screen element is stale. Inspect the screen again first.")
        if (!node.refresh() || !node.isEditable || node.isPassword) error("The agent can only type into visible, non-password text fields")
        val label = cachedLabels.getOrNull(id).orEmpty()
        if (Regex("(?i)password|passcode|pin|credit.?card|card number|security code|cvv|one.?time|verification code|otp|bank account").containsMatchIn(label)) {
            error("For safety, the agent cannot type into credential, payment, or verification fields")
        }
        if (!node.isEnabled || !node.isVisibleToUser) error("That text field is unavailable")
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) error("Android rejected text entry")
        return "Entered text in field #$id"
    }

    private fun scroll(direction: String): String {
        if (direction !in setOf("up", "down")) error("Direction must be up or down")
        val root = rootInActiveWindow ?: error("No active app screen is available")
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.isVisibleToUser && node.isScrollable) {
                val action = if (direction == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (node.performAction(action)) { root.recycle(); return "Scrolled $direction" }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
        }
        root.recycle()
        error("No scrollable area was available")
    }

    fun clearPendingCommands() {
        while (true) {
            val command = PhoneControlBridge.commands.tryReceive().getOrNull() ?: break
            command.result.complete(PhoneControlActionResult("Phone task stopped"))
        }
        cachedNodes.forEach { runCatching { it.recycle() } }
        cachedNodes = emptyList()
        cachedLabels = emptyList()
    }
}
