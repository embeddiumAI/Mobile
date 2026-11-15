package com.ero.eai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import android.graphics.BitmapFactory
import android.content.Intent
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Bg = Color(0xFF101014)
private val Surface = Color(0xFF1A1A20)
private val SurfaceHi = Color(0xFF24242C)
private val Ink = Color(0xFFF3F2F8)
private val Muted = Color(0xFF9B9AA6)
private val Accent = Color(0xFF9A82F8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 16, 20)
        window.navigationBarColor = android.graphics.Color.rgb(16, 16, 20)
        setContent { EaiApp() }
    }
}

@Composable
private fun EaiApp() {
    val context = LocalContext.current
    val store = remember { ChatStore(context) }
    val localModels = remember { LocalModelManager(context) }
    val localDownload by localModels.download.collectAsState()
    val deviceCapacity = remember { localModels.deviceCapacity() }
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsState()
    var prompt by remember { mutableStateOf("") }
    var pendingImage by remember { mutableStateOf<String?>(null) }
    var pendingTextFile by remember { mutableStateOf<String?>(null) }
    var pendingFileName by remember { mutableStateOf("") }
    var attachmentNotice by remember { mutableStateOf<String?>(null) }
    var conversationQuery by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var editValue by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var host by remember { mutableStateOf(state.host) }
    var provider by remember { mutableStateOf(state.provider) }
    var apiKey by remember { mutableStateOf(state.apiKey) }
    var settingsModel by remember { mutableStateOf(state.model) }
    var systemPrompt by remember { mutableStateOf(state.systemPrompt) }
    var webSearch by remember { mutableStateOf(state.webSearch) }
    var agentMode by remember { mutableStateOf(false) }
    var phoneAgentMode by remember { mutableStateOf(false) }
    var agentMenu by remember { mutableStateOf(false) }
    var showPhoneSetup by remember { mutableStateOf(false) }
    var showPhoneDisclosure by remember { mutableStateOf(false) }
    DisposableEffect(store) {
        PhoneControlBridge.onStop = { scope.launch { store.stop() } }
        PhoneControlBridge.onInstruction = { instruction ->
            phoneAgentMode = true
            agentMode = false
            scope.launch { store.send(instruction, null, false, true) }
        }
        onDispose { PhoneControlBridge.onStop = null; PhoneControlBridge.onInstruction = null }
    }
    var thinking by remember { mutableStateOf(state.thinking) }
    var maxTokens by remember { mutableStateOf(state.maxTokens.toString()) }
    var temperature by remember { mutableFloatStateOf(state.temperature) }
    var searxHost by remember { mutableStateOf(state.searxHost) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
            if (mime.startsWith("image/")) {
                if (state.provider == ApiProvider.ON_DEVICE) {
                    attachmentNotice = "Image attachments need a connected vision-capable model. Text files work with the on-phone model."
                } else {
                    val file = java.io.File(context.filesDir, "attach-${System.currentTimeMillis()}.jpg")
                    val saved = runCatching {
                        val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return@runCatching false
                        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, it) }.also { bitmap.recycle() }
                    }.getOrDefault(false)
                    if (saved) {
                        pendingImage = file.absolutePath
                        pendingTextFile = null
                        pendingFileName = name
                    } else attachmentNotice = "eAI couldn't read that image. Try choosing another image."
                }
            } else {
                val extension = name.substringAfterLast('.', "").lowercase()
                val textFile = mime.startsWith("text/") || mime in setOf("application/json", "application/xml") ||
                    extension in setOf("txt", "md", "csv", "json", "xml", "yaml", "yml", "html", "css", "js", "kt", "java", "py", "rs", "c", "h", "cpp", "sh", "log")
                if (!textFile) {
                    attachmentNotice = "Choose an image or a text, Markdown, code, JSON, or CSV file."
                } else {
                    val imported = runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                            val result = StringBuilder()
                            val buffer = CharArray(4096)
                            while (result.length < 50_000) {
                                val count = reader.read(buffer, 0, minOf(buffer.size, 50_000 - result.length))
                                if (count < 0) break
                                result.append(buffer, 0, count)
                            }
                            result.toString()
                        } ?: error("Unable to open the selected file")
                    }.getOrElse {
                        attachmentNotice = "eAI couldn't read that file. Try another text file."
                        null
                    }
                    if (imported != null) {
                        pendingTextFile = imported
                        pendingImage = null
                        pendingFileName = name
                    }
                }
            }
        }
    }
    val messageToSend = buildString {
        append(prompt.trim())
        pendingTextFile?.let { fileText ->
            if (isNotEmpty()) append("\n\n")
            append("Attached file: ").append(pendingFileName).append("\n\n").append(fileText)
        }
    }

    LaunchedEffect(state.messages.size, state.streamingText) {
        val count = state.messages.size + if (state.streamingText.isNotEmpty()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    BackHandler(showSettings) { showSettings = false }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, background = Bg, surface = Surface,
        onBackground = Ink, onSurface = Ink
    )) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Bg, drawerContentColor = Ink) {
                    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(18.dp)) {
                        Text("eAI", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = { scope.launch { store.newChat(); drawerState.close() }; showSettings = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("＋   New chat")
                        }
                        Text("Export chats", color = Ink, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickable { shareText(context, store.conversationJson()) }.padding(vertical = 12.dp))
                        Spacer(Modifier.height(20.dp))
                        Text("RECENT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = conversationQuery, onValueChange = { conversationQuery = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search chats") },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = SurfaceHi))
                        state.sessions.filter { it.title.contains(conversationQuery, true) || it.messages.any { m -> m.content.contains(conversationQuery, true) } }.forEach { session ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { scope.launch { store.selectChat(session.id); drawerState.close() }; showSettings = false }
                                .padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(session.title, color = if (session.id == state.activeId) Ink else Muted,
                                    modifier = Modifier.weight(1f), maxLines = 1, fontSize = 14.sp)
                                Text("×", color = Muted, fontSize = 20.sp, modifier = Modifier.clickable {
                                    scope.launch { store.deleteChat(session.id) }
                                }.padding(horizontal = 8.dp))
                                Text("✎", color = Muted, fontSize = 17.sp, modifier = Modifier.clickable {
                                    renameTarget = session; renameValue = session.title
                                }.padding(horizontal = 8.dp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Settings & connection", color = Ink, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                showSettings = true; scope.launch { drawerState.close() }
                            }.padding(12.dp))
                    }
                }
            }
        ) {
        Column(Modifier.fillMaxSize().background(Bg).imePadding().windowInsetsPadding(WindowInsets.navigationBars)) {
            TopBar(
                title = if (showSettings) "Settings" else state.activeTitle ?: "eAI",
                onBack = { if (showSettings) showSettings = false else showSettings = true },
                showBack = showSettings,
                onNew = { scope.launch { store.newChat() }; showSettings = false },
                onMenu = { if (showSettings) showSettings = false else scope.launch { drawerState.open() } }
            )
            if (showSettings) {
                SettingsScreen(
                    state = state,
                    host = host,
                    onHost = { host = it },
                    provider = provider,
                    onProvider = { provider = it },
                    providerMenu = providerMenu,
                    onProviderMenu = { providerMenu = it },
                    apiKey = apiKey,
                    onApiKey = { apiKey = it },
                    settingsModel = settingsModel,
                    onSettingsModel = { settingsModel = it },
                    systemPrompt = systemPrompt,
                    onSystemPrompt = { systemPrompt = it },
                    webSearch = webSearch,
                    onWebSearch = { webSearch = it },
                    thinking = thinking,
                    onThinking = { thinking = it },
                    maxTokens = maxTokens,
                    onMaxTokens = { maxTokens = it },
                    temperature = temperature,
                    onTemperature = { temperature = it },
                    searxHost = searxHost,
                    onSearxHost = { searxHost = it },
                    onSave = {
                        scope.launch {
                            store.saveSettings(host, provider, apiKey, settingsModel, systemPrompt, webSearch, thinking,
                                maxTokens.toIntOrNull() ?: 4096, temperature, searxHost)
                            store.refreshModels()
                        }
                        showSettings = false
                    },
                    onRefresh = {
                        scope.launch {
                            store.saveSettings(host, provider, apiKey, settingsModel, systemPrompt, webSearch, thinking,
                                maxTokens.toIntOrNull() ?: 4096, temperature, searxHost)
                            store.refreshModels()
                        }
                    },
                    deviceCapacity = deviceCapacity,
                    localModels = LocalModelManager.CATALOG,
                    isLocalInstalled = localModels::isInstalled,
                    localDownload = localDownload,
                    onDownloadLocal = { model -> scope.launch { localModels.install(model); localModels.modelPath(model)?.let { store.selectLocalModel(it) } } },
                    onRemoveLocal = { model -> scope.launch { val selected = state.localModelPath == localModels.modelPath(model); localModels.remove(model); if (selected) store.selectLocalModel(null) } },
                    onUseLocal = { model -> localModels.modelPath(model)?.let { path -> scope.launch { store.selectLocalModel(path) } } }
                )
            } else {
                if (renameTarget != null) AlertDialog(
                    onDismissRequest = { renameTarget = null },
                    title = { Text("Rename conversation") },
                    text = { OutlinedTextField(value = renameValue, onValueChange = { renameValue = it }, singleLine = true, label = { Text("Title") }) },
                    confirmButton = { TextButton(onClick = { val id = renameTarget?.id; if (id != null) scope.launch { store.renameChat(id, renameValue) }; renameTarget = null }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
                )
                if (editTarget != null) AlertDialog(
                    onDismissRequest = { editTarget = null }, title = { Text("Edit message") },
                    text = { OutlinedTextField(value = editValue, onValueChange = { editValue = it }, minLines = 3) },
                    confirmButton = { TextButton(onClick = { val id = editTarget?.id; if (id != null) scope.launch { store.editMessage(id, editValue) }; editTarget = null }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel") } }
                )
                if (attachmentNotice != null) AlertDialog(
                    onDismissRequest = { attachmentNotice = null },
                    title = { Text("Attachment") },
                    text = { Text(attachmentNotice.orEmpty()) },
                    confirmButton = { TextButton(onClick = { attachmentNotice = null }) { Text("OK") } }
                )
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    if (agentMode) WebGoalBrowserPane(
                        url = state.browserUrl,
                        activity = state.agentActivity.ifBlank { "Enter a goal to begin" },
                        modifier = Modifier.fillMaxWidth().weight(0.48f)
                    )
                    if (agentMode) {
                        Column(Modifier.fillMaxWidth().weight(0.52f)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("AGENT ACTIVITY", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (state.loading) Text("RUNNING", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(state.agentActivity.ifBlank { "The agent’s steps and conversation appear here." },
                                color = Muted, fontSize = 12.sp, maxLines = 2,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp))
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                ConversationTimeline(state, listState, context,
                                    onPrompt = { prompt = it; focus.requestFocus() },
                                    onEdit = { editTarget = it; editValue = it.content },
                                    onRegenerate = { scope.launch { store.regenerate() } })
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            ConversationTimeline(state, listState, context,
                                onPrompt = { prompt = it; focus.requestFocus() },
                                onEdit = { editTarget = it; editValue = it.content },
                                onRegenerate = { scope.launch { store.regenerate() } })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Text("${state.model}  ▾", color = Muted, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { modelMenu = true }.padding(horizontal = 10.dp, vertical = 6.dp))
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }, modifier = Modifier.background(Surface)) {
                            state.models.forEach { item -> DropdownMenuItem(text = { Text(item, color = Ink) }, onClick = {
                                modelMenu = false; settingsModel = item; scope.launch { store.setModel(item) }
                            }) }
                            if (state.models.isEmpty()) DropdownMenuItem(text = { Text("No models loaded", color = Muted) }, onClick = { modelMenu = false })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        Text(if (phoneAgentMode) "✓ Phone agent ▾" else if (agentMode) "✓ Web goal ▾" else "Agents ▾",
                            color = if (phoneAgentMode || agentMode) Accent else Muted, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = !state.loading) { agentMenu = true }
                                .background(if (phoneAgentMode || agentMode) Color(0x332C2248) else Color.Transparent).padding(horizontal = 11.dp, vertical = 7.dp))
                        DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }, modifier = Modifier.background(Surface)) {
                            DropdownMenuItem(text = { Text("Normal chat", color = Ink) }, onClick = { agentMode = false; phoneAgentMode = false; agentMenu = false })
                            DropdownMenuItem(text = { Text("Web goal agent", color = Ink) }, onClick = { agentMode = true; phoneAgentMode = false; agentMenu = false })
                            DropdownMenuItem(text = { Text("Control this phone…", color = Ink) }, onClick = {
                                agentMenu = false
                                if (PhoneControlBridge.isEnabled(context)) showPhoneDisclosure = true else showPhoneSetup = true
                            })
                        }
                    }
                }
                if (showPhoneSetup) AlertDialog(
                    onDismissRequest = { showPhoneSetup = false },
                    title = { Text("Enable phone control") },
                    text = { Text("Android requires you to enable the eAI Phone Control accessibility service yourself. While a task runs, it can read screen text, take screenshots, and interact with apps. The floating panel has Stop and new-instruction controls. Screen text and screenshots are sent to your selected AI provider, which may be remote. Avoid sensitive screens.") },
                    confirmButton = { TextButton(onClick = { showPhoneSetup = false; context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open accessibility settings") } },
                    dismissButton = { TextButton(onClick = { showPhoneSetup = false }) { Text("Cancel") } }
                )
                if (showPhoneDisclosure) AlertDialog(
                    onDismissRequest = { showPhoneDisclosure = false },
                    title = { Text("Start phone control?") },
                    text = { Text("The agent can read visible text, capture screenshots, and tap, swipe, or type in apps. Screen text and screenshots are sent to ${state.provider.title}, which may be remote. The floating panel can stop or redirect it. Password fields are blocked. Avoid sensitive screens and stop it before payments, purchases, messages, or account changes. Continue only if you trust the provider and current screen.") },
                    confirmButton = { TextButton(onClick = { showPhoneDisclosure = false; phoneAgentMode = true; agentMode = false }) { Text("I understand") } },
                    dismissButton = { TextButton(onClick = { showPhoneDisclosure = false }) { Text("Cancel") } }
                )
                if (phoneAgentMode && !state.loading) {
                    Text("Phone agent · keep the floating Stop panel visible", color = Muted, fontSize = 10.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 1.dp))
                }
                if (state.status.isNotBlank() && (state.loading || (state.status != "Task complete" && state.status != "Phone task complete" && state.status != "Set your API connection in settings"))) {
                    Text(state.status, color = Muted, fontSize = 11.sp, maxLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 1.dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(26.dp)).background(SurfaceHi).padding(start = 16.dp, end = 9.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    if (pendingImage != null || pendingTextFile != null) {
                        Text("${pendingFileName.ifBlank { "Attachment" }}  ×", color = Accent, fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.clickable { pendingImage = null; pendingTextFile = null; pendingFileName = "" }.padding(end = 8.dp))
                    } else {
                        Box(Modifier.size(34.dp).clip(CircleShape).clickable(enabled = !state.loading) {
                            attachmentPicker.launch(arrayOf("image/*", "text/*", "application/json", "application/xml"))
                        }, contentAlignment = Alignment.Center) {
                            AttachmentIcon(Modifier.size(21.dp), Muted)
                        }
                    }
                    BasicTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 28.dp).focusRequester(focus),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 16.sp, lineHeight = 22.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if ((messageToSend.isNotBlank() || pendingImage != null) && !state.loading) {
                                val text = messageToSend; prompt = ""; keyboard?.hide()
                                val image = pendingImage; pendingImage = null; pendingTextFile = null; pendingFileName = ""
                                scope.launch { store.send(text, image, agentMode, phoneAgentMode) }
                            }
                        }),
                        decorationBox = { inner ->
                            Box {
                                if (prompt.isEmpty()) Text("Message eAI…", color = Muted, fontSize = 16.sp)
                                inner()
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (state.loading) Color(0xFF3A354D) else Accent)
                            .clickable(enabled = state.loading || messageToSend.isNotBlank() || pendingImage != null) {
                                if (state.loading) { if (phoneAgentMode) PhoneControlBridge.stopTask() else scope.launch { store.stop() } }
                                else if (messageToSend.isNotBlank() || pendingImage != null) {
                                    val text = messageToSend; prompt = ""; keyboard?.hide()
                                    val image = pendingImage; pendingImage = null; pendingTextFile = null; pendingFileName = ""
                                    scope.launch { store.send(text, image, agentMode, phoneAgentMode) }
                                }
                            }, contentAlignment = Alignment.Center
                    ) { SendActionIcon(state.loading) }
                }
            }
        }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, showBack: Boolean, onNew: () -> Unit, onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (showBack) "‹" else "☰", color = Ink, fontSize = 24.sp,
            modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onMenu).wrapContentSize(Alignment.Center))
        Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1)
        Text("＋", color = Ink, fontSize = 25.sp,
            modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onNew).wrapContentSize(Alignment.Center))
    }
}

@Composable
private fun Welcome(onPrompt: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Text("A better way to think.", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Your private AI, connected to your own models.", color = Muted, fontSize = 15.sp)
        Spacer(Modifier.height(24.dp))
        listOf("Explain a concept simply", "Help me brainstorm ideas", "Summarize some text").forEach { suggestion ->
            Text(suggestion + "  ›", color = Ink, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(Surface).clickable {
                    onPrompt(when (suggestion) {
                        "Explain a concept simply" -> "Explain this concept simply: "
                        "Help me brainstorm ideas" -> "Help me brainstorm ideas for "
                        else -> "Summarize this text: "
                    })
                }.padding(15.dp))
        }
    }
}

@Composable
private fun AttachmentIcon(modifier: Modifier = Modifier, color: Color = Muted) {
    Canvas(modifier) {
        val scale = size.minDimension / 24f
        val p = Path().apply {
            moveTo(8.3f * scale, 12.2f * scale)
            lineTo(15.3f * scale, 5.2f * scale)
            cubicTo(18.4f * scale, 2.1f * scale, 23.1f * scale, 6.8f * scale, 20f * scale, 9.9f * scale)
            lineTo(10.8f * scale, 19.1f * scale)
            cubicTo(7.7f * scale, 22.2f * scale, 3f * scale, 17.5f * scale, 6.1f * scale, 14.4f * scale)
            lineTo(15.3f * scale, 5.2f * scale)
            cubicTo(17.2f * scale, 3.3f * scale, 20.1f * scale, 6.2f * scale, 18.2f * scale, 8.1f * scale)
            lineTo(9.4f * scale, 16.9f * scale)
            cubicTo(8.4f * scale, 17.9f * scale, 6.9f * scale, 16.4f * scale, 7.9f * scale, 15.4f * scale)
        }
        drawPath(p, color, style = Stroke(width = 1.9f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun SendActionIcon(stopping: Boolean) {
    Canvas(Modifier.size(21.dp)) {
        if (stopping) {
            drawRoundRect(
                Color.White,
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.3f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.4f, size.height * 0.4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.04f)
            )
        } else {
            val w = size.width
            val h = size.height
            val p = Path().apply {
                moveTo(w * 0.08f, h * 0.08f)
                lineTo(w * 0.94f, h * 0.5f)
                lineTo(w * 0.08f, h * 0.92f)
                lineTo(w * 0.08f, h * 0.59f)
                lineTo(w * 0.67f, h * 0.5f)
                lineTo(w * 0.08f, h * 0.41f)
                close()
            }
            drawPath(p, Color.White)
        }
    }
}

@Composable
private fun ConversationTimeline(
    state: ChatState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    context: android.content.Context,
    onPrompt: (String) -> Unit,
    onEdit: (ChatMessage) -> Unit,
    onRegenerate: () -> Unit
) {
    if (state.messages.isEmpty() && state.streamingText.isEmpty()) {
        Welcome(onPrompt)
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message,
                    onCopy = { copyToClipboard(context, message.content) },
                    onEdit = { onEdit(message) },
                    onExport = { shareText(context, message.content) },
                    onRegenerate = onRegenerate)
            }
            if (state.streamingText.isNotEmpty()) {
                item { MessageBubble(ChatMessage("streaming", "assistant", state.streamingText), {}) }
            }
            if (state.loading && state.streamingText.isEmpty()) item { ThinkingIndicator() }
        }
    }
}

@Composable
private fun WebGoalBrowserPane(url: String?, activity: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val browser = remember(context) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(17, 17, 22))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val target = request.url
                    val host = target.host?.lowercase().orEmpty()
                    if (target.scheme !in setOf("http", "https") || host.isBlank() || host == "localhost" ||
                        host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")) return true
                    return false
                }
            }
        }
    }
    Column(modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        .clip(RoundedCornerShape(18.dp)).background(Surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 12.dp, top = 9.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE BROWSER", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() } ?: "Ready",
                color = Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text("●", color = if (url != null) Color(0xFF83D49A) else Muted, fontSize = 9.sp)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 5.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF111116))) {
            AndroidView(
                factory = { browser },
                modifier = Modifier.fillMaxSize()
            )
            if (url == null) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("◉", color = Accent, fontSize = 24.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Pages the agent visits appear here", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(activity, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        Text(activity, color = Muted, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 7.dp))
    }
    LaunchedEffect(url, browser) {
        if (url != null && browser.url != url) browser.loadUrl(url)
    }
    DisposableEffect(browser) {
        onDispose {
            browser.stopLoading()
            browser.destroy()
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onCopy: () -> Unit = {}, onEdit: () -> Unit = {}, onExport: () -> Unit = {}, onRegenerate: () -> Unit = {}) {
    val user = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(if (user) "You" else "eAI", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp))
        if (message.imagePath != null) {
            runCatching { BitmapFactory.decodeFile(message.imagePath)?.asImageBitmap() }.getOrNull()?.let { image ->
                Image(image, contentDescription = "Attached image", modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 220.dp).clip(RoundedCornerShape(14.dp)))
            }
        }
        if (message.content.isNotBlank()) MarkdownBody(message.content, user)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
            Text("Copy", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onCopy).padding(3.dp))
            if (user) Text("Edit", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onEdit).padding(3.dp))
            if (!user && message.id != "streaming") Text("Regenerate", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onRegenerate).padding(3.dp))
            Text("Share", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onExport).padding(3.dp))
        }
    }
}

@Composable
private fun MarkdownBody(source: String, user: Boolean) {
    val segments = remember(source) { parseMarkdownSegments(source) }
    Column(Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(if (user) 20.dp else 8.dp, 20.dp, 20.dp, 20.dp))
        .background(if (user) Color(0xFF34313D) else Surface).padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            if (segment is MarkdownSegment.Code) {
                CodeBlock(segment.language, segment.code)
            } else if (segment is MarkdownSegment.Text) {
                segment.lines.forEachIndexed { index, raw ->
                    val line = raw.trimEnd()
                    val text = buildAnnotatedString {
                        when {
                            line.startsWith("### ") -> withStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)) { append(line.removePrefix("### ")) }
                            line.startsWith("## ") -> withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)) { append(line.removePrefix("## ")) }
                            line.startsWith("# ") -> withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)) { append(line.removePrefix("# ")) }
                            line.startsWith("- ") || line.startsWith("* ") -> { append("•  "); appendInlineMarkdown(this, line.drop(2)) }
                            line.startsWith("> ") -> withStyle(SpanStyle(color = Muted)) { append(line.drop(2)) }
                            else -> appendInlineMarkdown(this, line)
                        }
                    }
                    Text(text, color = Ink, fontSize = 15.sp, lineHeight = 23.sp,
                        modifier = Modifier.then(if (user) Modifier else Modifier.fillMaxWidth())
                            .padding(top = if (index == 0) 0.dp else 1.dp))
                }
            }
        }
    }
}

private sealed interface MarkdownSegment {
    data class Text(val lines: List<String>) : MarkdownSegment
    data class Code(val language: String, val code: String) : MarkdownSegment
}

private fun parseMarkdownSegments(source: String): List<MarkdownSegment> {
    val result = mutableListOf<MarkdownSegment>()
    val textLines = mutableListOf<String>()
    val codeLines = mutableListOf<String>()
    var inCode = false
    var language = ""
    fun flushText() { if (textLines.isNotEmpty()) { result += MarkdownSegment.Text(textLines.toList()); textLines.clear() } }
    source.lines().forEach { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (!inCode) {
                flushText()
                language = trimmed.removePrefix("```").trim().substringBefore(' ')
                inCode = true
            } else {
                result += MarkdownSegment.Code(language, codeLines.joinToString("\n").trimEnd('\n'))
                codeLines.clear()
                inCode = false
            }
        } else if (inCode) codeLines += line else textLines += line
    }
    if (inCode) result += MarkdownSegment.Code(language, codeLines.joinToString("\n").trimEnd('\n'))
    flushText()
    return result
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF111116))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF25252D)).padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(language.ifBlank { "code" }, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(code)) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("Copy code", color = Accent, fontSize = 11.sp)
            }
        }
        Text(code, color = Color(0xFFE6E4ED), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), softWrap = false)
    }
}

private fun appendInlineMarkdown(builder: androidx.compose.ui.text.AnnotatedString.Builder, line: String) {
    val pattern = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`([^`]+)`|\\[(.+?)\\]\\((https?://[^)]+)\\)")
    var cursor = 0
    pattern.findAll(line).forEach { match ->
        if (match.range.first > cursor) builder.append(line.substring(cursor, match.range.first))
        when {
            match.groups[1] != null -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groups[1]!!.value) }
            match.groups[2] != null -> builder.withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(match.groups[2]!!.value) }
            match.groups[3] != null -> builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF111116))) { append(match.groups[3]!!.value) }
            else -> builder.withStyle(SpanStyle(color = Accent, textDecoration = TextDecoration.Underline)) { append(match.groups[4]!!.value) }
        }
        cursor = match.range.last + 1
    }
    if (cursor < line.length) builder.append(line.substring(cursor))
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("eAI response", text))
}

private fun shareText(context: android.content.Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, "Share from eAI"))
}

@Composable
private fun ThinkingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp)); Text("Thinking…", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun SettingsScreen(
    state: ChatState, host: String, onHost: (String) -> Unit,
    provider: ApiProvider, onProvider: (ApiProvider) -> Unit, providerMenu: Boolean, onProviderMenu: (Boolean) -> Unit,
    apiKey: String, onApiKey: (String) -> Unit, settingsModel: String, onSettingsModel: (String) -> Unit,
    systemPrompt: String, onSystemPrompt: (String) -> Unit, webSearch: Boolean, onWebSearch: (Boolean) -> Unit,
    thinking: Boolean, onThinking: (Boolean) -> Unit, maxTokens: String, onMaxTokens: (String) -> Unit,
    temperature: Float, onTemperature: (Float) -> Unit,
    searxHost: String, onSearxHost: (String) -> Unit,
    onSave: () -> Unit, onRefresh: () -> Unit,
    deviceCapacity: LocalDeviceCapacity, localModels: List<LocalModelSpec>, isLocalInstalled: (LocalModelSpec) -> Boolean,
    localDownload: LocalModelDownload?, onDownloadLocal: (LocalModelSpec) -> Unit,
    onRemoveLocal: (LocalModelSpec) -> Unit, onUseLocal: (LocalModelSpec) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Settings", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Configure your provider and chat behavior. Settings are saved on this device.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Text("PROVIDER", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Box {
            OutlinedButton(onClick = { onProviderMenu(true) }, modifier = Modifier.fillMaxWidth()) {
                Text(provider.title, color = Ink, modifier = Modifier.weight(1f)); Text("▾", color = Muted)
            }
            DropdownMenu(expanded = providerMenu, onDismissRequest = { onProviderMenu(false) }) {
                ApiProvider.entries.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { onProvider(item); onProviderMenu(false) }) }
            }
        }
        if (provider == ApiProvider.ON_DEVICE) {
            Spacer(Modifier.height(16.dp))
            Text("RUN A MODEL ON THIS PHONE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val totalRamGb = deviceCapacity.totalRamBytes / (1024.0 * 1024 * 1024)
            val freeStorageMb = deviceCapacity.freeStorageBytes / 1_000_000
            Text("This phone: ${"%.1f".format(totalRamGb)} GB RAM · ${freeStorageMb} MB free storage", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            localModels.forEach { model ->
                val installed = isLocalInstalled(model)
                val download = localDownload?.takeIf { it.modelId == model.id }
                val ramReady = deviceCapacity.totalRamBytes >= model.minRamBytes
                val storageReady = deviceCapacity.freeStorageBytes >= model.minFreeBytes
                val recommended = ramReady && storageReady
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Surface).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (recommended) Text("Recommended", color = Accent, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("${model.sizeLabel} · text only", color = Muted, fontSize = 12.sp)
                    Text("License: ${model.license}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    Text(model.description, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                    if (!ramReady) Text("May not run well: needs at least ${model.minRamBytes / (1024 * 1024 * 1024)} GB RAM.", color = Color(0xFFFFC078), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    if (!storageReady && !installed) Text("Not enough free space to download; free more storage first.", color = Color(0xFFFFC078), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    if (download != null && download.error == null) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth(), color = Accent)
                        Text(if (download.total > 0) "${download.downloaded / 1_000_000} / ${download.total / 1_000_000} MB" else "Starting download…", color = Muted, fontSize = 12.sp)
                    }
                    if (download?.error != null) Text(download.error, color = Color(0xFFFF8A80), fontSize = 12.sp)
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!installed && download == null) Button(onClick = { onDownloadLocal(model) }, enabled = storageReady) { Text("Download") }
                        if (download?.error != null) Button(onClick = { onDownloadLocal(model) }, enabled = storageReady) { Text("Retry") }
                        if (installed) {
                            Button(onClick = { onUseLocal(model) }) { Text("Use on this phone") }
                            OutlinedButton(onClick = { onRemoveLocal(model) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
        if (provider != ApiProvider.ON_DEVICE) {
        Spacer(Modifier.height(12.dp))
        Text(if (provider == ApiProvider.OLLAMA) "OLLAMA SERVER URL" else "API BASE URL", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(value = host, onValueChange = onHost, modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(if (provider == ApiProvider.OLLAMA) "http://192.168.1.20:11434" else "https://api.example.com/v1") },
            supportingText = { Text(if (provider == ApiProvider.OLLAMA) "Use the computer's LAN address." else "OpenAI compatible endpoints use /chat/completions and /models." ) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = SurfaceHi))
        if (provider != ApiProvider.OLLAMA) {
            Spacer(Modifier.height(6.dp))
            Text("API KEY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = apiKey, onValueChange = onApiKey, modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                placeholder = { Text("Paste your API key") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = SurfaceHi))
        }
        Spacer(Modifier.height(8.dp))
        Text("MODEL", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = settingsModel, onValueChange = onSettingsModel, modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Model ID") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = SurfaceHi))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text("Load models") }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Save settings") }
        }
        Text(state.status, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        if (state.models.isNotEmpty()) {
            Text("Available models", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            state.models.take(12).forEach { model ->
                Text(model, color = if (model == settingsModel) Accent else Ink, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { onSettingsModel(model) }.padding(vertical = 5.dp))
            }
        }
        HorizontalDivider(color = SurfaceHi, modifier = Modifier.padding(vertical = 12.dp))
        Text("CHAT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = maxTokens, onValueChange = { onMaxTokens(it.filter(Char::isDigit).take(5)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Maximum response tokens") },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = SurfaceHi))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Thinking mode", color = Ink, modifier = Modifier.weight(1f)); Switch(checked = thinking, onCheckedChange = onThinking)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Web search preference", color = Ink, modifier = Modifier.weight(1f)); Switch(checked = webSearch, onCheckedChange = onWebSearch)
        }
        if (webSearch) {
            OutlinedTextField(value = searxHost, onValueChange = onSearxHost, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("SearXNG search URL") }, placeholder = { Text("http://192.168.1.20:8080") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = SurfaceHi))
        }
        Text("Response creativity · ${"%.1f".format(temperature)}", color = Muted, fontSize = 12.sp)
        Slider(value = temperature, onValueChange = onTemperature, valueRange = 0f..2f)
        Text("System instructions", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = systemPrompt, onValueChange = onSystemPrompt, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            placeholder = { Text("Set instructions that should guide every reply") }, minLines = 3,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = SurfaceHi))
        Spacer(Modifier.height(16.dp))
        Text("Your API key and settings are stored in this app's private preferences on this device.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(20.dp))
        }
    }
}
