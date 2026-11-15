package com.ero.eai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
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
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsState()
    var prompt by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var host by remember { mutableStateOf(state.host) }
    var provider by remember { mutableStateOf(state.provider) }
    var apiKey by remember { mutableStateOf(state.apiKey) }
    var settingsModel by remember { mutableStateOf(state.model) }
    var systemPrompt by remember { mutableStateOf(state.systemPrompt) }
    var webSearch by remember { mutableStateOf(state.webSearch) }
    var thinking by remember { mutableStateOf(state.thinking) }
    var maxTokens by remember { mutableStateOf(state.maxTokens.toString()) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

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
                        Spacer(Modifier.height(20.dp))
                        Text("RECENT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        state.sessions.forEach { session ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { scope.launch { store.selectChat(session.id); drawerState.close() }; showSettings = false }
                                .padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(session.title, color = if (session.id == state.activeId) Ink else Muted,
                                    modifier = Modifier.weight(1f), maxLines = 1, fontSize = 14.sp)
                                Text("×", color = Muted, fontSize = 20.sp, modifier = Modifier.clickable {
                                    scope.launch { store.deleteChat(session.id) }
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
                    onSave = {
                        scope.launch {
                            store.saveSettings(host, provider, apiKey, settingsModel, systemPrompt, webSearch, thinking,
                                maxTokens.toIntOrNull() ?: 4096)
                            store.refreshModels()
                        }
                        showSettings = false
                    },
                    onRefresh = {
                        scope.launch {
                            store.saveSettings(host, provider, apiKey, settingsModel, systemPrompt, webSearch, thinking,
                                maxTokens.toIntOrNull() ?: 4096)
                            store.refreshModels()
                        }
                    }
                )
            } else {
                Box(Modifier.weight(1f)) {
                    if (state.messages.isEmpty() && state.streamingText.isEmpty()) {
                        Welcome(onPrompt = { prompt = it; focus.requestFocus() })
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            items(state.messages, key = { it.id }) { MessageBubble(it) }
                            if (state.streamingText.isNotEmpty()) {
                                item { MessageBubble(ChatMessage("streaming", "assistant", state.streamingText)) }
                            }
                            if (state.loading && state.streamingText.isEmpty()) item { ThinkingIndicator() }
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
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(26.dp)).background(SurfaceHi).padding(start = 16.dp, end = 9.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    BasicTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 28.dp).focusRequester(focus),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 16.sp, lineHeight = 22.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            val text = prompt.trim(); if (text.isNotEmpty() && !state.loading) {
                                prompt = ""; keyboard?.hide(); scope.launch { store.send(text) }
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
                            .clickable {
                                if (state.loading) scope.launch { store.stop() }
                                else if (prompt.isNotBlank()) {
                                    val text = prompt.trim(); prompt = ""; keyboard?.hide()
                                    scope.launch { store.send(text) }
                                }
                            }, contentAlignment = Alignment.Center
                    ) { Text(if (state.loading) "■" else "↑", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
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
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(if (user) "You" else "eAI", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp))
        Text(message.content.ifEmpty { " " }, color = Ink, fontSize = 15.sp, lineHeight = 23.sp,
            modifier = Modifier.widthIn(max = 330.dp).clip(RoundedCornerShape(if (user) 20.dp else 8.dp, 20.dp, 20.dp, 20.dp))
                .background(if (user) Color(0xFF34313D) else Surface).padding(horizontal = 15.dp, vertical = 12.dp))
    }
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
    onSave: () -> Unit, onRefresh: () -> Unit
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
        Text("System instructions", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = systemPrompt, onValueChange = onSystemPrompt, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            placeholder = { Text("Set instructions that should guide every reply") }, minLines = 3,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = SurfaceHi))
        Spacer(Modifier.height(16.dp))
        Text("Your API key and settings are stored in this app's private preferences on this device.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(20.dp))
    }
}
